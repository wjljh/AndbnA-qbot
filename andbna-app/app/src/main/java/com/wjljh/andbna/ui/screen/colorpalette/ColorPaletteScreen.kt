package com.wjljh.andbna.ui.screen.colorpalette

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.wjljh.andbna.ui.LocalUiMode
import com.wjljh.andbna.ui.UiMode
import com.wjljh.andbna.ui.theme.ColorMode
import com.wjljh.andbna.ui.viewmodel.SettingsViewModel

@Composable
fun ColorPaletteScreen() {
    val pop = LocalPaletteBack.current
    val viewModel = viewModel<SettingsViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val currentPaletteStyle = try {
        PaletteStyle.valueOf(uiState.colorStyle)
    } catch (_: Exception) {
        PaletteStyle.TonalSpot
    }
    val currentColorSpec = try {
        ColorSpec.SpecVersion.valueOf(uiState.colorSpec)
    } catch (_: Exception) {
        ColorSpec.SpecVersion.Default
    }
    val state = ColorPaletteUiState(
        uiState = uiState,
        currentColorMode = ColorMode.fromValue(uiState.themeMode),
        currentPaletteStyle = currentPaletteStyle,
        currentColorSpec = currentColorSpec,
    )
    val actions = ColorPaletteScreenActions(
        onBack = dropUnlessResumed { pop() },
        onSetThemeMode = viewModel::setThemeMode,
        onSetMiuixMonet = viewModel::setMiuixMonet,
        onSetKeyColor = viewModel::setKeyColor,
        onSetColorMode = viewModel::setColorMode,
        onSetColorStyle = viewModel::setColorStyle,
        onSetColorSpec = viewModel::setColorSpec,
        onSetEnableBlur = viewModel::setEnableBlur,
        onSetEnableFloatingBottomBar = viewModel::setEnableFloatingBottomBar,
        onSetEnableFloatingBottomBarBlur = viewModel::setEnableFloatingBottomBarBlur,
        // 只落盘：真正的开关效果由 App.kt / BotEditScreen.kt 里的 PredictiveBackGate 在运行时读
        // 这个 pref 决定（改 ApplicationInfo 那条路在 targetSdk 36+ 被系统忽略，已删）
        onSetEnablePredictiveBack = viewModel::setEnablePredictiveBack,
        onSetPageScale = viewModel::setPageScale,
    )

    when (LocalUiMode.current) {
        // Material 版（kit 的 ColorPaletteScreenMaterial.kt）按用户要求暂不搬：material 分支落到同一支
        UiMode.Miuix, UiMode.Material -> ColorPaletteScreenMiuix(state, actions)
    }
}

/**
 * 二级页返回。kit 用 navigation3 的 LocalNavigator.pop()；本工程没有 navigation3 包，
 * 由 App.kt 的 NavDisplay 把 pop() 灌进来（其余转发逻辑与 kit 完全一致）。
 */
val LocalPaletteBack = staticCompositionLocalOf<() -> Unit> { {} }
