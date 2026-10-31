package com.wjljh.andbna.ui.component.miuix

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wjljh.andbna.ui.component.SecretEyeButton
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import kotlin.math.max

@Composable
fun EditText(
    title: String,
    value: String,
    onValueChange: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    summary: String? = null,
    textHint: String = "",
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    // 密钥行：默认掩码 + 行尾眼睛切换（配合 keyboardType = Password 的系统安全键盘）
    password: Boolean = false,
    // 行首图标（与 kit 的 preference 行同一列：卡内 0dp 起，图标 24dp，再 12dp 到文字）
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    insideMargin: PaddingValues = EditTextDefaults.InsideMargin,
    titleColor: BasicComponentColors = EditTextDefaults.titleColor(),
    summaryColor: BasicComponentColors = EditTextDefaults.summaryColor(),
    rightActionColor: BasicComponentColors = EditTextDefaults.rightActionColors(),
    isError: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    var revealed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .clickable(
                indication = null,
                interactionSource = null
            ) {
                if (enabled) {
                    coroutineScope.launch {
                        interactionSource.emit(FocusInteraction.Focus())
                    }
                }
            }
            .heightIn(min = 56.dp)
            .fillMaxWidth()
            .padding(insideMargin),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = titleColor.color(enabled),
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
            Box(Modifier.weight(1f)) {
                EditTextFieldLayout(
                    title = title,
                    summary = summary,
                    value = value,
                    onValueChange = onValueChange,
                    textHint = textHint,
                    enabled = enabled,
                    keyboardOptions = keyboardOptions,
                    titleColor = titleColor,
                    summaryColor = summaryColor,
                    rightActionColor = rightActionColor,
                    isError = isError,
                    focusRequester = focusRequester,
                    interactionSource = interactionSource,
                    visualTransformation = if (password && !revealed) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                )
            }
            if (password) {
                SecretEyeButton(revealed) { revealed = !revealed }
            }
        }
    }
}

/** 上面那一层只负责「行首图标 + 内容列」；真正的 标题/摘要/输入框 三行布局在这里。 */
@Composable
private fun EditTextFieldLayout(
    title: String,
    summary: String?,
    value: String,
    onValueChange: (String) -> Unit,
    textHint: String,
    enabled: Boolean,
    keyboardOptions: KeyboardOptions,
    titleColor: BasicComponentColors,
    summaryColor: BasicComponentColors,
    rightActionColor: BasicComponentColors,
    isError: Boolean,
    focusRequester: FocusRequester,
    interactionSource: MutableInteractionSource,
    visualTransformation: VisualTransformation,
) {
        Layout(
            content = {
                Text(
                    text = title,
                    fontSize = MiuixTheme.textStyles.headline1.fontSize,
                    fontWeight = FontWeight.Medium,
                    color = titleColor.color(enabled)
                )
                summary?.let {
                    Text(
                        text = it,
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = summaryColor.color(enabled)
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = {
                        onValueChange(it)
                    },
                    modifier = Modifier
                        .focusRequester(focusRequester)
                        .semantics {
                            onClick {
                                focusRequester.requestFocus()
                                true
                            }
                        },
                    enabled = enabled,
                    textStyle = MiuixTheme.textStyles.main.copy(
                        textAlign = TextAlign.End,
                        color = if (isError) {
                            Color.Red.copy(alpha = if (isSystemInDarkTheme()) 0.3f else 0.6f)
                        } else {
                            rightActionColor.color(enabled)
                        }
                    ),
                    keyboardOptions = keyboardOptions,
                    visualTransformation = visualTransformation,
                    cursorBrush = SolidColor(colorScheme.primary),
                    interactionSource = interactionSource,
                    decorationBox =
                        @Composable { innerTextField ->
                            Box(
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                Text(
                                    text = if (value.isEmpty()) textHint else "",
                                    color = rightActionColor.color(enabled),
                                    textAlign = TextAlign.End,
                                    softWrap = false,
                                    maxLines = 1
                                )
                                innerTextField()
                            }
                        }
                )
            }
        ) { measurables, constraints ->
            val leftConstraints = constraints.copy(maxWidth = constraints.maxWidth / 2)
            val hasSummary = measurables.size > 2
            val titleText = measurables[0].measure(leftConstraints)
            val summaryText = (if (hasSummary) measurables[1] else null)?.measure(leftConstraints)
            val leftWidth = max(titleText.width, (summaryText?.width ?: 0))
            val leftHeight = titleText.height + (summaryText?.height ?: 0)
            val rightWidth = constraints.maxWidth - leftWidth - 16.dp.roundToPx()
            val rightConstraints = constraints.copy(maxWidth = rightWidth)
            val inputField = (if (hasSummary) measurables[2] else measurables[1]).measure(rightConstraints)
            val totalHeight = max(leftHeight, inputField.height)
            layout(constraints.maxWidth, totalHeight) {
                val titleY = (totalHeight - leftHeight) / 2
                titleText.placeRelative(0, titleY)
                summaryText?.placeRelative(0, titleY + titleText.height)
                inputField.placeRelative(constraints.maxWidth - inputField.width, (totalHeight - inputField.height) / 2)
            }
        }
}

object EditTextDefaults {
    val InsideMargin = PaddingValues(16.dp)

    @Composable
    fun titleColor(
        color: Color = colorScheme.onSurface,
        disabledColor: Color = colorScheme.disabledOnSecondaryVariant
    ): BasicComponentColors {
        return BasicComponentColors(
            color = color,
            disabledColor = disabledColor
        )
    }

    @Composable
    fun summaryColor(
        color: Color = colorScheme.onSurfaceVariantSummary,
        disabledColor: Color = colorScheme.disabledOnSecondaryVariant
    ): BasicComponentColors {
        return BasicComponentColors(
            color = color,
            disabledColor = disabledColor
        )
    }

    @Composable
    fun rightActionColors(
        color: Color = colorScheme.onSurfaceVariantActions,
        disabledColor: Color = colorScheme.disabledOnSecondaryVariant,
    ): BasicComponentColors {
        return BasicComponentColors(
            color = color,
            disabledColor = disabledColor
        )
    }
}

@Immutable
class BasicComponentColors(
    private val color: Color,
    private val disabledColor: Color
) {
    @Stable
    fun color(enabled: Boolean): Color = if (enabled) color else disabledColor
}
