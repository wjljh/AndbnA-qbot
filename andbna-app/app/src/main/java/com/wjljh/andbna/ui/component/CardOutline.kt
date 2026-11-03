package com.wjljh.andbna.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wjljh.andbna.ui.PanelColors

/**
 * 卡片描边（**只有浅色生效**）。
 *
 * 浅色下页面底与卡片都是近白（实测 247 / 255），只差 1.07:1，卡片边界立不住；
 * 这里给卡片描一圈 1dp 的 [PanelColors.cardBorder]（与页面 3.2:1），边界才看得见。
 * 深色下 [PanelColors.cardBorder] 是透明，直接返回原 Modifier —— 深色观感一字不改。
 *
 * 画在内容**之后**，所以不会被卡片自己的底色盖掉；半个描边内缩，避免被父层裁掉。
 */
@Composable
fun Modifier.cardOutline(radius: Dp = PanelTokens.CardRadius): Modifier {
    val color = PanelColors.cardBorder
    if (color.alpha <= 0f) return this
    val density = LocalDensity.current
    val strokePx = with(density) { 1.dp.toPx() }
    val radiusPx = with(density) { radius.toPx() }
    return drawWithContent {
        drawContent()
        drawRoundRect(
            color = color,
            topLeft = Offset(strokePx / 2f, strokePx / 2f),
            size = Size(size.width - strokePx, size.height - strokePx),
            cornerRadius = CornerRadius((radiusPx - strokePx / 2f).coerceAtLeast(0f)),
            style = Stroke(width = strokePx),
        )
    }
}
