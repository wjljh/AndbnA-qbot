package com.wjljh.andbna.ui.component

import androidx.compose.ui.graphics.vector.ImageVector
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.ContactsCircle
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Report
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.icon.extended.Update

/**
 * 页面用到的图标：全部在顶层建一次（kit 的 ImageVector），
 * 不在 Composable 里现场构造，避免每帧重建 Vector。
 */
object PanelIcons {
    val ChevronForward: ImageVector = MiuixIcons.ChevronForward
    val ContactsCircle: ImageVector = MiuixIcons.ContactsCircle
    val Delete: ImageVector = MiuixIcons.Delete
    val Info: ImageVector = MiuixIcons.Info
    val Lock: ImageVector = MiuixIcons.Lock
    val Messages: ImageVector = MiuixIcons.Messages
    val Notes: ImageVector = MiuixIcons.Notes
    val Report: ImageVector = MiuixIcons.Report
    val Settings: ImageVector = MiuixIcons.Settings
    val Theme: ImageVector = MiuixIcons.Theme
    val Tune: ImageVector = MiuixIcons.Tune
    val Update: ImageVector = MiuixIcons.Update
}
