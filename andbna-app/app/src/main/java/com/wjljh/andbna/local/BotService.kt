package com.wjljh.andbna.local

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.wjljh.andbna.MainActivity
import com.wjljh.andbna.local.BotStore.chatProb
import com.wjljh.andbna.local.BotStore.groupAutochat
import com.wjljh.andbna.local.BotStore.groupAutochatOverride
import com.wjljh.andbna.local.BotStore.llmApi
import com.wjljh.andbna.local.BotStore.llmJudgeModel
import com.wjljh.andbna.local.BotStore.llmKey
import com.wjljh.andbna.local.BotStore.llmModel
import com.wjljh.andbna.local.BotStore.llmSearchModel
import com.wjljh.andbna.local.BotStore.llmSummaryModel
import com.wjljh.andbna.local.BotStore.llmVisionModel
import com.wjljh.andbna.local.BotStore.replyJudge
import java.io.File

/**
 * 纯 APK 模式的托管入口：把「所有启用的 bot」一起跑起来。
 *
 * 为什么是前台服务：Android 会随时回收后台进程，而 QQ 官方机器人断线期间
 * 平台不补发消息 —— 被杀了就是真丢消息。前台服务把进程钉住。
 */
class BotService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        alive = true
        // 渠道按落盘状态备好（顺带清掉旧版本遗留的「不显示」渠道）
        RunNotification.ensureChannels(this)
        // 记录服务本次启动的墙钟时间：hero 的「已运行」用它算，跨页面/进程重启都不丢
        com.wjljh.andbna.ui.Prefs.put(this, "svc_started_at", System.currentTimeMillis().toString())
        // 前台服务起来就保证「自动拉回」的监督在跑（ADB/root 满足条件时无需用户点任何开关）
        PullBack.supervise(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            BotHost.stopAll()
            stopSelf()
            return START_NOT_STICKY
        }
        // ACTION_REFRESH 只重挂通知（设置页刚切换了开关），不重启 bot
        if (intent?.action != ACTION_REFRESH) BotHost.startAll(this)
        // 通知内容就写「运行中」，不要把逐条启动结果塞进通知栏
        applyNotification()
        return START_STICKY
    }

    /**
     * 按开关决定「前台服务 + 通知」还是「只留服务」：
     *  开 → IMPORTANCE_LOW 渠道挂通知，服务处于前台状态；
     *  关 → stopForeground(STOP_FOREGROUND_REMOVE)：通知真的消失，服务还在但不再是前台服务。
     *
     * 关闭时也先 startForeground 一次：context.startForegroundService 要求 5 秒内必须进前台，
     * 先进再退是合法顺序；否则冷启动即抛 ForegroundServiceDidNotStartInTimeException。
     */
    private fun applyNotification() {
        val on = RunNotification.enabled(this)
        if (on) {
            RunNotification.ensureOnChannel(this)
            startForeground(NOTIF_ID, buildNotification("AndbnA", "运行中", RunNotification.CHANNEL_ON))
            Log.i(TAG, "运行通知：开（前台服务+通知）")
        } else {
            startForeground(NOTIF_ID, buildNotification("AndbnA", "运行中", RunNotification.CHANNEL_ON))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            RunNotification.cancel(this)
            Log.i(TAG, "运行通知：关（已退出前台，通知已移除）")
        }
    }

    override fun onDestroy() {
        alive = false
        BotHost.stopAll()
        // 顺手把 Shizuku 的 UserService 解绑：只靠 binder 断开去回收并不可靠（实测 Shizuku 13.7
        // 在客户端进程消失后会把 `<包名>:shell` 的 app_process 留成 PPID=1 的孤儿），
        // 这里有 onDestroy 就有机会显式退掉；force-stop 之类叫不到 onDestroy 的情况交给 ShellJanitor 兜底
        runCatching { ShizukuShell.unbind("前台服务销毁") }
        super.onDestroy()
    }

    private fun buildNotification(title: String, text: String, channel: String = CHANNEL_ID): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, channel)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val TAG = "BotService"

        /** 服务活着就是 true。冷启动时先用它把「运行中」显示出来，不用等 Python 起来。 */
        @Volatile
        var alive: Boolean = false
            private set

        const val CHANNEL_ID = RunNotification.CHANNEL_ON
        const val NOTIF_ID = 4711
        const val ACTION_START = "com.wjljh.andbna.START_LOCAL"
        const val ACTION_STOP = "com.wjljh.andbna.STOP_LOCAL"
        const val ACTION_REFRESH = "com.wjljh.andbna.REFRESH_NOTIF"

        fun start(context: Context) {
            val i = Intent(context, BotService::class.java).apply { action = ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        /**
         * 设置页切开关后调：服务活着就让 onStartCommand 按新状态重挂一次通知（不重启 bot）。
         * 服务没在跑时什么都不做 —— 下次启动 onCreate/onStartCommand 会自己按落盘值建渠道。
         */
        fun refreshNotification(context: Context) {
            if (!alive) return
            val i = Intent(context, BotService::class.java).apply { action = ACTION_REFRESH }
            runCatching { context.startService(i) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BotService::class.java))
        }
    }
}

/**
 * 内嵌 Python 的门面。Chaquopy 的 Python.start 只能调一次，进程内共用。
 */
object BotHost {

    private var module: PyObject? = null
    private var ctx: Context? = null
    var lastError: String = ""
        private set

    val pythonReady: Boolean get() = Python.isStarted()

    fun init(context: Context) {
        ctx = context.applicationContext
    }

    private fun ensure(): PyObject? {
        val c = ctx ?: return null
        if (!Python.isStarted()) {
            try {
                Python.start(AndroidPlatform(c))
            } catch (e: Throwable) {
                lastError = "Python 启动失败：${e.message}"
                return null
            }
        }
        if (module == null) {
            module = try {
                Python.getInstance().getModule("bot_runner")
            } catch (e: Throwable) {
                lastError = "加载 bot_runner 失败：${e.message}"
                null
            }
        }
        return module
    }

    fun dataDir(context: Context): File {
        val d = File(context.filesDir, "localbot")
        if (!d.exists()) d.mkdirs()
        return d
    }

    /**
     * 把「只活在进程内存里的运行时配置」重推一遍：Key / 地址 / 主模型 + 四个槽位、回复判定、自动接话。
     *
     * startAll 之前必调；**保存了模型配置之后也必须再调一次** —— Python 的 _llm_conf 是
     * 「内存值 or llm.json」，内存里那份不换，光重启 bot 读到的还是旧 Key / 旧模型名。
     */
    fun pushRuntimeConfig(context: Context): Boolean {
        val c = context.applicationContext
        ctx = c
        val m = ensure() ?: return false
        return try {
            // 模型配置先落到数据目录，Python 侧读得到
            BotStore.writeLlmJson(c)
            // Key 不落盘：明文只经这一行进 Python 进程内存（llm.json 里只有地址和四个槽位的模型名）
            // 四个槽位（识图/判定/摘要/搜索）留空 = 沿用主模型，由 Python 侧 _conf_for 回退
            m.callAttr(
                "set_llm", c.llmKey, c.llmApi, c.llmModel, c.llmVisionModel,
                c.llmJudgeModel, c.llmSummaryModel, c.llmSearchModel,
            )
            // 「回复判定」：非 @ 消息由模型判要不要插话（random 则按「闲聊概率」掷骰子）
            m.callAttr("set_reply_judge", c.replyJudge, c.chatProb)
            // 「群里自动接话」三层：先交全局开关，再交每个 bot 的覆盖（"" = 跟随全局）
            m.callAttr("set_group_autochat", "", "", c.groupAutochat)
            true
        } catch (e: Throwable) {
            lastError = "推送运行时配置失败：${e.message}"
            false
        }
    }

    /** 往「日志」页写一行（Python 侧的 log()）：Kotlin 这一侧的保存/重启动作也要留痕。 */
    fun logNote(msg: String) {
        try {
            ensure()?.callAttr("log", msg)
        } catch (_: Throwable) {
        }
    }

    /** 这个 bot 现在连着没有；没起来就是「没在跑」（重启对它是空动作）。 */
    fun isBotRunning(context: Context, appid: String): Boolean = try {
        ctx = context.applicationContext
        ensure()?.callAttr("is_running", appid)?.toBoolean() ?: false
    } catch (e: Throwable) {
        false
    }

    /** 启动所有启用中的 bot，返回逐条结果 */
    fun startAll(context: Context): String {
        val m = ensure() ?: return lastError.ifBlank { "Python 未就绪" }
        if (!pushRuntimeConfig(context)) return lastError.ifBlank { "Python 未就绪" }
        return try {
            // 每个人格先落到各自的数据目录，Python 侧读得到
            BotStore.load(context).forEach { b ->
                m.callAttr(
                    "set_group_autochat",
                    b.appid,
                    context.groupAutochatOverride(b.appid),
                    context.groupAutochat,
                )
                if (b.persona.isNotBlank()) {
                    val d = File(dataDir(context), b.appid)
                    d.mkdirs()
                    PersonaAssets.writeTo(context, b.persona, d)
                }
            }
            m.callAttr(
                "start_all", BotStore.enabledJson(context), dataDir(context).absolutePath
            ).toString().replace("\n", " · ")
        } catch (e: Throwable) {
            lastError = "启动异常：${e.message}"
            lastError
        }
    }

    /**
     * 「验证思考强度」：用当前配置发一次最小请求（hi / max_tokens=8），拿平台的原话回来。
     *
     * 走 bot_runner.verify_reasoning_effort —— 请求体和真实调用同一处拼（_req_body），
     * 所以验的就是真会发出去的那个包（留空 = 不带该字段）。返回 state / line / detail 的 JSON 串。
     */
    fun verifyReasoning(
        context: Context,
        api: String,
        key: String,
        model: String,
        effort: String,
    ): String = try {
        ensure()?.callAttr(
            "verify_reasoning_effort", api, key, model, effort, dataDir(context).absolutePath,
        )?.toString() ?: org.json.JSONObject()
            .put("state", "fail").put("line", "失败：Python 未就绪").put("detail", "").toString()
    } catch (e: Throwable) {
        org.json.JSONObject()
            .put("state", "fail")
            .put("line", "失败：" + (e.message ?: e.javaClass.simpleName))
            .put("detail", "")
            .toString()
    }

    // ── 本机记忆（供「内容」页读）──

    private fun botDir(context: Context, appid: String): String =
        File(dataDir(context), appid).absolutePath

    /**
     * 今日成本/用量快照（bot_runner.cost_snapshot）。
     *
     * {calls, in_tokens, out_tokens, today_cost_yuan|null, search_calls}
     * token 与次数来自 chat/completions 的 usage，金额 = 今日首次余额 − 当前余额（上游余额接口）；
     * 余额查不到就是 null，界面只显示 token 与次数。失败给空串，hero 少一行字，不影响别的。
     */
    fun costSnapshot(context: Context, appid: String): String = try {
        ensure()?.callAttr("cost_snapshot", appid, dataDir(context).absolutePath)?.toString() ?: ""
    } catch (e: Throwable) {
        ""
    }

    /**
     * 「群里自动接话」开关：改了立刻生效（Python 侧 _autochat 是每条消息现读的，不用重启 bot）。
     * 关着时群里只有 @ 才回 —— 状态页那行小字读的就是这个落盘值。
     */
    fun setGroupAutochat(on: Boolean): Boolean = try {
        ensure()?.callAttr("set_group_autochat", "", "", on) != null
    } catch (e: Throwable) {
        false
    }

    /**
     * 某个 bot 的三态覆盖改了：把（覆盖值 + 当前全局值）一起推给 Python，立刻生效。
     * override = "" 跟随全局 / "1" 开 / "0" 关（落盘在 BotStore.group_autochat_override）。
     */
    fun setGroupAutochatOverride(context: Context, appid: String, override: String): Boolean = try {
        ensure()?.callAttr(
            "set_group_autochat", appid, override, context.groupAutochat,
        ) != null
    } catch (e: Throwable) {
        false
    }

    /**
     * 群资料（群名 / 人数）：bot_runner.group_info → GET /v2/groups/{openid}/info。
     *
     * 用这个 bot 自己的 AppID/AppSecret 换 token（Python 侧按 appid 缓存到过期）；
     * 返回 {"ok":true,"name":"…","members":N}，失败给空串 —— 界面用 openid 兜底，绝不挡页面。
     */
    fun groupInfo(context: Context, appid: String, gid: String): String = try {
        val secret = BotStore.load(context).firstOrNull { it.appid == appid }?.secret.orEmpty()
        ensure()?.callAttr("group_info", appid, secret, gid)?.toString() ?: ""
    } catch (e: Throwable) {
        ""
    }

    fun memoryList(context: Context, appid: String): String = try {
        ensure()?.callAttr("memory_list", botDir(context, appid))?.toString() ?: "[]"
    } catch (e: Throwable) {
        "[]"
    }

    fun memoryDelete(context: Context, appid: String, key: String): String = try {
        ensure()?.callAttr("memory_delete", botDir(context, appid), key)?.toString() ?: ""
    } catch (e: Throwable) {
        "删除失败：${e.message}"
    }

    /**
     * 一次删多段：bot_runner.memory_delete_many 把这一批写成**一条** undo 记录，
     * 所以撤销是「一次操作」而不是「一段记忆」（多选删除也能整批倒回来）。
     */
    fun memoryDeleteMany(context: Context, appid: String, keys: List<String>): String = try {
        val arr = org.json.JSONArray()
        keys.forEach { arr.put(it) }
        ensure()?.callAttr("memory_delete_many", botDir(context, appid), arr.toString())?.toString() ?: ""
    } catch (e: Throwable) {
        "删除失败：${e.message}"
    }

    /** 撤销最近一次删除：bot_runner.memory_undo 把 undo 里的行合并回 memory.json */
    fun memoryUndo(context: Context, appid: String): String = try {
        ensure()?.callAttr("memory_undo", botDir(context, appid))?.toString() ?: ""
    } catch (e: Throwable) {
        "撤销失败：${e.message}"
    }

    /** 本 bot 采集到的 openid（群 / 私聊用户），JSON 字符串；来源只有运行期的 memory.json */
    fun idsFor(context: Context, appid: String): String = try {
        ensure()?.callAttr("ids_for", botDir(context, appid))?.toString()
            ?: """{"groups":[],"users":[],"owner":""}"""
    } catch (e: Throwable) {
        lastError = "采集失败：" + (e.message ?: "")
        """{"groups":[],"users":[],"owner":""}"""
    }

    /**
     * 内置人格自带的问候文案（bot_runner._PRESET_GREETINGS 的**只读快照**）。
     *
     * 只给「问候」卡展示：内置人格的候选文本是代码里定死的，页面只读、不改。
     * 直接问运行时那张表而不是在 Kotlin 里抄一份 —— 抄一份就会跟人格正文一起漂。
     * 名字对不上 / Python 没起来 → "[]"（页面就只显示时段与时刻，不编造候选）。
     */
    fun presetGreetings(personaName: String): String {
        val m = try { ensure() } catch (e: Throwable) { null } ?: return "[]"
        return try {
            // getattr(module, "_PRESET_GREETINGS") 再从字典里按人格名取一段；整段用 json.dumps
            // 序列化回 Kotlin（PyObject 的 toString/repr 是 Python 字面量，JSON 解析器读不了）
            val table = Python.getInstance().getModule("builtins")
                .callAttr("getattr", m, "_PRESET_GREETINGS")
            val entry = table.callAttr("get", personaName.trim())
            Python.getInstance().getModule("json").callAttr("dumps", entry).toString()
        } catch (e: Throwable) {
            "[]"
        }
    }

    fun memoryClear(context: Context, appid: String): String = try {
        ensure()?.callAttr("memory_clear", botDir(context, appid))?.toString() ?: ""
    } catch (e: Throwable) {
        "清空失败：${e.message}"
    }

    // ── bot 数据目录（存档等各功能共用）──

    /** 所有 bot 的数据目录：<dataDir>/<appid>/ */
    private fun botDirs(context: Context): List<File> =
        dataDir(context).listFiles()?.filter { it.isDirectory }.orEmpty()

    // ── 消息存档（<botdir>/messages.db，每个 bot 一个库）──

    private fun fileBytes(f: File): Long = if (f.isFile) f.length() else 0L

    /**
     * 消息存档的占用与条数（跨全部 bot 累加）。
     *
     * 条数要走 Python 的 archive_stats —— 它跟检索、导出同一套口径（先把内存缓冲 flush 再数）。
     * Python 没起来就只报文件大小、条数给 -1（界面显示「条数未知」），
     * 绝不为了显示一行字去把解释器拉起来。
     */
    fun archiveStats(context: Context): Pair<Long, Int> {
        val dirs = botDirs(context)
        val m = try { ensure() } catch (e: Throwable) { null }
            ?: return dirs.sumOf { fileBytes(File(it, "messages.db")) } to -1
        var bytes = 0L
        var rows = 0
        var known = true
        dirs.forEach { d ->
            val o = try {
                org.json.JSONObject(m.callAttr("archive_stats", d.absolutePath).toString())
            } catch (e: Throwable) {
                null
            }
            if (o == null) {
                bytes += fileBytes(File(d, "messages.db"))
                known = false
            } else {
                bytes += o.optLong("bytes", 0L)
                rows += o.optInt("rows", 0)
            }
        }
        return bytes to if (known) rows else -1
    }

    /** 清空所有 bot 的存档（DELETE + VACUUM）→ (删掉的条数, 清完的字节数)。 */
    fun archiveClear(context: Context): Pair<Int, Long> {
        val m = try { ensure() } catch (e: Throwable) { null }
        var rows = 0
        var bytes = 0L
        botDirs(context).forEach { d ->
            val o = if (m == null) null else try {
                org.json.JSONObject(m.callAttr("archive_clear", d.absolutePath).toString())
            } catch (e: Throwable) {
                null
            }
            if (o == null) {
                // Python 没起来也得能清：直接删库文件，下次 bot 启动会重建一个空库
                val f = File(d, "messages.db")
                bytes += f.length()
                if (f.exists() && f.delete()) rows += 1
            } else {
                rows += o.optInt("rows", 0)
                bytes += o.optLong("bytes", 0L)
            }
        }
        return rows to bytes
    }

    /**
     * 导出正文（一行一条 [时间] 群/私聊 作者：消息 ⇢ 回复，时间正序）。
     *
     * 只有一个 bot 有存档时第一行就是消息本身；多个 bot 都有才在每段前加一行「# <appid>」。
     */
    fun archiveExportText(context: Context): String {
        val m = try { ensure() } catch (e: Throwable) { null } ?: return ""
        val parts = ArrayList<Pair<String, String>>()
        botDirs(context).forEach { d ->
            val t = try {
                m.callAttr("archive_export_text", d.absolutePath).toString()
            } catch (e: Throwable) {
                ""
            }
            if (t.isNotBlank()) parts.add(d.name to t)
        }
        if (parts.isEmpty()) return ""
        if (parts.size == 1) return parts[0].second
        return parts.joinToString("\n\n") { p -> "# " + p.first + "\n" + p.second }
    }

    /**
     * 真跑着的解释器版本（bot_runner.python_version）。
     * Python 没起来就返回空串 —— 调用方据此写「未知（Python 未初始化）」，
     * 绝不为了显示一行版本号去把解释器拉起来。
     */
    fun pythonVersion(): String {
        if (!Python.isStarted()) return ""
        return try {
            ensure()?.callAttr("python_version")?.toString()?.trim().orEmpty()
        } catch (e: Throwable) {
            ""
        }
    }

    fun stopAll() {
        try {
            module?.callAttr("stop_all")
        } catch (_: Throwable) {
        }
    }

    /**
     * 手动启停单个 bot（Bot 页的开关）：切换即生效，不用重启 App。
     *
     * 为什么要有：以前那个开关只落盘 localbots.json，运行中的 bot 得等下次 startAll
     * （也就是重启 App）才会变 —— 用户明确说这是痛点。走 Python 的 start_one / stop_one，
     * 只动这一个 bot，别的 bot 不断线（和 restart_one 同一个理由）。
     *
     * 返回人能读的一句结果（"已启动" / "已停止" / 失败原因），并且**一定**留一行日志：
     * 「手动启停：<name> 已启动/已停止」（[reason] 默认就是那四个字）。
     */
    fun startOne(context: Context, appid: String, reason: String = "手动启停"): String {
        val c = context.applicationContext
        ctx = c
        val why = reason.trim().ifBlank { "手动启停" }
        val bot = BotStore.load(c).firstOrNull { it.appid == appid }
        val label = bot?.title ?: appid
        val m = ensure()
        if (m == null) {
            val err = lastError.ifBlank { "Python 未就绪" }
            logNote(why + "：" + label + " 启动失败（" + err + "）")
            return "启动失败：" + err
        }
        if (bot == null) {
            logNote(why + "：" + label + " 启动失败（本机清单里没有这个 bot）")
            return "启动失败：本机清单里没有这个 bot"
        }
        if (bot.secret.isBlank()) {
            logNote(why + "：" + label + " 启动失败（缺 AppSecret）")
            return "启动失败：缺 AppSecret"
        }
        return try {
            // 人格与运行时配置先推一遍：内存里那份不换，新起来的 bot 会用旧 Key / 旧模型
            if (bot.persona.isNotBlank()) {
                val d = File(dataDir(c), appid)
                d.mkdirs()
                PersonaAssets.writeTo(c, bot.persona, d)
            }
            pushRuntimeConfig(c)
            m.callAttr(
                "set_group_autochat", appid, c.groupAutochatOverride(appid), c.groupAutochat,
            )
            val r = m.callAttr(
                "start_one", appid, bot.secret, dataDir(c).absolutePath,
            ).toString().trim()
            when {
                r == "OK" -> {
                    logNote(why + "：" + label + " 已启动")
                    "已启动"
                }
                r.contains("已经在跑") -> {
                    logNote(why + "：" + label + " 已经在跑了，跳过")
                    "已在运行"
                }
                else -> {
                    logNote(why + "：" + label + " 启动失败（" + r + "）")
                    "启动失败：" + r
                }
            }
        } catch (e: Throwable) {
            lastError = "启动异常：" + (e.message ?: e.javaClass.simpleName)
            logNote(why + "：" + label + " 启动失败（" + lastError + "）")
            "启动失败：" + (e.message ?: e.javaClass.simpleName)
        }
    }

    /** 手动停单个 bot：见 [startOne]。返回 "已停止" / "未在运行" / 失败原因。 */
    fun stopOne(context: Context, appid: String, reason: String = "手动启停"): String {
        ctx = context.applicationContext
        val why = reason.trim().ifBlank { "手动启停" }
        val label = BotStore.load(context).firstOrNull { it.appid == appid }?.title ?: appid
        val m = ensure()
        if (m == null) {
            val err = lastError.ifBlank { "Python 未就绪" }
            logNote(why + "：" + label + " 停止失败（" + err + "）")
            return "停止失败：" + err
        }
        return try {
            when (val r = m.callAttr("stop_one", appid).toString().trim()) {
                "OK" -> {
                    logNote(why + "：" + label + " 已停止")
                    "已停止"
                }
                "没在跑" -> {
                    logNote(why + "：" + label + " 本来就没在跑")
                    "未在运行"
                }
                else -> {
                    logNote(why + "：" + label + " 停止失败（" + r + "）")
                    "停止失败：" + r
                }
            }
        } catch (e: Throwable) {
            lastError = "停止异常：" + (e.message ?: e.javaClass.simpleName)
            logNote(why + "：" + label + " 停止失败（" + lastError + "）")
            "停止失败：" + (e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 只重启一个 bot，返回人能读的一句结果（空串 = 没在跑，无需重启）。
     *
     * 人格 / 记忆变了之后必须重连才有用：persona.txt 是**每次回复现读**的，但 botpy 在
     * run() 里长连接着，Kotlin 侧改文件不会惊动它；而为了生效去 stop_all/start_all
     * 会把同一台机上另外几个 bot 一起断线。所以走 Python 的 restart_one（停一个、起一个）。
     *
     * 注意这里**不**重写 persona.txt：人格落盘只有一个入口（编辑页保存时 writeTo），
     * 重启只负责让 python 重新连一次，读到的自然就是那份新文件。
     */
    fun restartOne(context: Context, appid: String, reason: String): String {
        val m = ensure() ?: return lastError.ifBlank { "Python 未就绪" }
        val running = try {
            m.callAttr("is_running", appid).toBoolean()
        } catch (e: Throwable) {
            false
        }
        if (!running) return ""
        val bot = BotStore.load(context).firstOrNull { it.appid == appid }
        return try {
            m.callAttr(
                "restart_one", appid, bot?.secret ?: "", dataDir(context).absolutePath, reason
            ).toString().replace("\n", " ").trim()
        } catch (e: Throwable) {
            lastError = "重启异常：${e.message}"
            "重启 $appid 失败：${e.message ?: "未知错误"}"
        }
    }

    // ── 定时消息 ──

    fun taskList(context: Context): String = try {
        ensure()?.callAttr("task_list", dataDir(context))?.toString() ?: "[]"
    } catch (e: Throwable) {
        "[]"
    }

    fun taskSave(context: Context, json: String): String = try {
        ensure()?.callAttr("task_save", dataDir(context), json)?.toString() ?: "没保存上"
    } catch (e: Throwable) {
        "保存失败：${e.message}"
    }

    fun isRunning(): Boolean = try {
        module?.callAttr("is_running")?.toBoolean() ?: false
    } catch (e: Throwable) {
        false
    }

    fun stateText(): String = try {
        module?.callAttr("state_text")?.toString() ?: "未启动"
    } catch (e: Throwable) {
        lastError.ifBlank { "未启动" }
    }

    /** Python 侧给的每个 bot 的运行快照（JSON） */
    fun snapshot(): String = try {
        module?.callAttr("snapshot")?.toString() ?: "[]"
    } catch (e: Throwable) {
        "[]"
    }

    fun logTail(n: Int = 150): String = try {
        module?.callAttr("log_text", n)?.toString() ?: ""
    } catch (e: Throwable) {
        ""
    }

    fun probe(): String = try {
        val m = ensure()
        m?.callAttr("probe")?.toString() ?: "Python 没起来"
    } catch (e: Throwable) {
        "自检失败：${e.message}"
    }
}
