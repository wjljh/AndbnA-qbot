package com.wjljh.andbna.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wjljh.andbna.local.BotHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 日志二级页。
 *
 * 布局照 SukiSU「发送日志」那个弹窗来的：上面两行带图标的动作（保存 / 分享），
 * 底部一条整宽的返回按钮，中间是日志正文。
 */
@Composable
fun LogScreen(metrics: Metrics, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(tick) {
        text = withContext(Dispatchers.IO) { BotHost.logTail(400) }
    }

    val iconTint = PanelColors.onSurfaceVariantSummary

    // 导出：走系统文件选择器，不申请存储权限
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            msg = try {
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(text.toByteArray(Charsets.UTF_8))
                    }
                }
                "已导出（${text.length} 字）"
            } catch (e: Exception) {
                "导出失败：${e.message}"
            }
        }
    }

    Scaffold(
        containerColor = PanelColors.surface,
        topBar = {
            SmallTopAppBar(
                title = "日志",
                subtitle = "最近 400 行",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text(
                            "\u2039",
                            fontSize = 30.sp,
                            color = PanelColors.onBackground,
                        )
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()),
        ) {
            PageColumn(metrics) {
                if (msg.isNotBlank()) Note(msg)

                SectionCard(metrics, gap = 2.dp) {
                    ArrowPreference(
                        insideMargin = prefInside,
                        title = "保存日志",
                        summary = "存到系统里自己挑的位置",
                        startAction = { RowIcon(Icons.Rounded.CheckCircle, iconTint) },
                        onClick = {
                            exportLauncher.launch("andbna_log_${System.currentTimeMillis()}.txt")
                        },
                    )
                    ArrowPreference(
                        insideMargin = prefInside,
                        title = "分享日志",
                        summary = "扔给别的 App",
                        startAction = { RowIcon(Icons.Rounded.Share, iconTint) },
                        onClick = {
                            val it = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text.ifBlank { "（日志是空的）" })
                            }
                            runCatching { ctx.startActivity(Intent.createChooser(it, "分享日志")) }
                        },
                    )
                    ArrowPreference(
                        insideMargin = prefInside,
                        title = "刷新",
                        summary = "重新读最新日志",
                        startAction = { RowIcon(Icons.Rounded.Refresh, iconTint) },
                        onClick = { tick++ },
                    )
                }

                SectionCard(metrics, title = "内容") {
                    if (text.isBlank()) {
                        Text(
                            "日志是空的",
                            fontSize = 14.sp,
                            color = PanelColors.onSurfaceVariantSummary,
                        )
                    } else {
                        Text(
                            text,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = PanelColors.onSurfaceVariantSummary,
                        )
                    }
                }

                // 底部整宽按钮，和 SukiSU 弹窗里的「取消」一个形态
                TextButton(
                    text = "返回",
                    onClick = onBack,
                    colors = ButtonDefaults.textButtonColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
