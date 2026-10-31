package com.wjljh.andbna.local

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启。
 *
 * 为什么加它：之前想靠 root 在 /data/adb/service.d/ 里 am 起前台服务，
 * 实测被系统拦（non-exported 服务不接受外部 uid，root 也不行）。
 * 而 BOOT_COMPLETED 是系统自己发起的启动，不受这条限制 —— 这也是
 * 所有需要常驻的 App 的标准做法，而且**不需要 root**。
 *
 * root 模式仍然有价值：电池白名单 + 掉线后能把它拉回来。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) return

        // 尊重设置页的「开机自动启动」开关（默认开）
        val autoStart = context.getSharedPreferences("andbna_local", 0)
            .getString("auto_start", "1") != "0"
        if (!autoStart) return

        val bots = BotStore.load(context)
        if (bots.none { it.enabled && it.appid.isNotBlank() && it.secret.isNotBlank() }) return

        BotService.start(context)
    }
}
