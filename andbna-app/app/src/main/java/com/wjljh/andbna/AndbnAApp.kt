package com.wjljh.andbna

import android.app.Application
import com.wjljh.andbna.local.PullBack

/**
 * 进程级入口。
 *
 * 「自动拉回」必须在**进程一起来**就上岗，而不是等到某个 Activity / Service 被创建：
 * 被看门狗 am start 拉回来、或只起了前台服务时，都不一定有 Activity 走 onCreate。
 * 这里只做一件事：挂上监督线程并立刻判一次（模式/开关不满足它自己会什么都不做）。
 */
class AndbnAApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PullBack.poke(this)
    }
}
