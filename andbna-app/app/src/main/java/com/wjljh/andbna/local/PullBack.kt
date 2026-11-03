package com.wjljh.andbna.local

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自动拉回看门狗：ADB（Shizuku shell）/ root 模式下，App 进程被杀后自动把它拉回来。
 *
 * 为什么判定与拉起都在**外部 sh 脚本**里，而不是本进程里的定时器：被杀的正是本进程，
 * 进程内的定时器跟着一起没了，永远等不到「把自己拉起来」的那一刻。所以真正干活的是一个
 * 常驻 sh 循环 —— ADB 模式用 Shizuku 的 shell 通道 spawn，root 模式用 su spawn，
 * **同一份脚本**，两种模式行为一致，不会分叉出两套逻辑。
 *
 * App 与脚本之间只有一种通信方式：外部私有目录（getExternalFilesDir）里的几个文件。
 * shell 与 root 都能读写那里，App 自己也能直接读，不需要再走 binder：
 *   autopull.off   → 看门狗该退出（模式切走 / 通道断开）
 *   autopull.pause → 用户点了「停止全部」，别拉回（user_stopped=true）
 *   autopull.state → 脚本回报的状态（state / reason / count / last / hb），UI 只读它
 *   autopull.pid   → 脚本自己的 pid
 *   autopull.log   → 日志；同一行也进 logcat（tag AndbnA），统一前缀「自动拉回：」
 */
object PullBack {

    /** 所有日志的统一前缀（脚本里也是这一行），grep 这一个词就够了 */
    const val PREFIX = "自动拉回："

    private const val TAG = "AndbnA"

    /** 检查周期（秒）：被杀后最迟一个周期内被拉起 */
    private const val PERIOD = 60

    /** 防抖：10 分钟内最多拉 3 次 */
    private const val MAX_HITS = 3
    private const val WINDOW_SEC = 600

    private const val SCRIPT = "watchdog.sh"
    private const val OFF = "autopull.off"
    private const val PAUSE = "autopull.pause"
    private const val STATE = "autopull.state"
    private const val PID = "autopull.pid"

    /** 心跳文件：脚本每轮刷新一次，App 靠它区分「脚本还在跑」和「脚本早退了」 */
    private const val HB = "autopull.hb"

    private const val LOGFILE = "autopull.log"

    /** 脚本自己的 stdout/stderr：detach 之后没人读它了，全落到这个文件里 */
    private const val RUNLOG = "autopull.run.log"

    private const val PREF = "andbna_pullback"
    private const val KEY_STOPPED = "user_stopped"

    /** 设置页那个「自动拉回」开关的落盘键（默认开，见 [autoEnabled]） */
    private const val KEY_AUTO = "auto_pull"

    // ── 对外：状态 ─────────────────────────────────────────────────────────

    /** 脚本回报的状态文件（读不到就是默认值，等于「没在跑」） */
    data class State(
        val state: String = "off",
        val reason: String = "none",
        val count: Int = 0,
        val last: Long = 0L,
        val hb: Long = 0L,
        val channel: String = "",
    ) {
        /** 心跳够新才算活着（脚本每周期刷一次 hb） */
        val alive: Boolean
            get() = hb > 0 && System.currentTimeMillis() / 1000 - hb < PERIOD * 2
    }

    /**
     * UI 那一行文案。四态：已启用（带上次时间）/ 未启用（未绑定 Shizuku、没有 root）/
     * 已暂停（达上限、用户停止全部）/ 已关闭（设置里的开关关掉了）。
     */
    fun statusText(ctx: Context, mode: RunMode): String {
        val d = dir(ctx) ?: return "未启用"
        val st = readState(d)
        return when {
            mode == RunMode.NORMAL -> "未启用（普通模式）"
            // 设置里关掉优先于通道状态：这是用户自己的决定，不该被写成「未绑定 Shizuku」
            !autoEnabled(ctx) -> "已关闭（设置里开启）"
            mode == RunMode.ADB && !(ShizukuShell.bound && ShizukuShell.running()) ->
                "未启用（未绑定 Shizuku）"
            mode == RunMode.ROOT && !RootHost.hasRoot() -> "未启用（没有 root 权限）"
            st.state == "paused" && st.reason == "limit" -> "已暂停（达上限）"
            st.state == "paused" && st.reason == "user" -> "已暂停（用户停止全部）"
            st.state == "running" && st.alive ->
                if (st.last > 0) "已启用 · 上次 " + clock(st.last) else "已启用"
            else -> "未启用"
        }
    }

    /** 应用 Context：日志要同时写进看门狗那份文件，grep 一个前缀就能看到 App 侧和脚本侧 */
    @Volatile
    private var appCtx: Context? = null

    /** 统一出口：logcat（tag AndbnA）+ 看门狗日志文件，两处都是同一行「自动拉回：…」 */
    private fun log(msg: String) {
        Log.i(TAG, PREFIX + msg)
        val d = appCtx?.let { dir(it) }
        if (d == null || !d.exists()) return
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        runCatching { File(d, LOGFILE).appendText(stamp + " " + PREFIX + msg + "\n") }
    }

    fun isUserStopped(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_STOPPED, false)

    /** 设置页「自动拉回」开关：默认开（老用户升级上来行为不变） */
    fun autoEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_AUTO, true)

    /**
     * 设置页开关：落盘 + 立刻生效，不用重启 App。
     *
     * 开 → 清掉 off 标记并 poke 一次（等同原来的「授权成功后立刻上岗」）；
     * 关 → 写 off 让脚本自己退出，并把 pid 那个进程收掉；之后 tick 会先看这个开关，一次都不 spawn。
     */
    fun setAutoEnabled(ctx: Context, on: Boolean) {
        val app = ctx.applicationContext
        appCtx = app
        app.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO, on).apply()
        if (on) {
            dir(app)?.let { runCatching { File(it, OFF).delete() } }
            // 关着的时候脚本已经退了，它留下的 state/hb 是「尸体」：不清掉的话心跳还新鲜，
            // start() 会以为看门狗还在跑直接 return，开关打开后要等两分钟才真上岗
            clearState(app)
            log("设置里打开了「自动拉回」：立刻让看门狗上岗")
            poke(app)
        } else {
            log("设置里关了「自动拉回」：让看门狗退出，之后不再拉起")
            stop(app, "设置里关了自动拉回")
        }
    }

    // ── 对外：用户意图 ─────────────────────────────────────────────────────

    /**
     * 用户点「停止全部」/「启动全部」时调。
     *
     * 标记写两处：App 私有 prefs（user_stopped，给 UI 和 App 侧判定读）+ 外部目录里的
     * pause 文件（shell 读不到 App 私有 prefs，只能靠这个文件知道用户不想被拉回）。
     */
    fun setUserStopped(ctx: Context, stopped: Boolean) {
        val app = ctx.applicationContext
        app.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_STOPPED, stopped).apply()
        val d = dir(app)
        if (d != null) {
            if (stopped) {
                runCatching { File(d, PAUSE).writeText("user_stopped=1") }
            } else {
                runCatching { File(d, PAUSE).delete() }
                // 用户重新启动 = 想跑：如果看门狗是「达上限」自己退的，顺手解除停顿让它重新上岗；
                // 没到上限就别动状态文件，否则监督线程会以为脚本没了、再起一个重复的
                val st = readState(d)
                if (st.state == "paused" && st.reason == "limit") {
                    runCatching { File(d, STATE).delete() }
                    runCatching { File(d, OFF).delete() }
                }
            }
        }
        log(
            if (stopped) "用户点「停止全部」：已写停顿标记（user_stopped=true）"
            else "用户点「启动全部」：已清停顿标记（user_stopped=false）",
        )
        poke(app)
    }

    // ── 对外：监督 ─────────────────────────────────────────────────────────

    /**
     * 保证「监督线程」在跑（幂等）：它每 20s 判一次模式与通道，
     * 满足了就把看门狗拉起来，不满足了就让它退出。
     *
     * App 启动（Activity / 前台服务 / 授权成功）都调它，所以不需要任何 UI 开关。
     */
    fun supervise(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        synchronized(this) {
            val t = supervisor
            if (t != null && t.isAlive) return
            supervisor = Thread {
                while (true) {
                    try {
                        tick(app)
                    } catch (e: Throwable) {
                        log("监督异常：" + e.message)
                    }
                    runCatching { Thread.sleep(20_000L) }
                }
            }.also {
                it.isDaemon = true
                it.name = "autopull-supervisor"
                it.start()
            }
        }
    }

    /** 立刻判一次（授权成功 / 用户点了按钮之后，不用等下一个 20s） */
    fun poke(ctx: Context) {
        val app = ctx.applicationContext
        supervise(app)
        Thread {
            runCatching { tick(app) }.onFailure { log("立即判定失败：" + it.message) }
        }.also { it.isDaemon = true }.start()
    }

    @Volatile
    private var supervisor: Thread? = null

    /** 通道连续判定失败的次数（进程刚被拉起来时 binder 还没绑上，别急着下结论） */
    private var unboundTicks = 0

    /** 上一轮看到的模式：用来发现 ADB ↔ root 切换，好换通道（任何时刻只允许一份脚本） */
    @Volatile
    private var lastMode: RunMode? = null

    /**
     * 一次判定只允许一个人做。
     *
     * 三个入口（Application 起来、Activity 起来、监督线程每 20s）会同时调它：
     * 不串行的话每人都读到「脚本不在」各 spawn 一份，后起的把先起的 pid 顶掉，
     * 日志里就是「检测到新的看门狗，本进程退出」+ 两条「看门狗已启动」。
     */
    private val gate = Any()

    private fun tick(ctx: Context) = synchronized(gate) { tickLocked(ctx) }

    private fun tickLocked(ctx: Context) {
        val mode = ModeStore.get(ctx) ?: ModeStore.initial(ctx)

        // 离开 ADB 模式：显式把 Shizuku 的 UserService 解绑掉。
        // 不解绑的话那个「包名:shell」的 app_process 没人叫它退，几小时后就成了 PPID=1 的孤儿
        if (lastMode == RunMode.ADB && mode != RunMode.ADB) {
            ShizukuShell.unbind("切出 ADB 模式（" + mode.label + "）")
        }

        // 设置里关掉了：一次都不 spawn；已经占着岗的脚本收掉（写 off，脚本看到就 exit）
        if (!autoEnabled(ctx)) {
            lastMode = mode
            unboundTicks = 0
            stop(ctx, "设置里关了自动拉回")
            return
        }

        // 通道对不上就换岗（模式刚切、或进程重启后旧脚本还占着岗）
        ensureChannel(ctx, mode)
        lastMode = mode

        when (mode) {
            RunMode.ADB -> {
                // 「通道可用」= 手里有代理 **且** Shizuku 的 binder 还活着：
                // 只看 bound 会被死 binder 骗（Shizuku 被 SIGKILL 时 binderDied 未必回调）
                if (!channelReady()) {
                    if (ShizukuShell.bound) ShizukuShell.invalidate("Shizuku 的 binder 已失联")
                    // **按需绑定**：这里不 bind。绑一次就是 Shizuku 起一个 `<包名>:shell` 的
                    // app_process（125MB），而本进程一死 Shizuku 不回收它 —— 那就是现场的孤儿。
                    // 真正要用 shell 的地方（spawn/stop/白名单/relaunch）会走 ensureForUse 按需绑，
                    // 只看「有没有整机 CPU」这种采样永远不 bind。
                    if (!ShizukuShell.running()) ShizukuShell.state(ctx)
                }
                if (!channelReady()) {
                    unboundTicks++
                    if (unboundTicks == 1) {
                        log(
                            "Shizuku 通道未绑定（running=" + ShizukuShell.running() +
                                "，已授权=" + ShizukuShell.hasPermission() + "：" +
                                ShizukuShell.lastError.ifBlank { "无" } + "）—— 按需绑定，等真要用 shell 再绑",
                        )
                    }
                    // 只有 Shizuku 真不可用（没跑 / 没授权）才算「通道断开」；单纯「还没按需绑定」
                    // 不能去收脚本 —— 否则每次启动都变成「bind 一次 + 杀掉看门狗」的抖动
                    val broken = !(ShizukuShell.running() && ShizukuShell.hasPermission())
                    if (broken) {
                        if (alive(ctx) && unboundTicks >= 3) {
                            stop(ctx, "Shizuku 通道断开（bound=false）")
                            unboundTicks = 0
                        }
                        return
                    }
                    // Shizuku 在跑且已授权，只是**还没按需绑定**：照常走 start()。
                    // 脚本还活着（心跳新鲜）→ 什么都不做、一个 bind 都不发；
                    // 脚本真没了 → spawnOnce 里的 ensureForUse 才按需绑一次（这才是「真要用 shell」）
                }
                unboundTicks = 0
                start(ctx, RunMode.ADB)
            }

            RunMode.ROOT -> {
                // root 通道和 ADB 走同一份脚本，只是 spawn 走 su：这里不另写一套逻辑
                if (!RootHost.hasRoot()) {
                    if (alive(ctx)) stop(ctx, "没有 root 权限")
                    return
                }
                start(ctx, RunMode.ROOT)
            }

            RunMode.NORMAL -> if (alive(ctx)) stop(ctx, "运行模式已切到普通")
        }
    }

    /**
     * 保证「跑着的那份脚本」用的正是当前模式该用的通道。
     *
     * 两种情况都会换岗：① 模式刚切（ADB ↔ root ↔ 普通）；② 进程重启后旧脚本仍占着岗，
     * 而它的通道和当前模式对不上（比如上一轮是 shell 通道，这轮是 root）。
     * 不换的话 start() 会被旧心跳骗成「已经在跑」直接 return —— 一直留在旧通道，
     * 或者旧脚本没死掉，和新脚本两份同时拉回。
     *
     * 杀旧脚本必须用**它自己的**通道（root 杀得掉 shell，shell 杀不掉 root）。
     */
    private fun ensureChannel(ctx: Context, mode: RunMode) {
        val d = dir(ctx) ?: return
        val st = readState(d)
        val want = if (mode == RunMode.ROOT) "root" else "shell"
        val prev = lastMode
        val modeChanged = prev != null && prev != mode
        val channelDiffers = mode != RunMode.NORMAL && st.alive &&
            st.channel.isNotBlank() && st.channel != want
        if (!modeChanged && !channelDiffers) return
        val oldRoot = st.alive && st.channel == "root"
        val via = when {
            oldRoot -> RunMode.ROOT
            prev != null && prev != RunMode.NORMAL -> prev
            else -> if (oldRoot) RunMode.ROOT else RunMode.ADB
        }
        val reason = if (channelDiffers) {
            "看门狗通道与当前模式不一致（" + st.channel + " → " + want + "）"
        } else {
            "运行模式切换（" + (prev?.label ?: "-") + " → " + mode.label + "）"
        }
        stop(ctx, reason, via)
        clearState(ctx)
        unboundTicks = 0
    }

    /** 通道可用时把看门狗拉起来（已经在跑就什么都不做） */
    private fun start(ctx: Context, mode: RunMode) {
        val d = dir(ctx) ?: return
        val st = readState(d)
        // 「已达上限」是脚本自己下的结论：别重启它，不然上限形同虚设（等用户点「启动全部」再解放）
        if (st.state == "paused" && st.reason == "limit") return
        // reason=off：外面正让它退出，这轮交给 stop() 收尸，别抢着重启
        if (st.state == "paused" && st.reason == "off") return
        // 三重判定：心跳新鲜但进程没了（被 kill -9 就是这么死的）也算死
        if (aliveNow(d, st)) return
        if (isUserStopped(ctx)) {
            // 用户停过且脚本不在了：标记已经表达了意图，不重新拉起
            if (!File(d, PAUSE).exists()) runCatching { File(d, PAUSE).writeText("user_stopped=1") }
            return
        }
        d.mkdirs()
        // 到这里就是「脚本已死」：先把尸体标清楚（state=dead），日志留下死因
        if (st.hb > 0 || st.state != "off") log("看门狗已死（" + deadWhy(d, st) + "）：清掉状态并重新拉起")
        markDead(d, mode, st)
        val script = File(d, SCRIPT)
        val text = scriptText(ctx, d, mode)
        val wrote = runCatching { script.writeText(text) }.isSuccess
        if (!wrote) {
            // 目录可能是上一次运行留下的、当前 App 写不进去：让通道按自己的身份重建一次再写
            val fix = "rm -rf " + d.absolutePath + " && mkdir -p " + d.absolutePath +
                " && chmod 0777 " + d.absolutePath
            if (mode == RunMode.ADB) {
                // 真要把目录重建掉 = 真要用 shell：这时才按需绑定
                ShizukuShell.ensureForUse(ctx)
                ShizukuShell.exec(fix)
            } else {
                RootHost.sh(fix)
            }
            d.mkdirs()
            if (!runCatching { script.writeText(text) }.isSuccess) {
                log("看门狗启动失败：脚本写不进去（" + d.absolutePath + "）")
                return
            }
        }
        runCatching { File(d, OFF).delete() }
        var ok = spawnOnce(ctx, mode, d, script)
        if (!ok) {
            log("看门狗启动失败：pid 不存在，重试一次")
            runCatching { Thread.sleep(1000L) }
            ok = spawnOnce(ctx, mode, d, script)
        }
        if (!ok) {
            log("看门狗启动失败：pid 不存在（重试后仍不存在，" + d.absolutePath + "）")
            return
        }
        log(
            "看门狗已启动（" + mode.label + " 通道，pid " + pidOf(d) + "，周期 " + PERIOD + "s，" +
                (WINDOW_SEC / 60) + " 分钟内最多 " + MAX_HITS + " 次）",
        )
    }

    /**
     * 起一份新脚本，并且**必须**让它脱离 su / Shizuku 的 shell：父 shell 一退出就跟着死，
     * 正是「App 重启后没有自动上岗」的根本原因。
     *
     * setsid（新会话、没有控制终端）+ nohup（忽略 SIGHUP）+ 三个流全重定向到文件，
     * 三者缺一不可：只要 stdin/stdout/stderr 还挂在父 shell 的管道上，父 shell 一退就把它带走。
     *
     * 启动后 3 秒内用 kill -0 确认它真活着，不行返回 false（调用方记日志并重试一次）。
     */
    private fun spawnOnce(ctx: Context, mode: RunMode, d: File, script: File): Boolean {
        val D = "\$"
        // 起新脚本前按 pid 文件收掉残留的旧进程（不用 pkill -f：模式串会命中包装它的 sh -c）
        val cmd = "cd " + d.absolutePath + " && rm -f " + OFF + "; P=" + D + "(cat " +
            File(d, PID).absolutePath + " 2>/dev/null); [ -n \"" + D + "P\" ] && kill -9 " +
            D + "P 2>/dev/null; rm -f " + File(d, PID).absolutePath + " " + File(d, HB).absolutePath + "; " +
            "nohup setsid sh " + script.absolutePath + " </dev/null >>" +
            File(d, RUNLOG).absolutePath + " 2>&1 & echo spawned"
        runCatching {
            // 起看门狗是「真要用 shell」的场景：ADB 通道到这里才按需绑定（启动时不绑）
            val out = if (mode == RunMode.ADB) {
                ShizukuShell.ensureForUse(ctx)
                ShizukuShell.exec(cmd)
            } else {
                RootHost.sh(cmd).out
            }
            if (!out.contains("spawned")) log("看门狗启动失败：" + out.trim())
        }
        // 脚本自己写 pid；3 秒内用 kill -0 验活（state/hb 会被上一份脚本的残留骗过去）
        val want = if (mode == RunMode.ROOT) "root" else "shell"
        for (i in 1..6) {
            runCatching { Thread.sleep(500L) }
            val pid = pidOf(d)
            if (pid.isNotBlank() && kill0(pid, want)) return true
        }
        return false
    }

    /**
     * 让看门狗退出（写 off 标记，同时尽量立刻 pkill；通道没了就靠脚本下一轮自己看到标记）。
     *
     * [via] 指定用哪条通道去杀：换模式时必须用**旧**通道 —— shell 杀不掉 uid 0 的进程，
     * 不然切 root→ADB 时旧脚本会活下来，变成两份同时在跑。为空就按当前模式。
     */
    private fun stop(ctx: Context, reason: String, via: RunMode? = null) {
        val d = dir(ctx) ?: return
        // 同一条暂停原因只记一次（脚本退出前心跳还新鲜，下一轮会再来一次，不该重复刷屏）
        val off = File(d, OFF)
        val same = off.exists() && runCatching { off.readText() }.getOrNull() == reason
        runCatching { off.writeText(reason) }
        // 杀进程这一步不能进「同一原因只记一次」的分支：off 内容没变、但进程还在时也得补一刀
        val stillAlive = alive(ctx)
        if (same && !stillAlive) return
        // 换通道时按「旧」通道去杀：shell 杀不掉 uid 0 的进程，root 谁都杀得掉
        val mode = via ?: ModeStore.get(ctx)
        val D = "\$"
        // 必须 -9：实测 SIGTERM 打不死 mksh 里的看门狗（进程还在，接着和新脚本一起拉回）。
        // 路径也必须是绝对路径：su / Shizuku 的 cwd 不是这个目录，写相对名会 cat 不到 pid，
        // 等于什么都没杀（旧脚本存活 = 两份脚本同时拉回）。
        val pidFile = File(d, PID).absolutePath
        val cmd = "P=" + D + "(cat " + pidFile + " 2>/dev/null); [ -n \"" + D + "P\" ] && kill -9 " +
            D + "P 2>/dev/null; pkill -9 -f 'autopull/watch[d]og.sh' 2>/dev/null; echo done"
        var killed = false
        runCatching {
            if (mode == RunMode.ADB && ShizukuShell.bound) {
                ShizukuShell.exec(cmd)
                killed = true
            } else if (mode == RunMode.ROOT) {
                RootHost.sh(cmd)
                killed = true
            }
        }
        // 选中的通道不在（Shizuku 没绑 / 刚切模式）：root 早就授权过就再用 su 补一刀，
        // 否则旧脚本会活下来，和新脚本两份同时拉回（root 杀得掉任何 uid 的进程）
        if (!killed && RootHost.known() == true) runCatching { RootHost.sh(cmd) }
        if (same) return
        log("看门狗已停止（" + reason + "）")
    }

    /**
     * 换通道时清掉旧脚本留下的状态与心跳。
     *
     * 不清的话 start() 会被「上一秒刚写的心跳」骗成「已经在跑」直接 return —— 结果一直留在旧通道。
     * pid 文件故意留着：start() 起新脚本前会按它再 kill 一次，双保险。
     */
    private fun clearState(ctx: Context) {
        val d = dir(ctx) ?: return
        runCatching { File(d, STATE).delete() }
        runCatching { File(d, HB).delete() }
    }

    private fun channelReady(): Boolean = ShizukuShell.bound && ShizukuShell.running()

    private fun alive(ctx: Context): Boolean = dir(ctx)?.let { aliveNow(it) } ?: false

    /**
     * **三重**存活判定（任何一条不成立都算「脚本已死」）：
     *   ① kill -0 <pid> 成功 —— 进程真的在（心跳能被残留文件骗，进程不会）；
     *   ② autopull.hb 在 2 个周期（[PERIOD]*2 秒）内更新过 —— 进程在但卡死也算死；
     *   ③ state 是 running（limit / off / dead 这些「尸体状态」都不算活）。
     *
     * 文件是 App 能低成本读的，所以先读文件，最后才走通道跑 kill -0。
     */
    private fun aliveNow(d: File, st: State = readState(d)): Boolean {
        if (st.state != "running") return false
        if (!st.alive) return false
        val pid = pidOf(d)
        if (pid.isBlank()) return false
        // ADB 通道是按需绑定的：没绑定的时候 kill -0 得先 bind，而「只为确认还活着」不值得
        // 一个新进程（每次进程重启会多留一个 :shell 孤儿）。这种情况退一步只认心跳文件：
        // 心跳 2 个周期内刷过、state 还是 running，就当它活着；真异常会有别的路径去处理。
        if (st.channel != "root" && !ShizukuShell.bound) return true
        return kill0(pid, st.channel)
    }

    /** kill -0：进程在不在。走哪条通道看脚本自己报的 channel（root 杀得掉 shell，反之不行） */
    private fun kill0(pid: String, channel: String): Boolean {
        if (pid.isBlank()) return false
        val cmd = "kill -0 " + pid + " 2>/dev/null && echo alive || echo gone"
        val out = runCatching {
            when {
                channel == "root" && RootHost.hasRoot() -> RootHost.sh(cmd).out
                channel == "root" -> if (ShizukuShell.bound) ShizukuShell.exec(cmd) else ""
                ShizukuShell.bound -> ShizukuShell.exec(cmd)
                RootHost.known() == true -> RootHost.sh(cmd).out
                else -> ""
            }
        }.getOrDefault("")
        return out.contains("alive")
    }

    private fun pidOf(d: File): String =
        runCatching { File(d, PID).readText().trim() }.getOrNull().orEmpty()

    /** 死因（只进日志；UI 只看 state） */
    private fun deadWhy(d: File, st: State): String {
        val pid = pidOf(d)
        val who = if (pid.isBlank()) "pid 未知" else "pid " + pid
        val ago = if (st.hb <= 0) -1L else System.currentTimeMillis() / 1000 - st.hb
        return when {
            st.state == "dead" -> "上一次没起来（state=dead），" + who
            st.state != "running" ->
                "state=" + st.state + (if (st.reason != "none") "/" + st.reason else "") + "，" + who
            !st.alive -> "心跳 " + ago + "s 没更新（超过 " + PERIOD * 2 + "s），" + who
            else -> who + " 不存在"
        }
    }

    /** 把尸体标成 state=dead：UI 立刻显示未启用，下一轮也不会被旧心跳骗成「还在跑」 */
    private fun markDead(d: File, mode: RunMode, st: State) {
        val ch = if (mode == RunMode.ROOT) "root" else "shell"
        val text = "state=dead\n" +
            "reason=" + (if (st.reason == "none") "dead" else st.reason) + "\n" +
            "count=" + st.count + "\n" +
            "since=0\n" +
            "last=" + st.last + "\n" +
            "channel=" + ch + "\n" +
            "pid=\n"
        runCatching { File(d, STATE).writeText(text) }
        runCatching { File(d, HB).delete() }
        runCatching { File(d, PID).delete() }
    }

    // ── 文件通道 ───────────────────────────────────────────────────────────

    private fun dir(ctx: Context): File? = ctx.getExternalFilesDir(null)?.let { File(it, "autopull") }

    private fun readState(d: File): State {
        val f = File(d, STATE)
        if (!f.exists()) return State()
        val m = runCatching {
            f.readLines().mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
            }.toMap()
        }.getOrNull() ?: return State()
        return State(
            state = m["state"] ?: "off",
            reason = m["reason"] ?: "none",
            count = m["count"]?.toIntOrNull() ?: 0,
            last = m["last"]?.toLongOrNull() ?: 0L,
            hb = File(d, HB).let { if (it.exists()) it.readText().trim().toLongOrNull() ?: 0L else 0L },
            channel = m["channel"] ?: "",
        )
    }

    private fun clock(sec: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(sec * 1000))

    // ── 看门狗脚本（ADB / root 共用同一份）────────────────────────────────

    /**
     * 脚本模板。DOLLAR 常量代替 $（Kotlin 原始字符串里 $ 会被当模板插值，没法直接写 shell 变量）。
     *
     * 判定只有一条：「应当运行」= 没有用户停顿标记；此时进程不在就 am start 拉回来。
     * 兜底全写在脚本里，App 死没死都不影响判定。
     */
    private fun scriptText(ctx: Context, d: File, mode: RunMode): String {
        val pkg = ctx.packageName
        val channel = if (mode == RunMode.ROOT) "root" else "shell"
        // 拉起日志里写的命令名：「已通过 su 拉起」/「已通过 shell 拉起」，一眼看出走的哪条通道
        val via = if (mode == RunMode.ROOT) "su" else "shell"
        return TPL
            .replace("@PKG@", pkg)
            .replace("@ACT@", "com.wjljh.andbna.MainActivity")
            .replace("@DIR@", d.absolutePath)
            .replace("@CH@", channel)
            .replace("@VIA@", via)
            .replace("@PERIOD@", PERIOD.toString())
            .replace("@MAX@", MAX_HITS.toString())
            .replace("@WIN@", WINDOW_SEC.toString())
            .replace("§", "$")
    }

    private val TPL: String = """
#!/system/bin/sh
# AndbnA 自动拉回看门狗（由 App 生成，每次启用都会覆盖，手改无效）
PKG=@PKG@
ACT=@ACT@
DIR=@DIR@
CH=@CH@
LOG=§DIR/autopull.log
STATE=§DIR/autopull.state
OFF=§DIR/autopull.off
PAUSE=§DIR/autopull.pause
PIDF=§DIR/autopull.pid
HB=§DIR/autopull.hb
PERIOD=@PERIOD@
MAX=@MAX@
WIN=@WIN@

COUNT=0
SINCE=0
LAST=0
REASON=none
PAUSE_SEEN=0
ALIVE=0

echo §§ > "§PIDF"
: >> "§LOG" 2>/dev/null
# 日志文件是 shell 建的：放开写权限，App 侧才能把同一行「自动拉回：…」也写进来
chmod 0666 "§LOG" 2>/dev/null
date +%s > "§HB"
chmod 0666 "§HB" 2>/dev/null

al() {
  echo "§(date '+%m-%d %H:%M:%S') 自动拉回：§1" >> "§LOG"
  /system/bin/log -t AndbnA "自动拉回：§1" >/dev/null 2>&1
  chmod 0666 "§LOG" 2>/dev/null
}

write_state() {
  {
    echo "state=§1"
    echo "reason=§REASON"
    echo "count=§COUNT"
    echo "since=§SINCE"
    echo "last=§LAST"
    echo "channel=§CH"
    echo "pid=§§"
  } > "§STATE"
  chmod 0666 "§STATE" 2>/dev/null
}

al "看门狗已启动（§CH 通道，周期 §{PERIOD}s，§((WIN/60)) 分钟内最多 §MAX 次）"
write_state running

while :; do
  # 同一时刻只允许一份看门狗：pid 文件里不是自己就退出（后起的赢）
  if [ -f "§PIDF" ]; then
    OTH=§(cat "§PIDF" 2>/dev/null)
    if [ -n "§OTH" ] && [ "§OTH" != "§§" ]; then
      al "检测到新的看门狗（pid §OTH），本进程退出"
      exit 0
    fi
  fi
  # 活下来就每轮抢注一次：外面（设置页关掉 / 换模式）永远按 pid 文件杀得掉这一个
  echo §§ > "§PIDF"
  chmod 0666 "§PIDF" 2>/dev/null
  if [ -f "§OFF" ]; then
    REASON=off
    write_state paused
    rm -f "§PIDF"
    al "看门狗已停止（§(cat "§OFF" 2>/dev/null)）"
    exit 0
  fi
  if [ -f "§PAUSE" ]; then
    if [ "§PAUSE_SEEN" = "0" ]; then
      PAUSE_SEEN=1
      REASON=user
      write_state paused
      al "用户已停止全部，本轮不拉回"
    fi
  else
    if [ "§PAUSE_SEEN" = "1" ]; then
      PAUSE_SEEN=0
      REASON=none
      al "用户已重新启动，恢复拉回"
    fi
    if pidof "§PKG" >/dev/null 2>&1; then
      ALIVE=§((ALIVE + 1))
      if [ "§COUNT" -gt 0 ] && [ "§ALIVE" -ge 2 ]; then
        COUNT=0
        SINCE=0
        write_state running
        al "运行已稳定，计数重置"
      fi
    else
      ALIVE=0
      NOW=§(date +%s)
      if [ "§COUNT" -gt 0 ] && [ §((NOW - SINCE)) -ge "§WIN" ]; then
        COUNT=0
        SINCE=0
      fi
      if [ "§COUNT" -ge "§MAX" ]; then
        REASON=limit
        write_state paused
        rm -f "§PIDF"
        al "已达上限，暂停"
        exit 0
      fi
      if [ "§COUNT" -eq 0 ]; then SINCE=§NOW; fi
      COUNT=§((COUNT + 1))
      LAST=§NOW
      # 拉起：root 直接起服务（uid 0 不受 exported 限制）；shell 只能 start 无界面跳板
      # （BotService 是 exported=false，shell 起它会被 AMS Permission Denial）。
      # 两条通道都必须看返回码与输出：失败就记失败，不许再写「已通过 … 拉起」。
      if [ "§CH" = "root" ]; then
        OUT=§(am start-foreground-service -n "§PKG/com.wjljh.andbna.local.BotService" -a com.wjljh.andbna.START_LOCAL 2>&1)
      else
        OUT=§(am start -n "§PKG/com.wjljh.andbna.local.WakeActivity" 2>&1)
      fi
      RC=§?
      OK=0
      # 成功判据：rc=0、没有 Error 行、且输出里有 Starting（Activity 是 "Starting: Intent"，
      # 服务是 "Starting service: Intent"，两种都认）
      if [ "§RC" -eq 0 ] && ! printf '%s' "§OUT" | grep -q '^Error' && printf '%s' "§OUT" | grep -q 'Starting'; then
        OK=1
      fi
      write_state running
      if [ "§OK" = "1" ]; then
        al "已通过 @VIA@ 拉起（第 §{COUNT} 次）"
      else
        WHY=§(printf '%s' "§OUT" | grep -m1 -E 'Error|Exception|not exported|Permission Denial' | cut -c1-160)
        [ -z "§WHY" ] && WHY="rc=§RC 无输出"
        al "拉起失败（第 §{COUNT} 次，rc=§RC）：§WHY"
      fi
    fi
  fi
  date +%s > "§HB"
  sleep "§PERIOD"
done
"""
}
