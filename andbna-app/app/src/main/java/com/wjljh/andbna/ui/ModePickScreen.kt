package com.wjljh.andbna.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.wjljh.andbna.local.RootHost
import com.wjljh.andbna.local.RunMode
import com.wjljh.andbna.local.ShizukuShell
import com.wjljh.andbna.ui.component.PanelAnimatedNotice
import com.wjljh.andbna.ui.component.PanelChoiceRow
import com.wjljh.andbna.ui.component.PanelGroupCard
import com.wjljh.andbna.ui.component.PanelNotice
import com.wjljh.andbna.ui.component.PanelScreen
import com.wjljh.andbna.ui.component.PanelTokens
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back

/**
 * 运行模式选择（二级页）。
 *
 * 版式照 SukiSU 的二级页：SmallTopAppBar（返回 + 居中标题，与主页顶栏同一字体体系）
 * + 一张无标题 [PanelGroupCard]，里面每个模式一行 [PanelChoiceRow]：
 * 单选圆点（选中主色实心 / 未选 outline 空心）+ 模式名 17sp Medium + 说明 14sp 次级色，
 * 选中行整行底色提亮一档。
 *
 * 只在两种时候出现：第一次打开（还没配过），以及从设置里点「重新选择模式」。
 * 配过之后就默认按上次选的模式直接开跑，不再每次拦一下。
 */
@Composable
fun ModePickScreen(
    metrics: Metrics,
    current: RunMode?,
    allowBack: Boolean,
    onPick: (RunMode) -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    var blocked by remember { mutableStateOf("") }
    // ADB 模式是异步流程（授权 → 绑定），期间不接受第二次点击
    var busy by remember { mutableStateOf(false) }
    val tight = PlatformTextStyle(includeFontPadding = false)

    Scaffold(
        containerColor = PanelColors.surface,
        topBar = {
            if (allowBack) {
                SmallTopAppBar(
                    title = "选择运行模式",
                    subtitle = "改完会立即生效",
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = "返回",
                                tint = PanelColors.onBackground,
                            )
                        }
                    },
                )
            } else {
                SmallTopAppBar(title = "欢迎使用 AndbnA", subtitle = "先选一个运行方式")
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            PanelScreen(bottomInset = PanelTokens.BottomBarSafetyGap) {
                if (blocked.isNotBlank()) item(key = "notice") {
                    PanelAnimatedNotice(visible = true) { PanelNotice(blocked, isError = true) }
                }
                item(key = "modes") {
                    PanelGroupCard {
                        Text(
                            text = "选一个用着最省心的，随时能改。",
                            fontSize = PanelTokens.InfoValueSize,
                            color = PanelColors.onSurfaceVariantSummary,
                            modifier = Modifier.padding(bottom = PanelTokens.ContentGap),
                            style = TextStyle(platformStyle = tight),
                        )
                        RunMode.entries.forEachIndexed { index, mode ->
                            PanelChoiceRow(
                                title = mode.label,
                                subtitle = mode.summary,
                                selected = current == mode,
                                divider = index != RunMode.entries.lastIndex,
                                // 点一下就生效，不再二次确认；root / ADB 要先验证
                                onClick = {
                                    if (mode.key == "root" && !RootHost.hasRoot()) {
                                        blocked = "请授予 root 权限后重启应用"
                                    } else if (mode == RunMode.ADB) {
                                        // ADB 不能静默写盘：授权 + 绑定都成功才算切过去
                                        if (!busy) {
                                            busy = true
                                            blocked = "正在申请 Shizuku 授权…"
                                            ShizukuShell.switchToAdb(ctx) { r ->
                                                busy = false
                                                if (r.ok) {
                                                    blocked = ""
                                                    onPick(RunMode.ADB)
                                                } else {
                                                    blocked = "没切到 ADB 模式（模式仍是「" +
                                                        (current?.label ?: "未选") + "」）；" +
                                                        r.text + "。下一步：" + r.hint
                                                }
                                            }
                                        }
                                    } else {
                                        onPick(mode)
                                    }
                                },
                            )
                        }
                    }
                }
                if (!allowBack) {
                    item(key = "foot") {
                        Text(
                            text = "选好以后就按这个模式开跑，想换在「设置 → 运行模式」里改。",
                            fontSize = 11.sp,
                            color = PanelColors.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 4.dp),
                            style = TextStyle(platformStyle = tight),
                        )
                    }
                }
            }
        }
    }
}
