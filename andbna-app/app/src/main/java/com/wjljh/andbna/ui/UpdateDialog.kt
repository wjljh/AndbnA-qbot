package com.wjljh.andbna.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wjljh.andbna.local.Updater
import com.wjljh.andbna.ui.component.MarkdownText
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 更新详情对话框：标题写清「发现新版本 X（当前 Y）」，正文是 release body 的 Markdown 渲染
 * （原生，见 [MarkdownText]；body 为空就退化成一行说明）。
 *
 * 三个动作：更新（进前台服务下载）/ 忽略此版本（同一版本不再提示）/ 取消。
 * 更新源地址不在这页出现：默认走仓库 docs/latest.json（失败回退 GitHub API），要换只走隐藏 prefs 键 update_api。
 */
@Composable
fun UpdateDialog(
    current: String,
    newer: Updater.Release,
    onUpdate: () -> Unit,
    onIgnore: () -> Unit,
    onDismiss: () -> Unit,
) {
    WindowDialog(
        show = true,
        title = "发现新版本 " + newer.versionName + "（当前 " + current + "）",
        onDismissRequest = onDismiss,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val body = newer.body.trim()
                if (body.isEmpty()) {
                    Text(
                        text = "（该版本未提供更新说明）",
                        fontSize = 14.sp,
                        color = PanelColors.onSurfaceVariantSummary,
                    )
                } else {
                    MarkdownText(markdown = body, modifier = Modifier.fillMaxWidth())
                }

                Text(
                    text = "忽略此版本",
                    fontSize = 13.sp,
                    color = PanelColors.onSurfaceVariantSummary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.Start)
                        .padding(vertical = 4.dp)
                        .clickable(onClick = onIgnore),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TextButton(
                        text = "取消",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = "更新",
                        onClick = onUpdate,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
    )
}
