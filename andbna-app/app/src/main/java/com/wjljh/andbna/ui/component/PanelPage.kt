package com.wjljh.andbna.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import android.os.Build
import com.wjljh.andbna.ui.LocalUiMode
import com.wjljh.andbna.ui.UiMode
import com.wjljh.andbna.ui.theme.LocalEnableBlur
import com.wjljh.andbna.ui.theme.MiuixTemplateTheme
import com.wjljh.andbna.ui.theme.ThemeController
import com.wjljh.andbna.ui.util.BlurredBar
import com.wjljh.andbna.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 四个 tab 页共用的外壳，只用 kit 的 miuix 组件（Scaffold / TopAppBar / LazyColumn）。
 *
 * 为什么不再按 UiMode 分两套：页面内容应当只有一种设计语言（kit 的 miuix 语言），
 * 免得同一页里混进 Material 默认样式。若外层是 Material 模式，这里用 kit 的
 * [MiuixTemplateTheme] 补一层 MiuixTheme，颜色/字体仍然跟着 kit 的主题设置走。
 *
 * 页面内容以 [LazyListScope] 交给调用方，要求每一项都带稳定 key。
 * 毛玻璃只在 API 33+ 且用户开了「模糊」设置时才启用（kit 的默认是开，重效果默认不用）。
 */
@Composable
fun PanelPage(
    title: String,
    bottomInnerPadding: Dp,
    content: LazyListScope.() -> Unit,
) {
    if (LocalUiMode.current == UiMode.Miuix) {
        PanelScaffold(title, bottomInnerPadding, content)
    } else {
        val context = LocalContext.current
        MiuixTemplateTheme(appSettings = ThemeController.getAppSettings(context)) {
            PanelScaffold(title, bottomInnerPadding, content)
        }
    }
}

@Composable
private fun PanelScaffold(
    title: String,
    bottomInnerPadding: Dp,
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val blurEnabled = LocalEnableBlur.current && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val backdrop = rememberBlurBackdrop(blurEnabled)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface
    val windowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Horizontal)

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = title,
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        popupHost = { },
        contentWindowInsets = windowInsets,
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    // 底栏是浮在内容之上的（不是 Scaffold 的分栏）：把列表可视区整体抬到底栏之上，
                    // 这样任何滚动位置都不会有内容钻到胶囊下面。[bottomInnerPadding] 由
                    // MainActivity 按底栏实测高度 + 12dp 安全距算出，不是猜的常数。
                    .padding(bottom = bottomInnerPadding)
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(horizontal = PanelTokens.PageHorizontal, vertical = PanelTokens.PageTop),
                contentPadding = innerPadding,
                verticalArrangement = Arrangement.spacedBy(PanelTokens.CardSpacing),
            ) {
                content()
                item(key = "panel-page-bottom-spacer") {
                    Spacer(Modifier.height(PanelTokens.PageTop))
                }
            }
        }
    }
}
