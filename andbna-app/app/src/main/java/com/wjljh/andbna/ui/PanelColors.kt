package com.wjljh.andbna.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 语义色的唯一出口：页面与基础组件只写 [PanelColors.xxx]，不写具体主题的色板。
 *
 * 这样页面文件里没有任何 UiMode 分支——两套主题的差异只发生在这一层：
 * miuix 模式取 MiuixTheme.colorScheme，material 模式取 MaterialTheme.colorScheme。
 */
object PanelColors {

    private val isMaterial: Boolean
        @Composable @ReadOnlyComposable get() = LocalUiMode.current == UiMode.Material

    /**
     * 浅色判据：看**真实卡片底色**的亮度，不看系统设置。
     * 用户可以在主题页把 App 强制成深色而系统仍是浅色，那种情况下必须走深色那一支。
     * （口径与 design/Tokens.kt 的 StatusColors.surface 一致。）
     */
    private val isLightScheme: Boolean
        @Composable @ReadOnlyComposable get() =
            (if (isMaterial) MaterialTheme.colorScheme.surfaceContainer
            else MiuixTheme.colorScheme.surfaceContainer).luminance() >= 0.5f

    // 浅色专用色值：深色一律不读这几个常量，深色色值因此一个都没动。
    /** 页面底色：比白卡暗一档，卡片才不会「糊」在页面上。 */
    private val LightPage = Color(0xFFEFEFF4)
    /** 卡片 1dp 描边：与页面底 3.2:1、与白卡 3.7:1（WCAG 非文本元素 3:1 线）。 */
    private val LightCardBorder = Color(0xFF85858A)
    /** 分隔线：原来的 #E8E8E8 在白卡上只有 1.2:1，几乎看不见。 */
    private val LightDivider = Color(0xFFD5D5DC)
    /** 次要文字：#626262 在白卡上 6.3:1 但观感偏浅，加深一档到 8:1。 */
    private val LightSecondaryText = Color(0xFF4E4E55)

    /** 页面底色（Scaffold / 顶栏下的大面）。深色原样返回主题值。 */
    val pageBackground: Color
        @Composable @ReadOnlyComposable get() = when {
            isLightScheme -> LightPage
            isMaterial -> MaterialTheme.colorScheme.background
            else -> MiuixTheme.colorScheme.surface
        }

    /** 卡片 1dp 描边色。深色返回透明 = 不描边，观感与改动前完全一致。 */
    val cardBorder: Color
        @Composable @ReadOnlyComposable get() = if (isLightScheme) LightCardBorder else Color.Transparent

    val onSurface: Color
        @Composable @ReadOnlyComposable get() =
            if (isMaterial) MaterialTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurface

    val onSurfaceContainer: Color
        @Composable @ReadOnlyComposable get() =
            if (isMaterial) MaterialTheme.colorScheme.onSurfaceVariant else MiuixTheme.colorScheme.onSurfaceContainer

    val onSurfaceVariantSummary: Color
        @Composable @ReadOnlyComposable get() = when {
            isLightScheme -> LightSecondaryText
            isMaterial -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
        }

    val onSurfaceVariantActions: Color
        @Composable @ReadOnlyComposable get() =
            if (isMaterial) MaterialTheme.colorScheme.onSurfaceVariant else MiuixTheme.colorScheme.onSurfaceVariantActions

    val disabledOnSecondaryVariant: Color
        @Composable @ReadOnlyComposable get() =
            if (isMaterial) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            else MiuixTheme.colorScheme.disabledOnSecondaryVariant

    val onBackground: Color
        @Composable @ReadOnlyComposable get() =
            if (isMaterial) MaterialTheme.colorScheme.onBackground else MiuixTheme.colorScheme.onBackground

    val surface: Color
        @Composable @ReadOnlyComposable get() =
            if (isMaterial) MaterialTheme.colorScheme.surface else MiuixTheme.colorScheme.surface

    val surfaceContainer: Color
        @Composable @ReadOnlyComposable get() =
            if (isMaterial) MaterialTheme.colorScheme.surfaceContainer else MiuixTheme.colorScheme.surfaceContainer

    val surfaceContainerHigh: Color
        @Composable @ReadOnlyComposable get() =
            if (isMaterial) MaterialTheme.colorScheme.surfaceContainerHigh else MiuixTheme.colorScheme.surfaceContainerHigh

    val dividerLine: Color
        @Composable @ReadOnlyComposable get() = when {
            isLightScheme -> LightDivider
            isMaterial -> MaterialTheme.colorScheme.outlineVariant
            else -> MiuixTheme.colorScheme.dividerLine
        }

    val outline: Color
        @Composable @ReadOnlyComposable get() =
            if (isMaterial) MaterialTheme.colorScheme.outline else MiuixTheme.colorScheme.outline

    val primary: Color
        @Composable @ReadOnlyComposable get() =
            if (isMaterial) MaterialTheme.colorScheme.primary else MiuixTheme.colorScheme.primary

    val onPrimary: Color
        @Composable @ReadOnlyComposable get() =
            if (isMaterial) MaterialTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onPrimary

    val secondaryContainer: Color
        @Composable @ReadOnlyComposable get() =
            if (isMaterial) MaterialTheme.colorScheme.secondaryContainer else MiuixTheme.colorScheme.secondaryContainer

    val tertiaryContainer: Color
        @Composable @ReadOnlyComposable get() =
            if (isMaterial) MaterialTheme.colorScheme.tertiaryContainer else MiuixTheme.colorScheme.tertiaryContainer

    val onTertiaryContainer: Color
        @Composable @ReadOnlyComposable get() =
            if (isMaterial) MaterialTheme.colorScheme.onTertiaryContainer else MiuixTheme.colorScheme.onTertiaryContainer

    val error: Color
        @Composable @ReadOnlyComposable get() =
            if (isMaterial) MaterialTheme.colorScheme.error else MiuixTheme.colorScheme.error

    val errorContainer: Color
        @Composable @ReadOnlyComposable get() =
            if (isMaterial) MaterialTheme.colorScheme.errorContainer else MiuixTheme.colorScheme.errorContainer

    val onErrorContainer: Color
        @Composable @ReadOnlyComposable get() =
            if (isMaterial) MaterialTheme.colorScheme.onErrorContainer else MiuixTheme.colorScheme.onErrorContainer

    val onError: Color
        @Composable @ReadOnlyComposable get() =
            if (isMaterial) MaterialTheme.colorScheme.onError else MiuixTheme.colorScheme.onError
}
