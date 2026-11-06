package com.wjljh.andbna.local

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 本机模式的 bot 清单。
 *
 * 每个 bot 一条：AppID / AppSecret / 人格 / 是否启用。
 * 存在 App 私有目录的 localbots.json 里（和之前的 prefs 分开，
 * 因为要存多条记录；旧的单条 prefs 会在首次读取时自动迁移过来）。
 */
data class LocalBot(
    val appid: String,
    val secret: String = "",
    val persona: String = "",
    val enabled: Boolean = false,
    /** 显示名：给这个 bot 起个好记的名字，空则回落到 AppID */
    val name: String = "",
    /** 是否沿用全局的模型配置（Key / 地址 / 模型名） */
    val useGlobalLlm: Boolean = true,
    /** 回复判定三档覆盖："" = 跟随全局 / conservative / normal / eager（键名 judge_level_override） */
    val judgeLevelOverride: String = "",
    /** 本 bot 的回复延迟（毫秒）：0 = 用全局（键名 reply_delay_min_ms / reply_delay_max_ms） */
    val replyDelayMinMs: Int = 0,
    val replyDelayMaxMs: Int = 0,
) {
    val title: String get() = name.ifBlank { appid }

    /** 落盘一律加密：业务代码用的 [secret] 始终是明文，加密只发生在这里 */
    fun toJson(): JSONObject = JSONObject()
        .put("appid", appid)
        .put("secret", SecureStore.encrypt(secret))
        .put("persona", persona)
        .put("enabled", enabled)
        .put("name", name)
        .put("use_global_llm", useGlobalLlm)
        // Bot 互聊：每 bot 的判定覆盖 + 延迟覆盖（0 = 用全局）
        .put("judge_level_override", judgeLevelOverride)
        .put("reply_delay_min_ms", replyDelayMinMs)
        .put("reply_delay_max_ms", replyDelayMaxMs)

    companion object {
        fun from(j: JSONObject): LocalBot = LocalBot(
            appid = j.optString("appid"),
            // 旧明文没有前缀，decrypt 会原样返回；BotStore.load 负责把它加密写回
            secret = SecureStore.decrypt(j.optString("secret")),
            persona = j.optString("persona"),
            enabled = j.optBoolean("enabled"),
            name = j.optString("name"),
            useGlobalLlm = j.optBoolean("use_global_llm", true),
            judgeLevelOverride = normJudge(j.optString("judge_level_override")),
            replyDelayMinMs = j.optInt("reply_delay_min_ms", 0).coerceAtLeast(0),
            replyDelayMaxMs = j.optInt("reply_delay_max_ms", 0).coerceAtLeast(0),
        )

        /** 脏值一律当「跟随全局」：Python 侧也只认这三个字面值 */
        fun normJudge(raw: String): String = raw.trim().lowercase()
            .let { if (it in setOf("conservative", "normal", "eager")) it else "" }
    }
}

object BotStore {

    /** 「群里自动接话」按 bot 覆盖的落盘键（值 = {appid: "1"/"0"}，缺键 = 跟随全局）。 */
    const val KEY_AUTOCHAT_OVERRIDE = "group_autochat_override"

    private fun file(ctx: Context): File = File(ctx.filesDir, "localbots.json")

    fun load(ctx: Context): List<LocalBot> {
        val f = file(ctx)
        val out = ArrayList<LocalBot>()
        var hadPlaintext = false
        if (f.exists()) {
            try {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val raw = o.optString("secret")
                    if (raw.isNotEmpty() && !SecureStore.isEncrypted(raw)) hadPlaintext = true
                    out.add(LocalBot.from(o))
                }
            } catch (_: Exception) {
            }
        }
        // 从旧版单条 prefs 迁移一次
        if (out.isEmpty()) {
            val sp = ctx.getSharedPreferences("andbna_local", Context.MODE_PRIVATE)
            val id = sp.getString("local_appid", "").orEmpty()
            // 旧版 flat prefs 里的密钥也是明文：读出来顺手加密写回，再搬进 localbots.json
            val sec = SecureStore.readPref(sp, "local_secret")
            if (id.isNotBlank()) {
                out.add(
                    LocalBot(
                        appid = id.trim(),
                        secret = sec.trim(),
                        persona = PersonaAssets.selected(ctx),
                        enabled = true,
                    )
                )
                save(ctx, out)
            }
        }
        // 旧明文立刻重新加密写回（内容不变，只改落盘形态）
        if (hadPlaintext) save(ctx, out)
        return out
    }

    fun save(ctx: Context, bots: List<LocalBot>) {
        val arr = JSONArray()
        bots.forEach { arr.put(it.toJson()) }
        try {
            file(ctx).writeText(arr.toString(2))
        } catch (_: Exception) {
        }
    }

    fun upsert(ctx: Context, bot: LocalBot) {
        val list = load(ctx).toMutableList()
        val idx = list.indexOfFirst { it.appid == bot.appid }
        if (idx >= 0) list[idx] = bot else list.add(bot)
        save(ctx, list)
    }

    fun remove(ctx: Context, appid: String) {
        save(ctx, load(ctx).filter { it.appid != appid })
    }

    fun setEnabled(ctx: Context, appid: String, enabled: Boolean) {
        val list = load(ctx).map { if (it.appid == appid) it.copy(enabled = enabled) else it }
        save(ctx, list)
    }

    fun setPersona(ctx: Context, appid: String, persona: String) {
        val list = load(ctx).map { if (it.appid == appid) it.copy(persona = persona) else it }
        save(ctx, list)
    }

    /**
     * 交给 Python 的启动清单（只含启用的）。
     *
     * 这里必须是**明文** secret：这是进程内交接，不落盘；如果直接把 toJson()（密文）
     * 丢过去，botpy 会拿着 enc:v1:… 去登录，平台回 100016 invalid appid or secret。
     */
    fun enabledJson(ctx: Context): String {
        val arr = JSONArray()
        load(ctx).filter { it.enabled }.forEach { b ->
            arr.put(
                JSONObject()
                    .put("appid", b.appid)
                    .put("secret", b.secret)
                    .put("persona", b.persona)
                    .put("enabled", b.enabled)
                    .put("name", b.name)
                    .put("use_global_llm", b.useGlobalLlm)
                    // 每 bot 的判定 / 延迟覆盖（"" 与 0 = 用全局），Python 侧按 bot 现读
                    .put("judge_level_override", b.judgeLevelOverride)
                    .put("reply_delay_min_ms", b.replyDelayMinMs)
                    .put("reply_delay_max_ms", b.replyDelayMaxMs)
            )
        }
        return arr.toString()
    }

    fun selectedAppid(ctx: Context): String =
        ctx.getSharedPreferences("andbna_local", Context.MODE_PRIVATE)
            .getString("selected_bot", "").orEmpty()

    fun selectAppid(ctx: Context, appid: String) {
        ctx.getSharedPreferences("andbna_local", Context.MODE_PRIVATE)
            .edit().putString("selected_bot", appid).apply()
    }

    // ── 模型配置（全局一份，所有 bot 共用）──

    private fun sp(ctx: Context) = ctx.getSharedPreferences("andbna_local", Context.MODE_PRIVATE)

    var Context.llmKey: String
        get() = SecureStore.readPref(sp(this), "llm_key")
        set(v) {
            SecureStore.writePref(sp(this), "llm_key", v)
        }

    var Context.llmApi: String
        get() = sp(this).getString("llm_api", "").orEmpty()
        set(v) {
            sp(this).edit().putString("llm_api", v.trim()).apply()
        }

    var Context.llmModel: String
        get() = sp(this).getString("llm_model", "").orEmpty()
        set(v) {
            sp(this).edit().putString("llm_model", v.trim()).apply()
        }

    /**
     * 识图模型：留空 = 识图沿用主模型（落盘也存空串，不复制主模型的名字）。
     *
     * 键名统一成 llm_vision_model（与 llm_model 同一套）；老版本的 vision_model 只在
     * 新键为空时读一次兜底，写的时候不再写它（下次保存顺手删掉，见 ModelPrefs.save）。
     */
    var Context.llmVisionModel: String
        get() = sp(this).getString("llm_vision_model", "").orEmpty()
            .ifBlank { sp(this).getString("vision_model", "").orEmpty() }
        set(v) {
            sp(this).edit().putString("llm_vision_model", v.trim()).apply()
        }

    /** 回复判定用的模型：留空 = 沿用主模型（判定是短问答，配个便宜模型省钱）。 */
    var Context.llmJudgeModel: String
        get() = sp(this).getString("llm_judge_model", "").orEmpty()
        set(v) {
            sp(this).edit().putString("llm_judge_model", v.trim()).apply()
        }

    /**
     * 回复判定的主动程度（1.0.6）：conservative / normal / eager。
     *
     * 只影响「没 @ 本 bot 的群消息」；Python 侧按这个值换提示词倾向，认不出就落回 normal。
     */
    var Context.llmJudgeLevel: String
        get() = sp(this).getString("llm_judge_level", "normal").orEmpty().ifBlank { "normal" }
        set(v) {
            sp(this).edit().putString("llm_judge_level", v.trim().ifBlank { "normal" }).apply()
        }

    /** 名字命中就当被叫（默认开）：正文里出现人格名或称呼 → 跳过回复判定直接回。 */
    var Context.nameHitAsCall: Boolean
        // 历史/备份都按字符串存（"1"/"0"），别用 getBoolean 撞 ClassCastException
        get() = sp(this).getString("name_hit_as_call", "1").orEmpty().trim() != "0"
        set(v) {
            sp(this).edit().putString("name_hit_as_call", if (v) "1" else "0").apply()
        }

    /** 上下文摘要用的模型：留空 = 沿用主模型（压缩时才用一次）。 */
    var Context.llmSummaryModel: String
        get() = sp(this).getString("llm_summary_model", "").orEmpty()
        set(v) {
            sp(this).edit().putString("llm_summary_model", v.trim()).apply()
        }

    /** 搜索结果转述用的模型：留空 = 沿用主模型（#搜 / #读 人设化加工时才用一次）。 */
    var Context.llmSearchModel: String
        get() = sp(this).getString("llm_search_model", "").orEmpty()
        set(v) {
            sp(this).edit().putString("llm_search_model", v.trim()).apply()
        }

    /**
     * 上下文窗口（token）：压缩阈值 = 这个值的 75%。默认 131072（128K）。
     *
     * 这是**估算阈值基数**，不是硬上限：真正发出去的只有 system + 摘要 + 最近 12 条。
     * 存字符串是为了让输入框原样保存；Python 侧读不到 / 解析失败都按 131072 处理。
     * 老配置里已经存着的值（例如 64000、用户自己试过的 600）一律不动。
     */
    var Context.llmContextWindow: String
        get() = sp(this).getString("llm_context_window", "131072").orEmpty().ifBlank { "131072" }
        set(v) {
            sp(this).edit().putString("llm_context_window", v.trim().ifBlank { "131072" }).apply()
        }

    /** 联网搜索（DeepSeek 官方 Search / Fetch）：默认开。关掉后 #搜/#读 与自动搜索都不发请求。 */
    var Context.webSearchEnabled: Boolean
        // 历史键按字符串存（"0"/"1"），别用 getBoolean 撞 ClassCastException
        get() = sp(this).getString("web_search_enabled", "1").orEmpty().trim() != "0"
        set(v) {
            sp(this).edit().putString("web_search_enabled", if (v) "1" else "0").apply()
        }

    /**
     * 搜索结果人设化加工（默认开）：#搜 / #读 交给模型按人格**合成一段自然回答**
     * （至多带一条洗过跟踪参数的链接）。关掉 = 原始列表贴法（标题｜摘要 + 链接逐条贴），
     * 只用于调试 / 对比。
     */
    var Context.searchPersonaStyle: Boolean
        get() = sp(this).getString("search_persona_style", "1").orEmpty().trim() != "0"
        set(v) {
            sp(this).edit().putString("search_persona_style", if (v) "1" else "0").apply()
        }

    /**
     * 搜索后端（默认 deepseek）：deepseek / searxng / custom / off。
     *
     * 后端可换，上层只认统一结构 [{title,url,summary}]：中转站与第三方模型通常没有
     * DeepSeek 那套 /v1/tools 接口，那就改用自建 SearXNG 或自定义 JSON 接口。
     */
    var Context.searchProvider: String
        get() = sp(this).getString("search_provider", "deepseek").orEmpty()
            .ifBlank { "deepseek" }
        set(v) {
            sp(this).edit().putString("search_provider", v.trim().ifBlank { "deepseek" }).apply()
        }

    /** 搜索后端地址：searxng 填实例根地址，custom 填带 {query} 占位（或不带、自动加 ?q=）的地址。 */
    var Context.searchBase: String
        get() = sp(this).getString("search_base", "").orEmpty()
        set(v) {
            sp(this).edit().putString("search_base", v.trim()).apply()
        }

    /** 搜索鉴权：none / bearer（用主模型 Key）/ header:Name=Value。 */
    var Context.searchAuth: String
        get() = sp(this).getString("search_auth", "").orEmpty()
        set(v) {
            sp(this).edit().putString("search_auth", v.trim()).apply()
        }

    /** 自定义后端的结果字段路径：默认 results[].title,url,summary。 */
    var Context.searchFields: String
        get() = sp(this).getString("search_fields", "results[].title,url,summary").orEmpty()
            .ifBlank { "results[].title,url,summary" }
        set(v) {
            sp(this).edit()
                .putString("search_fields", v.trim().ifBlank { "results[].title,url,summary" }).apply()
        }

    /**
     * 让模型自己判断「要不要先上网查」（默认开）。
     *
     * 开：普通聊天里模型不确定或需要最新信息时，自己输出一行 <search>关键词</search>，
     * 程序搜完再让它按资料回答（一条消息最多一次搜索 + 一次额外模型调用）。
     * 关：退回老逻辑 —— 只有 #搜 / #读 和「最近/新闻/价格」这类触发词才会联网。
     */
    var Context.searchAutoDecide: Boolean
        get() = sp(this).getString("search_auto_decide", "1").orEmpty().trim() != "0"
        set(v) {
            sp(this).edit().putString("search_auto_decide", if (v) "1" else "0").apply()
        }

    /**
     * 思考强度（可选）：空串 = 请求体里不带 reasoning_effort（按平台默认跑）。
     *
     * 各家取值不一样（DeepSeek 官方 off/low/high/max，百炼 default/low/medium/high/xhigh/max，
     * 部分模型只认 default），所以这里不枚举、不校验，存什么就发什么。
     */
    var Context.llmReasoningEffort: String
        get() = sp(this).getString("llm_reasoning_effort", "").orEmpty()
        set(v) {
            sp(this).edit().putString("llm_reasoning_effort", v.trim()).apply()
        }

    // ── Bot 互聊（1.0.8）：多 bot 之间谁回、说几句、多久停、真人限速、回复延迟 ──
    //
    // 六个键名与 Python 侧一字不改：gate_enabled / bot_max_turns / bot_silence_minutes /
    // human_hourly_limit / reply_delay_min_ms / reply_delay_max_ms。
    // prefs 里存一份（界面立刻能读到），llm.json 里再写一份（bot_runner._llm_conf 每次都重读文件）。

    /** 多 bot 由总模型决定谁回（默认开）：关掉 = 每个启用的 bot 各回各的。 */
    var Context.botGateEnabled: Boolean
        // 历史/备份都按字符串存（"1"/"0"），别用 getBoolean 撞 ClassCastException
        get() = sp(this).getString("gate_enabled", "1").orEmpty().trim() != "0"
        set(v) {
            sp(this).edit().putString("gate_enabled", if (v) "1" else "0").apply()
        }

    /** 每个 bot 连续发言上限（默认 6）：到上限就静默一段时间。 */
    var Context.botMaxTurns: Int
        get() = sp(this).getString("bot_max_turns", "").orEmpty().trim().toIntOrNull()
            ?.takeIf { it > 0 } ?: 6
        set(v) {
            sp(this).edit().putString("bot_max_turns", v.coerceAtLeast(1).toString()).apply()
        }

    /** 到上限后静默多少分钟（默认 10）。 */
    var Context.botSilenceMinutes: Int
        get() = sp(this).getString("bot_silence_minutes", "").orEmpty().trim().toIntOrNull()
            ?.takeIf { it > 0 } ?: 10
        set(v) {
            sp(this).edit().putString("bot_silence_minutes", v.coerceAtLeast(1).toString()).apply()
        }

    /** 真人每小时最多发几条（默认 40）：超过就只当观众，不插话。 */
    var Context.humanHourlyLimit: Int
        get() = sp(this).getString("human_hourly_limit", "").orEmpty().trim().toIntOrNull()
            ?.takeIf { it > 0 } ?: 40
        set(v) {
            sp(this).edit().putString("human_hourly_limit", v.coerceAtLeast(1).toString()).apply()
        }

    /**
     * 设置页「每日上限」：0 = 无上限（落盘就是字符串 "0"），默认 50 条/天。
     *
     * 这是**全局**那一档：群配置里的「跟随全局」就是读它（llm.json 的 daily_limit，Python 侧同一口径）。
     * 历史值（"50" / 空白 / 脏值）一律按默认 50 处理，不报错。
     */
    var Context.dailyLimit: Int
        get() = sp(this).getString("daily_limit", "").orEmpty().trim().toIntOrNull()
            ?.coerceAtLeast(0) ?: 50
        set(v) {
            sp(this).edit().putString("daily_limit", v.coerceAtLeast(0).toString()).apply()
        }

    /** 全局回复延迟（毫秒，默认 3000 / 7000）：0 = 不延迟。 */
    var Context.replyDelayMinMs: Int
        get() = sp(this).getString("reply_delay_min_ms", "").orEmpty().trim().toIntOrNull()
            ?.coerceAtLeast(0) ?: 3000
        set(v) {
            sp(this).edit().putString("reply_delay_min_ms", v.coerceAtLeast(0).toString()).apply()
        }

    var Context.replyDelayMaxMs: Int
        get() = sp(this).getString("reply_delay_max_ms", "").orEmpty().trim().toIntOrNull()
            ?.coerceAtLeast(0) ?: 7000
        set(v) {
            sp(this).edit().putString("reply_delay_max_ms", v.coerceAtLeast(0).toString()).apply()
        }

    /** 回复判定：llm=模型判断要不要插话，random=按闲聊概率掷骰子 */
    var Context.replyJudge: String
        get() = sp(this).getString("reply_judge", "llm").orEmpty().ifBlank { "llm" }
        set(v) {
            sp(this).edit().putString("reply_judge", v.trim().ifBlank { "llm" }).apply()
        }

    val Context.chatProb: String
        get() = sp(this).getString("chat_prob", "0.3").orEmpty().ifBlank { "0.3" }

    /**
     * 群里自动接话（默认关）：开了才轮到「回复判定」决定要不要插话。
     *
     * 设置页与 Bot 编辑页各有一行，写的是同一个键（全局开关，所有 bot 共用）：
     * 关着时群里只有 @ 才回 —— 这是「觉得 bot 必须 @ 才回」那个反馈的根因。
     * 写入后要立刻生效：调 BotHost.setGroupAutochat（Python 侧 _autochat 每条消息现读）。
     */
    var Context.groupAutochat: Boolean
        // 历史键是字符串（"0"/"1"），按字符串读，别用 getBoolean 撞 ClassCastException
        get() = sp(this).getString("group_autochat", "0").orEmpty().trim() == "1"
        set(v) {
            sp(this).edit().putString("group_autochat", if (v) "1" else "0").apply()
        }

    /**
     * 按 bot 的「群里自动接话」覆盖（Bot 编辑页三态）："" = 跟随全局 / "1" = 开 / "0" = 关。
     *
     * 与全局键 [groupAutochat] 分开存（prefs 键 group_autochat_override，值是 {appid: "1"/"0"}）：
     * 改某个 bot 的覆盖不动全局开关；生效优先级见 bot_runner._group_autochat_state
     * （群配置显式值 > 该 bot 覆盖 > 全局）。
     */
    fun Context.groupAutochatOverride(appid: String): String {
        val o = runCatching {
            JSONObject(sp(this).getString(KEY_AUTOCHAT_OVERRIDE, "").orEmpty().ifBlank { "{}" })
        }.getOrNull() ?: return ""
        val v = o.optString(appid.trim(), "").trim()
        return if (v == "1" || v == "0") v else ""
    }

    fun Context.setGroupAutochatOverride(appid: String, value: String) {
        val key = appid.trim()
        if (key.isBlank()) return
        val o = runCatching {
            JSONObject(sp(this).getString(KEY_AUTOCHAT_OVERRIDE, "").orEmpty().ifBlank { "{}" })
        }.getOrNull() ?: JSONObject()
        if (value == "1" || value == "0") o.put(key, value) else o.remove(key)
        sp(this).edit().putString(KEY_AUTOCHAT_OVERRIDE, o.toString()).apply()
    }

    /**
     * 把模型配置写到 localbot/llm.json 给 Python 读。
     *
     * 只写地址和模型名：Key 不再落盘 —— 这个文件在 app 私有目录但仍是明文，
     * 运行时的 Key 由 BotHost.startAll 通过 bot_runner.set_llm 直接交给 Python（只在内存里）。
     */
    fun writeLlmJson(ctx: Context) {
        val o = JSONObject()
            .put("api", ctx.llmApi)
            .put("model", ctx.llmModel.ifBlank { "deepseek-chat" })
            // 四个槽位模型：空串就是「沿用主模型」，Python 侧按空回退（这里不复制主模型）
            .put("vision_model", ctx.llmVisionModel)
            .put("judge_model", ctx.llmJudgeModel)
            .put("summary_model", ctx.llmSummaryModel)
            .put("search_model", ctx.llmSearchModel)
            // 思考强度：空串也写（Python 侧按空 = 请求体不带该字段）
            .put("reasoning_effort", ctx.llmReasoningEffort)
            // 回复判定两件套：主动程度三档 + 名字命中是否算被叫
            .put("judge_level", ctx.llmJudgeLevel)
            .put("name_hit_as_call", ctx.nameHitAsCall)
            // 上下文压缩阈值基数（token，默认 131072）与两个联网开关
            .put("context_window", ctx.llmContextWindow.toIntOrNull() ?: 131072)
            .put("web_search_enabled", ctx.webSearchEnabled)
            .put("search_persona_style", ctx.searchPersonaStyle)
            .put("search_auto_decide", ctx.searchAutoDecide)
            // 搜索后端：换后端不影响上层（统一 [{title,url,summary}]）
            .put("search_provider", ctx.searchProvider)
            .put("search_base", ctx.searchBase)
            .put("search_auth", ctx.searchAuth)
            .put("search_fields", ctx.searchFields)
            // Bot 互聊（1.0.8）：多 bot 谁回 / 连续发言上限 / 静默分钟 / 真人每小时上限 / 回复延迟
            .put("gate_enabled", ctx.botGateEnabled)
            .put("bot_max_turns", ctx.botMaxTurns)
            .put("bot_silence_minutes", ctx.botSilenceMinutes)
            .put("human_hourly_limit", ctx.humanHourlyLimit)
            .put("reply_delay_min_ms", ctx.replyDelayMinMs)
            .put("reply_delay_max_ms", ctx.replyDelayMaxMs)
            // 全局每日上限：群配置「跟随全局」时 Python 侧读这一档（0 = 无上限）
            .put("daily_limit", ctx.dailyLimit)
        try {
            val f = File(ctx.filesDir, "localbot/llm.json")
            f.parentFile?.mkdirs()
            f.writeText(o.toString(2))
        } catch (_: Exception) {
        }
    }
}