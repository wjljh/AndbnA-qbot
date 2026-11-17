package com.wjljh.andbna.local

import android.content.Context
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File

/**
 * 群名缓存：<botdir>/group_names.json = {openid: {name, members, ts}}，有效期 7 天。
 *
 * 名字只来自官方 GET /v2/groups/{openid}/info（30 QPM），所以这里限流很保守：
 * 一次最多补 [BATCH] 个、串行、相邻两次隔 [GAP_MS]。查不到就静默 ——
 * 列表用 openid 前 8 位兜底，绝不为了一个名字把整页卡住或刷屏报错。
 */
object GroupNames {

    /** 缓存有效期：7 天（过期后下次打开列表再取一次） */
    const val TTL_MS: Long = 7L * 24 * 60 * 60 * 1000

    /** 一次补几个（30 QPM 下 5 个 × 200ms 远远打不满） */
    const val BATCH: Int = 5

    /** 相邻两次请求的间隔 */
    const val GAP_MS: Long = 200L

    /** 同一个群「取不到」的日志最多每条隔多久写一行（10 分钟），免得轮询把日志页刷满 */
    private const val FAIL_NOTE_GAP_MS: Long = 10L * 60 * 1000

    /** gid 前 8 位 → 上次写「取不到」的时间（只在内存里，重启就重来） */
    private val failNotes = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 一个群的缓存项；[name] 为空视为「没缓存」（下次再试）。 */
    data class Entry(val name: String = "", val members: Int = 0, val ts: Long = 0L) {
        fun fresh(now: Long = System.currentTimeMillis()): Boolean =
            name.isNotBlank() && ts > 0L && now - ts < TTL_MS
    }

    fun file(ctx: Context, appid: String): File =
        File(File(BotHost.dataDir(ctx), appid.trim()), "group_names.json")

    /** 读全量缓存（坏 JSON / 空名字的项直接跳过，不抛异常）。 */
    fun load(ctx: Context, appid: String): Map<String, Entry> {
        val f = file(ctx, appid)
        if (!f.isFile) return emptyMap()
        val o = runCatching { JSONObject(f.readText(Charsets.UTF_8)) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, Entry>()
        o.keys().forEach { gid ->
            val e = o.optJSONObject(gid) ?: return@forEach
            val name = e.optString("name", "").trim()
            if (name.isBlank()) return@forEach
            out[gid] = Entry(name, e.optInt("members", 0), e.optLong("ts", 0L))
        }
        return out
    }

    /** 写一个群（读-改-写整份文件：群数量是「几十」量级，不值得上数据库）。 */
    fun put(ctx: Context, appid: String, gid: String, name: String, members: Int) {
        val key = gid.trim()
        if (key.isBlank() || name.isBlank()) return
        try {
            val o = JSONObject()
            load(ctx, appid).forEach { (id, e) ->
                o.put(id, entryJson(e))
            }
            o.put(key, entryJson(Entry(name.trim(), members.coerceAtLeast(0), System.currentTimeMillis())))
            val f = file(ctx, appid)
            f.parentFile?.mkdirs()
            f.writeText(o.toString(1), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    /**
     * 补缓存：只补「没有有效缓存」的群，最多 [max] 个，串行 + 间隔 [GAP_MS]。
     * 返回真的补到名字的个数（0 = 全是缓存 / 全失败，页面什么都不用做）。
     */
    suspend fun fill(ctx: Context, appid: String, ids: List<String>, max: Int = BATCH): Int {
        val now = System.currentTimeMillis()
        val cached = load(ctx, appid)
        val todo = ids.map { it.trim() }.filter { it.isNotBlank() }
            .filter { !(cached[it]?.fresh(now) ?: false) }.distinct().take(max)
        var got = 0
        todo.forEachIndexed { i, gid ->
            if (i > 0) delay(GAP_MS)
            val raw = BotHost.groupInfo(ctx, appid, gid)
            val o = runCatching { JSONObject(raw) }.getOrNull()
            if (o == null) {
                noteFail(gid, "")
                return@forEachIndexed
            }
            if (!o.optBoolean("ok", false)) {
                val err = o.optString("error", "")
                noteFail(gid, err)
                // 频控（429）/ 没权限（403）：这一轮别再往下打了，越打越慢
                if (httpCode(err) in BLOCKING_CODES) return got
                return@forEachIndexed
            }
            val name = o.optString("name", "").trim()
            if (name.isBlank()) {
                noteFail(gid, "响应里没有群名")
                return@forEachIndexed
            }
            put(ctx, appid, gid, name, o.optInt("members", 0))
            got++
        }
        return got
    }

    /**
     * 群显示名：有群名就写群名，取不到就「群 + openid 前 8 位」——
     * 界面上任何地方都不摊开整串 openid（太长、也看不出是什么群）。
     */
    fun label(gid: String, name: String): String =
        if (name.isNotBlank()) name.trim() else "群 " + gid.trim().take(8)

    /** 会话键的短形式：g/p 前缀 + openid 前 8 位（私聊以前是 take(18)，也一并压短）。 */
    fun shortKey(key: String): String {
        val k = key.trim()
        if (k.isEmpty()) return ""
        return k.take(1) + k.drop(1).take(8)
    }

    /** 一行日志：群名：<gid 前 8 位> 取不到（HTTP xxx），同一个群 10 分钟内只写一次。 */
    private fun noteFail(gid: String, error: String) {
        val id8 = gid.trim().take(8)
        val now = System.currentTimeMillis()
        val prev = failNotes[id8]
        if (prev != null && now - prev < FAIL_NOTE_GAP_MS) return
        failNotes[id8] = now
        val code = httpCode(error)
        val why = if (code > 0) "HTTP " + code
        else error.trim().take(40).ifBlank { "无响应" }
        BotHost.logNote("群名：" + id8 + " 取不到（" + why + "）")
    }

    /** 从 Python 的错误串里抠 3 位状态码（urllib 的 HTTP Error 401 / Status: 429）。 */
    private fun httpCode(error: String): Int {
        val m = Regex("(?i)(?:http[^0-9]{0,12}|status[^0-9]{0,3}|状态码[^0-9]{0,3})([0-9]{3})")
            .find(error.trim()) ?: return 0
        return m.groupValues[1].toIntOrNull() ?: 0
    }

    /** 命中就别再往下打了：429 频控 / 403 没权限，同一次批量里继续只会更糟。 */
    private val BLOCKING_CODES = setOf(403, 429)

    private fun entryJson(e: Entry): JSONObject = JSONObject()
        .put("name", e.name)
        .put("members", e.members)
        .put("ts", e.ts)
}
