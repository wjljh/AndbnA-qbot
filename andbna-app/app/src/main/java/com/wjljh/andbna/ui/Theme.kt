package com.wjljh.andbna.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.wjljh.andbna.data.PrefsWatch
import com.wjljh.andbna.ui.theme.AppMaterialTheme
import com.wjljh.andbna.ui.theme.rememberAppMaterialScheme
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

/**
 * 主题：两套设计语言共存，页面只写一份。
 *
 * · 外层永远挂一个 [MiuixTheme]：kit 的偏好行 / 图标 / 卡片都在内部读它，
 *   material 模式下也必须存在，否则那些组件会拿不到色板。为了不让两套色彩打架，
 *   material 模式下这个 MiuixTheme 的种子色**取 material3 动态主色**，
 *   于是 kit 组件也跟着 Material You 走。
 * · 内层始终是一个 [AppMaterialTheme]（= material3 MaterialTheme），只有色板参数二选一：
 *   material → Material You 动态色板；miuix → 由 Miuix 色板推导的桥接色板
 *   （给页面里零散的 material3 Text/Icon 兜底）。调用点保持唯一，切模式才不会重建子树。
 * · 模式来自 [UiModeStore]，是 Compose State：设置页下拉一改，整棵树立即重组。
 *
 * 尺寸令牌不在这里，统一放 PanelTokens / PanelMetrics。
 */
object Tokens {
    val Pink = Color(0xFFFF8FB1)
    val PinkDeep = Color(0xFFE5678F)

    val Ok = Color(0xFF3FBF7F)
    val Idle = Color(0xFFBFB5BB)
    val Bad = Color(0xFFE4574F)

    val OkSurfaceLight = Color(0xFFDFFAE4)
    val OkSurfaceDark = Color(0xFF1A3825)
    val OkIcon = Color(0xFF36D167)
}

@Composable
fun AndbnATheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val uiMode = UiModeStore.current
    val systemDark = isSystemInDarkTheme()
    // 主题页（搬来的 kit ColorPaletteScreen）改值后 revision 会变，这里读一次即可跟着重组
    PrefsWatch.observed()

    // 值来自 "settings" 里的 kit 键（color_mode / key_color / color_style / color_spec / miuix_monet）；
    // ui/theme/Theme.kt 的 ThemeController.getAppSettings 就是读这一套的现成实现
    val settings = com.wjljh.andbna.ui.theme.ThemeController.getAppSettings(ctx)
    val dark = settings.colorMode.isDark || (settings.colorMode.isSystem && systemDark)

    val materialScheme = rememberAppMaterialScheme(dark)

    // miuix 那一支的偏好：色板 / 深浅 / 调色风格，都由「主题设置」页写入
    val keyColor = if (settings.keyColor == 0) null else Color(settings.keyColor)
    val style = runCatching { ThemePaletteStyle.valueOf(settings.paletteStyle) }
        .getOrDefault(ThemePaletteStyle.TonalSpot)

    val controller = remember(uiMode, dark, keyColor, style, materialScheme.primary) {
        if (uiMode == UiMode.Material) {
            // 种子色 = 系统动态主色 ⇒ kit 组件与 material3 同一调色板
            ThemeController(
                if (dark) ColorSchemeMode.MonetDark else ColorSchemeMode.MonetLight,
                keyColor = materialScheme.primary,
                isDark = dark,
                paletteStyle = ThemePaletteStyle.TonalSpot,
            )
        } else {
            ThemeController(
                if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light,
                keyColor = keyColor,
                isDark = dark,
                paletteStyle = style,
            )
        }
    }

    MiuixTheme(controller = controller) {
        val m = MiuixTheme.colorScheme
        // miuix 模式下给页面里零散的 material3 Text/Icon 兜底的桥接色板：
        // background 用 Miuix 的 surface（页面底色）、surface 用 surfaceContainer（卡片底色）。
        val bridge = if (dark) darkColorScheme(
            primary = Tokens.Pink,
            onPrimary = Color(0xFF3A2028),
            secondary = Tokens.PinkDeep,
            background = m.surface,
            onBackground = m.onSurface,
            surface = m.surfaceContainer,
            onSurface = m.onSurface,
            surfaceVariant = m.surfaceContainerHigh,
            onSurfaceVariant = m.onSurfaceVariantSummary,
            error = Color(0xFFFF8A80),
            onError = Color(0xFF3B0A06),
            errorContainer = Color(0xFF5A1F1A),
            onErrorContainer = Color(0xFFFFD9D4),
        ) else lightColorScheme(
            primary = Tokens.PinkDeep,
            onPrimary = Color.White,
            secondary = Tokens.Pink,
            background = m.surface,
            onBackground = m.onSurface,
            surface = m.surfaceContainer,
            onSurface = m.onSurface,
            surfaceVariant = m.surfaceContainerHigh,
            onSurfaceVariant = m.onSurfaceVariantSummary,
            error = Color(0xFFC6443C),
            onError = Color.White,
            errorContainer = Color(0xFFFFE1DE),
            onErrorContainer = Color(0xFF6B1C17),
        )

        CompositionLocalProvider(
            LocalUiMode provides uiMode,
            LocalContentColor provides m.onBackground,
        ) {
            // 两套模式只换参数、不换调用点：content 永远落在同一个组合位置。
            // 若写成 if (material) AppMaterialTheme{content} else MaterialTheme{content}，
            // 切模式时 content 会被当成另一处的子树整体重建 —— PanelAppContent 里的
            // rememberSaveable(pagerState) 随之丢失，表现就是「切完主题自动跳回第一个 tab」。
            AppMaterialTheme(
                colorScheme = if (uiMode == UiMode.Material) materialScheme else bridge,
            ) { content() }
        }
    }
}
