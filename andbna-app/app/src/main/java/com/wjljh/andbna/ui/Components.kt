package com.wjljh.andbna.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Ok
import com.wjljh.andbna.ui.component.PanelTokens
import com.wjljh.andbna.ui.component.cardOutline
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 尺寸令牌。取值全部来自 spec（sukisu-ui-spec.md §6），改这里等于全站换皮。
 *
 * 安全区（状态栏 / 挖孔 / 刘海）已经交给 Miuix 的 TopAppBar 与 Scaffold 处理，
 * 这里只算和屏幕尺寸有关的东西：字号、页边距、卡片圆角、宽屏行宽。
 */
data class Metrics(
    val compact: Boolean,
    val wide: Boolean,
    // spec §2.1 / §6 #12：页左右 12dp（原 16/12/24 三档屏宽浮动，SukiSU 只有 12dp）
    val pageGap: Dp,
    // spec §2.1 / §6 #11：卡间距 12dp
    val cardGap: Dp,
    val innerGap: Dp,
    // spec §6 #1：全站卡片圆角 16dp（原 22/26dp），hero 与分组卡同一个值
    val radiusCard: Dp,
    // spec §6 #5：hero 主标题 22sp，不再按屏宽浮动
    val titleSize: TextUnit,
    val contentMax: Dp,
    // 组卡内沿：SukiSU 一律 Card { Column(padding(16.dp)) }（spec §2.3 / §2.4 / §6 #3）
    val cardInside: Dp,
    // hero 卡高。【推算】spec §6 #2：SukiSU 无显式高度，由 110dp 大图标撑起，
    // 源码未实测过（spec §7.4 第 1 条），照规格书的推算值用。
    val heroHeight: Dp,
    // spec §6 #5：hero 里的版本行 15sp
    val heroValueSize: TextUnit,
    // spec §6 #7：信息行图标 24dp
    val rowIcon: Dp,
    // spec §6 #8：无分隔线，行下 24dp（末行 0dp）
    val rowGap: Dp,
    // spec §6 #9：行标题 17sp / 值 14sp
    val rowTitleSize: TextUnit,
    val rowValueSize: TextUnit,
    // spec §6 #6：真徽标（StatusTag）圆角 6dp / 9sp。全站暂无该用例，
    // 只入令牌备用 —— spec 明确不许把这套值套在 Pill 上（语义不同）。
    val tagRadius: Dp,
    val tagFontSize: TextUnit,
)

@Composable
fun rememberMetrics(): Metrics {
    val cfg = LocalConfiguration.current
    val w = cfg.screenWidthDp
    val h = cfg.screenHeightDp
    val fontScale = cfg.fontScale
    return remember(w, h, fontScale) {
        val compact = w < 380 || h < 620 || fontScale > 1.15f
        val wide = w >= 600
        Metrics(
            compact = compact,
            wide = wide,
            pageGap = 12.dp,
            cardGap = 12.dp,
            innerGap = if (compact) 8.dp else 10.dp,
            radiusCard = 16.dp,
            titleSize = 22.sp,
            contentMax = if (wide) 720.dp else Dp.Unspecified,
            cardInside = 16.dp,
            heroHeight = 110.dp,
            heroValueSize = 15.sp,
            rowIcon = 24.dp,
            rowGap = 24.dp,
            rowTitleSize = 17.sp,
            rowValueSize = 14.sp,
            tagRadius = 6.dp,
            tagFontSize = 9.sp,
        )
    }
}

/**
 * 页面外层：统一页边距 + 宽屏限制行宽并居中。
 *
 * 底部留白**只由 Scaffold 的 innerPadding 给一次**（spec §5.2 / §6 #13）：
 * miuix 的 Scaffold 把 bottomBar 实测高度写进内容 padding 的 bottom，
 * App.kt 的 `.padding(pad)` 已经吃过这一口，这里再加就是双重留白。
 */
@Composable
fun PageColumn(
    metrics: Metrics,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .then(
                    if (metrics.contentMax == Dp.Unspecified) Modifier
                    else Modifier.widthIn(max = metrics.contentMax)
                )
                .fillMaxWidth()
                .padding(horizontal = metrics.pageGap)
                .padding(top = metrics.cardGap),
            verticalArrangement = Arrangement.spacedBy(metrics.cardGap),
            content = content,
        )
    }
}

/** preference 行的内边距：左右保持 16dp（图标对齐），上下压到 8dp 让行更紧凑 */
val prefInside = PaddingValues(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)

/** 卡片：用 Miuix 的 Card，圆角和色板都由主题给 */
@Composable
fun SectionCard(
    metrics: Metrics,
    title: String? = null,
    hint: String? = null,
    // 子元素之间的间距。信息行卡片要传 0：行自带 24dp 下间距（spec §6 #8），
    // 两者一起加行距会翻倍。
    gap: Dp = Dp.Unspecified,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        cornerRadius = metrics.radiusCard,
        // spec §2.3 / §2.4：SukiSU 的组卡就是 Card { Column(padding(16.dp)) }
        insideMargin = PaddingValues(metrics.cardInside),
        modifier = Modifier.fillMaxWidth().cardOutline(metrics.radiusCard),
    ) {
        if (title != null) {
            Text(
                title,
                // spec 90 §2.2：组内标题 17sp / Medium / onBackground
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = PanelColors.onBackground,
            )
        }
        if (hint != null) {
            Text(
                hint,
                fontSize = 14.sp,
                color = PanelColors.onSurfaceVariantSummary,
            )
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(
                if (gap == Dp.Unspecified) metrics.innerGap else gap
            ),
        ) {
            content()
        }
    }
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** 行：标签 17sp Medium onBackground，值靠右 14sp onSurfaceVariantActions（spec 90 §8） */
@Composable
fun KeyValue(
    label: String,
    value: String,
    dot: Color? = null,
    mono: Boolean = false,
    valueColor: Color? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
    ) {
        if (dot != null) {
            Dot(dot)
            Spacer(Modifier.size(8.dp))
        }
        Text(
            label,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            color = PanelColors.onBackground,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            value,
            fontSize = 14.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            color = valueColor ?: PanelColors.onSurfaceVariantActions,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 可点的小胶囊（角色、标签、筛选） */
@Composable
fun Pill(
    text: String,
    selected: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    // 用库的 TextButton：尺寸、圆角、选中色全归 Miuix 管，不再自己拼
    TextButton(
        text = text,
        onClick = onClick,
        colors = if (selected) ButtonDefaults.textButtonColorsPrimary()
        else ButtonDefaults.textButtonColors(),
    )
}

/**
 * 两段式确认按钮。
 *
 * 为什么不用对话框：模块 WebUI 里 window.confirm 被 WebView 直接拦掉，点删除毫无反应。
 * 这里点第一次只"上膛"（按钮变文案），6 秒内再点才真执行，误触也不会出事。
 *
 * 样式直接复用 Pill：危险操作用错误色（白字），普通操作走中性底。
 * 之前用 Miuix 的 textButtonColorsPrimary，结果「卸载」是主色蓝的，看起来像推荐操作。
 */
@Composable
fun ConfirmButton(
    text: String,
    confirmText: String = "再点一次确认",
    danger: Boolean = true,
    enabled: Boolean = true,
    onConfirm: () -> Unit,
) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(6000)
            armed = false
        }
    }
    // 上膛前用中性胶囊（SukiSU 的胶囊不用红），上膛后才用主色强调。
    // 尺寸与圆角交给 kit 的 Button：35dp 高、40dp 圆角，和 PanelSecondaryButton 同一口径。
    val bg = when {
        !enabled -> PanelColors.secondaryContainer.copy(alpha = 0.5f)
        armed -> PanelColors.primary
        else -> PanelColors.secondaryContainer
    }
    val fg = when {
        !enabled -> PanelColors.disabledOnSecondaryVariant
        armed -> PanelColors.onPrimary
        else -> PanelColors.onSurface.copy(alpha = 0.9f)
    }
    Button(
        onClick = {
            if (!enabled) return@Button
            if (armed) {
                armed = false
                onConfirm()
            } else {
                armed = true
            }
        },
        enabled = enabled,
        cornerRadius = PanelTokens.ButtonCornerRadius,
        minHeight = PanelTokens.ButtonHeight,
        colors = ButtonDefaults.buttonColors(color = bg, contentColor = fg),
    ) {
        Text(
            text = if (armed) confirmText else text,
            fontSize = PanelTokens.ButtonTextSize,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** 普通操作按钮：同样用 secondaryContainer 胶囊（spec 90 §8） */
@Composable
fun ActionChip(text: String, enabled: Boolean = true, tone: Color? = null, onClick: () -> Unit) {
    val bg = when {
        !enabled -> PanelColors.secondaryContainer.copy(alpha = 0.5f)
        tone != null -> tone
        else -> PanelColors.secondaryContainer
    }
    val fg = when {
        !enabled -> PanelColors.disabledOnSecondaryVariant
        tone != null -> Color.White
        else -> PanelColors.onSurface.copy(alpha = 0.9f)
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        cornerRadius = PanelTokens.ButtonCornerRadius,
        minHeight = PanelTokens.ButtonHeight,
        colors = ButtonDefaults.buttonColors(color = bg, contentColor = fg),
    ) {
        Text(
            text = text,
            fontSize = PanelTokens.ButtonTextSize,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
fun LineField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    singleLine: Boolean = true,
    password: Boolean = false,
    numeric: Boolean = false,
    minLines: Int = 1,
    modifier: Modifier = Modifier,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = if (singleLine) 1 else Int.MAX_VALUE,
        visualTransformation =
            if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text
        ),
        textStyle = TextStyle(
            fontSize = 14.sp,
            fontFamily = if (password) FontFamily.Monospace else FontFamily.Default,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/** 提示条：对齐 WarningCard（Notice = tertiaryContainer，Error = errorContainer + #F72727） */
@Composable
fun Note(text: String, ok: Boolean = true, mono: Boolean = false) {
    if (text.isBlank()) return
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val bg = if (ok) PanelColors.tertiaryContainer
    else if (dark) Color(0xFF310808) else Color(0xFFF8E2E2)
    // spec 11 §4.5：这个文字色的 when 只判是否动态色，暗/亮同值，不要自己加暗色分支
    val fg = if (ok) PanelColors.onTertiaryContainer else Color(0xFFF72727)
    Card(
        modifier = Modifier.fillMaxWidth().cardOutline(),
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(16.dp),
        colors = CardDefaults.defaultColors(color = bg),
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            color = fg,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

/**
 * SukiSU 设置页那种行：前置图标 + 标题 17sp Medium + 副标题 14sp + 行尾控件。
 * 行尾控件可以是 Switch / SpinnerValue（值 + 上下双箭头）/ 单箭头。
 */
@Composable
fun SuperRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
            )
            .padding(vertical = 10.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = PanelColors.onSurfaceVariantSummary,
            modifier = Modifier.size(24.dp),
        )
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(
                title,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = PanelColors.onBackground,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    fontSize = 14.sp,
                    color = PanelColors.onSurfaceVariantSummary,
                )
            }
        }
        trailing?.invoke()
    }
}

/** 行尾的「当前值 + 上下双箭头」，对应 SukiSU 的 spinner 控件外观 */
@Composable
fun SpinnerValue(value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            value,
            fontSize = 14.sp,
            color = PanelColors.onSurfaceVariantActions,
        )
        Column(
            modifier = Modifier.padding(start = 6.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "\u2303",
                fontSize = 11.sp,
                lineHeight = 7.sp,
                color = PanelColors.onSurfaceVariantActions,
            )
            Text(
                "\u2304",
                fontSize = 11.sp,
                lineHeight = 7.sp,
                color = PanelColors.onSurfaceVariantActions,
            )
        }
    }
}

/** 行尾的单个细箭头 */
@Composable
fun ChevronValue() {
    Text(
        "\u203a",
        fontSize = 18.sp,
        color = PanelColors.onSurfaceVariantActions,
    )
}

/**
 * 点开弹输入框的行，对应 SukiSU 的 SuperEditArrow。
 *
 * 库里有 ArrowPreference，但没有"带输入"的版本，所以这里用
 * ArrowPreference + OverlayDialog + TextField 组合出来（不是自绘样式）。
 */
@Composable
fun EditArrowPreference(
    title: String,
    value: String,
    summary: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    numeric: Boolean = false,
    enabled: Boolean = true,
    insideMargin: PaddingValues = prefInside,
    onCommit: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var draft by remember(value) { mutableStateOf(value) }

    ArrowPreference(
        title = title,
        summary = summary ?: value,
        insideMargin = insideMargin,
        startAction = icon?.let { v ->
            { RowIcon(v, PanelColors.onSurfaceVariantSummary) }
        },
        onClick = { if (enabled) { draft = value; open = true } },
    )

    if (open) {
        top.yukonga.miuix.kmp.overlay.OverlayDialog(
            show = true,
            title = title,
            onDismissRequest = { open = false },
        ) {
            Column(Modifier.fillMaxWidth()) {
                TextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = title,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                // 保存：主色整条按钮
                TextButton(
                    text = "保存",
                    onClick = {
                        onCommit(draft.trim())
                        open = false
                    },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                // 取消：灰底整条按钮（对齐 SukiSU 弹窗底部那种）
                TextButton(
                    text = "取消",
                    onClick = { open = false },
                    colors = ButtonDefaults.textButtonColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

enum class HeroState { WORKING, IDLE, ERROR }

/**
 * 主页 hero 卡。
 *
 * 严格按 spec 11 §2 / spec 10 §4 的三态做：
 *  · WORKING：绿底整卡染色，22sp 主标题 + 15sp 副标题 + 左下 16sp 模式文本
 *             + 右下 110dp 图标（offset 27/31，超出卡片的部分天然被 Card 图层裁掉）
 *            卡高**不写死**，由 110dp 图标撑开
 *  · IDLE / ERROR：不染色，退回 surfaceContainer；17sp Medium 标题 + 14sp 副标题
 *            + 左侧 24dp 图标（右距 6dp）
 *  · **hero 永远不染红**——错误由调用方在卡片上方放一张红色横幅（WarningCard）
 */
@Composable
fun HeroCard(
    state: HeroState,
    title: String,
    subtitle: String = "",
    modeText: String = "",
    onClick: (() -> Unit)? = null,
    /** 弹层必须写在 Card 内容里，锚点才是卡片（spec 15 §2.1 末尾） */
    popup: @Composable (() -> Unit)? = null,
) {
    if (state == HeroState.WORKING) {
        val dark = androidx.compose.foundation.isSystemInDarkTheme()
        // spec 11 §6：非 monet 下亮 #DFFAE4 / 暗 #1A3825
        val bg = if (dark) Color(0xFF1A3825) else Color(0xFFDFFAE4)
        val content = PanelColors.onSurfaceContainer
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onClick != null) {
                Card(
                    colors = CardDefaults.defaultColors(color = bg),
                    onClick = onClick,
                    showIndication = true,
                    // spec 15 §2.4：Tilt ±8°、spring(0.6, 400)、cameraDistance 12×density
                    pressFeedbackType = PressFeedbackType.Tilt,
                    modifier = Modifier.fillMaxWidth().cardOutline(),
                ) {
                    HeroWorkingBody(title, subtitle, modeText, content, popup)
                }
            } else {
                Card(
                    colors = CardDefaults.defaultColors(color = bg),
                    modifier = Modifier.fillMaxWidth().cardOutline(),
                ) {
                    HeroWorkingBody(title, subtitle, modeText, content, popup)
                }
            }
        }
    } else {
        Card(modifier = Modifier.fillMaxWidth().cardOutline()) {
            Row(
                Modifier.fillMaxSize().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    MiuixIcons.Notes,
                    contentDescription = null,
                    tint = PanelColors.onBackground,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(6.dp))
                Column {
                    Text(
                        title,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                        color = PanelColors.onBackground,
                    )
                    if (subtitle.isNotBlank()) {
                        Text(
                            subtitle,
                            fontSize = 14.sp,
                            color = PanelColors.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroWorkingBody(
    title: String,
    subtitle: String,
    modeText: String,
    content: Color,
    popup: @Composable (() -> Unit)?,
) {
    Box {
        // 绿勾放右侧、垂直居中，只往右溢出一点被卡片裁掉（SukiSU 那个位置）
        // 水印勾：加大到 150dp，往右多溢出一些，让卡片切掉一部分（Kit 那个分量）
        Box(
            Modifier.fillMaxSize().offset(x = 40.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Icon(
                MiuixIcons.Ok,
                contentDescription = null,
                tint = Color(0xFF36D167),
                modifier = Modifier.size(150.dp),
            )
        }
        if (modeText.isNotBlank()) {
            Box(
                Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.BottomStart,
            ) {
                Text(
                    modeText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = content,
                )
            }
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                title,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = content,
            )
            Spacer(Modifier.height(1.dp))
            if (subtitle.isNotBlank()) {
                Text(subtitle, fontSize = 15.sp, color = content)
            }
        }
        popup?.invoke()
    }
}

@Composable
fun Gap(h: Dp = 4.dp) = Spacer(Modifier.height(h))

/** 行首图标，统一 22dp（preference 行的 startAction 用它）。 */
@Composable
fun RowIcon(icon: ImageVector, tint: Color) {
    Icon(
        icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.padding(end = 10.dp).size(22.dp),
    )
}

