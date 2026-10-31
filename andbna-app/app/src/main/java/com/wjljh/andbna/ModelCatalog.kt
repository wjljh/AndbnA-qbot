package com.wjljh.andbna

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * 「获取模型」：GET {baseUrl}/v1/models（OpenAI 兼容，Bearer 鉴权），解析 data[].id。
 *
 * 失败不静默：每个分支都给一句人能读的原因（超时 / 401 / 非 OpenAI 格式各自不同），
 * 由调用方直接贴到页面上的提示条里。超时定在 10s，取消耗掉连接。
 */
object ModelCatalog {

    private const val TIMEOUT_MS = 10_000

    class Out(val models: List<String> = emptyList(), val error: String = "") {
        val ok: Boolean get() = error.isEmpty() && models.isNotEmpty()
    }

    /** 接口地址 → models 地址：容忍末尾 /、/chat/completions、/v1 三种写法 */
    fun modelsUrl(api: String): String {
        var b = api.trim().trimEnd('/')
        if (b.endsWith("/chat/completions")) b = b.removeSuffix("/chat/completions")
        if (b.endsWith("/v1")) b = b.removeSuffix("/v1")
        return b + "/v1/models"
    }

    suspend fun fetch(api: String, key: String): Out = withContext(Dispatchers.IO) {
        if (api.isBlank()) return@withContext Out(error = "先填接口地址")
        if (key.isBlank()) return@withContext Out(error = "先填 API Key")
        val url = modelsUrl(api)
        var conn: HttpURLConnection? = null
        // 页面离开 / 重新点击就断开，别让旧请求挂着
        val cancelHook = coroutineContext[Job]?.invokeOnCompletion { runCatching { conn?.disconnect() } }
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer " + key.trim())
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            if (code == 401 || code == 403) {
                return@withContext Out(error = "鉴权失败（HTTP " + code + "）：API Key 不对或没有权限")
            }
            if (code !in 200..299) {
                val body = (conn.errorStream ?: conn.inputStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                return@withContext Out(
                    error = "HTTP " + code + "：" + body.take(120).ifBlank { "服务端没给原因" }
                )
            }
            parse(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            Out(error = "超时（" + (TIMEOUT_MS / 1000) + "s）：连不上 " + url + "，检查地址与网络")
        } catch (e: java.net.UnknownHostException) {
            Out(error = "域名解析失败：" + (e.message ?: url))
        } catch (e: java.io.IOException) {
            Out(error = "网络错误：" + (e.message ?: "IO 异常"))
        } catch (e: Throwable) {
            Out(error = "请求失败：" + (e.message ?: e.javaClass.simpleName))
        } finally {
            cancelHook?.dispose()
            conn?.disconnect()
        }
    }

    private fun parse(body: String): Out {
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            return Out(error = "返回不是 JSON：" + body.take(80).replace("\n", " "))
        }
        val arr = root.optJSONArray("data")
            ?: return Out(error = "返回里没有 data 数组，不是 OpenAI 兼容格式")
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val id = arr.optJSONObject(i)?.optString("id").orEmpty()
            if (id.isNotBlank() && id !in out) out.add(id)
        }
        if (out.isEmpty()) return Out(error = "接口返回了空模型列表（data[].id 都是空）")
        return Out(models = out)
    }
}
