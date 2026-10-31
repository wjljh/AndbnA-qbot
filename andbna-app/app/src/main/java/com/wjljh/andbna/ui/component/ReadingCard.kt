package com.wjljh.andbna.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 读数卡片：标题 + 若干「标签 / 值」行 + 可选错误行 + 可选操作按钮。
 *
 * 纯 kit 组件：miuix [Card] / [Text] / [TextButton]，字号与行高全部取自
 * [MiuixTheme.textStyles]，不写裸 sp；内边距复用 [PanelTokens.CardInside]。
 */
@Composable
fun ReadingCard(
    title: String,
    rows: List<Pair<String, String>>,
    errorKind: String = "",
    errorMessage: String = "",
    actionLabel: String? = null,
    actionEnabled: Boolean = true,
    onAction: (() -> Unit)? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(PanelTokens.CardInside),
            verticalArrangement = Arrangement.spacedBy(PanelTokens.RowSpacing),
        ) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.headline1,
                color = colorScheme.onBackground,
            )
            if (errorMessage.isNotBlank()) {
                Text(
                    text = "[${errorKind}] ${errorMessage}",
                    style = MiuixTheme.textStyles.footnote2,
                    color = PanelTokens.ErrorColor,
                )
            }
            rows.forEach { (label, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = label,
                        style = MiuixTheme.textStyles.body2,
                        color = colorScheme.onBackground.copy(alpha = 0.7f),
                    )
                    Text(
                        text = value,
                        style = MiuixTheme.textStyles.body2,
                        color = colorScheme.onBackground,
                    )
                }
            }
            if (actionLabel != null && onAction != null) {
                TextButton(
                    text = actionLabel,
                    onClick = onAction,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = actionEnabled,
                )
            }
        }
    }
}

/** 占位卡片：告诉后续代理这里缺什么。 */
@Composable
fun PlaceholderCard(title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(PanelTokens.CardInside),
            verticalArrangement = Arrangement.spacedBy(PanelTokens.RowSpacing),
        ) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.subtitle,
                color = colorScheme.onBackground,
            )
            Text(
                text = body,
                style = MiuixTheme.textStyles.footnote2,
                color = colorScheme.onBackground.copy(alpha = 0.7f),
            )
        }
    }
}
