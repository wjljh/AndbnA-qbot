package com.wjljh.andbna.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button as M3Button
import androidx.compose.material3.ButtonDefaults as M3ButtonDefaults
import androidx.compose.material3.CardColors as M3CardColors
import androidx.compose.material3.CardDefaults as M3CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.TextButton as M3TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wjljh.andbna.ui.LocalUiMode
import com.wjljh.andbna.ui.PanelColors
import com.wjljh.andbna.ui.UiMode
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField

/**
 * 记忆页 / 设置页共用的卡片、行与按钮 —— 同样是 **UI 抽象层**：
 * 排版只有一份，外壳与色板按 [LocalUiMode] 在组件内部二选一。
 *
 * 颜色一律走 [PanelColors]；尺寸与字号一律走 [PanelMetrics] / [PanelTokens]。
 */

/** 分组卡：标题 17sp Medium + 可选说明 + 内容。圆角 16dp、内边距 16dp。 */
@Composable
fun PanelSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 排版只有这一份：标题块 + 间距 + 内容，两种模式共用
    val body: @Composable ColumnScope.() -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(PanelTokens.RowSpacing)) {
            Text(
                text = title,
                fontSize = PanelMetrics.CardTitleSize,
                fontWeight = FontWeight.Medium,
                color = PanelColors.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = PanelMetrics.ValueSize,
                    color = PanelColors.onSurfaceVariantSummary,
                )
            }
        }
        Spacer(Modifier.size(PanelMetrics.ContentGap))
        Column(
            verticalArrangement = Arrangement.spacedBy(PanelMetrics.ContentGap),
            content = content,
        )
    }
    if (LocalUiMode.current == UiMode.Material) {
        androidx.compose.material3.Card(
            modifier = modifier.fillMaxWidth().cardOutline(PanelMetrics.CardRadius),
            shape = RoundedCornerShape(PanelMetrics.CardRadius),
            colors = M3CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
            elevation = M3CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(modifier = Modifier.padding(PanelMetrics.CardInside)) { body() }
        }
    } else {
        Card(
            modifier = modifier.fillMaxWidth().cardOutline(PanelMetrics.CardRadius),
            cornerRadius = PanelMetrics.CardRadius,
            insideMargin = PanelMetrics.CardInside,
        ) { body() }
    }
}

/** 读数行：左主文本 17sp、右值 14sp 右对齐，行高 ≥56dp。 */
@Composable
fun PanelReadingRow(
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified,
    mono: Boolean = false,
    showDivider: Boolean = false,
    caption: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PanelMetrics.RowMinHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = PanelMetrics.BodySize,
            color = PanelColors.onSurface,
        )
        Spacer(Modifier.width(PanelMetrics.ButtonGap))
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            fontSize = if (caption) PanelMetrics.HintSize else PanelMetrics.ValueSize,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            color = if (valueColor == Color.Unspecified) PanelColors.onSurfaceVariantSummary else valueColor,
            textAlign = TextAlign.End,
            maxLines = if (caption) 1 else 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (showDivider) {
        HorizontalDivider(
            thickness = PanelMetrics.Divider,
            color = PanelColors.dividerLine,
        )
    }
}

/** 列表行：主文本 + 次文本，右侧放操作按钮。 */
@Composable
fun PanelListRow(
    title: String,
    meta: String,
    enabled: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PanelMetrics.RowMinHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(PanelTokens.RowSpacing),
        ) {
            Text(
                text = title,
                fontSize = PanelMetrics.BodySize,
                color = if (enabled) PanelColors.onSurface else PanelColors.onSurface.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    fontSize = PanelMetrics.ValueSize,
                    color = PanelColors.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
    }
}

/**
 * 带箭头的可点行（二级入口）。
 *
 * [feedback] = false：仍然可点，但**不挂按压反馈**（miuix 不歪、material 不留 ripple）——
 * 群配置列表项与「按群单独设置」入口卡按用户要求就是这一种。
 */
@Composable
fun PanelArrowRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: ImageVector? = null,
    feedback: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = PanelMetrics.RowMinHeight)
            .modeClickable(onClick, feedback = feedback),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = PanelColors.onSurface,
                modifier = Modifier.size(PanelMetrics.RowIconSize),
            )
            Spacer(Modifier.width(PanelMetrics.ContentGap))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = PanelMetrics.BodySize,
                color = PanelColors.onSurface,
            )
            if (summary != null) {
                Text(
                    text = summary,
                    fontSize = PanelMetrics.ValueSize,
                    color = PanelColors.onSurfaceVariantSummary,
                )
            }
        }
        Spacer(Modifier.width(PanelMetrics.ButtonGap))
        Icon(
            imageVector = PanelIcons.ChevronForward,
            contentDescription = null,
            tint = PanelColors.onSurfaceVariantSummary,
            modifier = Modifier.size(PanelMetrics.RowIconSize),
        )
    }
}

/**
 * 主操作（第一档）：软胶囊，高 35dp、圆角 40dp、文字 15sp Medium。
 *
 * miuix：secondaryContainer 底 + onSurface 90% 文字（SukiSU 的软胶囊，不是高饱和主色）；
 * material：FilledTonalButton（同一个 secondaryContainer 语义）。
 * 只有危险操作才用 error 系。
 */
@Composable
fun PanelPrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    if (LocalUiMode.current == UiMode.Material) {
        if (danger) {
            M3Button(
                onClick = onClick,
                modifier = modifier,
                enabled = enabled,
                shape = RoundedCornerShape(PanelTokens.ButtonCornerRadius),
                colors = M3ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                M3ButtonLabel(text)
            }
        } else {
            FilledTonalButton(
                onClick = onClick,
                modifier = modifier,
                enabled = enabled,
                shape = RoundedCornerShape(PanelTokens.ButtonCornerRadius),
            ) {
                M3ButtonLabel(text)
            }
        }
    } else {
        Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            cornerRadius = PanelTokens.ButtonCornerRadius,
            minHeight = PanelTokens.ButtonHeight,
            // kit 默认 insideMargin 的上下留白会把 35dp 撑到 45dp，这里自己钉住真实高度
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
            colors = ButtonDefaults.buttonColors(
                color = if (danger) PanelColors.errorContainer else PanelColors.secondaryContainer,
                contentColor = if (danger) PanelColors.onErrorContainer
                else PanelColors.onSurface.copy(alpha = PanelTokens.ButtonContentAlpha),
            ),
        ) {
            PanelButtonText(text)
        }
    }
}

/**
 * 危险确认（比 [PanelPrimaryButton] 更显眼）：实心 error 底 + onError 文字，
 * 尺寸与其它胶囊一致。只给「两步确认」的第二步用，其余危险操作仍走软底 [danger] 胶囊。
 */
@Composable
fun PanelDangerSolidButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    if (LocalUiMode.current == UiMode.Material) {
        M3Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = RoundedCornerShape(PanelTokens.ButtonCornerRadius),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
            colors = M3ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) {
            M3ButtonLabel(text)
        }
    } else {
        Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            cornerRadius = PanelTokens.ButtonCornerRadius,
            minHeight = PanelTokens.ButtonHeight,
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
            colors = ButtonDefaults.buttonColors(
                color = PanelColors.error,
                contentColor = PanelColors.onError,
            ),
        ) {
            PanelButtonText(text)
        }
    }
}

/**
 * 次操作（第二档）：比主操作更沉一档的软底，尺寸与主操作一致。
 * 破坏性操作（移出白名单等）才走 error 系，由 [danger] 打开。
 */
@Composable
fun PanelSecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    if (LocalUiMode.current == UiMode.Material) {
        M3Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = RoundedCornerShape(PanelTokens.ButtonCornerRadius),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
            colors = if (danger) M3ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) else M3ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            M3ButtonLabel(text)
        }
    } else {
        Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            cornerRadius = PanelTokens.ButtonCornerRadius,
            minHeight = PanelTokens.ButtonHeight,
            // kit 默认 insideMargin 的上下留白会把 35dp 撑到 45dp，这里自己钉住真实高度
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
            // 软底：onSurface 8% 的 scrim —— 放在任何卡片底色上都读得出来，
            // 不用写死某个容器色（写死会在 surfaceContainer 卡上糊成一片）
            colors = if (danger) ButtonDefaults.buttonColors(
                color = PanelColors.errorContainer,
                contentColor = PanelColors.onErrorContainer,
            ) else ButtonDefaults.buttonColors(
                color = PanelColors.onSurface.copy(alpha = 0.08f),
                contentColor = PanelColors.onSurface.copy(alpha = PanelTokens.ButtonContentAlpha),
            ),
        ) {
            PanelButtonText(text)
        }
    }
}

/** 弱操作（第三档）：透明底 + 次级色文字，尺寸同上，禁用时只降透明度不消失。 */
@Composable
fun PanelTertiaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    if (LocalUiMode.current == UiMode.Material) {
        M3TextButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = RoundedCornerShape(PanelTokens.ButtonCornerRadius),
        ) {
            M3ButtonLabel(text)
        }
    } else {
        Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            cornerRadius = PanelTokens.ButtonCornerRadius,
            minHeight = PanelTokens.ButtonHeight,
            // kit 默认 insideMargin 的上下留白会把 35dp 撑到 45dp，这里自己钉住真实高度
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
            colors = ButtonDefaults.buttonColors(
                color = Color.Transparent,
                contentColor = PanelColors.onSurfaceVariantSummary,
                disabledColor = Color.Transparent,
                disabledContentColor = PanelColors.onSurfaceVariantSummary.copy(alpha = 0.5f),
            ),
        ) {
            PanelButtonText(text)
        }
    }
}

/**
 * 「图标 + 文字」胶囊（SukiSU ModuleMiuix.kt:1033-1070 的卸载/删除胶囊）：
 * 高 35dp、圆角 40dp、图标 20dp + 4dp + 15sp 文字，尺寸与三档按钮完全一致，可以同排 8dp 间隔。
 *
 * miuix：kit 的 Button + insideMargin 只留左右 12dp（上下 0 才不会把 35dp 撑成 45dp）；
 * material：M3Button + contentPadding 同口径。
 */
@Composable
fun PanelIconPillButton(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    if (LocalUiMode.current == UiMode.Material) {
        M3Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = RoundedCornerShape(PanelTokens.ButtonCornerRadius),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            // 参照 SukiSU「卸载」胶囊：中性软底 + 常规文字色，危险感交给两步确认里的红色「确认」
            colors = M3ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            M3Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(PanelTokens.ButtonIconSize),
            )
            Spacer(Modifier.width(PanelTokens.ButtonIconGap))
            M3Text(
                text = text,
                fontSize = PanelTokens.ButtonTextSize,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    } else {
        // 同上：中性软底 + 常规文字色（danger 保留参数位，不再染红）
        val content = PanelColors.onSurface.copy(alpha = PanelTokens.ButtonContentAlpha)
        Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            cornerRadius = PanelTokens.ButtonCornerRadius,
            minHeight = PanelTokens.ButtonHeight,
            insideMargin = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            colors = ButtonDefaults.buttonColors(
                color = PanelColors.secondaryContainer,
                contentColor = content,
            ),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(PanelTokens.ButtonIconSize),
            )
            Spacer(Modifier.width(PanelTokens.ButtonIconGap))
            Text(
                text = text,
                fontSize = PanelTokens.ButtonTextSize,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

/** 三档按钮共用的文字：15sp Medium，单行不换行，颜色继承 buttonColors。 */
@Composable
private fun PanelButtonText(text: String) {
    Text(
        text = text,
        fontSize = PanelTokens.ButtonTextSize,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun M3ButtonLabel(text: String) {
    M3Text(
        text = text,
        fontSize = PanelTokens.ButtonTextSize,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 软底按钮：kit 的 [TextButton] / material3 的 TextButton。 */
@Composable
fun PanelSoftButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    if (LocalUiMode.current == UiMode.Material) {
        M3TextButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = if (danger) M3ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ) else M3ButtonDefaults.textButtonColors(),
        ) {
            M3Text(text = text, fontSize = PanelTokens.ButtonTextSize, fontWeight = FontWeight.Medium)
        }
    } else {
        TextButton(
            text = text,
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = if (danger) {
                ButtonDefaults.textButtonColorsPrimary(PanelTokens.ErrorColor)
            } else {
                ButtonDefaults.textButtonColors()
            },
        )
    }
}

/** 提示条：成功用 surfaceContainerHigh，失败用 errorContainer。 */
@Composable
fun PanelNotice(
    text: String,
    isError: Boolean = false,
    mono: Boolean = false,
) {
    if (text.isBlank()) return
    Text(
        text = text,
        fontSize = PanelMetrics.HintSize,
        color = if (isError) PanelColors.onErrorContainer else PanelColors.onSurfaceVariantSummary,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PanelMetrics.CardRadius))
            .background(if (isError) PanelColors.errorContainer else PanelColors.surfaceContainerHigh)
            .padding(PanelMetrics.NoticeInside),
    )
}

/** 空态：图标 + 标题 + 说明，避免「什么都没有」的空洞排版。 */
@Composable
fun PanelEmptyState(
    icon: ImageVector,
    title: String,
    body: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = PanelMetrics.ContentGap),
        verticalArrangement = Arrangement.spacedBy(PanelTokens.RowSpacing),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = PanelColors.onSurfaceVariantSummary,
            modifier = Modifier.size(PanelMetrics.EmptyIconSize),
        )
        Text(
            text = title,
            fontSize = PanelMetrics.BodySize,
            color = PanelColors.onSurface,
        )
        Text(
            text = body,
            fontSize = PanelMetrics.ValueSize,
            color = PanelColors.onSurfaceVariantSummary,
        )
    }
}

/** 运行模式胶囊：SukiSU「Built-in」那个位置，高 20dp、圆角 6dp、底色为状态色 20%。 */
@Composable
fun PanelModePill(
    text: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    if (text.isBlank()) return
    val color = tint.copy(alpha = PanelTokens.ModePillTintAlpha)
    if (LocalUiMode.current == UiMode.Material) {
        androidx.compose.material3.Card(
            modifier = modifier,
            shape = RoundedCornerShape(PanelTokens.ModePillRadius),
            colors = M3CardDefaults.cardColors(containerColor = color),
            elevation = M3CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            M3Text(
                text = text,
                modifier = Modifier.padding(
                    horizontal = PanelTokens.ModePillPaddingH,
                    vertical = PanelTokens.ModePillPaddingV,
                ),
                fontSize = PanelTokens.ModePillTextSize,
                fontWeight = FontWeight.Medium,
                color = tint,
                maxLines = 1,
            )
        }
    } else {
        Card(
            modifier = modifier,
            cornerRadius = PanelTokens.ModePillRadius,
            insideMargin = PaddingValues(
                horizontal = PanelTokens.ModePillPaddingH,
                vertical = PanelTokens.ModePillPaddingV,
            ),
            colors = CardDefaults.defaultColors(color = color),
        ) {
            Text(
                text = text,
                fontSize = PanelTokens.ModePillTextSize,
                fontWeight = FontWeight.Medium,
                color = tint,
                maxLines = 1,
            )
        }
    }
}

/**
 * 双数字卡：高 58dp、数字 30sp/34sp 行高、标签 14sp/18sp 行高、左右各 16dp 内边距。
 *
 * 卡高由外框 [Box] 锁死，内边距写在 **卡内**（16dp 吃住文字，长标签只会在 16dp 处被
 * Ellipsis 截断，不会贴边或越界）；内容块 34+18=52sp 用 [Alignment.CenterStart] 垂直居中。
 * 两行文字都关掉系统的字体额外行距（includeFontPadding），否则 30sp 的字在 34sp 行框里
 * 会偏上 9dp 左右 —— 那正是「数字贴顶、标签贴底」的成因。
 */
@Composable
fun PanelStatCard(
    number: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    // 对齐口径与 SukiSU 一致：不做任何 includeFontPadding=false / 负 offset 的「光学补偿」，
    // 那两样正是之前看起来"整体上移"的原因（文本 ink 被抬起，而卡片仍居中）。
    val body: @Composable ColumnScope.() -> Unit = {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(PanelTokens.StatCardHeight)
                .padding(horizontal = PanelTokens.StatPaddingHorizontal),
            contentAlignment = Alignment.CenterStart,
        ) {
            Column(verticalArrangement = Arrangement.Center) {
                Text(
                    text = number,
                    fontSize = PanelTokens.StatNumberSize,
                    lineHeight = PanelTokens.StatNumberLineHeight,
                    fontWeight = FontWeight.SemiBold,
                    color = PanelColors.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = label,
                    fontSize = PanelTokens.StatLabelSize,
                    lineHeight = PanelTokens.StatLabelLineHeight,
                    color = PanelColors.onSurfaceVariantSummary,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    if (LocalUiMode.current == UiMode.Material) {
        androidx.compose.material3.Card(
            modifier = modifier.cardOutline(PanelTokens.CardRadius),
            shape = RoundedCornerShape(PanelTokens.CardRadius),
            colors = M3CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
            elevation = M3CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) { body() }
    } else {
        Card(
            modifier = modifier.cardOutline(PanelTokens.CardRadius),
            cornerRadius = PanelTokens.CardRadius,
            insideMargin = PaddingValues(0.dp),
        ) { body() }
    }
}

/** 软底胶囊标签（人格 / 称呼 / 筛选）：kit 的 [TextButton]，不自己拼背景。 */
@Composable
fun PanelChip(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    if (LocalUiMode.current == UiMode.Material) {
        M3TextButton(
            onClick = onClick,
            modifier = modifier,
            colors = if (selected) M3ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.primary,
            ) else M3ButtonDefaults.textButtonColors(),
        ) {
            M3Text(text = text, fontSize = PanelTokens.ButtonTextSize)
        }
    } else {
        TextButton(
            text = text,
            onClick = onClick,
            modifier = modifier,
            colors = if (selected) ButtonDefaults.textButtonColorsPrimary()
            else ButtonDefaults.textButtonColors(),
        )
    }
}

/** 输入框：miuix [TextField] / material3 OutlinedTextField，圆角/底色/字号都归主题，页面里不写裸 sp。 */
@Composable
fun PanelTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    password: Boolean = false,
    numeric: Boolean = false,
    minLines: Int = 1,
    // 长串（接口地址）走「不聚焦尾部省略、聚焦横向滚动」那一支，不再被 Input 硬裁
    ellipsis: Boolean = false,
) {
    if (ellipsis) {
        PanelEllipsisField(value, onValueChange, label, modifier, secret = password)
        return
    }
    // 密钥框：默认掩码 + 眼睛切换。keyboardType = Password 会让输入法带上
    // IME_FLAG_NO_PERSONALIZED_LEARNING（系统安全键盘：不进联想、不进词库）。
    var revealed by remember { mutableStateOf(false) }
    val transformation = if (password && !revealed) PasswordVisualTransformation()
    else VisualTransformation.None
    val options = KeyboardOptions(
        keyboardType = when {
            numeric -> KeyboardType.Number
            password -> KeyboardType.Password
            else -> KeyboardType.Text
        },
    )
    val eye: (@Composable () -> Unit)? =
        if (password) ({ SecretEyeButton(revealed) { revealed = !revealed } }) else null
    if (LocalUiMode.current == UiMode.Material) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { M3Text(label) },
            modifier = modifier.fillMaxWidth(),
            singleLine = singleLine,
            visualTransformation = transformation,
            keyboardOptions = options,
            trailingIcon = eye,
        )
    } else {
        TextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = if (singleLine) 1 else Int.MAX_VALUE,
            visualTransformation = transformation,
            keyboardOptions = options,
            trailingIcon = eye,
            textStyle = TextStyle(
                fontSize = PanelTokens.ButtonTextSize,
                fontFamily = if (password) FontFamily.Monospace else FontFamily.Default,
            ),
            modifier = modifier.fillMaxWidth(),
        )
    }
}

/**
 * 长串值输入框（接口地址这类）：不聚焦时**单行 + 尾部省略**，聚焦后变成可横向滚动的单行输入。
 *
 * 以前这里用 [TextField]：值超长时 BasicTextField 直接按宽度裁掉，末尾既没有省略号也看不全，
 * 截图里就是「https://api.deepseek.com/…」被硬切一刀。
 * 做法：底层始终是 BasicTextField（焦点 / 光标 / 选区 / 滚动都归它），不聚焦时把它的文字画成透明，
 * 上面盖一层同样样式、同样单行的 Text（maxLines = 1 + Ellipsis）—— 于是没聚焦时是省略号，
 * 一聚焦就露出真正的输入框并跟着光标横向滚动。
 */
@Composable
fun PanelEllipsisField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String = "",
    modifier: Modifier = Modifier,
    mono: Boolean = true,
    // 密钥框：默认掩码 + 行尾眼睛；掩码后的点同样单行 + 尾部省略（不再换行占三行）
    secret: Boolean = false,
    // 框上方的小标签（miuix preference 行没有标题位时用它；BotFormField 自己有标签，传空）
    label: String = "",
) {
    var focused by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(PanelTokens.CardRadius)
    val style = TextStyle(
        fontSize = PanelTokens.ButtonTextSize,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
    )
    val masked = secret && !revealed
    // 一个字符一颗点：跟明文同长度，超长时同样尾部省略
    val shown = if (masked) "•".repeat(value.length) else value
    Column(modifier.fillMaxWidth()) {
        if (label.isNotBlank()) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight(550),
                color = PanelColors.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .background(PanelColors.surfaceContainer, shape)
                .border(1.dp, if (focused) PanelColors.primary else PanelColors.dividerLine, shape)
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            Box(Modifier.weight(1f)) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = style.copy(color = if (focused) PanelColors.onSurface else Color.Transparent),
                    visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
                    cursorBrush = SolidColor(PanelColors.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!focused) {
                    Text(
                        text = shown.ifEmpty { hint },
                        style = style,
                        color = if (shown.isEmpty()) PanelColors.onSurfaceVariantSummary else PanelColors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (secret) SecretEyeButton(revealed) { revealed = !revealed }
        }
    }
}
