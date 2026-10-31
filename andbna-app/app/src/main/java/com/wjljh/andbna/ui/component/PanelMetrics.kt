package com.wjljh.andbna.ui.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 记忆页 / 设置页用的视觉令牌（spacing + 字号 + 圆角）。
 *
 * 为什么不在 [PanelTokens] 里：PanelTokens 归状态页代理维护，本代理不改别人的文件。
 * 这里放的全是「四个 tab 页都该统一」的基线值，等 PanelTokens 的 owner 合并过去，
 * 这两个页面把引用换掉即可 —— 值是唯一的来源，页面里没有裸 dp / 裸 sp。
 */
object PanelMetrics {

    /** 卡片圆角 16dp（视觉基线）。 */
    val CardRadius: Dp = 16.dp

    /** 卡片内边距 16dp（视觉基线）。 */
    val CardInside: PaddingValues = PaddingValues(16.dp)

    /** 卡片内标题块与内容之间、以及行与行之间的间距。 */
    val ContentGap: Dp = 12.dp

    /** 读数行最小行高（视觉基线 ≥56dp），保证「左主右值」的行有呼吸感。 */
    val RowMinHeight: Dp = 56.dp

    /** 卡片标题 17sp Medium。 */
    val CardTitleSize: TextUnit = 17.sp

    /** 主文本 17sp。 */
    val BodySize: TextUnit = 17.sp

    /** 读数 / 次文本 14sp。 */
    val ValueSize: TextUnit = 14.sp

    /** 说明与提示文案 12sp。 */
    val HintSize: TextUnit = 12.sp

    /** 行首 / 按钮内图标 24dp。 */
    val RowIconSize: Dp = 24.dp

    /** 空态图标 40dp。 */
    val EmptyIconSize: Dp = 40.dp

    /** 并排两个按钮之间的间距。 */
    val ButtonGap: Dp = 8.dp

    /** 提示条内边距。 */
    val NoticeInside: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 10.dp)

    /** 读数行之间的细线 0.5dp。 */
    val Divider: Dp = 0.5.dp

    /** 日志弹窗文本区最大高度，超出后内部滚动。 */
    val LogDialogMaxHeight: Dp = 320.dp
}
