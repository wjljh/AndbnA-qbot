package com.wjljh.andbna.ui.screen.settings

import androidx.compose.runtime.Immutable
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.wjljh.andbna.ui.UiMode

/**
 * 照搬 kit ui/screen/settings/SettingsUiState.kt，只留主题页读得到的字段。
 * kit 那一版还带面板（地址 / TOKEN / 配置 / 人格 / 日志）与 appVersion —— 那是 kit 的
 * andbnA 数据层，本工程已有自己的 Panel / Store，不重复搬（见报告里的依赖链说明）。
 */
@Immutable
data class SettingsUiState(
    val uiMode: String = UiMode.DEFAULT_VALUE,
    val themeMode: Int = 0,
    val miuixMonet: Boolean = false,
    val keyColor: Int = 0,
    val colorStyle: String = PaletteStyle.TonalSpot.name,
    val colorSpec: String = ColorSpec.SpecVersion.Default.name,
    val enablePredictiveBack: Boolean = false,
    val enableBlur: Boolean = false,
    val enableFloatingBottomBar: Boolean = true,
    val enableFloatingBottomBarBlur: Boolean = true,
    val pageScale: Float = 1.0f,
)
