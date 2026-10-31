package com.wjljh.andbna.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
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

    val onSurface: Color
        @Composable @ReadOnlyComposable get() =
            if (isMaterial) MaterialTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurface

    val onSurfaceContainer: Color
        @Composable @ReadOnlyComposable get() =
            if (isMaterial) MaterialTheme.colorScheme.onSurfaceVariant else MiuixTheme.colorScheme.onSurfaceContainer

    val onSurfaceVariantSummary: Color
        @Composable @ReadOnlyComposable get() =
            if (isMaterial) MaterialTheme.colorScheme.onSurfaceVariant else MiuixTheme.colorScheme.onSurfaceVariantSummary

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
        @Composable @ReadOnlyComposable get() =
            if (isMaterial) MaterialTheme.colorScheme.outlineVariant else MiuixTheme.colorScheme.dividerLine

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
