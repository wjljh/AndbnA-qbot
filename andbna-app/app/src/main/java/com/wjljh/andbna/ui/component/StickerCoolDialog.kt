package com.wjljh.andbna.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.wjljh.andbna.ui.component.miuix.EditText
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 「同一张图的冷却 → 自定义…」面板：一格分钟数 + 保存 / 取消。
 *
 * 设置页（全局那档）与 Bot 的表情库页（本 bot 覆盖）共用这一份：界面按分钟填，
 * 落盘也是分钟（llm.json 的 sticker_cooldown_min / sticker_conf.json 的 cooldown_min），
 * 范围 1~10080（7 天）。与「回复延迟」同一个 WindowDialog 宿主，不引 material3。
 */
@Composable
internal fun StickerCoolDialog(
    show: Boolean,
    initial: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // 每次打开都按当前落盘值重新填（用 show 做 key，关掉再开不会留着上次没保存的草稿）
    var text by remember(show) { mutableStateOf(initial.toString()) }
    val numberKeys = KeyboardOptions(keyboardType = KeyboardType.Number)
    WindowDialog(
        show = show,
        title = "自定义冷却（分钟）",
        onDismissRequest = onDismiss,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(PanelTokens.RowSpacing),
            ) {
                Text(
                    text = "同一张图多久内不重复发。填 1~10080 分钟。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onBackground,
                )
                EditText(
                    title = "分钟",
                    value = text,
                    keyboardOptions = numberKeys,
                    onValueChange = { v -> text = v.filter { c -> c.isDigit() }.take(5) },
                )
                TextButton(
                    text = "保存",
                    onClick = { onConfirm(text.toIntOrNull() ?: 0) },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}
