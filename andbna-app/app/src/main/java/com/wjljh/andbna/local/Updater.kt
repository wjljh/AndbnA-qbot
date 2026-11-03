package com.wjljh.andbna.local

import android.content.Context
import android.util.Log
import com.wjljh.andbna.ui.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 检查更新：GET 更新源 JSON（默认 GitHub releases/latest），读 tag_name / body / assets[]。
 *
 * 两件硬要求：
 *  · GitHub 的匿名 API **必须带 User-Agent**，不带直接 403（实测）；
 *  · 失败一律静默 —— 只记一条「更新：检查失败（原因）」日志，不在界面上打扰人。
 *
 * 更新源可换（prefs 键 [KEY_API]，与搜索后端同一思路）：留空 = 官方 GitHub，
 * 自建镜像只要返回同样形状的 JSON（tag_name / body / assets[].browser_download_url / size）即可。
 */
object Updater {

    private const val TAG = "更新"

    /** 落盘键：自定义更新源（空 = 用 [DEFAULT_API]） */
    const val KEY_API = "update_api"

    /** 默认更新源：GitHub 官方 releases/latest */
    const val DEFAULT_API = "https://api.github.com/repos/wjljh/AndbnA-qbot/releases/latest"

    /** 超时 8s：检查更新不值得让人等 */
    private const val TIMEOUT_MS = 8_000

    /** GitHub 对匿名请求要求带 UA；这也顺便说明是谁在查 */
    private const val UA = "AndbnA-Updater/1.0 (Android)"

    /** 解析出来的一个 release：展示用 tag/body，下载用 url/大小 */
    data class Release(
        val tag: String,
        val versionName: String,
        val body: String,
        val apkUrl: String,
        val apkName: String,
        val size: Long,
    )

    /** 检查结论：有新版 / 已最新 / 失败（失败只记日志，界面静默） */
    sealed interface Result {
        data class Newer(val release: Release) : Result
        data object Latest : Result
        data object Failed : Result
    }

    fun apiUrl(ctx: Context): String = Prefs.get(ctx, KEY_API).ifBlank { DEFAULT_API }

    fun setApiUrl(ctx: Context, url: String) = Prefs.put(ctx, KEY_API, url)

    suspend fun check(ctx: Context, currentVersionName: String): Result =
        withContext(Dispatchers.IO) { fetch(apiUrl(ctx), currentVersionName) }

    private fun fetch(url: String, current: String): Result {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            val code = conn.responseCode
            if (code != 200) return fail("HTTP " + code)
            parse(conn.inputStream.bufferedReader().use { it.readText() }, current)
        } catch (e: CancellationException) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            fail("超时（" + TIMEOUT_MS / 1000 + "s）：" + url)
        } catch (e: java.net.UnknownHostException) {
            fail("域名解析失败：" + (e.message ?: url))
        } catch (e: java.io.IOException) {
            fail("网络错误：" + (e.message ?: "IO 异常"))
        } catch (e: Throwable) {
            fail(e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun fail(reason: String): Result {
        Log.w(TAG, "更新：检查失败（" + reason + "）")
        return Result.Failed
    }

    private fun parse(json: String, current: String): Result {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return fail("解析失败：" + (e.message ?: "不是 JSON"))
        }
        val tag = root.optString("tag_name").trim()
        if (tag.isBlank()) return fail("解析失败：没有 tag_name")
        val body = root.optString("body")
        val assets: JSONArray = root.optJSONArray("assets") ?: JSONArray()
        var apkUrl = ""
        var apkName = ""
        var apkSize = 0L
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            apkUrl = a.optString("browser_download_url")
            apkName = name
            apkSize = a.optLong("size")
            break
        }
        if (compare(versionParts(tag), versionParts(current)) <= 0) {
            Log.i(TAG, "更新：已是最新（当前 " + current + "，远端 " + tag + "）")
            return Result.Latest
        }
        if (apkUrl.isBlank()) return fail("解析失败：" + tag + " 没有 .apk 资产")
        val rel = Release(
            tag = tag,
            versionName = tag.trimStart('v', 'V'),
            body = body.orEmpty(),
            apkUrl = apkUrl,
            apkName = apkName,
            size = apkSize,
        )
        Log.i(TAG, "更新：发现新版本 " + tag + "（" + apkName + "，" + apkSize + " 字节）")
        return Result.Newer(rel)
    }

    /** 版本号 → 数字段（"v1.0.5" → [1,0,5]；非数字段按 0 处理，不让脏 tag 把比较搞崩） */
    fun versionParts(v: String): List<Int> = v.trim()
        .removePrefix("v").removePrefix("V")
        .split('.', '-', '+', '_')
        .map { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    // ── 自动检查（每 24 小时最多一次）────────────────────────────────────────

    /** 落盘键：自动检查开关（默认关）/ 上次检查时间 / 已忽略的版本号 */
    const val KEY_AUTO = "auto_check_update"
    const val KEY_LAST = "last_check_at"
    const val KEY_IGNORED = "ignored_version_code"

    /** 节流窗口：24 小时 */
    const val INTERVAL_MS: Long = 24L * 60L * 60L * 1000L

    private fun sp(ctx: Context) = ctx.getSharedPreferences("andbna_local", Context.MODE_PRIVATE)

    fun autoEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_AUTO, false)

    fun setAutoEnabled(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean(KEY_AUTO, on).apply()
        Log.i(TAG, "更新：自动检查更新 → " + (if (on) "开" else "关"))
    }

    fun lastCheckAt(ctx: Context): Long = sp(ctx).getLong(KEY_LAST, 0L)

    fun ignoredCode(ctx: Context): Int = sp(ctx).getInt(KEY_IGNORED, 0)

    /** 「忽略此版本」：只压下这一个版本号，更高的版本照样提示 */
    fun ignoreVersion(ctx: Context, versionName: String) {
        val code = versionCodeOf(versionName)
        sp(ctx).edit().putInt(KEY_IGNORED, code).apply()
        Log.i(TAG, "更新：已忽略版本 " + versionName + "（versionCode " + code + "）")
    }

    /** 版本名 → 构建脚本那套 versionCode（1.0.4 → 1040），用于「忽略该版本」的比较 */
    fun versionCodeOf(versionName: String): Int {
        val p = versionParts(versionName)
        return p.getOrElse(0) { 0 } * 1000 + p.getOrElse(1) { 0 } * 100 + p.getOrElse(2) { 0 } * 10
    }

    fun dueForCheck(ctx: Context): Boolean =
        autoEnabled(ctx) && System.currentTimeMillis() - lastCheckAt(ctx) >= INTERVAL_MS

    /**
     * 启动时那一轮自动检查。三种情况直接返回 null（不联网 / 不提示）：
     * 开关关着、距上次检查不到 24 小时、这个版本已被用户忽略。
     * 失败静默；**失败不写 last_check_at**，下次启动还能再试一次。
     */
    suspend fun autoCheck(ctx: Context, currentVersionName: String): Release? =
        withContext(Dispatchers.IO) {
            if (!autoEnabled(ctx)) {
                Log.i(TAG, "更新：自动检查关闭，跳过（不联网）")
                return@withContext null
            }
            if (!dueForCheck(ctx)) {
                Log.i(TAG, "更新：距上次检查不到 24 小时，跳过")
                return@withContext null
            }
            when (val r = check(ctx, currentVersionName)) {
                is Result.Newer -> {
                    sp(ctx).edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
                    val code = versionCodeOf(r.release.versionName)
                    if (code > 0 && code <= ignoredCode(ctx)) {
                        Log.i(TAG, "更新：版本 " + r.release.versionName + " 已被忽略，不提示")
                        null
                    } else {
                        r.release
                    }
                }
                Result.Latest -> {
                    sp(ctx).edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
                    null
                }
                Result.Failed -> null
            }
        }

    /** 逐段比较：>0 表示 a 比 b 新 */
    fun compare(a: List<Int>, b: List<Int>): Int {
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }
}
