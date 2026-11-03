package com.wjljh.andbna.local

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 无界面跳板：只给看门狗的 **shell 通道** 用。
 *
 * 为什么需要它：shell(uid 2000) 拉不起 [BotService]（它 exported=false，AMS 直接
 * Permission Denial），但可以 start 一个 exported 的 Activity。所以 shell 通道
 * 改成拉起这个 Activity，它在 onCreate 里把前台服务起起来、立刻 finish：
 * 不进最近任务（excludeFromRecents）、不设布局（Theme.NoDisplay）、不弹界面。
 *
 * 安全面：不读任何外部参数、不把任何 intent 数据往下传，只做「起服务」这一件事。
 */
class WakeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = applicationContext
        runCatching {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, BotService::class.java).setAction(BotService.ACTION_START),
            )
        }.onFailure {
            Log.w(TAG, "跳板：起前台服务失败（" + (it.message ?: it.javaClass.simpleName) + "）")
        }
        finish()
    }

    private companion object {
        const val TAG = "AndbnA"
    }
}
