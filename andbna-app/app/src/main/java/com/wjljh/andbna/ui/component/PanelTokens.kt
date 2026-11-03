package com.wjljh.andbna.ui.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 四个 tab 页共用的数值 token（间距 / 字号 / 圆角 / 高度）。
 *
 * 取值全部按 SukiSU 真机实测：页边距 12、卡间距 12、卡圆角 16、卡内边距 16、
 * 标题 17sp Medium、行标签 17sp、行值 14sp、行高 ≥56dp、行间 0.5dp、
 * 按钮软底胶囊 35dp/40dp、hero 22sp SemiBold、双数字卡 58dp/30sp。
 * 页面里不再出现裸 dp / 裸 sp。
 */
object PanelTokens {
    /** 页面左右边距 12dp。 */
    val PageHorizontal: Dp = 12.dp

    /** 卡片之间的间距 12dp。 */
    val CardSpacing: Dp = 12.dp

    /** 页面顶部第一个元素的间距 12dp。 */
    val PageTop: Dp = 12.dp

    /** 卡片内边距 16dp。 */
    val CardInside: PaddingValues = PaddingValues(16.dp)

    /** 卡片内部行与行之间的间距。 */
    val RowSpacing: Dp = 6.dp

    /** 卡片标题块与内容之间的间距。 */
    val ContentGap: Dp = 12.dp

    /** 错误文案的颜色。 */
    val ErrorColor: Color = Color(0xFFF72727)

    /** 卡片圆角 16dp。 */
    val CardRadius: Dp = 16.dp

    /** 读数行最小高度（行高 ≥56dp）。 */
    val RowMinHeight: Dp = 56.dp

    /** 行间分隔线 0.5dp。 */
    val Divider: Dp = 0.5.dp

    /** preference 行的内边距：卡已经有 16dp 内边距，行自己不再留边。 */
    val PrefInside: PaddingValues = PaddingValues(0.dp)

    /** 浮动底栏与内容之间的安全距：实测底栏高度之外再留 12dp。 */
    val BottomBarSafetyGap: Dp = 12.dp

    // ── 三档按钮：SukiSU 的软底胶囊 ──────────────────────────────────────

    /** 三档统一高 35dp。 */
    val ButtonHeight: Dp = 35.dp

    /** 三档统一圆角 40dp（胶囊）。 */
    val ButtonCornerRadius: Dp = 40.dp

    /** 按钮文字 15sp Medium。 */
    val ButtonTextSize: TextUnit = 15.sp

    /** 同一组按钮之间的间距 8dp。 */
    val ButtonGap: Dp = 8.dp

    /** 读数行与按钮行之间 12dp。 */
    val ButtonRowTopGap: Dp = 12.dp

    /** 软底按钮的前景色：onSurface 90%。 */
    const val ButtonContentAlpha: Float = 0.9f

    /** 胶囊内的图标 20dp（SukiSU 的删除胶囊：Icon 20dp + 4dp + 15sp 文字）。 */
    val ButtonIconSize: Dp = 20.dp

    /** 图标与文字的间距 4dp。 */
    val ButtonIconGap: Dp = 4.dp

    // ── hero ──────────────────────────────────────────────────────────

    /** hero 最小高度 110dp。 */
    val HeroMinHeight: Dp = 110.dp

    /** hero 主标题 22sp SemiBold。 */
    val HeroTitleSize: TextUnit = 22.sp

    /** hero 主标题行高：收紧，别把 110dp 吃掉。 */
    val HeroTitleLineHeight: TextUnit = 28.sp

    /** hero 副标题 14sp。 */
    val HeroSubtitleSize: TextUnit = 14.sp

    /** 胶囊 / 标题 / 副标题之间 4dp。 */
    val HeroTextGap: Dp = 4.dp

    /** hero 左右内边距 16dp。 */
    val HeroPadding: Dp = 16.dp

    /** 右侧状态图标 92dp + 偏移（贴卡片右下角，靠 Card 圆角裁切）。 */
    val HeroIconSize: Dp = 92.dp
    val HeroIconOffsetX: Dp = 10.dp
    val HeroIconOffsetY: Dp = 12.dp

    /** hero 下段用量小字：比副标题再小一档，最多两行（卡片高度随内容撑开）。 */
    val HeroCostSize: TextUnit = 11.sp
    val HeroCostLineHeight: TextUnit = 15.sp
    val HeroCostGap: Dp = 3.dp

    /** 统计行与卡片底边的间距：不留就跟底边贴死。 */
    val HeroCostBottomPadding: Dp = 8.dp

    /** 上段（模式/状态 + 图标）与下段（通栏统计行）之间的间距。 */
    val HeroCostTopGap: Dp = 6.dp

    /** 状态页 hero 下方那行「群里只回 @」提示：11sp 次要色，无底无框。 */
    val PageHintSize: TextUnit = 11.sp
    val PageHintLineHeight: TextUnit = 16.sp

    /**
     * 右上角状态图标的实际尺寸：卡片里不再「顶出去」，圆必须完整落在卡内，
     * 四边各留 16dp（= [HeroPadding]）；72dp 更矮，卡片只长到文字块的高度。
     */
    val HeroIconInnerSize: Dp = 72.dp

    // ── 信息卡行（SukiSU HomeMiuix.kt:480-516 InfoText）─────────────────

    /** 行首图标 24dp。 */
    val InfoIconSize: Dp = 24.dp

    /** 图标与文字的间距 12dp。 */
    val InfoIconGap: Dp = 12.dp

    /** 行标题 17sp Medium onSurface。 */
    val InfoTitleSize: TextUnit = 17.sp

    /** 行值 14sp onSurfaceVariantSummary。 */
    val InfoValueSize: TextUnit = 14.sp

    /** 值距标题 2dp。 */
    val InfoValueGap: Dp = 2.dp

    /** 行下 24dp（末行 0）。 */
    val InfoRowGap: Dp = 24.dp

    // ── 列表行（SukiSU SuperUserMiuix.kt:550-624 GroupItem）────────────

    /** 前置图标 40dp。 */
    val EntityIconSize: Dp = 40.dp

    /** 前置图标圆角 12dp。 */
    val EntityIconRadius: Dp = 12.dp

    /** 列表行最小行高 64dp。 */
    val EntityRowMinHeight: Dp = 64.dp

    /** 列表行主标题 17sp / FontWeight(550)。 */
    val EntityTitleSize: TextUnit = 17.sp

    /** 列表行副标题 14sp。 */
    val EntitySubtitleSize: TextUnit = 14.sp

    // ── 运行模式胶囊（SukiSU「Built-in」那个位置）──────────────────────

    /** 胶囊高 20dp。 */
    val ModePillHeight: Dp = 20.dp

    /** 胶囊圆角 6dp。 */
    val ModePillRadius: Dp = 6.dp

    /** 胶囊文字 11sp Medium。 */
    val ModePillTextSize: TextUnit = 11.sp

    /** 胶囊左右内边距 8dp、上下内边距 2dp（11sp 文字 + 2×2 ≈ 20dp）。 */
    val ModePillPaddingH: Dp = 8.dp
    val ModePillPaddingV: Dp = 2.dp

    /** 胶囊底：状态色 20% 透明度。 */
    const val ModePillTintAlpha: Float = 0.20f

    // ── 双数字卡 ──────────────────────────────────────────────────────

    /** 数字卡高 58dp。 */
    val StatCardHeight: Dp = 68.dp

    /** 两张数字卡之间的间距 12dp。 */
    val StatGap: Dp = 12.dp

    /** 数字 30sp SemiBold。 */
    val StatNumberSize: TextUnit = 30.sp

    /** 数字行高 34sp（30sp 字 + 上下各 2sp 呼吸位，58dp 卡内不挤也不贴边）。 */
    val StatNumberLineHeight: TextUnit = 34.sp

    /** 标签 14sp 次级色。 */
    val StatLabelSize: TextUnit = 14.sp

    /** 标签行高 18sp：与数字行高一起锁死 52sp 内容块，58dp 卡内上下各 3dp。 */
    val StatLabelLineHeight: TextUnit = 18.sp

    /**
     * 内容块的光学上抬 3dp：数字（含下伸位）在行框里上下各留 5dp，而中文标签几乎贴着行框底，
     * 所以整块在卡内是「上留 8.3dp / 下留 2.8dp」，看着标签贴底。
     * 把内容块抬 3dp 之后上下各 5.3/5.8dp，才是视觉上的垂直居中。
     */
    val StatInkRise: Dp = 3.dp

    /** 数字卡水平内边距 16dp。 */
    val StatPaddingHorizontal: Dp = 16.dp

    // ── 单选圆点（模式选择那种一列单选项）──────────────────────────────

    /** 圆点直径 20dp。 */
    val RadioDotSize: Dp = 20.dp

    /** 未选中的空心描边 1.5dp。 */
    val RadioDotStroke: Dp = 1.5.dp

    // ── 首次使用引导（5 步 hero + 顶部细进度条）──────────────────────────

    /** 顶部细进度条高 3dp。 */
    val OnboardProgressHeight: Dp = 3.dp

    /** 状态栏与进度条之间 6dp。 */
    val OnboardProgressTop: Dp = 6.dp

    /** 进度条与「第 N / 5 步」这一行之间 10dp。 */
    val OnboardProgressGap: Dp = 10.dp

    /** 顶栏与内容之间 4dp（内容自己还有 hero 的 24dp）。 */
    val OnboardHeaderBottom: Dp = 4.dp

    /** 「第 N / 5 步 · 名字」13sp。 */
    val OnboardStepLabelSize: TextUnit = 13.sp

    /** 右上角「跳过」15sp（弱化色，热区左右 8dp / 上下 6dp）。 */
    val OnboardSkipSize: TextUnit = 15.sp
    val OnboardSkipPaddingH: Dp = 8.dp
    val OnboardSkipPaddingV: Dp = 6.dp

    /** hero 大图标 104dp（96~120 的中段，比 HeroIconSize 的 92dp 更撑得住场）。 */
    val OnboardHeroIcon: Dp = 104.dp

    /** hero 上下留白各 24dp（不挤）。 */
    val OnboardHeroTop: Dp = 24.dp
    val OnboardHeroBottom: Dp = 24.dp

    /** 图标与大标题之间 18dp。 */
    val OnboardHeroGap: Dp = 18.dp

    /** hero 大标题 27sp SemiBold / 行高 34sp。 */
    val OnboardHeroTitleSize: TextUnit = 27.sp
    val OnboardHeroTitleLineHeight: TextUnit = 34.sp

    /** hero 说明 15sp / 行高 22sp，距大标题 10dp。 */
    val OnboardHeroBodySize: TextUnit = 15.sp
    val OnboardHeroBodyLineHeight: TextUnit = 22.sp
    val OnboardHeroBodyGap: Dp = 10.dp

    /** 检测结果的圆底状态圈 40dp，里面图形 22dp。 */
    val OnboardStateIcon: Dp = 40.dp
    val OnboardStateGlyph: Dp = 22.dp

    /** 状态圈底色 = 状态色 14%。 */
    const val OnboardStateTintAlpha: Float = 0.14f

    /** 「详细信息」折叠行文字 14sp、箭头 18dp。 */
    val OnboardDetailToggleSize: TextUnit = 14.sp
    val OnboardDetailGlyph: Dp = 18.dp

    /** 步骤切换 / 折叠展开的时长 300ms（spec 的 tween(300)）。 */
    const val OnboardAnimMs: Int = 300

    /** 欢迎页「接下来 5 步」的序号列宽 22dp、行标题 15sp / 注脚 13sp。 */
    val OnboardStepIndexWidth: Dp = 22.dp
    val OnboardListTitleSize: TextUnit = 15.sp
    val OnboardListNoteSize: TextUnit = 13.sp

    /** 小结卡行标题 13sp / 值 15sp、完成页欢迎语 13sp。 */
    val OnboardSummaryLabelSize: TextUnit = 13.sp
    val OnboardSummaryValueSize: TextUnit = 15.sp
    val OnboardWelcomeSize: TextUnit = 13.sp
}
