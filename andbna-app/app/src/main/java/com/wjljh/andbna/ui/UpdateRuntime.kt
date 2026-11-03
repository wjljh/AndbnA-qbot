package com.wjljh.andbna.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wjljh.andbna.local.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「发现新版本」的进程级状态。
 *
 * 状态页那张提示卡读它，卡片被忽略 / 装完也写它；一个进程只自动检查一次
 * （切页、重组不会把检查打成轮询，真正的时间节流在 [Updater.autoCheck] 里）。
 */
object UpdateRuntime {

    /** 已发现的新版本；null = 没有要提示的 */
    var release by mutableStateOf<Updater.Release?>(null)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val kicked = AtomicBoolean(false)

    fun triggerOnce(ctx: Context, currentVersionName: String) {
        if (!kicked.compareAndSet(false, true)) return
        scope.launch { release = Updater.autoCheck(ctx.applicationContext, currentVersionName) }
    }

    fun clear() {
        release = null
    }
}
