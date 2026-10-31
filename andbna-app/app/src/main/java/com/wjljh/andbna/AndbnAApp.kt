package com.wjljh.andbna

import android.app.Application
import com.wjljh.andbna.local.PullBack
import com.wjljh.andbna.local.ShellJanitor

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
        // 兜底清理：su 起的一次性 shell 万一还是被落下了（客户端被杀、超时），在这里收掉，
        // 一个孤儿 ~70MB，攒一堆能把手机 RAM 吃穿。启动一次 + 之后每 10 分钟一次，root 模式才跑
        ShellJanitor.start(this)
        // 给 ShizukuShell 一个 App 级 ctx：它的 bind 硬门要按当前运行模式判断（root 模式一律不绑）
        com.wjljh.andbna.local.ShizukuShell.appCtx = this
    }
}
