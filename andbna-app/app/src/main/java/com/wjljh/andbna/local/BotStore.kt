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
            // 识图模型：空串就是「沿用主模型」，Python 侧按空回退（这里不复制主模型）
            .put("vision_model", ctx.llmVisionModel)
            // 思考强度：空串也写（Python 侧按空 = 请求体不带该字段）
            .put("reasoning_effort", ctx.llmReasoningEffort)
        try {
            val f = File(ctx.filesDir, "localbot/llm.json")
            f.parentFile?.mkdirs()
            f.writeText(o.toString(2))
        } catch (_: Exception) {
        }
    }
}