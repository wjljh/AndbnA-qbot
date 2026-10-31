package com.wjljh.andbna.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 危险操作的二次确认框。
 *
 * 只用 kit 的 [WindowDialog]（与 kit 的 ConfirmDialogMiuix 同一个宿主），
 * 不引 material3 的 AlertDialog；确认按钮走 [ButtonDefaults]，[danger] 时用
 * [PanelTokens.ErrorColor] 而不是主色。
 */
@Composable
fun PanelConfirmDialog(
    show: Boolean,
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = true,
) {
    WindowDialog(
        show = show,
        title = title,
        onDismissRequest = onDismiss,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(PanelTokens.RowSpacing),
            ) {
                Text(
                    text = body,
                    style = MiuixTheme.textStyles.body2,
                    color = colorScheme.onBackground,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(PanelMetrics.ButtonGap),
                ) {
                    TextButton(
                        text = "取消",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = confirmLabel,
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        colors = if (danger) {
                            ButtonDefaults.textButtonColorsPrimary(PanelTokens.ErrorColor)
                        } else {
                            ButtonDefaults.textButtonColorsPrimary()
                        },
                    )
                }
            }
        },
    )
}
