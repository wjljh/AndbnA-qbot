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
        val todo = ids.filter { !(cached[it]?.fresh(now) ?: false) }.distinct().take(max)
        var got = 0
        todo.forEachIndexed { i, gid ->
            if (i > 0) delay(GAP_MS)
            val raw = BotHost.groupInfo(ctx, appid, gid)
            if (raw.isBlank()) return@forEachIndexed
            val o = runCatching { JSONObject(raw) }.getOrNull() ?: return@forEachIndexed
            if (!o.optBoolean("ok", false)) return@forEachIndexed
            val name = o.optString("name", "").trim()
            if (name.isBlank()) return@forEachIndexed
            put(ctx, appid, gid, name, o.optInt("members", 0))
            got++
        }
        return got
    }

    private fun entryJson(e: Entry): JSONObject = JSONObject()
        .put("name", e.name)
        .put("members", e.members)
        .put("ts", e.ts)
}
