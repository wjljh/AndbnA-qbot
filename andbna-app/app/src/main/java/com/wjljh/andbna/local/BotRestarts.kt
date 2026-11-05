package com.wjljh.andbna.local

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「保存完自动重启」的唯一入口：合并连点 + 串行重启 + 异步执行。
 *
 * 为什么要合并：用户改完一个字段常常连点两下保存，逐次重启会把 bot 反复踢下线
 * （断线期间平台的群消息不补发，是真丢消息）。所以 1.5 秒内的多次保存只重启一次。
 * 为什么串行：restart_one 是「停一个、起一个」，并发跑同一批 bot 会互相踩。
 */
object BotRestarts {
    private const val TAG = "BotRestarts"

    /** 合并窗口：这段时间内的多次保存合成一批 */
    private const val WINDOW_MS = 1_500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    /** 待重启：appid → 原因集合（同一批里同一个人格被两个地方改了就把原因并起来） */
    private val pending = LinkedHashMap<String, LinkedHashSet<String>>()
    private var pendingPush = false
    private var dueAt = 0L
    /** 在跑的那一轮重启；不为空时新请求只入队，不另起一轮 */
    private var worker: Job? = null
    private var app: Context? = null

    /**
     * 全局配置（模型 / 回复判定 / 联网）保存后：重启**所有启用中的 bot**，并先把运行时配置重推一遍。
     * 返回排进队列的 bot 数（0 = 没有启用中的 bot，界面就不必提重启）。
     */
    fun requestAllEnabled(ctx: Context, reason: String): Int {
        val ids = try {
            BotStore.load(ctx).filter { it.enabled }.map { it.appid }.filter { it.isNotBlank() }
        } catch (e: Throwable) {
            Log.i(TAG, "读清单失败，放弃重启：" + (e.message ?: ""))
            emptyList()
        }
        if (ids.isEmpty()) return 0
        enqueue(ctx, ids, reason, push = true)
        return ids.size
    }

    /** 单个 bot 的保存（编辑页 / 群配置）：只重启它，别动别人。 */
    fun requestOne(ctx: Context, appid: String, reason: String): Boolean {
        val id = appid.trim()
        if (id.isBlank()) return false
        enqueue(ctx, listOf(id), reason, push = false)
        return true
    }

    private fun enqueue(ctx: Context, ids: List<String>, reason: String, push: Boolean) {
        val why = reason.trim().ifBlank { "保存" }
        synchronized(lock) {
            app = ctx.applicationContext
            ids.forEach { id -> pending.getOrPut(id) { LinkedHashSet() }.add(why) }
            if (push) pendingPush = true
            dueAt = System.currentTimeMillis() + WINDOW_MS
            if (worker?.isActive != true) worker = scope.launch { drain() }
        }
    }

    /** 一轮一轮地跑：等够静默窗口 → 取走整批 → 逐个重启；跑的时候进来的新请求留给下一轮。 */
    private suspend fun drain() {
        while (true) {
            val wait = synchronized(lock) { dueAt - System.currentTimeMillis() }
            if (wait > 0) delay(wait)
            val batch = LinkedHashMap<String, String>()
            var push = false
            var c: Context? = null
            synchronized(lock) {
                if (pending.isEmpty()) {
                    worker = null
                    return
                }
                pending.forEach { (id, reasons) -> batch[id] = reasons.joinToString("、") }
                pending.clear()
                push = pendingPush
                pendingPush = false
                c = app
            }
            val ctx = c ?: continue
            if (push && !BotHost.pushRuntimeConfig(ctx)) {
                BotHost.logNote("运行时配置推送失败，重启后可能仍用旧 Key（详见设备日志）")
            }
            batch.forEach { (id, why) -> restartOne(ctx, id, why) }
        }
    }

    private fun restartOne(ctx: Context, appid: String, reason: String) {
        // 「重启 bot <appid>（原因：<reason>）」：用户能在日志页直接看到这一行
        BotHost.logNote("重启 bot " + appid + "（原因：" + reason + "）")
        val note = try {
            BotHost.restartOne(ctx, appid, reason)
        } catch (e: Throwable) {
            "重启异常：" + (e.message ?: e.javaClass.simpleName)
        }
        when {
            note.isBlank() -> BotHost.logNote("跳过 bot " + appid + "（没在跑，下次启动用新配置）")
            note.contains("失败") || note.contains("异常") -> BotHost.logNote(
                "重启 bot " + appid + " 失败（原因：" + reason + "）：" + note
            )
            else -> Log.i(TAG, note)
        }
    }
}
