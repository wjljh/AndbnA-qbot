package com.wjljh.andbna.local

import android.content.Context
import android.net.Uri
import android.util.Log
import com.wjljh.andbna.local.BotStore.llmApi
import com.wjljh.andbna.local.BotStore.llmContextWindow
import com.wjljh.andbna.local.BotStore.llmJudgeModel
import com.wjljh.andbna.local.BotStore.llmModel
import com.wjljh.andbna.local.BotStore.llmReasoningEffort
import com.wjljh.andbna.local.BotStore.llmSummaryModel
import com.wjljh.andbna.local.BotStore.llmVisionModel
import com.wjljh.andbna.local.BotStore.webSearchEnabled
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 配置导出 / 导入（一键备份与换机迁移）。
 *
 * 一个 JSON 文件装下整机配置：全局（模型三件套 + 通知 / 自动拉回 / 运行模式）、
 * 每个 bot（AppID / 名字 / 是否启用 / 人格名 / 是否继承全局模型 / 称呼 / 问候 / 任务表）、
 * 每个 bot 的记忆全文、以及自定义导入的人格文件内容。
 *
 * **密钥不进备份**：AppSecret 与 API Key 一律写成占位符 [PLACEHOLDER]，
 * 换机恢复后要用户自己重新填（本机合并 / 替换导入时同一个 AppID 沿用本机已有的密钥，不用重填）。
 *
 * 落盘只走两条路：导出用 SAF 的 Uri（ActivityResultContracts.CreateDocument），
 * 导入用 SAF 的 Uri（OpenDocument）—— 从不自己写 /sdcard，也不需要任何存储权限。
 * 导入前先整份镜像到 files/backup-auto-<时间戳>/（同结构），写坏了从那儿回滚。
 *
 * 日志前缀统一「备份：」（logcat tag AndbnA），与 App 其它日志同一处看。
 */
object BackupIO {

    private const val TAG = "AndbnA"
    private const val PREFIX = "备份："

    /** 备份格式版本：以后结构变了靠它分流 */
    const val VERSION = 1

    /** 密钥占位符：导出写这个，导入时忽略（绝不把占位符当成真密钥落盘） */
    const val PLACEHOLDER = "<需重新填写>"

    /** 单项导入上限：备份是文本，正常几 MB 顶天，超了直接当坏文件 */
    private const val MAX_BYTES = 32 * 1024 * 1024

    /** 自动备份目录前缀（files/backup-auto-<时间戳>/） */
    const val AUTO_PREFIX = "backup-auto-"

    /** 上下文窗口默认值（与 BotStore.llmContextWindow / llm.json 的默认一致：131072 = 128K） */
    private const val DEFAULT_CONTEXT_WINDOW = "131072"

    /** 每个 bot 目录里归备份管的文件 */
    private val BOT_FILES = listOf("phrases.json", "greetings.json", "tasks.json", "memory.json")

    private val BOT_KEYS = mapOf(
        "phrases.json" to "phrases",
        "greetings.json" to "greetings",
        "tasks.json" to "tasks",
        "memory.json" to "memory",
    )

    /** 一次导入的解析结果：校验通过才有 plan */
    class Plan(
        val bots: List<JSONObject>,
        val personas: List<JSONObject>,
        val global: JSONObject?,
        /** 对话条数（memory.json 里所有行的合计） */
        val memories: Int,
        /** 会话数（memory.json 键数合计，与状态页那个「条记忆」同一口径） */
        val sessions: Int,
        val personaCount: Int,
    )

    /** 校验结果：ok=false 时 error 是给人看的一句原因（这时一个字节都没写） */
    class Check(val ok: Boolean, val error: String = "", val plan: Plan? = null, val summary: String = "")

    /** 导出 / 导入的结果：text 直接显示在面板上 */
    class Result(
        val ok: Boolean,
        val text: String,
        val bots: Int = 0,
        val memories: Int = 0,
        val sessions: Int = 0,
        val personas: Int = 0,
    )

    private fun log(msg: String) = Log.i(TAG, PREFIX + msg)

    private fun botDir(ctx: Context, appid: String): File = File(BotHost.dataDir(ctx), appid)

    // ── 导出 ────────────────────────────────────────────────────────────

    /** 组出整份备份 JSON（不落盘；写文件是 [exportTo] 的事） */
    fun buildExport(ctx: Context): JSONObject {
        val bots = BotStore.load(ctx)
        val root = JSONObject()
            .put("version", VERSION)
            .put("created_at", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            .put("app", JSONObject().put("package", ctx.packageName))

        // 全局：模型三件套（不含 Key）+ 通知 / 自动拉回 / 运行模式
        val llm = JSONObject()
            .put("api", ctx.llmApi)
            .put("model", ctx.llmModel)
            .put("vision_model", ctx.llmVisionModel)
            .put("reasoning_effort", ctx.llmReasoningEffort)
            // 三个槽位模型 / 上下文窗口 / 联网开关：与 llm.json 同名同值（字符串原样带走）
            .put("judge_model", ctx.llmJudgeModel)
            .put("summary_model", ctx.llmSummaryModel)
            .put("context_window", ctx.llmContextWindow.ifBlank { DEFAULT_CONTEXT_WINDOW })
            .put("web_search_enabled", if (ctx.webSearchEnabled) "true" else "false")
            .put("secret", PLACEHOLDER)
        val global = JSONObject()
            .put("llm", llm)
            .put("running_notification", RunNotification.enabled(ctx))
            .put("auto_pull", PullBack.autoEnabled(ctx))
            .put("mode", ModeStore.get(ctx)?.key ?: ModeStore.initial(ctx).key)
        jsonOf(File(BotHost.dataDir(ctx), "tasks.json"))?.let { global.put("tasks", it) }
        root.put("global", global)

        val arr = JSONArray()
        var memTotal = 0
        var sesTotal = 0
        bots.forEach { b ->
            val dir = botDir(ctx, b.appid)
            val o = JSONObject()
                .put("appid", b.appid)
                .put("name", b.name)
                .put("enabled", b.enabled)
                .put("persona", b.persona)
                .put("use_global_llm", b.useGlobalLlm)
                .put("secret", PLACEHOLDER)
            jsonOf(File(dir, "phrases.json"))?.let { o.put("phrases", it) }
            jsonOf(File(dir, "greetings.json"))?.let { o.put("greetings", it) }
            jsonOf(File(dir, "tasks.json"))?.let { o.put("tasks", it) }
            val mem = jsonOf(File(dir, "memory.json"))
            if (mem != null) {
                o.put("memory", mem)
                val n = countMemory(mem)
                val s = countSessions(mem)
                o.put("memory_count", n)
                o.put("session_count", s)
                memTotal += n
                sesTotal += s
            }
            arr.put(o)
        }
        root.put("bots", arr)

        val personas = JSONArray()
        PersonaAssets.customNames(ctx).forEach { name ->
            val f = File(PersonaAssets.customDir(ctx), name + ".txt")
            val text = try {
                if (f.isFile) f.readText(Charsets.UTF_8) else ""
            } catch (e: Exception) {
                ""
            }
            if (text.isEmpty()) return@forEach
            personas.put(
                JSONObject()
                    .put("name", name)
                    .put("content", text)
                    .put("original", PersonaAssets.originalName(ctx, name))
            )
        }
        root.put("personas", personas)
        log(
            "导出内容：" + bots.size + " 个 bot / " + memTotal + " 条对话 / " + sesTotal + " 个会话 / " +
                personas.length() + " 个人格（密钥不在备份里，写的是占位符）"
        )
        return root
    }

    /** 导出到用户用系统选择器挑的位置 */
    fun exportTo(ctx: Context, uri: Uri): Result {
        log("开始导出")
        val root = try {
            buildExport(ctx)
        } catch (e: Throwable) {
            val why = e.message ?: e.javaClass.simpleName
            log("导出失败：读取配置出错（" + why + "）")
            return Result(false, "导出失败：读取配置出错（" + why + "）")
        }
        val text = root.toString(2)
        return try {
            writeUri(ctx, uri, text)
            val arr = root.optJSONArray("bots") ?: JSONArray()
            var mem = 0
            var ses = 0
            for (i in 0 until arr.length()) {
                val b = arr.optJSONObject(i) ?: continue
                mem += b.optInt("memory_count", 0)
                ses += b.optInt("session_count", 0)
            }
            val personas = root.optJSONArray("personas")?.length() ?: 0
            val size = text.toByteArray(Charsets.UTF_8).size
            log(
                "导出完成：" + arr.length() + " 个 bot / " + mem + " 条对话 / " + ses + " 个会话 / " +
                    personas + " 个人格，" + fmtSize(size) + "（" + uri + "）"
            )
            Result(
                true,
                "已导出 " + arr.length() + " 个 bot / " + mem + " 条对话 / " + ses + " 个会话 / " + personas +
                    " 个人格（" + fmtSize(size) + "）。密钥不在备份里，恢复后需重新填写。",
                arr.length(), mem, ses, personas,
            )
        } catch (e: Exception) {
            val why = e.message ?: e.javaClass.simpleName
            log("导出失败：写文件出错（" + why + "）")
            Result(false, "导出失败：写文件出错（" + why + "）")
        }
    }

    private fun writeUri(ctx: Context, uri: Uri, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        // "wt" = 覆盖写：选择器里已有同名文件时不能追加（追加出来的是两份 JSON，导入必坏）
        val os = try {
            ctx.contentResolver.openOutputStream(uri, "wt")
        } catch (e: Exception) {
            null
        } ?: ctx.contentResolver.openOutputStream(uri, "w") ?: throw IllegalStateException("这个位置不可写，换一个目录再试")
        os.use {
            it.write(bytes)
            it.flush()
        }
    }

    // ── 导入：读 + 校验（不动数据）──────────────────────────────────────

    /** 读用户选的文件并校验；坏文件只返回 error，一个字节都不写 */
    fun check(ctx: Context, uri: Uri): Check {
        log("开始导入：读取所选文件")
        val text = try {
            readUri(ctx, uri)
        } catch (e: Exception) {
            val why = e.message ?: e.javaClass.simpleName
            log("导入失败：读取文件出错（" + why + "）")
            return Check(false, "读取失败：" + why)
        }
        return parse(text)
    }

    /** 校验 + 结构检查（纯函数式：不碰任何文件） */
    fun parse(text: String): Check {
        if (text.isBlank()) return fail("文件是空的")
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            return fail("不是合法的 JSON：" + brief(e.message))
        }
        if (!root.has("version")) return fail("缺少 version 字段，不像是本 App 导出的备份")
        val ver = root.optInt("version", -1)
        if (ver != VERSION) return fail("备份版本 " + ver + " 本机不支持（只认 " + VERSION + "）")
        val botsRaw = root.optJSONArray("bots") ?: return fail("缺少 bots 数组（结构不完整）")
        val bots = ArrayList<JSONObject>()
        for (i in 0 until botsRaw.length()) {
            val o = botsRaw.optJSONObject(i)
                ?: return fail("bots 第 " + (i + 1) + " 项不是对象（结构不完整）")
            if (o.optString("appid").trim().isBlank()) {
                return fail("bots 第 " + (i + 1) + " 项缺少 appid（结构不完整）")
            }
            bots.add(o)
        }
        val globalRaw = root.opt("global")
        val global = when (globalRaw) {
            null, JSONObject.NULL -> null
            is JSONObject -> globalRaw
            else -> return fail("global 字段不是对象（结构不完整）")
        }
        val personas = ArrayList<JSONObject>()
        val personasRaw = root.opt("personas")
        if (personasRaw != null && personasRaw != JSONObject.NULL) {
            if (personasRaw !is JSONArray) return fail("personas 字段不是数组（结构不完整）")
            for (i in 0 until personasRaw.length()) {
                val p = personasRaw.optJSONObject(i)
                    ?: return fail("personas 第 " + (i + 1) + " 项不是对象（结构不完整）")
                if (p.optString("name").isBlank()) {
                    return fail("personas 第 " + (i + 1) + " 项缺少 name（结构不完整）")
                }
                if (!p.has("content")) {
                    return fail("personas 第 " + (i + 1) + " 项缺少 content（结构不完整）")
                }
                personas.add(p)
            }
        }
        var mem = 0
        var ses = 0
        bots.forEach { b ->
            val m = b.opt("memory")
            mem += countMemory(m)
            ses += countSessions(m)
        }
        val summary = bots.size.toString() + " 个 bot / " + mem + " 条对话 / " + ses + " 个会话 / " +
            personas.size + " 个人格"
        log("校验通过：" + summary + "（" + ver + " 版）")
        return Check(true, plan = Plan(bots, personas, global, mem, ses, personas.size), summary = summary)
    }

    private fun fail(msg: String): Check {
        log("校验失败：" + msg)
        return Check(false, msg)
    }

    private fun brief(msg: String?): String {
        val m = (msg ?: "").trim()
        if (m.isEmpty()) return "格式错误"
        // org.json 的原文是英文长句，截一句够定位就行
        return if (m.length > 80) m.substring(0, 80) + "…" else m
    }

    // ── 导入：写（先自动备份，失败回滚）─────────────────────────────────

    /**
     * 真正写入。顺序：自动备份 → 人格文件 → bot 清单 → 每个 bot 的数据文件 → 全局 → 重启受影响的 bot。
     * 任何一步抛异常都从自动备份回滚，并把原因回给人看。
     */
    fun applyPlan(ctx: Context, plan: Plan, replace: Boolean): Result {
        val mode = if (replace) "替换" else "合并"
        log("开始写入（" + mode + " 模式）")
        val auto = try {
            autoBackup(ctx, mode + " 模式导入前自动备份")
        } catch (e: Exception) {
            val why = e.message ?: e.javaClass.simpleName
            log("导入中止：自动备份失败（" + why + "），现有数据未改动")
            return Result(false, "导入中止：自动备份失败（" + why + "），现有数据未改动")
        }
        log("已备份现有数据到 files/" + auto.name + "/")
        val appids = plan.bots.map { it.optString("appid").trim() }.filter { it.isNotBlank() }
        try {
            writeAll(ctx, plan, replace)
        } catch (e: Throwable) {
            val why = e.message ?: e.javaClass.simpleName
            val rolled = rollback(ctx, auto)
            if (rolled) {
                log("导入失败：" + why + "；已回滚到导入前状态")
                return Result(false, "导入失败：" + why + "（已回滚，现有数据没变）")
            }
            log("导入失败：" + why + "；回滚没成功，备份在 files/" + auto.name)
            return Result(false, "导入失败：" + why + "（回滚失败，备份在 files/" + auto.name + "）")
        }
        // 受影响的 bot 逐个重启（没在跑就返回空串，不会替用户拉起）
        val notes = appids.mapNotNull { id -> BotHost.restartOne(ctx, id, "配置导入").takeIf { it.isNotBlank() } }
        val note = if (notes.isEmpty()) "无需重启（受影响的 bot 都没在跑）" else notes.joinToString(" · ")
        val summary = plan.bots.size.toString() + " 个 bot / " + plan.memories + " 条对话 / " +
            plan.sessions + " 个会话 / " + plan.personaCount + " 个人格"
        log("导入完成：" + mode + " 模式，" + summary + "；" + note)
        return Result(
            true,
            "导入完成（" + mode + "）：" + summary + "。已自动备份到 files/" + auto.name + "。" + note,
            plan.bots.size, plan.memories, plan.sessions, plan.personaCount,
        )
    }

    private fun writeAll(ctx: Context, plan: Plan, replace: Boolean) {
        // ① 自定义人格文件先落盘：bot 目录里的 persona.txt 要从这儿读正文
        if (plan.personas.isNotEmpty()) {
            val dir = PersonaAssets.customDir(ctx).apply { mkdirs() }
            var written = 0
            plan.personas.forEach { p ->
                val name = safeName(p.optString("name"))
                if (name.isBlank()) return@forEach
                File(dir, name + ".txt").writeText(p.optString("content", "").trimEnd() + "\n", Charsets.UTF_8)
                val orig = p.optString("original", "").trim()
                if (orig.isNotBlank()) File(dir, name + ".orig").writeText(orig, Charsets.UTF_8)
                written++
            }
            log("人格文件已写入：files/personas/ 下 " + written + " 个")
        }

        // ② bot 清单：合并=同 AppID 覆盖、新的新增；替换=整体换成备份里那份
        val cur = BotStore.load(ctx)
        val byId = cur.associateBy { it.appid }
        val merged = if (replace) {
            plan.bots.map { importBot(it, byId[it.optString("appid").trim()]) }
        } else {
            val out = cur.toMutableList()
            plan.bots.forEach { p ->
                val bot = importBot(p, byId[p.optString("appid").trim()])
                val i = out.indexOfFirst { it.appid == bot.appid }
                if (i >= 0) out[i] = bot else out.add(bot)
            }
            out
        }
        BotStore.save(ctx, merged)
        log("bot 清单已写入：" + merged.size + " 条（" + (if (replace) "替换" else "合并") + " 模式）")

        // ③ 每个 bot 自己的数据文件
        for (p in plan.bots) {
            val appid = p.optString("appid").trim()
            if (appid.isBlank()) continue
            val dir = botDir(ctx, appid).apply { mkdirs() }
            for (fileName in BOT_FILES) {
                val v = p.opt(BOT_KEYS.getValue(fileName)) ?: continue
                if (v == JSONObject.NULL) continue
                File(dir, fileName).writeText(asText(v), Charsets.UTF_8)
            }
            // persona.txt / persona_name.txt 跟着人格名重写（重启后立刻读到新正文）
            val persona = p.optString("persona", "")
            if (persona.isNotBlank() && !PersonaAssets.writeTo(ctx, persona, dir)) {
                log("人格正文没写进 " + appid + "（" + persona + " 读不到，可能是内置档缺失）")
            }
        }

        // ④ 全局
        plan.global?.let { applyGlobal(ctx, it) }
        BotStore.writeLlmJson(ctx)
    }

    private fun importBot(p: JSONObject, old: LocalBot?): LocalBot = LocalBot(
        appid = p.optString("appid").trim(),
        // 密钥不在备份里：同一个 AppID 沿用本机已有的；新 bot 只能是空（用户自己填）
        secret = old?.secret.orEmpty(),
        persona = p.optString("persona", ""),
        enabled = p.optBoolean("enabled", false),
        name = p.optString("name", ""),
        useGlobalLlm = p.optBoolean("use_global_llm", true),
    )

    private fun applyGlobal(ctx: Context, g: JSONObject) {
        g.optJSONObject("llm")?.let { l ->
            if (l.has("api")) ctx.llmApi = l.optString("api")
            if (l.has("model")) ctx.llmModel = l.optString("model")
            if (l.has("vision_model")) ctx.llmVisionModel = l.optString("vision_model")
            if (l.has("reasoning_effort")) ctx.llmReasoningEffort = l.optString("reasoning_effort")
            // 这 4 个键是后加的：旧备份里没有，就按默认值写（不报错），别让换机恢复缺项
            val old = !l.has("judge_model") && !l.has("summary_model") &&
                !l.has("context_window") && !l.has("web_search_enabled")
            ctx.llmJudgeModel = l.optString("judge_model", "")
            ctx.llmSummaryModel = l.optString("summary_model", "")
            ctx.llmContextWindow = l.optString("context_window", DEFAULT_CONTEXT_WINDOW)
            ctx.webSearchEnabled = truthy(l.optString("web_search_enabled", "true"), true)
            BotStore.writeLlmJson(ctx)
            log(
                "模型配置已写入：" + ctx.llmApi + " / " + ctx.llmModel +
                    "（判定 " + ctx.llmJudgeModel.ifBlank { "同主模型" } +
                    " / 摘要 " + ctx.llmSummaryModel.ifBlank { "同主模型" } +
                    " / 窗口 " + ctx.llmContextWindow +
                    " / 联网 " + (if (ctx.webSearchEnabled) "开" else "关") +
                    (if (old) "；旧备份无这 4 个键，已按默认值写" else "") +
                    "；Key 不在备份里，没有改动）"
            )
        }
        if (g.has("running_notification")) {
            val on = g.optBoolean("running_notification", true)
            if (RunNotification.enabled(ctx) != on) RunNotification.set(ctx, on)
        }
        if (g.has("auto_pull")) {
            val on = g.optBoolean("auto_pull", true)
            if (PullBack.autoEnabled(ctx) != on) PullBack.setAutoEnabled(ctx, on)
        }
        RunMode.of(g.optString("mode", "").trim())?.let { m ->
            if (ModeStore.get(ctx) != m) {
                ModeStore.set(ctx, m)
                log("运行模式已写入 " + m.key + "（重启 App 后生效）")
            }
        }
        val tasks = g.opt("tasks")
        if (tasks != null && tasks != JSONObject.NULL) {
            File(BotHost.dataDir(ctx), "tasks.json").writeText(asText(tasks), Charsets.UTF_8)
        }
    }

    // ── 自动备份 / 回滚 ─────────────────────────────────────────────────

    /**
     * 把导入会碰到的文件整份镜像到 files/backup-auto-<时间戳>/（相对路径不变）。
     * manifest.json 记下「当时存在哪些文件」，回滚时据此还原、并删掉导入新建的那些。
     */
    fun autoBackup(ctx: Context, reason: String): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        var dir = File(ctx.filesDir, AUTO_PREFIX + stamp)
        var seq = 2
        while (dir.exists()) {
            dir = File(ctx.filesDir, AUTO_PREFIX + stamp + "-" + seq)
            seq++
        }
        if (!dir.mkdirs() && !dir.isDirectory) throw IllegalStateException("建不了备份目录")
        val existed = JSONArray()
        managedPaths(ctx).forEach { rel ->
            val src = File(ctx.filesDir, rel)
            if (!src.isFile) return@forEach
            val dst = File(dir, rel)
            dst.parentFile?.mkdirs()
            try {
                src.copyTo(dst, overwrite = true)
                existed.put(rel)
            } catch (e: Exception) {
                Log.w(TAG, PREFIX + "复制 " + rel + " 没成功：" + (e.message ?: ""))
            }
        }
        File(dir, "manifest.json").writeText(
            JSONObject()
                .put("created_at", stamp)
                .put("reason", reason)
                .put("paths", existed)
                .toString(2),
            Charsets.UTF_8,
        )
        return dir
    }

    /** 从自动备份回滚：备份里有就拷回去，备份里本来没有（导入新建的）就删掉 */
    private fun rollback(ctx: Context, dir: File): Boolean {
        val existed = try {
            JSONObject(File(dir, "manifest.json").readText(Charsets.UTF_8)).optJSONArray("paths") ?: JSONArray()
        } catch (e: Exception) {
            return false
        }
        val keep = HashSet<String>()
        for (i in 0 until existed.length()) keep.add(existed.optString(i))
        var ok = true
        keep.forEach { rel ->
            try {
                File(dir, rel).copyTo(File(ctx.filesDir, rel), overwrite = true)
            } catch (e: Exception) {
                ok = false
            }
        }
        managedPaths(ctx).forEach { rel ->
            if (!keep.contains(rel)) {
                try {
                    val f = File(ctx.filesDir, rel)
                    if (f.isFile) f.delete()
                } catch (e: Exception) {
                }
            }
        }
        log(if (ok) "已回滚（files/" + dir.name + "/）" else "回滚完成但有文件没还原（files/" + dir.name + "/）")
        return ok
    }

    /** 归备份管的文件（相对 files/ 的路径） */
    private fun managedPaths(ctx: Context): List<String> {
        val out = mutableListOf("localbots.json", "localbot/llm.json", "localbot/tasks.json")
        try {
            BotStore.load(ctx).map { it.appid }.filter { it.isNotBlank() }.forEach { id ->
                BOT_FILES.forEach { out.add("localbot/" + id + "/" + it) }
            }
        } catch (e: Exception) {
        }
        try {
            PersonaAssets.customDir(ctx).listFiles()?.filter { it.isFile }?.forEach {
                out.add("personas/" + it.name)
            }
        } catch (e: Exception) {
        }
        return out
    }

    // ── 小工具 ─────────────────────────────────────────────────────────

    private fun readUri(ctx: Context, uri: Uri): String {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = input.read(chunk)
                if (n <= 0) break
                total += n
                if (total > MAX_BYTES) throw IllegalStateException("文件超过 " + (MAX_BYTES / 1048576) + "MB")
                buf.write(chunk, 0, n)
            }
            buf.toByteArray()
        } ?: throw IllegalStateException("打不开这个文件（拿不到读取流）")
        return String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
    }

    /** 读成 JSON（Object 或 Array）；读不到 / 不是 JSON 返回 null */
    private fun jsonOf(f: File): Any? {
        if (!f.isFile) return null
        val text = try {
            f.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            return null
        }
        if (text.isBlank()) return null
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            try {
                JSONArray(text)
            } catch (e2: Exception) {
                text
            }
        }
    }

    /** memory.json 里还剩多少条对话（所有会话加起来） */
    private fun countMemory(v: Any?): Int {
        val o = v as? JSONObject ?: return 0
        var n = 0
        val keys = o.keys()
        while (keys.hasNext()) {
            val x = o.opt(keys.next())
            n += when (x) {
                is JSONArray -> x.length()
                null, JSONObject.NULL -> 0
                else -> 1
            }
        }
        return n
    }

    /** 文件正文：字符串原样，JSON 对象 / 数组按缩进写（两者都能被下次导入读回来） */
    private fun asText(v: Any): String = when (v) {
        is String -> v
        is JSONObject -> v.toString(2)
        is JSONArray -> v.toString(2)
        else -> v.toString()
    }

    /** memory.json 有几个会话（键数），与状态页的「条记忆」同一口径 */
    private fun countSessions(v: Any?): Int = (v as? JSONObject)?.length() ?: 0

    /** 开关值：备份里存的是 "true"/"false" 字符串，也认 "1"/"0"；认不出来就用默认 */
    private fun truthy(raw: String, def: Boolean): Boolean = when (raw.trim().lowercase(Locale.US)) {
        "" -> def
        "1", "true", "on", "yes" -> true
        "0", "false", "off", "no" -> false
        else -> def
    }

    private fun fmtSize(bytes: Int): String = when {
        bytes >= 1048576 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> bytes.toString() + " 字节"
    }

    /** 人格档名：与 PersonaAssets 导入时同一套安全字符（路径分隔符与保留字符换成 _） */
    private fun safeName(raw: String): String = raw.trim()
        .removePrefix(PersonaAssets.CUSTOM_PREFIX)
        .replace(Regex("[\\/:*?\"<>|\u0000-\u001f]"), "_")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trim('.')
        .take(40)
}
