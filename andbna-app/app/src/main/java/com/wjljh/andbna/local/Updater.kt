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

    /** 检查结论：有新版 / 已最新 / 失败（自动检查失败只记日志，手动「立即检查」才提示） */
    sealed interface Result {
        data class Newer(val release: Release) : Result
        data object Latest : Result
        data object Failed : Result
    }

    /** 一次检查的结论 + 给人看的一行说明（手动「立即检查」把它直接写进界面） */
    data class Checked(val result: Result, val note: String)

    fun apiUrl(ctx: Context): String = Prefs.get(ctx, KEY_API).ifBlank { DEFAULT_API }

    fun setApiUrl(ctx: Context, url: String) = Prefs.put(ctx, KEY_API, url)

    suspend fun check(ctx: Context, currentVersionName: String): Result =
        checkDetailed(ctx, currentVersionName).result

    /** 一次检查：查到有更新就顺手覆盖本地缓存（失败/最新都不留过期缓存） */
    suspend fun checkDetailed(ctx: Context, currentVersionName: String): Checked =
        withContext(Dispatchers.IO) {
            val c = fetch(apiUrl(ctx), currentVersionName)
            applyCache(ctx, c)
            c
        }

    /**
     * 手动「立即检查」：不看 30 分钟节流、不看自动检查开关，一律联网；
     * 不论成败都写 last_check_at —— 是用户自己点的，下次启动不必再补查一遍。
     */
    suspend fun checkNow(ctx: Context, currentVersionName: String): Checked =
        withContext(Dispatchers.IO) {
            val c = fetch(apiUrl(ctx), currentVersionName)
            sp(ctx).edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
            applyCache(ctx, c)
            Log.i(TAG, "更新：手动立即检查 → " + c.note)
            c
        }

    private fun fetch(url: String, current: String): Checked {
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
            // 响应体只进日志（限流 / UA / 路径错全靠它区分），界面那行小字保持干净
            if (code != 200) return fail("HTTP " + code, errorHint(conn))
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

    /** 非 200 时把响应体前一段带上：403/404 光看码分不清是限流、UA 还是路径错 */
    private fun errorHint(conn: HttpURLConnection): String {
        val body = runCatching {
            conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        }.getOrDefault("").trim().replace("\n", " ")
        if (body.isBlank()) return ""
        return "：" + body.take(160)
    }

    private fun fail(reason: String, detail: String = ""): Checked {
        Log.w(TAG, "更新：检查失败（" + reason + detail + "）")
        return Checked(Result.Failed, reason)
    }

    /** 只把 JSON 解析成 [Release]（不判断新旧）；字段不全 / 不是 JSON 时记日志并返回 null */
    private fun parseRelease(json: String): Release? {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            Log.w(TAG, "更新：解析失败（" + (e.message ?: "不是 JSON") + "）")
            return null
        }
        val tag = root.optString("tag_name").trim()
        if (tag.isBlank()) {
            Log.w(TAG, "更新：解析失败（没有 tag_name）")
            return null
        }
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
        if (apkUrl.isBlank()) {
            Log.w(TAG, "更新：解析失败（" + tag + " 没有 .apk 资产）")
            return null
        }
        return Release(
            tag = tag,
            versionName = tag.trimStart('v', 'V'),
            body = body.orEmpty(),
            apkUrl = apkUrl,
            apkName = apkName,
            size = apkSize,
        )
    }

    private fun parse(json: String, current: String): Checked {
        val rel = parseRelease(json) ?: return fail("解析失败：JSON 不完整或没有 .apk 资产")
        if (compare(versionParts(rel.tag), versionParts(current)) <= 0) {
            Log.i(TAG, "更新：已是最新（当前 " + current + "，远端 " + rel.tag + "）")
            return Checked(Result.Latest, "已是最新（当前 " + current + "，远端 " + rel.tag + "）")
        }
        Log.i(TAG, "更新：发现新版本 " + rel.tag + "（" + rel.apkName + "，" + rel.size + " 字节）")
        return Checked(Result.Newer(rel), "发现新版本 " + rel.tag)
    }

    /** 版本号 → 数字段（"v1.0.5" → [1,0,5]；非数字段按 0 处理，不让脏 tag 把比较搞崩） */
    fun versionParts(v: String): List<Int> = v.trim()
        .removePrefix("v").removePrefix("V")
        .split('.', '-', '+', '_')
        .map { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    // ── 自动检查（最短间隔 30 分钟，缓存优先）──────────────────────────────────

    /** 落盘键：自动检查开关（默认关）/ 上次检查时间 / 已忽略的版本号 */
    const val KEY_AUTO = "auto_check_update"
    const val KEY_LAST = "last_check_at"
    const val KEY_IGNORED = "ignored_version_code"

    /** 节流窗口：最少间隔 30 分钟（缓存优先，不再有 24 小时硬节流） */
    const val INTERVAL_MS: Long = 30L * 60L * 1000L

    private fun sp(ctx: Context) = ctx.getSharedPreferences("andbna_local", Context.MODE_PRIVATE)

    fun autoEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_AUTO, false)

    fun setAutoEnabled(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean(KEY_AUTO, on).apply()
        Log.i(TAG, "更新：自动检查更新 → " + (if (on) "开" else "关"))
    }

    fun lastCheckAt(ctx: Context): Long = sp(ctx).getLong(KEY_LAST, 0L)

    fun ignoredCode(ctx: Context): Int = sp(ctx).getInt(KEY_IGNORED, 0)

    /** 「忽略此版本」：只压下这一个版本号，更高的版本照样提示；顺手删掉落盘缓存 */
    fun ignoreVersion(ctx: Context, versionName: String) {
        val code = versionCodeOf(versionName)
        sp(ctx).edit().putInt(KEY_IGNORED, code).apply()
        val gone = clearCache(ctx)
        Log.i(TAG, "更新：已忽略版本 " + versionName + "（versionCode " + code + "）" +
            if (gone) "，缓存已清理" else "")
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
     * 开关关着、距上次检查不足 30 分钟（用缓存）、这个版本已被用户忽略。
     * 失败静默；**失败不写 last_check_at**，下次启动还能再试一次。
     */
    suspend fun autoCheck(ctx: Context, currentVersionName: String): Release? =
        withContext(Dispatchers.IO) {
            if (!autoEnabled(ctx)) {
                Log.i(TAG, "更新：自动检查关闭，跳过（不联网）")
                return@withContext null
            }
            if (!dueForCheck(ctx)) {
                Log.i(TAG, "更新：距上次检查不足 30 分钟，使用缓存")
                return@withContext null
            }
            when (val r = check(ctx, currentVersionName)) {
                is Result.Newer -> {
                    sp(ctx).edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
                    val code = versionCodeOf(r.release.versionName)
                    if (code > 0 && code <= ignoredCode(ctx)) {
                        clearCache(ctx)
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

    // ── 每日 12 点后台检查（前台服务里每 10 分钟问一次）──────────────────────

    /**
     * 已过「今天 12:00」、且上次检查早于「今天 12:00」→ 查一次。
     * 与启动检查同一条口径：「自动检查更新」关着就一次网络请求都不发。
     * 成功（有新版 / 已最新）才写 last_check_at，失败不写，下一轮 10 分钟后自然重试。
     * 返回查到的 [Release]（null = 没触发 / 无新版 / 失败）。
     */
    suspend fun dailyCheckIfDue(ctx: Context, currentVersionName: String): Release? =
        withContext(Dispatchers.IO) {
            if (!autoEnabled(ctx)) {
                Log.i(TAG, "更新：每日检查跳过（自动检查已关闭）")
                return@withContext null
            }
            val now = System.currentTimeMillis()
            val noon = noonToday(now)
            if (now < noon) return@withContext null
            if (lastCheckAt(ctx) >= noon) return@withContext null
            Log.i(TAG, "更新：每日 12 点检查触发")
            val c = fetch(apiUrl(ctx), currentVersionName)
            applyCache(ctx, c)
            when (val r = c.result) {
                is Result.Newer -> {
                    sp(ctx).edit().putLong(KEY_LAST, now).apply()
                    val code = versionCodeOf(r.release.versionName)
                    if (code > 0 && code <= ignoredCode(ctx)) {
                        Log.i(TAG, "更新：版本 " + r.release.versionName + " 已被忽略，不提示")
                        null
                    } else {
                        r.release
                    }
                }
                Result.Latest -> {
                    sp(ctx).edit().putLong(KEY_LAST, now).apply()
                    null
                }
                Result.Failed -> {
                    // 失败不写 last_check_at：这一轮不算数，10 分钟后（或下次启动）再试
                    Log.w(TAG, "更新：每日 12 点检查失败，下轮再试（" + c.note + "）")
                    null
                }
            }
        }

    /** 今天 12:00 的毫秒时间戳（本机时区；不改系统时间也能量出「过没过点」） */
    private fun noonToday(now: Long): Long = java.util.Calendar.getInstance().apply {
        timeInMillis = now
        set(java.util.Calendar.HOUR_OF_DAY, 12)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    // ── 下载前校验（缓存里的 assetUrl 可能已经失效）────────────────────────────
    //
    // 真机事故：update_info.json 里留着测试源写的 .../AndbnA_1.0.8.apk，点更新直接 404，
    // 用户只看到「下载失败 HTTP 404」。所以下载前先探活，失效就按 API 重新拿一次
    // browser_download_url 覆盖缓存，再不行才失败。

    /** 下载准备结论：release 非空就能下；为空时 [reason] 是给人看的原因 */
    data class Prep(val release: Release?, val reason: String = "")

    /**
     * 下载前校验（同步，跑在下载线程上）：
     *  ① HEAD 探活 assetUrl（不支持 HEAD 的源退化成 Range: bytes=0-0 的 GET）；
     *  ② 能用 → 顺手核对 API：缓存 versionCode 与 API 不一致（同 tag 被重发）或 URL 变了，以 API 为准；
     *  ③ 不能用 → 「缓存 URL 已失效，重新获取」，重新拉一次 API 并覆盖缓存后重试；
     *  ④ 仍不行 → 返回 null + 「URL 失效且刷新后仍失败（HTTP xxx）」。
     */
    fun prepareDownload(ctx: Context, url: String, name: String, size: Long): Prep {
        val cachedName = readCache(ctx)?.release?.versionName.orEmpty()
        val code = probe(url)
        if (code == 200 || code == 206) {
            val latest = fetchReleaseQuiet(ctx)
            if (latest != null) {
                val cached = versionCodeOf(cachedName)
                val fresh = versionCodeOf(latest.versionName)
                if (fresh > cached || (fresh == cached && latest.apkUrl != url)) {
                    Log.i(TAG, "更新：缓存已过期（" + (cachedName.ifBlank { "未知" }) + " → " +
                        latest.versionName + "），以 API 为准")
                    saveCache(ctx, latest)
                    return Prep(latest)
                }
            }
            return Prep(
                Release(
                    tag = "v" + cachedName,
                    versionName = cachedName,
                    body = "",
                    apkUrl = url,
                    apkName = name,
                    size = size,
                )
            )
        }
        Log.i(TAG, "更新：缓存 URL 已失效，重新获取")
        val latest = fetchReleaseQuiet(ctx)
        if (latest == null) {
            val reason = "URL 失效且刷新后仍失败（" + httpText(code) + "）"
            Log.w(TAG, "下载失败：" + reason)
            return Prep(null, reason)
        }
        saveCache(ctx, latest)
        val fresh = probe(latest.apkUrl)
        if (fresh == 200 || fresh == 206) return Prep(latest)
        val reason = "URL 失效且刷新后仍失败（" + httpText(fresh) + "）"
        Log.w(TAG, "下载失败：" + reason)
        return Prep(null, reason)
    }

    private fun httpText(code: Int): String = if (code > 0) "HTTP " + code else "连接失败"

    /** 探活：200/206 = 可以下；0 = 连不上（超时 / DNS / IO） */
    private fun probe(url: String): Int {
        val head = requestCode(url, "HEAD", range = false)
        // 少数源不支持 HEAD（405/403/501），退化成只取 1 字节的 GET
        return if (head == 405 || head == 501 || head == 403) requestCode(url, "GET", range = true) else head
    }

    private fun requestCode(url: String, method: String, range: Boolean): Int {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                if (range) setRequestProperty("Range", "bytes=0-0")
            }
            conn.responseCode
        } catch (e: Throwable) {
            0
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** 只解析、不判断新旧：给「刷新失效 URL」用；失败只记日志并返回 null */
    private fun fetchReleaseQuiet(ctx: Context): Release? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(apiUrl(ctx)).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "更新：刷新失败（HTTP " + code + "）")
                return null
            }
            parseRelease(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Throwable) {
            Log.w(TAG, "更新：刷新失败（" + (e.message ?: e.javaClass.simpleName) + "）")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    // ── 本地缓存（<filesDir>/update_info.json）────────────────────────────────
    //
    // 「发现新版本」不能只活在进程内存里：重启后要么重新联网（断网 / 被限流就没内容），
    // 要么干脆看不见。查到有更新就落盘一份；启动时先读它，当前版本没超过缓存版本就直接出卡。
    // 备份不含它（临时状态，BackupIO 不用管）。

    private const val CACHE_FILE = "update_info.json"

    /** 读出来的缓存：版本 + 缓存时刻（日志要写「缓存于 …」） */
    data class Cached(val release: Release, val checkedAt: Long)

    private fun cacheFile(ctx: Context) = java.io.File(ctx.filesDir, CACHE_FILE)

    /** 检查结论回写缓存：有更新→覆盖；已最新→清掉（过期）；失败→不动（断网也看得到上次结果） */
    private fun applyCache(ctx: Context, c: Checked) {
        when (val r = c.result) {
            is Result.Newer -> saveCache(ctx, r.release)
            Result.Latest -> if (clearCache(ctx)) Log.i(TAG, "更新：远端已是最新，清理过期缓存")
            Result.Failed -> Unit
        }
    }

    /** 有更新才落盘：tag / versionName / versionCode / body / assetUrl / assetSize / checkedAt */
    fun saveCache(ctx: Context, r: Release) {
        val json = JSONObject().apply {
            put("tag", r.tag)
            put("versionName", r.versionName)
            put("versionCode", versionCodeOf(r.versionName))
            put("body", r.body)
            put("assetUrl", r.apkUrl)
            // 下载落盘用的文件名（需求字段之外多这一个，省得从 URL 里猜）
            put("assetName", r.apkName)
            put("assetSize", r.size)
            put("checkedAt", System.currentTimeMillis())
        }
        runCatching { cacheFile(ctx).writeText(json.toString()) }
            .onSuccess { Log.i(TAG, "更新：缓存已写入 " + r.versionName) }
            .onFailure { Log.w(TAG, "更新：缓存写入失败（" + (it.message ?: "IO 异常") + "）") }
    }

    /** 读缓存；文件不在 / 解析不了都返回 null（坏文件顺手删掉） */
    fun readCache(ctx: Context): Cached? {
        val f = cacheFile(ctx)
        if (!f.isFile) return null
        return try {
            val o = JSONObject(f.readText())
            val versionName = o.optString("versionName").trim()
            val url = o.optString("assetUrl").trim()
            if (versionName.isBlank() || url.isBlank()) {
                Log.w(TAG, "更新：缓存字段不全，丢弃")
                clearCache(ctx)
                return null
            }
            Cached(
                Release(
                    tag = o.optString("tag").trim().ifBlank { "v" + versionName },
                    versionName = versionName,
                    body = o.optString("body"),
                    apkUrl = url,
                    apkName = o.optString("assetName").trim().ifBlank { url.substringAfterLast('/') },
                    size = o.optLong("assetSize"),
                ),
                o.optLong("checkedAt"),
            )
        } catch (e: Exception) {
            Log.w(TAG, "更新：缓存解析失败，丢弃（" + (e.message ?: "不是 JSON") + "）")
            clearCache(ctx)
            null
        }
    }

    /** 删缓存；返回是否真的删掉了文件 */
    fun clearCache(ctx: Context): Boolean =
        runCatching { cacheFile(ctx).delete() }.getOrDefault(false)

    /**
     * 启动时用（不联网）：缓存里那个版本比当前新、且没被忽略 → 返回它。
     * 当前版本已经追平/超过缓存 = 用户装上了 → 清缓存并记一条日志。
     */
    fun cachedRelease(ctx: Context, currentVersionName: String): Release? {
        val c = readCache(ctx) ?: return null
        val cachedCode = versionCodeOf(c.release.versionName)
        if (cachedCode <= versionCodeOf(currentVersionName)) {
            val gone = clearCache(ctx)
            Log.i(TAG, "更新：缓存已失效并清理（当前 " + currentVersionName + "，缓存 " +
                c.release.versionName + "）" + if (gone) "" else "（文件已被删过）")
            return null
        }
        if (cachedCode <= ignoredCode(ctx)) {
            Log.i(TAG, "更新：缓存里的 " + c.release.versionName + " 已被忽略，不提示")
            return null
        }
        Log.i(TAG, "更新：命中本地缓存（" + c.release.versionName + "，缓存于 " +
            timeText(c.checkedAt) + "）")
        return c.release
    }

    /** 毫秒 → 「2026-11-06 21:44」（日志与卡片副标题共用） */
    fun timeText(at: Long): String =
        if (at <= 0L) "未知时间"
        else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            .format(java.util.Date(at))

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
