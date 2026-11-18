package com.wjljh.andbna.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.wjljh.andbna.local.BackupIO
import com.wjljh.andbna.ui.component.AppToast
import com.wjljh.andbna.ui.component.PanelMetrics
import com.wjljh.andbna.ui.component.PanelTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 设置页「备份与恢复」的小面板：导出 / 导入配置（一键备份与换机迁移）。
 *
 * 两级都在这个面板里完成，不再开第二个弹窗：
 *  ① 说明 + 导出 / 导入两个按钮；
 *  ② 选到一个能用的备份文件后，就地选「合并」还是「替换」（替换再就地二次确认）。
 *
 * 文件位置全交给系统的文件选择器（导出 CreateDocument / 导入 OpenDocument），
 * 自己不写 /sdcard，也不需要任何存储权限；真正的读写与校验在 [BackupIO] 里。
 */
@Composable
fun BackupPanel(show: Boolean, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var plan by remember { mutableStateOf<BackupIO.Plan?>(null) }
    var summary by remember { mutableStateOf("") }
    var askReplace by remember { mutableStateOf(false) }

    fun apply(replace: Boolean) {
        val p = plan ?: return
        scope.launch {
            busy = true
            val r = withContext(Dispatchers.IO) { BackupIO.applyPlan(ctx, p, replace) }
            busy = false
            plan = null
            summary = ""
            askReplace = false
            // 结果交给全局提示条：面板先关，条浮在底栏上方，看得见
            msg = ""
            onDismiss()
            AppToast.show(r.text)
        }
    }

    // 导出：系统文件选择器里挑位置（不自己写 /sdcard）
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) {
            msg = "取消了，没导出"
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            busy = true
            val r = withContext(Dispatchers.IO) { BackupIO.exportTo(ctx, uri) }
            busy = false
            msg = ""
            onDismiss()
            AppToast.show(r.text)
        }
    }

    // 导入：只读选择器给的那一份，先校验、再让用户选合并 / 替换
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            msg = "取消了，没选文件"
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            busy = true
            val c = withContext(Dispatchers.IO) { BackupIO.check(ctx, uri) }
            busy = false
            if (c.ok && c.plan != null) {
                plan = c.plan
                summary = c.summary
                askReplace = false
                msg = ""
            } else {
                msg = ""
                onDismiss()
                AppToast.show("没导进来：" + c.error + "，现有数据没动")
            }
        }
    }

    WindowDialog(
        show = show,
        title = "备份与恢复",
        onDismissRequest = onDismiss,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(PanelTokens.RowSpacing),
            ) {
                Text(
                    text = "导出一个 JSON，换机器时拿它搬回来。",
                    style = MiuixTheme.textStyles.body2,
                    color = colorScheme.onBackground,
                )
                Text(
                    text = "AppSecret 和 API Key 不进备份，存成 " + BackupIO.PLACEHOLDER +
                        "，恢复后自己重填。",
                    style = MiuixTheme.textStyles.body2,
                    color = PanelTokens.ErrorColor,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(PanelMetrics.ButtonGap),
                ) {
                    TextButton(
                        text = "导出配置",
                        onClick = {
                            if (!busy) {
                                msg = "挑个位置存…"
                                exportLauncher.launch(
                                    "andbna-backup-" + android.text.format.DateFormat.format(
                                        "yyyyMMdd-HHmm", System.currentTimeMillis()
                                    ) + ".json"
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = "导入配置",
                        onClick = {
                            if (!busy) {
                                msg = "挑一个备份 JSON…"
                                // OpenDocument 的入参是 mime 过滤：这里认 json 与 text/*
                                importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (busy) {
                    Text(
                        text = "在弄了…",
                        style = MiuixTheme.textStyles.body2,
                        color = colorScheme.onBackground,
                    )
                }
                if (summary.isNotBlank() && plan != null) {
                    Text(
                        text = "文件里读到 " + summary + "，挑个导入方式：",
                        style = MiuixTheme.textStyles.body2,
                        color = colorScheme.onBackground,
                    )
                }
                if (plan != null && !askReplace) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(PanelMetrics.ButtonGap),
                    ) {
                        TextButton(
                            text = "合并导入",
                            onClick = { if (!busy) apply(false) },
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            text = "替换导入",
                            onClick = { if (!busy) askReplace = true },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    TextButton(
                        text = "先不导入",
                        onClick = {
                            if (!busy) {
                                plan = null
                                summary = ""
                                msg = ""
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (askReplace) {
                    Text(
                        text = "先清空本机 bot，再写进备份的 " +
                            (plan?.bots?.size ?: 0) + " 个。密钥留着，确定？",
                        style = MiuixTheme.textStyles.body2,
                        color = PanelTokens.ErrorColor,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(PanelMetrics.ButtonGap),
                    ) {
                        TextButton(
                            text = "取消",
                            onClick = { askReplace = false },
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            text = "确定替换",
                            onClick = { if (!busy) apply(true) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (msg.isNotBlank()) {
                    Text(
                        text = msg,
                        style = MiuixTheme.textStyles.body2,
                        color = colorScheme.onBackground,
                    )
                }
                TextButton(
                    text = "关闭",
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}
