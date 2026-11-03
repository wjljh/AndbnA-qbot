package com.wjljh.andbna.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设计系统的唯一数值来源。
 *
 * 页面里不许再出现裸 dp / 裸 sp：要新数字先加到这里的某一个阶梯。
 * 取值来自真机实测（页边距 12 / 卡间距 12 / 卡圆角 16 / 卡内边距 16 /
 * 行高 56 / hero 110）与 sukisu-ui-spec 00 号总览。
 */
object Spacing {
    /** 4dp：图标与文字、贴边细节。 */
    val Xs: Dp = 4.dp

    /** 8dp：同一组内相邻元素。 */
    val Sm: Dp = 8.dp

    /** 12dp：页面左右边距、卡片间距。 */
    val Md: Dp = 12.dp

    /** 16dp：卡片内边距。 */
    val Lg: Dp = 16.dp

    /** 24dp：分块之间的大留白。 */
    val Xl: Dp = 24.dp
}

/** 圆角三档，全站只有这三档。 */
object Radius {
    /** 卡片 16dp（含 hero、数字卡）。 */
    val Card: Dp = 16.dp

    /** 按钮 16dp。 */
    val Button: Dp = 16.dp

    /** 胶囊（底栏、药丸按钮）。 */
    val Pill: Dp = 999.dp
}

/** 字号层级：hero 22 / 卡标题与行标签 17 / 行值 14 / 说明 12。 */
object TextSize {
    val Hero: TextUnit = 22.sp
    val CardTitle: TextUnit = 17.sp
    val RowLabel: TextUnit = 17.sp
    val RowValue: TextUnit = 14.sp
    val Caption: TextUnit = 12.sp
    val Button: TextUnit = 15.sp
    val StatNumber: TextUnit = 28.sp
    val BarLabel: TextUnit = 12.sp
}

/** 字重：只用两档，避免每页各写一个。 */
object TextWeight {
    val Hero: FontWeight = FontWeight.SemiBold
    val CardTitle: FontWeight = FontWeight.Medium
    val StatNumber: FontWeight = FontWeight.SemiBold
    val Button: FontWeight = FontWeight.Medium
    val BarSelected: FontWeight = FontWeight.Bold
}

/** 尺寸令牌（高度 / 图标 / 细线）。 */
object Size {
    /** 页面左右边距 12dp（真机实测）。 */
    val PageHorizontal: Dp = 12.dp

    /** 卡片之间 12dp。 */
    val CardGap: Dp = 12.dp

    /** 卡片内边距 16dp。 */
    val CardInside: Dp = 16.dp

    /** 读数行最小行高 56dp。 */
    val RowMinHeight: Dp = 56.dp

    /** 行间细线 0.5dp。 */
    val Divider: Dp = 0.5.dp

    /** 行首图标 24dp。 */
    val RowIcon: Dp = 24.dp

    /** 三档按钮统一高 40dp。 */
    val ButtonHeight: Dp = 40.dp

    /** hero 中等卡最小高 110dp（SukiSU 那版 182dp 太大，不要学）。 */
    val HeroMin: Dp = 110.dp

    /**
     * hero 右下角状态图标 66dp + 偏移 (16, 19)。
     *
     * 等比缩放：SukiSU HomeMiuix.kt:294-299 是 110dp 图标配 182dp 卡高，
     * 我们的卡高 110dp → 110/182 ≈ 0.60，图标 110 * 0.60 ≈ 66dp，
     * 偏移 (27,31) 同乘 0.60 得 (16,19)。
     */
    // 真机像素标定：SukiSU 可见圆环高/卡高 = 0.568（110dp 图标在 182dp 卡内几乎完整可见）。
    // 我们卡高 104dp，按同一可见比例反推：0.83×92 − 12 − 圆角裁切6 ≈ 58dp（≈0.56）。
    // 旧的 66dp/(16,19) 实测只有 0.369，明显偏小。
    val HeroIconSize: Dp = 82.dp
    val HeroIconOffsetX: Dp = 10.dp
    val HeroIconOffsetY: Dp = 12.dp

    /** 双数字卡高 72dp。 */
    val StatCard: Dp = 72.dp

    /** 双数字卡之间的间隔 12dp。 */
    val StatGap: Dp = 12.dp

    /** 底栏条目高 56dp。 */
    val BarItem: Dp = 56.dp

    /** 底栏胶囊高 64dp（条目 56 + 上下各 4）。 */
    val Bar: Dp = 64.dp

    /** 底栏胶囊内沿 4dp。 */
    val BarInside: Dp = 4.dp

    /** 底栏宽度上限（宽屏不摊满）。 */
    val BarMaxWidth: Dp = 480.dp

    /** 底栏与页面内容的呼吸 12dp —— 内容底部留白 = 底栏实测高度 + 这个值。 */
    val BarGap: Dp = 12.dp

    /** 底栏图标 22dp。 */
    val BarIcon: Dp = 22.dp

    /** 底栏角标点 6dp。 */
    val BadgeDot: Dp = 6.dp

    /** 细描边（底栏胶囊）。 */
    val Hairline: Dp = 0.5.dp
}

/** 状态语义：ok / warn / error / idle 四档。 */
enum class StatusTone { Ok, Warn, Error, Idle }

/**
 * 状态色。
 *
 * 亮色 ok 淡底 = #DFFAE4、暗色 ok 淡底 = #1A3825（SukiSU 实测值）；
 * warn / error / idle 没有实测淡底，按「状态色 10% 叠到 surfaceContainer」算，
 * 与 ok 是同一套 10% 口径，不再另造数值。
 */
object StatusColors {
    val OkAccent: Color = Color(0xFF36D167)
    val OkSurfaceLight: Color = Color(0xFFDFFAE4)
    val OkSurfaceDark: Color = Color(0xFF1A3825)
    val WarnAccent: Color = Color(0xFFE8A33D)
    val ErrorAccent: Color = Color(0xFFE4574F)
    val IdleAccent: Color = Color(0xFF9E9AA0)

    /** 淡底叠加强度：10%。 */
    const val TintAlpha: Float = 0.10f

    /** 浅色专用的 idle 灰：原 #9E9AA0 压在 10% 淡底上只有 2.4:1，「未启动」胶囊几乎读不出来。 */
    val IdleAccentLight: Color = Color(0xFF6A666D)

    @Composable
    fun accent(tone: StatusTone): Color = when (tone) {
        StatusTone.Ok -> OkAccent
        StatusTone.Warn -> WarnAccent
        StatusTone.Error -> ErrorAccent
        // 浅色加深一档深色原样：胶囊文字与淡底都读这一个值
        StatusTone.Idle ->
            if (MiuixTheme.colorScheme.surfaceContainer.luminance() >= 0.5f) IdleAccentLight
            else IdleAccent
    }

    /**
     * hero 淡底。深浅由容器底色亮度判定（跟着用户选的主题走，不看系统设置）。
     * ok 用实测两档，其余按 10% 叠加 —— 结果都是实色，交给 Card 直接画。
     */
    @Composable
    fun surface(tone: StatusTone): Color {
        val container = MiuixTheme.colorScheme.surfaceContainer
        val dark = container.luminance() < 0.5f
        if (tone == StatusTone.Ok) {
            return if (dark) OkSurfaceDark else OkSurfaceLight
        }
        // 浅色下 10% 灰叠在白卡上只有 #F5F5F5，和页面底 #EFEFF4/#F7F7F7 几乎同色，hero 边界直接消失；
        // 浅色这一档退回白卡，边界交给 1dp 描边（StatusHeroCard 的 cardOutline）。
        // 深色不动：叠加后的淡底在深色里是有意义的层次。
        if (tone == StatusTone.Idle && !dark) return container
        return accent(tone).copy(alpha = TintAlpha).compositeOver(container)
    }
}
