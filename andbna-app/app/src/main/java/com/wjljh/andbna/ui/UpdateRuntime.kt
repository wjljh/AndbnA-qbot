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
 * （切页、重组不会把检查打成轮询，真正的节流在 [Updater.autoCheck] 里：最短 30 分钟）。
 *
 * 启动顺序刻意是「先缓存、后联网」：断网或被 GitHub 限流时，上次查到的版本仍然立刻出卡
 * （缓存见 [Updater.cachedRelease]），联网只是去确认 / 覆盖它。
 */
object UpdateRuntime {

    /** 已发现的新版本；null = 没有要提示的 */
    var release by mutableStateOf<Updater.Release?>(null)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val kicked = AtomicBoolean(false)

    fun triggerOnce(ctx: Context, currentVersionName: String) {
        if (!kicked.compareAndSet(false, true)) return
        scope.launch {
            val app = ctx.applicationContext
            // ① 本地缓存：不联网也要能看见上次的结果
            val cached = Updater.cachedRelease(app, currentVersionName)
            if (cached != null) release = cached
            // ② 联网确认（30 分钟节流仍在 autoCheck 里）；失败/跳过都不动缓存
            val fresh = Updater.autoCheck(app, currentVersionName)
            when {
                fresh != null -> release = fresh
                // 联网结论是「已最新」→ 缓存被清掉了，卡也得跟着撤；
                // 失败 / 被节流不会删缓存，所以这时保留缓存卡
                cached != null && Updater.readCache(app) == null -> release = null
            }
        }
    }

    /** 手动「立即检查」发现新版本：直接接管提示卡，不用等下次启动 */
    fun found(release: Updater.Release) {
        this.release = release
    }

    fun clear() {
        release = null
    }
}
