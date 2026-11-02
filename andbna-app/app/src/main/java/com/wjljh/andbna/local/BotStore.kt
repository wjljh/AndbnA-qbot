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

    companion object {
        fun from(j: JSONObject): LocalBot = LocalBot(
            appid = j.optString("appid"),
            // 旧明文没有前缀，decrypt 会原样返回；BotStore.load 负责把它加密写回
            secret = SecureStore.decrypt(j.optString("secret")),
            persona = j.optString("persona"),
            enabled = j.optBoolean("enabled"),
            name = j.optString("name"),
            useGlobalLlm = j.optBoolean("use_global_llm", true),
        )
    }
}

object BotStore {

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

    /** 回复判定：llm=模型判断要不要插话，random=按闲聊概率掷骰子 */
    var Context.replyJudge: String
        get() = sp(this).getString("reply_judge", "llm").orEmpty().ifBlank { "llm" }
        set(v) {
            sp(this).edit().putString("reply_judge", v.trim().ifBlank { "llm" }).apply()
        }

    val Context.chatProb: String
        get() = sp(this).getString("chat_prob", "0.3").orEmpty().ifBlank { "0.3" }

    /** 群里自动接话（没有界面行，默认关）：开了才轮到「回复判定」决定要不要插话 */
    val Context.groupAutochat: Boolean
        // 历史键是字符串（"0"/"1"），按字符串读，别用 getBoolean 撞 ClassCastException
        get() = sp(this).getString("group_autochat", "0").orEmpty().trim() == "1"

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
        try {
            val f = File(ctx.filesDir, "localbot/llm.json")
            f.parentFile?.mkdirs()
            f.writeText(o.toString(2))
        } catch (_: Exception) {
        }
    }
}