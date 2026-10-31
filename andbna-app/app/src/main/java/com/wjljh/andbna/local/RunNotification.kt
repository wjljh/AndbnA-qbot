package com.wjljh.andbna.local

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * 「运行中的通知栏提醒」开关：设置页与前台服务共用一份落盘值（andbna_local / running_notification，默认开）。
 *
 * 开：走 IMPORTANCE_LOW 渠道，前台服务 + 通知（静默不打扰）。
 * 关：通知真的消失 —— 服务调用 stopForeground(STOP_FOREGROUND_REMOVE)，服务本体还在，
 *     只是不再处于前台状态。
 *
 * 为什么不是「渠道重要性改 IMPORTANCE_NONE」：本机实测（Android 17，dumpsys 证据见交付报告），
 * 一条渠道一旦挂上前台服务通知，系统会把 IMPORTANCE_NONE 提升成 IMPORTANCE_LOW 继续显示
 * （mOriginalImp=0 / mImportance=2），IMPORTANCE_MIN 也只部分路径能保住。
 * 只要保持前台服务，App 侧就没有「一条通知都不留」的 API —— 所以关闭改走「退出前台状态」，
 * 换来通知真正消失；代价是进程更容易被系统回收（本机有 root + 电池白名单，通常没事）。
 */
object RunNotification {

    private const val TAG = "RunNotification"
    private const val PREFS = "andbna_local"

    /** 落盘键：与设置页 / 服务同一份，不是密钥，走普通 boolean */
    const val KEY = "running_notification"

    /** 正常显示（IMPORTANCE_LOW）的渠道 —— 沿用旧 id，老用户升级后行为不变 */
    const val CHANNEL_ON = "andbna_local_bot"
    const val CHANNEL_ON_NAME = "AndbnA 本机 Bot"

    /** 早前版本遗留的「不显示」渠道前缀，启动时清一次 */
    private const val LEGACY_OFF_PREFIX = "andbna_local_bot_off_"
    private const val LEGACY_HIDDEN_PREFIX = "andbna_local_bot_hidden"
    private const val KEY_OFF_SEQ = "notif_off_seq"
    private const val KEY_LEGACY_CLEANED = "notif_legacy_cleaned"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 默认开：只有明确写过 false 才当成关 */
    fun enabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY, true)

    private fun manager(ctx: Context) =
        ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** 写盘 + 立刻生效（服务活着就让它按新状态重挂 / 撤掉通知） */
    fun set(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean(KEY, on).apply()
        Log.i(TAG, if (on) "运行通知：开（前台服务+通知）" else "运行通知：关（已退出前台，通知已移除）")
        ensureChannels(ctx)
        BotService.refreshNotification(ctx)
    }

    /** 渠道管理入口：「开」渠道补齐 + 清掉旧版本遗留的「不显示」渠道 */
    fun ensureChannels(ctx: Context) {
        ensureOnChannel(ctx)
        dropLegacyOffChannels(ctx)
    }

    /** 「开」用的渠道：IMPORTANCE_LOW，只建一次 */
    fun ensureOnChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = manager(ctx)
        if (mgr.getNotificationChannel(CHANNEL_ON) == null) {
            val ch = NotificationChannel(CHANNEL_ON, CHANNEL_ON_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = "bot 运行时挂着，告诉你有它在跑"
                setShowBadge(false)
            }
            runCatching { mgr.createNotificationChannel(ch) }
                .onFailure { Log.w(TAG, "运行通知：建渠道失败（" + it.message + "）") }
        }
    }

    /**
     * 一次性清掉试制期间建过的一批「不显示」渠道（现在不用了，留着只会脏系统设置里的通知列表）。
     * 用标记位保证只跑一次；删不掉的（已被系统回收成墓碑）忽略即可。
     */
    private fun dropLegacyOffChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val prefs = sp(ctx)
        if (prefs.getBoolean(KEY_LEGACY_CLEANED, false)) return
        val mgr = manager(ctx)
        val seq = prefs.getInt(KEY_OFF_SEQ, 0)
        val ids = buildList {
            add(LEGACY_HIDDEN_PREFIX)
            (1..24).forEach { add(LEGACY_HIDDEN_PREFIX + "_" + it) }
            (1..seq).forEach { add(LEGACY_OFF_PREFIX + it) }
        }
        ids.forEach { runCatching { mgr.deleteNotificationChannel(it) } }
        prefs.edit().putInt(KEY_OFF_SEQ, 0).putBoolean(KEY_LEGACY_CLEANED, true).apply()
        Log.i(TAG, "运行通知：清理旧渠道 " + ids.size + " 条")
    }

    fun cancel(ctx: Context) {
        runCatching { manager(ctx).cancel(BotService.NOTIF_ID) }
    }
}
