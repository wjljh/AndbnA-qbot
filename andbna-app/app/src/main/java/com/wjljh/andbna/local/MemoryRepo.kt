package com.wjljh.andbna.local

import android.content.Context

/**
 * 记忆写的唯一入口：本机数据 files/localbot/<appid>/memory.json（BotHost → bot_runner）。
 *
 * 成功文案直接采用后端回执 —— bot_runner.memory_delete 的「已删除 X（剩余 N 条）」。
 */
object MemoryRepo {

    /** undo：删除成功时为 true —— 提示条上给「撤销」按钮 */
    data class Result(val ok: Boolean, val text: String, val undo: Boolean = false)

    /** 单条删除 = 批量的单元素调用（仍然可以撤销，写一条 undo 记录） */
    suspend fun delete(ctx: Context, appid: String, key: String): Result =
        deleteMany(ctx, appid, listOf(key))

    /** 多选删除：一次调 bot_runner.memory_delete_many（整批合成一条 undo，可整批撤销） */
    suspend fun deleteMany(ctx: Context, appid: String, keys: List<String>): Result {
        val list = keys.filter { it.isNotBlank() }.distinct()
        if (list.isEmpty()) return Result(false, "没有选中任何会话")
        val r = BotHost.memoryDeleteMany(ctx, appid, list)
        val ok = r.startsWith("已删除")
        if (!ok) return Result(false, r.ifBlank { "删除失败：没有回执" })
        // 整批删完只重启一次（单条删除也是这条路径，所以不会重启两回）
        return Result(true, withNote(r, BotHost.restartOne(ctx, appid, "删除记忆")), true)
    }

    /** 撤销最近一次删除（单步，10 秒内有效） */
    suspend fun undo(ctx: Context, appid: String): Result {
        val r = BotHost.memoryUndo(ctx, appid)
        val ok = r.startsWith("已撤销")
        if (!ok) return Result(false, r.ifBlank { "撤销失败：没有回执" })
        return Result(true, withNote(r, BotHost.restartOne(ctx, appid, "撤销删除")))
    }

    suspend fun clear(ctx: Context, appid: String): Result {
        val r = BotHost.memoryClear(ctx, appid)
        return Result(r.startsWith("已清空"), r.ifBlank { "清空失败：没有回执" })
    }

    /** 删除回执后面接一句重启结果；没在跑（空串）就不接 */
    private fun withNote(text: String, note: String): String =
        if (note.isBlank()) text else text + " · " + note
}
