package com.wjljh.andbna.ui.component

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith

/**
 * 「二级页 / 步骤」切换的统一过渡 —— 就是引导页原来那套（方向感 + 时长都一致），
 * 现在抽出来给群配置的「列表 ↔ 单群编辑」共用，不另造第二套动画。
 *
 * [forward] = true：新页从右进、旧页往左出（进入二级页）；false 正好相反（返回）。
 * 位移一律 1/6 屏宽 + 淡入淡出，时长 [PanelTokens.OnboardAnimMs]（300ms）；
 * clip = false：两页高度/背景不同时不要在中途咔嚓裁一刀。
 */
fun <T> AnimatedContentTransitionScope<T>.panelStepTransition(
    forward: Boolean,
    ms: Int = PanelTokens.OnboardAnimMs,
): ContentTransform {
    val enter = slideInHorizontally(tween(ms)) { full -> if (forward) full / 6 else -full / 6 } +
        fadeIn(tween(ms))
    val exit = slideOutHorizontally(tween(ms)) { full -> if (forward) -full / 6 else full / 6 } +
        fadeOut(tween(ms))
    return (enter togetherWith exit).using(SizeTransform(clip = false))
}
