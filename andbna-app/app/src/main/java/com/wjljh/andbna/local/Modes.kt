package com.wjljh.andbna.local

import android.content.Context
import android.util.Log

/**
 * 三种托管方式。
 *
 * 运行内核完全一样：bot 都跑在 App 进程里（Python 打包在 APK 中，由前台服务托管）。
 * 区别只在**有没有额外的保活权限**：
 *  - 普通：没有额外保活权限，可能被系统清理
 *  - ADB ：Shizuku 给 shell 权限，能加电池白名单、被杀后能拉回来
 *  - root：su 权限，写 /data/adb/service.d/ 开机脚本 + 电池白名单（最稳）
 */
enum class RunMode(
    val key: String,
    val label: String,
    val summary: String,
    val detail: String,
) {
    NORMAL(
        "normal", "普通模式",
        "直接用，什么都不用装",
        "最省事。没有额外保活权限，可能被系统清理 —— 被清掉以后新消息就收不到了。",
    ),
    ADB(
        "adb", "ADB 模式",
        "用 Shizuku 授权，能加电池白名单、被杀后能拉回来",
        "运行内核和普通模式完全一样（都是 App 内跑 bot），区别只在保活权限：普通=无，ADB=Shizuku，root=su。手机重启后需要重连一次。",
    ),
    ROOT(
        "root", "root 模式",
        "最稳，适合长期挂着",
        "手机重启后自动起来，也不容易被省电清理掉。需要 root。",
    );

    companion object {
        fun of(key: String?): RunMode? = entries.firstOrNull { it.key == key }
    }
}

/** 模式选择结果存在 App 私有 prefs 里 */
object ModeStore {
    private const val TAG = "AndbnA"
    private const val PREF = "andbna_mode"

    /** 旧版本存过 module（模块模式已删除）：读到它就按普通模式处理，只记一条日志 */
    private const val LEGACY_MODULE = "module"

    private fun sp(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun get(ctx: Context): RunMode? =
        RunMode.of(sp(ctx).getString("mode", null))

    /**
     * 启动时用的模式。
     *
     * 存过就用存的（旧值 module 按普通模式处理）；没选过就看 root 可不可用 ——
     * 可用走 root，否则普通模式。想换在「设置 → 运行模式」里改。
     */
    fun initial(ctx: Context): RunMode {
        val raw = sp(ctx).getString("mode", null)
        if (raw == LEGACY_MODULE) {
            Log.w(TAG, "运行模式存的是 module：模块模式已删除，按普通模式运行")
            return RunMode.NORMAL
        }
        RunMode.of(raw)?.let { return it }
        // root 探测结果在 RootHost 里缓存，一个进程只 su 一次
        return if (RootHost.hasRoot()) RunMode.ROOT else RunMode.NORMAL
    }

    fun set(ctx: Context, mode: RunMode) {
        sp(ctx).edit().putString("mode", mode.key).apply()
    }

    fun clear(ctx: Context) {
        sp(ctx).edit().clear().apply()
    }
}
