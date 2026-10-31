package com.wjljh.andbna.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.wjljh.andbna.ui.component.bottombar.MainPagerState

/**
 * kit 里这一行原本写在 ui/MainActivity.kt 中；目标工程不搬 kit 的 MainActivity，
 * 所以单独放在这里（同一个包、同一个名字，供 bottombar 各文件消费）。
 */
val LocalMainPagerState = staticCompositionLocalOf<MainPagerState> { error("LocalMainPagerState not provided") }
