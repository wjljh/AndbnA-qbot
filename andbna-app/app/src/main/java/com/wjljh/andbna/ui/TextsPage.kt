package com.wjljh.andbna.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wjljh.andbna.local.PersonaAssets
import com.wjljh.andbna.ui.component.PanelConfirmDialog
import com.wjljh.andbna.ui.component.PanelPrimaryButton
import com.wjljh.andbna.ui.component.PanelScreen
import com.wjljh.andbna.ui.component.PanelSectionCard
import com.wjljh.andbna.ui.component.PanelTokens
import com.wjljh.andbna.ui.component.SecondaryTopBar
import com.wjljh.andbna.ui.component.rememberPersonaImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete

/**
 * 人格 / 文案（Route.Texts）：**导入 + 管理**，只剩这两块。
 *
 * 内置预设仍打包在 assets/personas 里（bot 编辑页的人格下拉照常能选），这里不展示；
 * 管理面板只列 [PersonaAssets.customNames]（files/personas 下用户导入的档），
 * 每行显示导入时的**原文件名**（侧车 .orig），行尾图标删除、删前确认。
 *
 * 三张全局文案卡已整体下线，全局文案不再由本页维护：相关存储改成一 bot 一份，
 * 挂在「编辑 bot」页里（见 ui/BotEditScreen.kt）。本页不再读写 phrases.json / greetings.json。
 *
 * 顶栏与关于页 / 主题页一致：细线 [MiuixIcons.Back] + 标题。
 */
@Composable
fun TextsPage(bottomInset: Dp = 0.dp, onBack: () -> Unit = {}) {
    val ctx = LocalContext.current
    // 已导入的人格：first = 落盘档名（删除用），second = 导入时的原文件名（显示用）
    var rows by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var rev by remember { mutableStateOf(0) }
    var importMsg by remember { mutableStateOf("") }
    var manageMsg by remember { mutableStateOf("") }
    // 等确认删除的那一行；null = 没有待确认的
    var pending by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(rev) {
        rows = withContext(Dispatchers.IO) {
            PersonaAssets.customNames(ctx).map { it to PersonaAssets.originalName(ctx, it) }
        }
    }

    val pickFile = rememberPersonaImporter(
        onImported = { stem ->
            importMsg = "已导入 " + PersonaAssets.originalName(ctx, stem)
            rev++
        },
        onMessage = { importMsg = it },
    )

    BackHandler { onBack() }

    val target = pending
    PanelConfirmDialog(
        show = target != null,
        title = "删除人格",
        body = "确认删除「" + (target?.second ?: "") + "」？只删应用私有目录里的这一份，删了要重新导入。",
        confirmLabel = "删除",
        onConfirm = {
            val row = pending
            if (row != null) manageMsg = PersonaAssets.removePersona(ctx, row.first).message
            pending = null
            rev++
        },
        onDismiss = { pending = null },
    )

    Scaffold(
        containerColor = PanelColors.surface,
        topBar = {
            SecondaryTopBar(
                title = "人格 / 文案",
                subtitle = "导入与管理自定义人格",
                onBack = onBack,
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            PanelScreen(bottomInset) {
                item(key = "import") {
                    PanelSectionCard(
                        title = "导入人格",
                        subtitle = "支持 .md / .txt，上限 256KB；只读你选的原文件，复制进应用私有目录",
                    ) {
                        PanelPrimaryButton(
                            text = "选择文件导入",
                            onClick = {
                                importMsg = ""
                                pickFile()
                            },
                        )
                        if (importMsg.isNotBlank()) {
                            Text(
                                text = importMsg,
                                fontSize = 13.sp,
                                color = if (importMsg.startsWith("已导入")) {
                                    PanelColors.primary
                                } else {
                                    PanelColors.error
                                },
                                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                            )
                        }
                    }
                }
                item(key = "manage") {
                    PanelSectionCard(
                        title = "管理已导入人格",
                        subtitle = "只列导入的；内置预设只读、不可删",
                    ) {
                        if (rows.isEmpty()) {
                            Text(
                                text = "还没有导入的人格",
                                fontSize = PanelTokens.HeroSubtitleSize,
                                color = PanelColors.onSurfaceVariantSummary,
                            )
                        } else {
                            rows.forEach { row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = row.second,
                                        fontSize = PanelTokens.InfoTitleSize,
                                        color = PanelColors.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                                    )
                                    IconButton(onClick = { pending = row }) {
                                        Icon(
                                            imageVector = MiuixIcons.Delete,
                                            contentDescription = "删除这个人格",
                                            tint = PanelColors.error,
                                        )
                                    }
                                }
                            }
                        }
                        if (manageMsg.isNotBlank()) {
                            Text(
                                text = manageMsg,
                                fontSize = 13.sp,
                                color = if (manageMsg == PersonaAssets.BUILTIN_LOCKED) {
                                    PanelColors.error
                                } else {
                                    PanelColors.primary
                                },
                                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
