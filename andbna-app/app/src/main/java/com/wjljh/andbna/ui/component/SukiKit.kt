package com.wjljh.andbna.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.foundation.Indication
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wjljh.andbna.ui.LocalUiMode
import com.wjljh.andbna.ui.PanelColors
import com.wjljh.andbna.ui.UiMode
import androidx.compose.material3.Card as MaterialCard
import androidx.compose.material3.Checkbox as MaterialCheckbox
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.IconButton as MaterialIconButton
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.TiltFeedback
import top.yukonga.miuix.kmp.utils.pressable

/**
 * 四个页面共用的骨架组件 —— **UI 抽象层**。
 *
 * 页面只写一份：每个组件内部按 [LocalUiMode] 二选一挑「外壳」，
 * 而排版（图标尺寸、字号、间距、分隔线位置）只有一份代码。
 *
 *  · [PanelGroupCard]  —— miuix Card（Tilt 按压）/ material3 Card（surfaceContainer + ripple）
 *  · [PanelInfoRow]    —— 结构照 SukiSU HomeMiuix.kt:480-516 InfoText（图标 24dp + 17sp 标题 / 14sp 值）
 *  · [PanelEntityRow]  —— SuperUserMiuix.kt:550-624 GroupItem（40dp 圆角 12dp 图标 + 17sp(550) 标题）
 *  · [PanelPillRow]    —— 胶囊动作行（35dp / 40dp 圆角 / 间隔 8dp）
 *
 * 颜色一律走 [PanelColors]（模式无关的语义名），所以页面里不会出现 UiMode 分支。
 */

/**
 * 组卡：没有卡标题，内容自己排版。
 *
 * miuix：kit 的 Card，insideMargin 0 + 内 padding 16dp，可点时 Tilt 按压、无水波。
 * material：material3 Card，surfaceContainer 底、圆角 16dp、默认 ripple。
 */
@Composable
fun PanelGroupCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalUiMode.current == UiMode.Material) {
        val shape = RoundedCornerShape(PanelTokens.CardRadius)
        val colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        )
        val elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        if (onClick != null) {
            // 可点卡：material3 的 clickable Card（自带 ripple），不用 enabled=false 那种降透明度写法
            MaterialCard(
                modifier = modifier.fillMaxWidth().cardOutline(PanelTokens.CardRadius),
                onClick = onClick,
                shape = shape,
                colors = colors,
                elevation = elevation,
            ) {
                Column(modifier = Modifier.padding(PanelTokens.CardInside), content = content)
            }
        } else {
            MaterialCard(
                modifier = modifier.fillMaxWidth().cardOutline(PanelTokens.CardRadius),
                shape = shape,
                colors = colors,
                elevation = elevation,
            ) {
                Column(modifier = Modifier.padding(PanelTokens.CardInside), content = content)
            }
        }
    } else {
        MiuixCard(
            modifier = modifier.fillMaxWidth().cardOutline(PanelTokens.CardRadius),
            cornerRadius = PanelTokens.CardRadius,
            insideMargin = PaddingValues(0.dp),
            pressFeedbackType = if (onClick != null) PressFeedbackType.Tilt else PressFeedbackType.None,
            showIndication = false,
            onClick = onClick,
        ) {
            Column(modifier = Modifier.padding(PanelTokens.CardInside), content = content)
        }
    }
}

/**
 * 信息行：行首图标 24dp（列宽锁死 24dp），图标与文字列之间 12dp；
 * 右侧上标题（17sp Medium）下值（14sp），距标题 2dp；行下 24dp、末行 0；
 * 行间 0.5dp 细线。两种模式共用这一段排版，只换颜色。
 *
 * 文字列用 weight(1f, fill = false)：宽度被卡内 16dp 内边距约束住，长值（数据目录那种
 * 绝对路径）最多两行 + Ellipsis，绝不会把卡片顶破；行首图标按「标题 + 值」整块垂直居中
 * （有值时在整块中间，无值时就是单行中间）。
 */
@Composable
fun PanelInfoRow(
    icon: ImageVector,
    title: String,
    value: String,
    last: Boolean = false,
    divider: Boolean = true,
    valueColor: Color = Color.Unspecified,
    // 传了 onClick 这一行才可点（默认 null = 纯信息行，其它调用点行为不变）
    onClick: (() -> Unit)? = null,
) {
    // 与 SukiSU 的 InfoText 一致：垂直居中、字体默认 padding（不关 includeFontPadding）。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { m -> if (onClick != null) m.clickable { onClick.invoke() } else m }
            // 行距不再挂在下边（那样分隔线会贴在下一行上）：改由分隔线自己上下各留半格
            .padding(bottom = 0.dp),
        // 整块居中：图标落「标题 + 值」这一整块的中间（有值时在整块中间，无值时就是单行中间）。
        // 不再用 Top 对齐 —— 那会让图标贴着标题走，块变高时看着偏上。
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = PanelColors.onSurface,
            modifier = Modifier
                .padding(end = PanelTokens.InfoIconGap)
                .size(PanelTokens.InfoIconSize),
        )
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = title,
                fontSize = PanelTokens.InfoTitleSize,
                fontWeight = FontWeight.Medium,
                color = PanelColors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                fontSize = PanelTokens.InfoValueSize,
                color = if (valueColor == Color.Unspecified) PanelColors.onSurfaceVariantSummary else valueColor,
                modifier = Modifier.padding(top = PanelTokens.InfoValueGap),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (divider && !last) {
        // 上下各留半格行距 → 分隔线落在两行正中，不再贴在下一行上（实测原来上 24.3dp / 下 0.3dp）
        HorizontalDivider(
            modifier = Modifier.padding(vertical = PanelTokens.InfoRowGap / 2),
            thickness = PanelTokens.Divider,
            color = PanelColors.dividerLine,
        )
    }
}

/**
 * 列表行：前置 40dp（圆角 12dp 的软底方块 + 图标）+ 主标题 17sp/FontWeight(550) + 副标题 14sp
 * + 行尾动作；行高 ≥64dp、行间 0.5dp 细线。
 */
@Composable
fun PanelEntityRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    divider: Boolean = true,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    feedback: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PanelTokens.EntityRowMinHeight)
            .modeClickable(onClick, enabled, onLongClick, feedback)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Box(
            modifier = Modifier
                .padding(end = PanelTokens.ContentGap)
                .size(PanelTokens.EntityIconSize)
                .clip(RoundedCornerShape(PanelTokens.EntityIconRadius))
                .background(PanelColors.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = PanelColors.onSurface.copy(alpha = if (enabled) 0.9f else 0.4f),
                modifier = Modifier.size(PanelMetrics.RowIconSize),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = PanelTokens.EntityTitleSize,
                fontWeight = FontWeight(550),
                color = if (enabled) PanelColors.onSurface else PanelColors.onSurface.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    fontSize = PanelTokens.EntitySubtitleSize,
                    color = PanelColors.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke(this)
    }
    if (divider) {
        HorizontalDivider(thickness = PanelTokens.Divider, color = PanelColors.dividerLine)
    }
}

/**
 * 多选用的选择圈（列表行左上的那个）：miuix 外壳用 kit 的 [Checkbox]，
 * material 外壳用 material3 的 Checkbox —— 两边都是现成组件，不自绘。
 * 行本身负责点击，所以这里 onCheckedChange = null（否则一次点击吃两次）。
 */
@Composable
fun PanelCheckCircle(checked: Boolean, modifier: Modifier = Modifier) {
    if (LocalUiMode.current == UiMode.Material) {
        MaterialCheckbox(checked = checked, onCheckedChange = null, modifier = modifier)
    } else {
        Checkbox(
            state = if (checked) ToggleableState.On else ToggleableState.Off,
            onClick = null,
            modifier = modifier,
        )
    }
}

/**
 * 单选行（模式选择那种）：行首单选圆点 + 标题 17sp Medium + 说明 14sp 次级色，
 * 选中时整行底色提亮一档（secondaryContainer + 12dp 圆角），未选就是透明。
 *
 * 圆点：选中 = 主色实心；未选 = outline 空心描边（1.5dp）。行间 0.5dp 细线。
 */
@Composable
fun PanelChoiceRow(
    title: String,
    subtitle: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    divider: Boolean = true,
    onClick: () -> Unit,
) {
    val tight = PlatformTextStyle(includeFontPadding = false)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PanelTokens.ContentGap))
            .background(if (selected) PanelColors.secondaryContainer else Color.Transparent)
            .modeClickable(if (enabled) onClick else null, enabled)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selected) {
                Box(
                    Modifier
                        .size(PanelTokens.RadioDotSize)
                        .clip(CircleShape)
                        .background(PanelColors.primary)
                )
            } else {
                Box(
                    Modifier
                        .size(PanelTokens.RadioDotSize)
                        .border(PanelTokens.RadioDotStroke, PanelColors.outline, CircleShape)
                )
            }
            Column(
                modifier = Modifier
                    .padding(start = PanelTokens.ContentGap)
                    .weight(1f, fill = false),
            ) {
                Text(
                    text = title,
                    fontSize = PanelTokens.InfoTitleSize,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) PanelColors.onSurface else PanelColors.disabledOnSecondaryVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(platformStyle = tight),
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        fontSize = PanelTokens.InfoValueSize,
                        color = PanelColors.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = PanelTokens.InfoValueGap),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(platformStyle = tight),
                    )
                }
            }
        }
    }
    if (divider) {
        HorizontalDivider(thickness = PanelTokens.Divider, color = PanelColors.dividerLine)
    }
}

/**
 * 胶囊动作行：统一 35dp 高 / 40dp 圆角 / 间隔 8dp；最多一个 primary 实心由调用方决定。
 *
 * [fillWidth] = false 时这行按内容宽度起始对齐 —— 调用方不再给每个按钮 weight，
 * 按钮保持自己的内宽，不会被拉成一排等宽大块（root 卡那种两三个动作的地方用这个）。
 */
@Composable
fun PanelPillRow(
    modifier: Modifier = Modifier,
    insideStart: Dp = 0.dp,
    fillWidth: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .padding(start = insideStart),
        horizontalArrangement = Arrangement.spacedBy(PanelTokens.ButtonGap),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * 可点行 / 卡片的按压反馈，按模式二选一：
 * · miuix：Tilt（±8°、spring(0.6f, 400f)），水波关掉
 * · material：默认 ripple
 */
@Composable
fun Modifier.modeClickable(
    onClick: (() -> Unit)?,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    feedback: Boolean = true,
): Modifier {
    if (onClick == null && onLongClick == null) return this
    return if (LocalUiMode.current == UiMode.Material) {
        materialClickable(onClick, enabled, onLongClick, feedback)
    } else {
        tiltClickable(onClick, enabled, onLongClick, feedback)
    }
}

/**
 * feedback = false：material 侧也把 ripple 摘掉（indication = null）——
 * 「点一下不该有按下动画」的列表项两种模式表现一致。
 */
@Composable
private fun Modifier.materialClickable(
    onClick: (() -> Unit)?,
    enabled: Boolean,
    onLongClick: (() -> Unit)?,
    feedback: Boolean = true,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val indication: Indication? = if (feedback) LocalIndication.current else null
    return if (onLongClick == null) {
        clickable(
            interactionSource = source,
            indication = indication,
            enabled = enabled,
            onClick = { onClick?.invoke() },
        )
    } else {
        combinedClickable(
            interactionSource = source,
            indication = indication,
            enabled = enabled,
            onClick = { onClick?.invoke() },
            onLongClick = onLongClick,
        )
    }
}

/**
 * miuix 专属的 Tilt 按压：kit 的 Tilt 是 [TiltFeedback] 这个 IndicationNodeFactory，
 * 只能经 [pressable] 挂上去；Card 内部也是这么拼的（Card.kt 的 modifier.pressable(indication = pressFeedback)）。
 */
@Composable
fun Modifier.tiltClickable(
    onClick: (() -> Unit)?,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    feedback: Boolean = true,
): Modifier {
    if (onClick == null && onLongClick == null) return this
    val source = remember { MutableInteractionSource() }
    val tilt = remember { TiltFeedback() }
    // feedback = false：不挂 pressable，就没有 Tilt 那一歪（记忆页长按进多选时不想整行晃）
    val press: Modifier = if (feedback) {
        Modifier.pressable(
            interactionSource = source,
            indication = tilt,
            enabled = enabled,
            delay = null,
        )
    } else {
        Modifier
    }
    return this
        .then(press)
        .combinedClickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            onClick = { onClick?.invoke() },
            onLongClick = onLongClick,
        )
}

/**
 * 记忆页多选态的顶栏（相册式）：左「取消」/ 中「已选 N 项」/ 右「全选」+ 删除图标。
 *
 * 由 PanelScreen 的 topBar 槽位渲染 —— 在 LazyColumn 之外，滚动列表时不动
 * （照 SukiSU 顶栏右上角那两个 action 按钮）。所以这里不再套 [PanelGroupCard]：
 * 它就是一条 action 行，自己带 PageHorizontal 横向内边距与其他卡片对齐。
 *
 * 删除图标走 kit 的 [IconButton] + [MiuixIcons.Delete]（material 外壳用 material3 的同名组件），
 * 不自绘；原来那行「全选 / 取消 / 删除所选」按这个布局重排，取消与删除各只出现一次。
 */
@Composable
fun PanelSelectBar(
    count: Int,
    onSelectAll: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = PanelTokens.PageHorizontal,
                end = PanelTokens.PageHorizontal,
                top = PanelTokens.PageTop,
                bottom = PanelTokens.PageTop,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PanelTertiaryButton(text = "取消", onClick = onCancel)
        Text(
            text = "已选 " + count + " 项",
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            fontSize = PanelTokens.EntityTitleSize,
            fontWeight = FontWeight(550),
            color = PanelColors.onSurface,
            maxLines = 1,
        )
        PanelTertiaryButton(text = "全选", onClick = onSelectAll)
        if (LocalUiMode.current == UiMode.Material) {
            MaterialIconButton(onClick = { if (count > 0) onDelete() }) {
                MaterialIcon(PanelIcons.Delete, contentDescription = "删除所选")
            }
        } else {
            IconButton(onClick = { if (count > 0) onDelete() }) {
                Icon(imageVector = PanelIcons.Delete, contentDescription = "删除所选")
            }
        }
    }
}

/** 提示条显隐：fadeIn + expandVertically 进、shrinkVertically + fadeOut 出（两种模式一致）。 */
@Composable
fun PanelAnimatedNotice(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        content()
    }
}
