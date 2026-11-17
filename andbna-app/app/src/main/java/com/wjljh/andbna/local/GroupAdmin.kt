package com.wjljh.andbna.local

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 群管理规则：<botdir>/group_admin/<group_openid>.json。
 *
 * 契约（Python 侧按这些键名读，改格式要两边一起改）：
 * {{"enabled":true,"mode":"keyword+model",
 *  "keywords":["卖挂","开挂","外挂","挂机脚本","辅助脚本"],
 *  "steps":[{{"n":1,"mute_min":10}},{{"n":2,"mute_min":60}}],
 *  "kick_at":3,"warn_enabled":true,"recall_enabled":true,
 *  "welcome":{{"enabled":false,"at":true,"text":""}}}}
 *
 * 三条约定：
 *  · mode 只有三个值：keyword / keyword+model / model，未知值按推荐的 keyword+model 处理；
 *  · 文件不存在 = 这个群还没配置（列表里显示「未配置」），读出来是带默认词库与两级阶梯的规则；
 *  · 文件存在时**缺项按默认补**，但空的 keywords / steps 数组算「用户真的删空了」，不补回默认。
 *
 * 进群欢迎（welcome）由 bot 详情页「问候 › 进群欢迎」写在 <botdir>/group_admin/_default.json，
 * 本群文件里没有 welcome 就沿用那份默认 —— 所以下面 [load] 会拿 [_default] 兜底。
 *
 * 统计（[stats]）读共享计数文件 <botdir>/group_admin/_stats.json：
 *   {{"<gid>":{{"users":3,"hits":7}}}}（读不到就返回 null，页面显示「—」）。
 */
object GroupAdmin {

    /** 规则目录名（与 Python 侧一致，别改）。 */
    const val DIR = "group_admin"

    /** bot 级欢迎语默认值（本群文件没有 welcome 时用它）。 */
    const val DEFAULT_FILE = "_default.json"

    /** 共享计数文件（运行侧写，这里只读）。 */
    const val STATS_FILE = "_stats.json"

    /** 默认词库：契约里那一组。 */
    val DEFAULT_KEYWORDS = listOf("卖挂", "开挂", "外挂", "挂机脚本", "辅助脚本")

    /** 默认阶梯：第 1 次禁言 10 分钟、第 2 次禁言 60 分钟。 */
    val DEFAULT_STEPS = listOf(Step(n = 1, muteMin = 10), Step(n = 2, muteMin = 60))

    /** 默认踢出档。 */
    const val DEFAULT_KICK_AT = 3

    const val MODE_KEYWORD = "keyword"
    const val MODE_KEYWORD_MODEL = "keyword+model"
    const val MODE_MODEL = "model"

    /** 三级阶梯：第 [n] 次违规 → 禁言 [muteMin] 分钟。 */
    data class Step(val n: Int = 1, val muteMin: Int = 10)

    /** 进群欢迎：开关 + 是否 @ 新成员 + 欢迎语文本。 */
    data class Welcome(val enabled: Boolean = false, val at: Boolean = true, val text: String = "")

    /** 一个群的完整规则（缺省值 = 契约里的默认值）。 */
    data class Rule(
        val enabled: Boolean = true,
        val mode: String = MODE_KEYWORD_MODEL,
        val keywords: List<String> = DEFAULT_KEYWORDS,
        val steps: List<Step> = DEFAULT_STEPS,
        val kickAt: Int = DEFAULT_KICK_AT,
        val warnEnabled: Boolean = true,
        val recallEnabled: Boolean = true,
        val welcome: Welcome = Welcome(),
    )

    /** 共享计数文件里本群的那一行。 */
    data class Stats(val users: Int = 0, val hits: Int = 0)

    fun dir(ctx: Context, appid: String): File =
        File(BotHost.dataDir(ctx), appid.trim()).resolve(DIR)

    fun file(ctx: Context, appid: String, gid: String): File =
        File(dir(ctx, appid), gid.trim() + ".json")

    fun has(ctx: Context, appid: String, gid: String): Boolean =
        gid.trim().isNotBlank() && file(ctx, appid, gid).isFile

    /** 已经配过规则的群（目录里的 <gid>.json，跳过 _default / _stats 这类下划线文件）。 */
    fun configured(ctx: Context, appid: String): List<String> {
        val fs = runCatching { dir(ctx, appid).listFiles() }.getOrNull().orEmpty()
        return fs.filter { it.isFile && it.name.endsWith(".json") && !it.name.startsWith("_") }
            .map { it.name.removeSuffix(".json") }
            .filter { it.isNotBlank() }
            .sorted()
    }

    /** 读一个群的规则；没有文件 / 坏 JSON → 契约默认值（welcome 走 bot 级默认）。 */
    fun load(ctx: Context, appid: String, gid: String): Rule {
        val base = Rule(welcome = defaultWelcome(ctx, appid))
        val f = file(ctx, appid, gid)
        if (!f.isFile) return base
        val o = runCatching { JSONObject(f.readText(Charsets.UTF_8)) }.getOrNull() ?: return base
        return Rule(
            enabled = if (o.isNull("enabled")) base.enabled else o.optBoolean("enabled", base.enabled),
            mode = normMode(if (o.isNull("mode")) base.mode else o.optString("mode", base.mode)),
            keywords = if (o.isNull("keywords")) base.keywords else keywords(o.optJSONArray("keywords")),
            steps = if (o.isNull("steps")) base.steps else steps(o.optJSONArray("steps")),
            kickAt = if (o.isNull("kick_at")) base.kickAt
            else o.optInt("kick_at", base.kickAt).coerceIn(1, 99),
            warnEnabled = if (o.isNull("warn_enabled")) base.warnEnabled
            else o.optBoolean("warn_enabled", base.warnEnabled),
            recallEnabled = if (o.isNull("recall_enabled")) base.recallEnabled
            else o.optBoolean("recall_enabled", base.recallEnabled),
            // 本群文件里没有 welcome 就用 bot 级默认（「问候 › 进群欢迎」写的那份）
            welcome = if (o.isNull("welcome")) base.welcome
            else welcome(o.optJSONObject("welcome"), base.welcome),
        )
    }

    /**
     * 写一个群的规则：[.tmp] → rename 原子替换（进程被杀不会留下半截 JSON）。
     * 返回 null = 成功，否则是给人看的原因（页面直接提示）。
     */
    fun save(ctx: Context, appid: String, gid: String, rule: Rule): String? {
        val key = gid.trim()
        if (key.isBlank()) return "没有群 openid，写不了"
        if (rule.keywords.isEmpty() && rule.mode != MODE_MODEL) {
            return "关键词判定至少要有一个词：补一个，或者把判定方式改成「只用模型」"
        }
        val o = JSONObject()
            .put("enabled", rule.enabled)
            .put("mode", normMode(rule.mode))
            .put("keywords", JSONArray(clampKeywords(rule.keywords)))
            .put("steps", stepsJson(rule.steps))
            .put("kick_at", rule.kickAt.coerceIn(1, 99))
            .put("warn_enabled", rule.warnEnabled)
            .put("recall_enabled", rule.recallEnabled)
            .put("welcome", welcomeJson(rule.welcome))
        return if (writeAtomic(file(ctx, appid, key), o.toString(2))) null
        else "写入 " + DIR + "/" + key + ".json 失败"
    }

    /** bot 级进群欢迎（<botdir>/group_admin/_default.json 的 welcome）。 */
    fun defaultWelcome(ctx: Context, appid: String): Welcome {
        val f = File(dir(ctx, appid), DEFAULT_FILE)
        if (!f.isFile) return Welcome()
        val o = runCatching { JSONObject(f.readText(Charsets.UTF_8)) }.getOrNull() ?: return Welcome()
        return welcome(o.optJSONObject("welcome"), Welcome())
    }

    /**
     * 写 bot 级进群欢迎：先落 [_default]，再把**已经存在**的每个群规则文件的 welcome 同步一份。
     *
     * 两处都要写的原因：新群还没有规则文件，靠 [_default] 兜底；已经配过的群文件里有自己的
     * welcome 键，不同步的话在详情页改完对它们不生效 —— 而这一页本来就是「一改全群生效」。
     */
    fun saveWelcome(ctx: Context, appid: String, w: Welcome): String? {
        val d = dir(ctx, appid)
        val body = JSONObject().put("welcome", welcomeJson(w))
        if (!writeAtomic(File(d, DEFAULT_FILE), body.toString(2))) {
            return "写入 " + DIR + "/" + DEFAULT_FILE + " 失败"
        }
        configured(ctx, appid).forEach { gid ->
            val f = file(ctx, appid, gid)
            val o = runCatching { JSONObject(f.readText(Charsets.UTF_8)) }.getOrNull() ?: return@forEach
            writeAtomic(f, o.put("welcome", welcomeJson(w)).toString(2))
        }
        return null
    }

    /** 本群统计（共享计数文件里的那一行）；读不到 / 没有这一行 → null（页面显示「—」）。 */
    fun stats(ctx: Context, appid: String, gid: String): Stats? {
        val f = File(dir(ctx, appid), STATS_FILE)
        if (!f.isFile) return null
        val o = runCatching { JSONObject(f.readText(Charsets.UTF_8)) }.getOrNull() ?: return null
        val row = o.optJSONObject(gid.trim()) ?: return null
        val users = row.optInt("users", row.optInt("violators", 0))
        val hits = row.optInt("hits", row.optInt("count", 0))
        return Stats(users.coerceAtLeast(0), hits.coerceAtLeast(0))
    }

    /** 判定方式归一：只认契约里的三个值，其余（含空串）按推荐的 keyword+model。 */
    fun normMode(raw: String): String = when (raw.trim()) {
        MODE_KEYWORD -> MODE_KEYWORD
        MODE_MODEL -> MODE_MODEL
        else -> MODE_KEYWORD_MODEL
    }

    /** 词条清洗：去首尾空白、去空串、去重（保持顺序）。 */
    fun clampKeywords(list: List<String>): List<String> =
        list.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** 阶梯清洗：按 n 升序、n 去重、禁言分钟 1..43200（30 天）。 */
    fun clampSteps(list: List<Step>): List<Step> {
        val seen = LinkedHashMap<Int, Step>()
        list.forEach { s ->
            val n = s.n.coerceIn(1, 99)
            if (!seen.containsKey(n)) {
                seen[n] = Step(n, s.muteMin.coerceIn(1, 43200))
            }
        }
        return seen.values.sortedBy { it.n }
    }

    private fun keywords(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val v = arr.optString(i, "").trim()
            if (v.isNotEmpty()) out.add(v)
        }
        return out
    }

    private fun steps(arr: JSONArray?): List<Step> {
        if (arr == null) return emptyList()
        val out = ArrayList<Step>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                Step(
                    n = o.optInt("n", i + 1).coerceIn(1, 99),
                    muteMin = o.optInt("mute_min", 10).coerceIn(1, 43200),
                )
            )
        }
        return clampSteps(out)
    }

    private fun stepsJson(list: List<Step>): JSONArray {
        val arr = JSONArray()
        clampSteps(list).forEach { s -> arr.put(JSONObject().put("n", s.n).put("mute_min", s.muteMin)) }
        return arr
    }

    private fun welcome(o: JSONObject?, fallback: Welcome): Welcome {
        if (o == null) return fallback
        return Welcome(
            enabled = if (o.isNull("enabled")) fallback.enabled else o.optBoolean("enabled", fallback.enabled),
            at = if (o.isNull("at")) fallback.at else o.optBoolean("at", fallback.at),
            text = if (o.isNull("text")) fallback.text else o.optString("text", fallback.text).trim(),
        )
    }

    private fun welcomeJson(w: Welcome): JSONObject = JSONObject()
        .put("enabled", w.enabled)
        .put("at", w.at)
        .put("text", w.text.trim())

    /** 同目录 rename 是原子的：先写 .tmp 再换名，失败了就把旧的留着。 */
    private fun writeAtomic(f: File, text: String): Boolean = try {
        val dir = f.parentFile
        if (dir != null && !dir.exists()) dir.mkdirs()
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (tmp.renameTo(f)) {
            true
        } else {
            f.delete()
            tmp.renameTo(f)
        }
    } catch (e: Exception) {
        false
    }
}
