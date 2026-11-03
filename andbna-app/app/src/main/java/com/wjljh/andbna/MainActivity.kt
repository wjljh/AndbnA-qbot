package com.wjljh.andbna

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.wjljh.andbna.data.initAppContext
import com.wjljh.andbna.local.BotHost
import com.wjljh.andbna.local.PullBack
import com.wjljh.andbna.local.UpdateInstaller
import com.wjljh.andbna.ui.AndbnATheme
import com.wjljh.andbna.ui.PanelApp
import com.wjljh.andbna.ui.UiModeStore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BotHost.init(this)
        // 「自动拉回」默认启用：界面一起来就挂上监督线程，并立刻判一次
        // （poke = supervise + 马上 tick；重启后不用等 20s 的第一个周期）
        PullBack.poke(this)
        // 界面模式（miuix / material）的持久化初值：设置页切换后写回，冷启动在这里读
        UiModeStore.init(this)
        // 主题页（kit 的 ColorPaletteScreen）落盘在 "settings" 里：这里灌 Context 并挂变更监听，
        // 主题页一改值根层就重组，不用重启
        initAppContext(this)
        // 左侧那条细竖线是 MIUI 的 com.miui.securitycenter/.FloatingWindow（SYSTEM_ALERT 浮窗），
        // 不属于本 App 的界面；API 31+ 可以要求系统在我们窗口上隐藏这类 overlay。
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            runCatching { window.setHideOverlayWindows(true) }
        }
        enableEdgeToEdge()
        setContent {
            AndbnATheme {
                PanelApp()
            }
        }
        // 下载完成通知点下来：带着安装包路径进 Activity（前台）→ 校验 + 安装
        handleUpdateIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUpdateIntent(intent)
    }

    /**
     * 通知点下来的安装动作：校验（包名 / 版本 / ABI / 签名）→ 按当前模式装。
     *
     * 放在 Activity 里跑而不是 Service 里：普通模式的 PackageInstaller 要能拉起系统确认页，
     * 「安装未知应用」的引导页也只能从 Activity 起（后台不允许起 Activity）。
     */
    private fun handleUpdateIntent(intent: Intent?) {
        if (intent?.action != UpdateInstaller.ACTION_INSTALL) return
        val path = intent.getStringExtra(UpdateInstaller.EXTRA_PATH).orEmpty()
        if (path.isBlank()) return
        val app = applicationContext
        Thread({ UpdateInstaller.verifyAndInstall(app, java.io.File(path)) }, "update-verify").start()
    }
}
