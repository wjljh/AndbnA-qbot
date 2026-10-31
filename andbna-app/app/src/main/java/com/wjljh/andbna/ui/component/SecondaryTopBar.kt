package com.wjljh.andbna.ui.component

import androidx.compose.runtime.Composable
import com.wjljh.andbna.ui.PanelColors
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back

/**
 * 二级页统一顶栏：细线返回箭头 + 标题 +（可选）副标题。
 *
 * 原来四个二级页各写一份：人格文案页用 MiuixIcons.Back，运行控制页手搓了个「‹」文字，
 * 关于页用 Material 的 ArrowBack，主题页是 kit 自己带模糊的 TopAppBar。
 * 现在能共用的（人格文案 / 运行控制）走这一个函数，图标固定用库的 [MiuixIcons.Back]；
 * 主题页仍是 kit 的 TopAppBar（要带背景模糊与滚动折叠），关于页仍是大标题版式 ——
 * 那两页只把返回图标换成同一个库图标，不去改它们的整体版式。
 */
@Composable
fun SecondaryTopBar(
    title: String,
    subtitle: String? = null,
    onBack: () -> Unit,
) {
    SmallTopAppBar(
        title = title,
        subtitle = subtitle.orEmpty(),
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = MiuixIcons.Back,
                    contentDescription = "返回",
                    tint = PanelColors.onBackground,
                )
            }
        },
    )
}
