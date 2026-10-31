package com.wjljh.andbna.ui.component.material

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * material 模式的分段列表 —— 结构照 SukiSU
 * manager/.../ui/component/material/SegmentedList.kt（SegmentedColumn / SegmentedListItem /
 * SegmentedSwitchItem / SegmentedDropdownItem）的**分组语义**，
 * 数值按本工程的设置页落地：
 *
 *  · 一组 = Column(spacedBy(2dp))，组内**每行独立一个 Surface**（surfaceBright 底），
 *    按位置给圆角：首行圆上 16dp / 末行圆下 16dp / 中间行 4dp；
 *  · 一行 = 最小高 56dp、左右内边距 16dp、可选前置图标 24dp（图标与文字 16dp）；
 *  · 标题 MaterialTheme.typography.bodyLarge / onSurface，副标题 bodyMedium / onSurfaceVariant；
 *  · 组间距由调用方给：Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp)（SukiSU SettingsMaterial.kt）。
 *
 * 行与行之间靠 2dp 空隙分隔，**不画分隔线**（SukiSU SegmentedList.kt:123 的 spacedBy(2.dp)）。
 */
object SegmentedMetrics {
    /** 行最小高度。 */
    val RowMinHeight = 56.dp

    /** 行左右内边距。 */
    val RowHorizontal = 16.dp

    /** 前置图标尺寸。 */
    val IconSize = 24.dp

    /** 前置图标与文字列之间。 */
    val IconGap = 16.dp

    /** 组圆角。 */
    val GroupRadius = 16.dp

    /** 行间分隔线。 */
    val Divider = 1.dp

    /** 组与组之间（写在组自己的 padding 上）。 */
    val GroupGap = 13.dp

    /** 组左右外边距。 */
    val GroupHorizontal = 16.dp

    /** 行与行之间的空隙（SukiSU SegmentedList.kt:123 spacedBy(2.dp)）。 */
    val RowGap = 2.dp

    /** 中间行的小圆角（SukiSU SegmentedInnerRadius）。 */
    val InnerRadius = 4.dp
}

/**
 * 组内容的作用域：每调一次 [item] 就是组内一行。
 * 收集成列表而不是直接叠 Column，是为了让组自己知道「谁是首/末行」（首行圆上、末行圆下）。
 */
/** 组外边距（SukiSU SettingsMaterial.kt）：start 16 / end 16 / bottom 13dp。 */
fun Modifier.segmentedGroupInsets(): Modifier = this.padding(
    start = SegmentedMetrics.GroupHorizontal,
    end = SegmentedMetrics.GroupHorizontal,
    bottom = SegmentedMetrics.GroupGap,
)

class SegmentedGroupScope internal constructor() {
    internal class Entry(val content: @Composable () -> Unit)

    internal val entries = mutableListOf<Entry>()

    fun item(content: @Composable () -> Unit) {
        entries.add(Entry(content))
    }
}

/** 一组：每行一个独立圆角 Surface，行间 2dp 空隙。 */
@Composable
fun SegmentedGroup(
    modifier: Modifier = Modifier,
    content: SegmentedGroupScope.() -> Unit,
) {
    val scope = SegmentedGroupScope().apply(content)
    if (scope.entries.isEmpty()) return
    // 与 SukiSU SegmentedList.kt:105-135 一致：不是「整组一个 Surface + 分隔线」，
    // 而是 Column(spacedBy(2.dp)) + 每行独立 Surface，按位置给圆角（首行圆上、末行圆下、中间小圆角），
    // 底色用 surfaceBright，行间靠 2dp 空隙分隔、不画线。
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SegmentedMetrics.RowGap),
    ) {
        scope.entries.forEachIndexed { index, entry ->
            val last = index == scope.entries.lastIndex
            val top = if (index == 0) SegmentedMetrics.GroupRadius else SegmentedMetrics.InnerRadius
            val bottom = if (last) SegmentedMetrics.GroupRadius else SegmentedMetrics.InnerRadius
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom),
                color = MaterialTheme.colorScheme.surfaceBright,
            ) { entry.content() }
        }
    }
}

/** 一行可点的设置项（带行尾插槽），供 Bot / 记忆页那种「行尾放动作按钮」的列表复用。 */
@Composable
fun SegmentedRowItem(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    SegmentedRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        enabled = enabled,
        onClick = onClick,
        leading = leading,
        onLongClick = onLongClick,
        trailing = trailing,
    )
}

/** 跳转 / 编辑行：行尾可选当前值 + 右箭头。 */
@Composable
fun SegmentedArrowItem(
    title: String,
    icon: ImageVector? = null,
    summary: String? = null,
    value: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    SegmentedRow(
        title = title,
        subtitle = summary,
        icon = icon,
        enabled = enabled,
        onClick = onClick,
    ) {
        if (!value.isNullOrBlank()) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp).size(24.dp),
        )
    }
}

/** 开关行：整行可点，行尾 material3 Switch。 */
@Composable
fun SegmentedSwitchItem(
    title: String,
    checked: Boolean,
    icon: ImageVector? = null,
    summary: String? = null,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    SegmentedRow(
        title = title,
        subtitle = summary,
        icon = icon,
        enabled = enabled,
        onClick = { onCheckedChange(!checked) },
    ) {
        Switch(
            checked = checked,
            onCheckedChange = { onCheckedChange(it) },
            enabled = enabled,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/** 下拉行：点开 material3 DropdownMenu，行尾显示当前值 + 三角。 */
@Composable
fun SegmentedDropdownItem(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    icon: ImageVector? = null,
    summary: String? = null,
    enabled: Boolean = true,
    onSelectedIndexChange: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val index = if (items.isEmpty()) -1 else selectedIndex.coerceIn(0, items.lastIndex)
    val current = items.getOrNull(index).orEmpty()

    Box {
        SegmentedRow(
            title = title,
            subtitle = summary,
            icon = icon,
            enabled = enabled && items.isNotEmpty(),
            onClick = { expanded = true },
        ) {
            Text(
                text = current,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp),
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEachIndexed { i, label ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (i == index) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelectedIndexChange(i)
                    },
                )
            }
        }
    }
}

/** 只读行：左侧标题 / 副标题，行尾只显示值（不点、不带箭头）。 */
@Composable
fun SegmentedInfoItem(
    title: String,
    value: String,
    icon: ImageVector? = null,
    summary: String? = null,
) {
    SegmentedRow(
        title = title,
        subtitle = summary,
        icon = icon,
        enabled = true,
        onClick = null,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/**
 * 数值行：**标签 + 行内可编辑值**，不是输入框。
 *
 * 左列照常是标题/副标题，右侧是右对齐的当前值；点这一行（或点值）值就地变成
 * 一个无边框 [BasicTextField]（同一字号、同一基线，没有浮动 label、没有 outline），
 * 回车 / 失焦即退出编辑。这样一个「数值设置」在分段组里就是一行普通行，
 * 和开关行、下拉行长得完全一致。
 */
@Composable
fun SegmentedValueItem(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    icon: ImageVector? = null,
    summary: String? = null,
    enabled: Boolean = true,
    numeric: Boolean = false,
    /** 编辑态之外显示的值（密钥这种要打码的字段用）。 */
    displayValue: String? = null,
) {
    var editing by remember { mutableStateOf(false) }
    // 首次回调一定是 isFocused = false（还没拿到焦点），不能把它当成「失焦退出编辑」，
    // 否则点了行就立刻退回只读态。只有真拿到过焦点之后失焦才退出。
    var hadFocus by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val valueStyle = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.End)
    val valueColor = MaterialTheme.colorScheme.onSurface
    val idleColor = MaterialTheme.colorScheme.onSurfaceVariant

    LaunchedEffect(editing) {
        if (editing) focus.requestFocus()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (enabled && !editing) Modifier.clickable { editing = true } else Modifier
            )
            .heightIn(min = SegmentedMetrics.RowMinHeight)
            .padding(horizontal = SegmentedMetrics.RowHorizontal, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) idleColor else idleColor.copy(alpha = 0.38f),
                modifier = Modifier
                    .padding(end = SegmentedMetrics.IconGap)
                    .size(SegmentedMetrics.IconSize),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!summary.isNullOrBlank()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = idleColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (editing) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = valueStyle.copy(color = valueColor),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
                ),
                keyboardActions = KeyboardActions(onDone = { editing = false }),
                modifier = Modifier
                    .padding(start = 12.dp)
                    .widthIn(min = 72.dp, max = 180.dp)
                    .focusRequester(focus)
                    .onFocusChanged { state ->
                        if (state.isFocused) hadFocus = true else if (hadFocus) editing = false
                    },
            )
        } else {
            Text(
                text = (displayValue ?: value).ifBlank { "—" },
                style = valueStyle,
                color = idleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .widthIn(min = 72.dp, max = 180.dp),
            )
        }
    }
}

/** 组内的自定义内容（展开出来的输入框、胶囊动作行）：只提供 16dp 的水平内边距。 */
@Composable
fun SegmentedContentItem(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SegmentedMetrics.RowHorizontal, vertical = 12.dp),
        content = content,
    )
}

/** 三种行共用的排版：56dp 高、左右 16dp、前置 24dp 图标、标题 bodyLarge、副标题 bodyMedium。 */
@Composable
private fun SegmentedRow(
    title: String,
    subtitle: String?,
    icon: ImageVector?,
    enabled: Boolean,
    onClick: (() -> Unit)?,
    leading: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    // trailing 仍是最后一个参数：老调用点的尾随 lambda（SegmentedRow(...) { ... }）继续可用
    val titleColor = if (enabled) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val summaryColor = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                when {
                    onClick == null && onLongClick == null -> Modifier
                    onLongClick == null -> Modifier.clickable(enabled = enabled, onClick = { onClick?.invoke() })
                    else -> Modifier.combinedClickable(
                        enabled = enabled,
                        onClick = { onClick?.invoke() },
                        onLongClick = onLongClick,
                    )
                }
            )
            .heightIn(min = SegmentedMetrics.RowMinHeight)
            .padding(horizontal = SegmentedMetrics.RowHorizontal, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                modifier = Modifier
                    .padding(end = SegmentedMetrics.IconGap)
                    .size(SegmentedMetrics.IconSize),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = titleColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = summaryColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke(this)
    }
}
