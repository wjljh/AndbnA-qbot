package com.wjljh.andbna.local

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Shizuku 的三种状态：进程没起 / 起了但本 App 没被授权 / 已授权。 */
enum class ShizukuState {
    NOT_RUNNING,
    DENIED,
    GRANTED;

    val text: String
        get() = when (this) {
            NOT_RUNNING -> "Shizuku 没在运行"
            DENIED -> "Shizuku 在运行，本 App 未授权"
            GRANTED -> "已授权，可以做后台豁免"
        }
}

/** ensureAuthorized 的授权结论三态（页面文案 / 日志共用）。 */
enum class ShizukuAuth(val label: String) {
    NOT_RUNNING("Shizuku 没在运行"),
    DENIED("已拒绝，没有授权"),
    GRANTED("已授权"),
}

/**
 * 一次 [ShizukuShell.ensureAuthorized] 的结果：授权三态 + 绑定结果 + 可读原因。
 *
 * 「切到 ADB 模式成没成」只认 [ok]（已授权**且** UserService 真绑上），
 * 光拿到授权不算 —— 没绑上 shell 能力一样用不了，不能把模式写成 ADB。
 */
data class ShizukuResult(
    val auth: ShizukuAuth,
    val bound: Boolean,
    val reason: String = "",
    /** 针对本次失败原因的具体下一步（比通用文案优先显示） */
    val advice: String = "",
) {
    val ok: Boolean get() = auth == ShizukuAuth.GRANTED && bound

    /** 一行可直接显示 / 打日志的结论：三种结果都能一眼分辨 */
    val text: String
        get() = when {
            ok -> "Shizuku 授权：GRANTED；绑定：bound=true"
            auth != ShizukuAuth.GRANTED ->
                "Shizuku 授权：" + auth.name + "（" + auth.label + "）" +
                    if (reason.isBlank()) "" else "；原因：" + reason
            else ->
                "Shizuku 授权：GRANTED；绑定：bound=false" +
                    if (reason.isBlank()) "" else "；原因：" + reason
        }

    /** 失败时该照着做什么（成功为空串）；页面的「下一步」直接用，不在每处重写一遍 */
    val hint: String
        get() = when {
            ok -> ""
            advice.isNotBlank() -> advice
            auth == ShizukuAuth.NOT_RUNNING -> ShizukuShell.NOT_RUNNING_HINT
            auth == ShizukuAuth.DENIED ->
                "在 Shizuku 的确认框里点「允许」（或在 Shizuku App 的授权应用里给本应用打开开关），再切一次"
            else -> "把 Shizuku 停止再启动一次（需 v13+），再切一次"
        }
}

/**
 * 绑定 Shizuku 的 UserService 并转发命令。
 *
 * 流程：bindUserService → onServiceConnected 拿到 binder → 转成 IUserService →
 * 之后所有 exec 都在 shell 进程里执行。
 *
 * 授权（checkSelfPermission / requestPermission / 结果回调）也收在这里：
 * 页面上只调 [requestPermission]，不再各自注册 addRequestPermissionResultListener
 * —— 两份监听会让同一次授权被回调两次，且页面销毁时机不同步就容易漏。
 */
object ShizukuShell {

    private const val TAG = "ShizukuShell"

    /** requestPermission 的请求码（页面以前各写各的，这里统一） */
    const val REQ_CODE: Int = 1001

    /** 等 UserService binder 回来的上限：bindUserService 是异步的，不等到就分不清「慢」和「失败」 */
    const val BIND_TIMEOUT_MS: Long = 4000L

    /**
     * 拿不到 binder 时的可操作指引（页面直接显示，不用每处重写一遍）。
     *
     * 两种情况都写进来：服务没启动；或者服务起来了但 Shizuku 没把 binder 发给本应用
     * （Shizuku 只在进程启动那一刻下发，没授权过的应用会一直拿到 null）。
     */
    const val NOT_RUNNING_HINT: String =
        "建议使用 Shizuku 13.7 及以上版本（旧版在 Android 17 上服务会自行退出）；" +
            "换过版本后要重启本 App 才会重新绑定"

    /**
     * 等 Shizuku 下发 binder 的上限。
     *
     * binder **不是**同步返回的：requestBinderForNonProviderProcess 只把请求发给 Shizuku，
     * 真正的 binder 事后通过 addBinderReceivedListener 回调进来。请求完立刻 ping 必然还是
     * false —— 这就是以前日志里「主动要 binder：running=false」的来源。
     */
    const val BINDER_TIMEOUT_MS: Long = 5000L

    /** 本 App 声明、Shizuku 授权时授予的权限（用来判断「以前授权过」） */
    private const val API_PERMISSION: String = "moe.shizuku.manager.permission.API_V23"

    /** 「授权拿到了，但 Shizuku 服务进程已经没了」——和「没授权」是两回事，文案必须分开 */
    const val BINDER_DIED_REASON: String =
        "Shizuku 服务已退出（binder 断开）：授权是拿到了，但服务进程不在了" +
            "——常见于 Shizuku 与当前 Android 版本不兼容";

    /** binder 死了以后该做什么（页面「下一步」直接用） */
    const val BINDER_DIED_HINT: String =
        "重启 Shizuku（Shizuku App 里点「重新启动」，或用 adb 再跑一次 start.sh）后回来重试；" +
            "如果它启动后很快就退出，说明 Shizuku 与当前 Android 版本不兼容，需要换支持当前系统的版本（≥13.7）"

    @Volatile
    private var remote: IUserService? = null

    /** 当前这条 binding。args 必须原样留着：Shizuku 解绑时要求参数与 bind 时完全一致 */
    private class Binding(
        val args: rikka.shizuku.Shizuku.UserServiceArgs,
        val conn: android.content.ServiceConnection,
    )

    @Volatile
    private var binding: Binding? = null

    /**
     * binding 正在建立中。bindUserService 是**异步**的，回调回来之前 remote 还是 null，
     * 这段窗口里再 bind 一次就会多起一个 `<包名>:shell` 的 app_process（几十 MB 一个）。
     */
    @Volatile
    private var bindingInFlight = false

    /** App 级 Context（AndbnAApp.onCreate 注入）：bind 的硬门要按当前模式判断，而 bind(pkg) 拿不到 ctx */
    @Volatile
    var appCtx: Context? = null

    /** 用户主动授权的窗口：只有在这个窗口里才允许 root 模式下 bind（「切到 ADB 模式」的流程要用） */
    @Volatile
    private var userIntentUntil = 0L

    /** 当前是不是 root 模式（拿不到 ctx 就当不是，避免误挡） */
    private fun rootModeNow(): Boolean {
        val c = appCtx ?: return false
        return ModeStore.get(c) == RunMode.ROOT
    }

    /** bind / unbind 共用同一份参数：进程名后缀、version 对不上就解绑不掉，进程留着变孤儿 */
    private fun argsFor(pkg: String, debuggable: Boolean): rikka.shizuku.Shizuku.UserServiceArgs =
        rikka.shizuku.Shizuku.UserServiceArgs(ComponentName(pkg, UserService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(debuggable)
            .version(1)

    @Volatile
    var lastError: String = ""
        private set

    val bound: Boolean get() = remote != null

    /** 正在等 binder 的人（Shizuku 回调 / 轮询兜底，谁先到谁说话） */
    private val binderWaiters = CopyOnWriteArrayList<(Boolean) -> Unit>()

    @Volatile
    private var binderListening = false

    /** 曾经从 Shizuku 拿到过通道（用来区分「从没连上」和「连上后服务没了」） */
    @Volatile
    private var everGotBinder = false

    /** 拿到过通道之后服务又没了（Shizuku 服务端崩溃的特征） */
    @Volatile
    private var binderDied = false

    private val binderReceivedListener = rikka.shizuku.Shizuku.OnBinderReceivedListener {
        everGotBinder = true
        Log.i(TAG, "Shizuku 回调：收到 binder")
        finishBinderWait(true)
    }

    private val binderDeadListener = rikka.shizuku.Shizuku.OnBinderDeadListener {
        binderDied = true
        lastError = BINDER_DIED_REASON
        Log.w(TAG, "Shizuku 回调：binder 断开（服务进程没了）→ 状态降级为未连接")
        remote = null
        finishBinderWait(false)
    }

    private fun startBinderListening() {
        if (binderListening) return
        binderListening = true
        try {
            rikka.shizuku.Shizuku.addBinderReceivedListener(binderReceivedListener)
            rikka.shizuku.Shizuku.addBinderDeadListener(binderDeadListener)
        } catch (e: Throwable) {
            binderListening = false
            Log.w(TAG, "注册 binder 回调失败：" + e.message)
        }
    }

    private fun finishBinderWait(ok: Boolean) {
        val waiters = binderWaiters.toList()
        binderWaiters.clear()
        waiters.forEach { it(ok) }
    }

    /** 向 Shizuku 主动要一次 binder（官方 API）；下发是异步的，别紧接着 ping */
    private fun requestBinder(ctx: Context) {
        try {
            rikka.shizuku.ShizukuProvider.requestBinderForNonProviderProcess(ctx)
            Log.i(TAG, "已向 Shizuku 请求 binder（authority=" + ctx.packageName + ".shizuku），等回调")
        } catch (e: Throwable) {
            Log.w(TAG, "主动要 binder 抛异常：" + e.message)
            lastError = "向 Shizuku 要通道失败：" + e.message
        }
    }

    // ── 授权 ────────────────────────────────────────────────────────────────

    /** Shizuku 进程（binder）起没起。全部 try/catch：Shizuku 没装时调用会抛。 */
    fun running(): Boolean = try {
        rikka.shizuku.Shizuku.pingBinder()
    } catch (e: Throwable) {
        Log.w(TAG, "pingBinder 失败：" + e.message)
        false
    }

    /**
     * 等 binder 回来（回调驱动 + 主线程轮询兜底），结果一定回主线程。
     *
     * Shizuku 只在本进程启动那一刻把 binder 递进来；服务比本进程晚上线时要用
     * ShizukuProvider.requestBinderForNonProviderProcess 主动要一次 —— 但要完必须**等回调**，
     * 立刻 ping 一定是 false。
     */
    private fun waitForBinder(ctx: Context, onReady: (Boolean) -> Unit) {
        if (running()) {
            onReady(true)
            return
        }
        val done = AtomicBoolean(false)
        val once: (Boolean) -> Unit = { ok -> if (done.compareAndSet(false, true)) onReady(ok) }
        binderWaiters.add(once)
        startBinderListening()
        requestBinder(ctx)
        val start = SystemClock.uptimeMillis()
        val main = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                val ok = running()
                val waited = SystemClock.uptimeMillis() - start
                if (!ok && waited < BINDER_TIMEOUT_MS) {
                    main.postDelayed(this, 100L)
                    return
                }
                binderWaiters.remove(once)
                if (!ok) {
                    lastError = "Shizuku 没回 binder（等了 " + waited + "ms）：服务没在跑，或本应用没被 Shizuku 授权"
                }
                Log.i(TAG, "等 binder：running=" + ok + "，耗时=" + waited + "ms")
                once(ok)
            }
        }
        main.postDelayed(tick, 100L)
    }

    /** [waitForBinder] 的阻塞版：只能在非主线程调（页面里都是 Dispatchers.IO 进来的） */
    private fun awaitBinderBlocking(ctx: Context): Boolean {
        if (running()) return true
        val latch = CountDownLatch(1)
        val waiter: (Boolean) -> Unit = { latch.countDown() }
        binderWaiters.add(waiter)
        startBinderListening()
        requestBinder(ctx)
        val start = SystemClock.uptimeMillis()
        try {
            while (SystemClock.uptimeMillis() - start < BINDER_TIMEOUT_MS) {
                if (running() || latch.count == 0L) break
                latch.await(50L, TimeUnit.MILLISECONDS)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            binderWaiters.remove(waiter)
        }
        val waited = SystemClock.uptimeMillis() - start
        val ok = running()
        Log.i(TAG, "等 binder（阻塞版）：running=" + ok + "，耗时=" + waited + "ms")
        return ok
    }

    /** Shizuku App 的版本名；没装返回空串 */
    private fun shizukuVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0).versionName.orEmpty()
    } catch (e: Throwable) {
        ""
    }

    /** 本 App 是否持有 Shizuku 的 API 权限（= 以前被授权过；授权状态记在 Shizuku 服务那侧） */
    private fun authorizedBefore(ctx: Context): Boolean = try {
        ctx.checkSelfPermission(API_PERMISSION) == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    /**
     * pingBinder 为 false 时到底该怎么办 —— 按能查到的证据给具体结论，不写含糊话。
     *
     * 已知两种：
     *  · 服务没启动（用户没开 Shizuku / 没跑 start.sh）→ 教他启动；
     *  · 服务启动后自己消失：Android 16/17 给 IProcessObserver 加了新方法，Shizuku
     *    13.5/13.6 的服务端没实现，系统一回调就 AbstractMethodError 自杀 —— 只能换版本。
     */
    fun notRunningAdvice(ctx: Context): String {
        val sdk = android.os.Build.VERSION.SDK_INT
        val ver = shizukuVersion(ctx)
        return when {
            ver.isBlank() ->
                "本机没有 Shizuku App：先装 Shizuku 并启动它的服务（打开 Shizuku App），再回来点一次"
            everGotBinder ->
                "Shizuku（" + ver + "）把通道交给过本应用，随后服务进程就没了：这是 Shizuku 与当前系统（API " +
                    sdk + "）不兼容，不是本应用的问题。换用支持当前系统的新版 Shizuku（13.5/13.6 在 Android 16/17 上" +
                    "会因 IProcessObserver 新增方法直接崩溃），或者改用 root 模式"
            sdk >= 36 && authorizedBefore(ctx) ->
                "Shizuku（" + ver + "）已授权过本应用，但服务进程不在：请确认它真的起来了；如果启动后很快就" +
                    "消失，说明 Shizuku 与 Android API " + sdk + " 不兼容（13.5/13.6 的服务端会被系统新回调打崩），" +
                    "需要换支持当前系统的版本（≥13.7），否则只能用 root 模式"
            else ->
                "Shizuku 服务没在跑：打开 Shizuku App 点「启动」，或用 adb 执行 start.sh，再回来点一次"
        }
    }

    /** 本 App 有没有被 Shizuku 授权。Shizuku 没运行时一律 false。 */
    fun hasPermission(): Boolean = try {
        running() && rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    /** 三态：给页面直接做文案用，不用每处各写一遍 when。 */
    fun state(): ShizukuState = when {
        !running() -> ShizukuState.NOT_RUNNING
        hasPermission() -> ShizukuState.GRANTED
        else -> ShizukuState.DENIED
    }

    /** 同 [state]，但会先主动要一次 binder（服务比本进程晚上线时也判得准） */
    fun state(ctx: Context): ShizukuState = when {
        !awaitBinderBlocking(ctx) -> ShizukuState.NOT_RUNNING
        hasPermission() -> ShizukuState.GRANTED
        else -> ShizukuState.DENIED
    }

    /** 等结果的回调；一次 requestPermission 收一次，收到就清空 */
    private val pending = CopyOnWriteArrayList<(Boolean) -> Unit>()

    @Volatile
    private var listening = false

    private val resultListener =
        rikka.shizuku.Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            val ok = grantResult == PackageManager.PERMISSION_GRANTED
            if (!ok) lastError = "Shizuku 授权被拒绝"
            val callbacks = pending.toList()
            pending.clear()
            stopListening()
            callbacks.forEach { deliver(it, ok) }
        }

    private fun startListening() {
        if (listening) return
        listening = true
        try {
            rikka.shizuku.Shizuku.addRequestPermissionResultListener(resultListener)
        } catch (e: Throwable) {
            listening = false
            lastError = "注册授权回调失败：${e.message}"
        }
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        try {
            rikka.shizuku.Shizuku.removeRequestPermissionResultListener(resultListener)
        } catch (_: Throwable) {
        }
    }

    /** 结果可能回到 binder 线程（或超时轮询里），统一甩到主线程再回调页面 */
    private fun <T> onMain(value: T, cb: (T) -> Unit) {
        val main = Looper.getMainLooper()
        if (Looper.myLooper() == main) cb(value) else Handler(main).post { cb(value) }
    }

    private fun deliver(cb: (Boolean) -> Unit, ok: Boolean) = onMain(ok, cb)

    /**
     * 主动申请授权：已经授权就直接回调 true；Shizuku 没在运行就回调 false 并写 [lastError]；
     * 否则弹系统确认框（Shizuku 自己弹），等到结果再回调一次。
     */
    fun requestPermission(onResult: (Boolean) -> Unit) {
        if (hasPermission()) {
            deliver(onResult, true)
            return
        }
        if (!running()) {
            lastError = "Shizuku 没在运行：先装 Shizuku 并启动它（打开 Shizuku App 或 adb 启动服务），再回来申请授权"
            deliver(onResult, false)
            return
        }
        pending.add(onResult)
        startListening()
        try {
            rikka.shizuku.Shizuku.requestPermission(REQ_CODE)
        } catch (e: Throwable) {
            lastError = "申请授权失败：${e.message}"
            pending.remove(onResult)
            if (pending.isEmpty()) stopListening()
            deliver(onResult, false)
        }
    }

    /**
     * 「切到 ADB 模式」的完整流程（所有入口共用，页面不要再自己 requestPermission + bind）：
     *
     *  1) pingBinder 没起 → [ShizukuAuth.NOT_RUNNING] + 可操作指引；
     *  2) 起来了但没授权 → 弹 Shizuku 自己的确认框，收一次结果；
     *  3) 已授权 → **立刻 bind(pkg)** 并等 binder 回来，把 bound / lastError 一起交回去。
     *
     * 回调一定回主线程，且只回调一次。
     */
    fun ensureAuthorized(ctx: Context, onResult: (ShizukuResult) -> Unit) {
        val pkg = ctx.packageName
        // 这是「用户主动授权」入口（ADB 卡 / 切换模式）：给它开一个 120s 的 bind 窗口，
        // 只有这里能绕过 root 模式的硬门
        userIntentUntil = System.currentTimeMillis() + 120_000L
        waitForBinder(ctx) { connected ->
        if (!connected) {
            if (lastError.isBlank()) {
                lastError = "Shizuku 没连上：服务没启动，或本应用还没在 Shizuku 里被授权"
            }
            Log.w(TAG, "Shizuku 授权：NOT_RUNNING（" + lastError + "）")
            onMain(
                ShizukuResult(ShizukuAuth.NOT_RUNNING, false, lastError, notRunningAdvice(ctx)),
                onResult,
            )
            return@waitForBinder
        }
        requestPermission { granted ->
            if (!granted) {
                val why = lastError.ifBlank { "Shizuku 授权被拒绝" }
                Log.w(TAG, "Shizuku 授权：DENIED（" + why + "）")
                onMain(ShizukuResult(ShizukuAuth.DENIED, false, why), onResult)
                return@requestPermission
            }
            Log.i(TAG, "Shizuku 授权：GRANTED")
            bindAndAwait(pkg) { bound ->
                if (bound) {
                    Log.i(TAG, "Shizuku 绑定：bound=true")
                    onMain(ShizukuResult(ShizukuAuth.GRANTED, true), onResult)
                } else if (!running()) {
                    // 授权拿到了，但 binder 已经死了：和「没授权」完全不是一回事
                    Log.w(TAG, "Shizuku 绑定：bound=false（GRANTED 但 binder 已死：" + lastError + "）")
                    onMain(
                        ShizukuResult(ShizukuAuth.GRANTED, false, lastError, BINDER_DIED_HINT),
                        onResult,
                    )
                } else {
                    val why = lastError.ifBlank { "UserService 没连上（需 Shizuku v13+）" }
                    Log.w(TAG, "Shizuku 绑定：bound=false（" + why + "）")
                    onMain(ShizukuResult(ShizukuAuth.GRANTED, false, why), onResult)
                }
            }
        }
        }
    }

    /**
     * 绑定并等 binder 回来。bindUserService 的回调在 binder 线程，页面要在主线程上
     * 拿到「绑没绑上」的确定结论，所以这里在主线程上轮询 [bound]，超时就算失败。
     */
    private fun bindAndAwait(pkg: String, onBound: (Boolean) -> Unit) {
        if (bound) {
            onBound(true)
            return
        }
        val main = Handler(Looper.getMainLooper())
        main.post {
            bind(pkg)
            val deadline = SystemClock.uptimeMillis() + BIND_TIMEOUT_MS
            val tick = object : Runnable {
                override fun run() {
                    if (bound) {
                        onBound(true)
                        return
                    }
                    if (SystemClock.uptimeMillis() >= deadline) {
                        lastError = if (!running()) {
                            BINDER_DIED_REASON
                        } else {
                            "绑定 UserService 超时：Shizuku 在跑且已授权，但 shell 服务没起来（需 v13+）"
                        }
                        onBound(false)
                        return
                    }
                    main.postDelayed(this, 150L)
                }
            }
            main.postDelayed(tick, 150L)
        }
    }

    /**
     * 进入 ADB 模式的唯一落盘入口：授权 + 绑定都成功才写 [RunMode.ADB]。
     *
     * 失败时模式保持原样（以前存过 ADB 的顺手回滚成普通模式），
     * 绝不留「模式写的是 ADB、实际没有任何保活权限」的半死状态；原因交给页面显示。
     */
    fun switchToAdb(ctx: Context, onResult: (ShizukuResult) -> Unit) {
        ensureAuthorized(ctx) { r ->
            if (r.ok) {
                ModeStore.set(ctx, RunMode.ADB)
                Log.i(TAG, "运行模式 → ADB（绑定成功，已落盘）")
                // 授权刚成功就把后台豁免一起做掉：用户不用再自己找「加白名单」。
                // binder 调用会阻塞，别放在这个主线程回调里。
                val pkg = ctx.packageName
                Thread {
                    val out = whitelist(pkg)
                    Log.i(TAG, "ADB 模式已加电池白名单：" + out.replace("\n", " "))
                    // 授权 + 绑定刚成功：立刻把「自动拉回」看门狗挂上（不等监督线程的下一个 20s）
                    PullBack.poke(ctx.applicationContext)
                }.start()
            } else {
                if (ModeStore.get(ctx) == RunMode.ADB) ModeStore.set(ctx, RunMode.NORMAL)
                Log.w(
                    TAG,
                    "运行模式保持「" + (ModeStore.get(ctx)?.label ?: "未选") + "」：ADB 未生效（" +
                        r.reason + "）",
                )
            }
            onResult(r)
        }
    }

    /**
     * 绑定 UserService，**幂等**：已绑定 / 正在绑定中一律不再 bind。
     *
     * 以前只看 `remote == null`，而 bindUserService 是异步的 —— 回调回来之前 remote 一直是 null，
     * 这段窗口里每调一次就多起一个 `<包名>:shell` 的 app_process（每个几十 MB）；
     * 绑定方一死它们就变成 PPID=1 的孤儿。现场那 11 个 :shell 就是这么攒出来的。
     */
    fun bind(pkg: String, debuggable: Boolean = true) {
        // 硬门（不看调用方）：root 模式下不许 bind。root 的白名单 / 拉回全走 su，Shizuku 在这儿没用，
        // 绑一次就白起一个「包名:shell」的 app_process（framework + 我们的 APK，几十上百 MB），
        // 客户端一死就变 PPID=1 的孤儿。唯一例外：用户正主动走「切到 ADB 模式」的授权窗口。
        if (rootModeNow() && System.currentTimeMillis() > userIntentUntil) {
            Log.i(TAG, "root 模式不绑 Shizuku：bind 被硬门拦下（pkg=" + pkg + "）")
            return
        }
        if (remote != null || bindingInFlight) return
        val args = argsFor(pkg, debuggable)
        val conn = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: android.os.IBinder?) {
                bindingInFlight = false
                remote = try {
                    service?.let { IUserService.Stub.asInterface(it) }
                } catch (e: Throwable) {
                    lastError = "转接 binder 失败：${e.message}"
                    null
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                bindingInFlight = false
                remote = null
            }
        }
        binding = Binding(args, conn)
        bindingInFlight = true
        try {
            rikka.shizuku.Shizuku.bindUserService(args, conn)
            Log.i(TAG, "bindUserService：开始绑定（pkg=" + pkg + "，processNameSuffix=shell）")
        } catch (e: Throwable) {
            bindingInFlight = false
            binding = null
            lastError = "绑定 UserService 失败：${e.message}"
            Log.w(TAG, "bindUserService 失败：" + e.message)
        }
    }

    /** 主动解绑（参数与 bind 一致）；失败也要留日志 —— 解不掉就等于放生一个 :shell */
    fun unbind(reason: String): Boolean {
        val b = binding
        binding = null
        bindingInFlight = false
        remote = null
        if (b == null) return false
        return try {
            // remove=true 会把 UserService 从 Shizuku 注册中移除，之后再也 bind 不上，必须 false。
            // （实测：remove=true 之后「切到 ADB 模式」直接失败并回滚成 root。）
            // 代价是 Shizuku 13.7 不会立刻退掉那个 app_process（ppid=1 的 :shell 会再活一会儿），
            // 这一段由 ShellJanitor 兜底（300s 阈值 + 2 分钟扫描）—— 这也是它必须留着的原因。
            rikka.shizuku.Shizuku.unbindUserService(b.args, b.conn, false)
            Log.i(TAG, "已解绑 UserService（" + reason + "）")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "解绑 UserService 失败（" + reason + "）：" + e.message)
            false
        }
    }

    /**
     * 自动绑定入口（监督线程那种每 20s 一次的地方用）：**root 模式一次都不 bind**。
     *
     * root 模式的白名单 / 拉回全走 su，Shizuku 在这儿没有任何用处，绑一次就白起一个 :shell 进程。
     * 只有用户主动在 ADB 卡上点授权（[ensureAuthorized] / [bind]）才会真去捆。
     */
    fun bindAuto(ctx: Context, debuggable: Boolean = true): Boolean {
        if (ModeStore.get(ctx) == RunMode.ROOT) {
            Log.i(TAG, "root 模式不绑 Shizuku：跳过自动绑定（不产生 :shell 进程）")
            return false
        }
        bind(ctx.packageName, debuggable)
        return true
    }

    /**
     * **真要用 shell 了**才按需绑定：拿不到就返回 false（调用方按「通道不可用」处理）。
     * 只有实际要跑命令的地方（spawn/stop/白名单/relaunch）才调它 —— 启动、切模式、看整机 CPU
     * 这类路径一次都不 bind，免得每重启一次进程就多留一个 :shell 孤儿。
     */
    fun ensureForUse(ctx: Context, waitMs: Long = 3000L): Boolean {
        if (bound && running()) return true
        if (!(running() && hasPermission())) {
            lastError = "Shizuku 不可用（running=" + running() + "，已授权=" + hasPermission() + "）"
            return false
        }
        bindAuto(ctx)
        val deadline = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < deadline) {
            if (bound) return true
            runCatching { Thread.sleep(120L) }
        }
        return bound
    }

    fun exec(cmd: String): String {
        val r = remote ?: return "UserService 没连上"
        return try {
            r.exec(cmd).orEmpty()
        } catch (e: Throwable) {
            "执行异常：${e.message}"
        }
    }

    /**
     * 自更新用的安装通道：把安装包的文件描述符连同大小一起交给 shell 进程，
     * 由它跑 `pm install -S <size>` 并从 fd 灌 stdin。
     *
     * 为什么不能走 [exec]：exec 只有一个字符串参数，塞不进二进制；
     * 而且 shell 也读不到 App 私有目录里的 cacheDir/update 下的 apk（SELinux）。
     * 文件描述符跨进程是可读的，所以这里传 fd 而不是路径。
     */
    fun pmInstall(apk: java.io.File, size: Long): String {
        val r = remote ?: return "UserService 没连上"
        val pfd = try {
            android.os.ParcelFileDescriptor.open(apk, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Throwable) {
            return "打开安装包失败：" + (e.message ?: "")
        }
        return try {
            r.pmInstall(pfd, size).orEmpty()
        } catch (e: Throwable) {
            "执行异常：${e.message}"
        } finally {
            runCatching { pfd.close() }
        }
    }

    fun whitelist(pkg: String): String = exec("dumpsys deviceidle whitelist +$pkg; echo done")

    fun unwhitelist(pkg: String): String = exec("dumpsys deviceidle whitelist -$pkg; echo done")

    fun isWhitelisted(pkg: String): Boolean =
        exec("dumpsys deviceidle whitelist").contains(pkg)

    /**
     * 通道已经作废（Shizuku 服务没了，手里的代理指向死 binder）：把 remote 置空。
     *
     * 不置空的话 bound 会一直骗人 —— 进程被 SIGKILL 时 binderDied 未必回调得到，
     * 「自动拉回」就会以为通道还在，继续挂着看门狗。
     */
    fun invalidate(reason: String) {
        if (remote != null) {
            // 只置空 remote 的话 Shizuku 那边的进程没人叫它退，最后攒成 PPID=1 的 :shell 孤儿：
            // 所以先显式解绑（解绑失败也记日志），再允许下一次重新绑定
            unbind("通道作废：" + reason)
            lastError = reason
            Log.w(TAG, "通道作废（" + reason + "）：已解绑并置空，等下一次重新绑定")
        }
    }

    /** shell 能启动导出的 Activity，用它把 App 从被杀状态拉回来 */
    fun relaunch(pkg: String): String =
        exec("am start-foreground-service -n $pkg/com.wjljh.andbna.local.BotService -a com.wjljh.andbna.START_LOCAL >/dev/null 2>&1; echo done")
}
