package com.wjljh.andbna.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wjljh.andbna.local.BotHost
import com.wjljh.andbna.local.BotService
import com.wjljh.andbna.local.BotStore
import com.wjljh.andbna.local.ModeStore
import com.wjljh.andbna.local.RootHost
import com.wjljh.andbna.local.RunMode
import com.wjljh.andbna.local.ShizukuShell
import com.wjljh.andbna.ui.component.PanelAnimatedNotice
import com.wjljh.andbna.ui.component.PanelChoiceRow
import com.wjljh.andbna.ui.component.PanelGroupCard
import com.wjljh.andbna.ui.component.PanelNotice
import com.wjljh.andbna.ui.component.PanelPillRow
import com.wjljh.andbna.ui.component.PanelPrimaryButton
import com.wjljh.andbna.ui.component.PanelSecondaryButton
import com.wjljh.andbna.ui.component.PanelTertiaryButton
import com.wjljh.andbna.ui.component.PanelTokens
import com.wjljh.andbna.ui.component.SecondaryTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text

/**
 * hero 卡点进来的二级页：选工作模式 + 运行控制。
 *
 * 版式照 SukiSU 的二级页：SmallTopAppBar（返回 + 居中标题，与主页顶栏同一字体体系）
 * + 无标题 [PanelGroupCard] + [PanelChoiceRow]（单选圆点 / 模式名 17sp Medium / 说明 14sp，
 * 选中行整行提亮一档）+ 起始对齐的胶囊动作行（35dp / 40dp 圆角 / 间隔 8dp）。
 *
 * 为什么是二级页而不是下拉菜单：模式选择是有代价的操作（会重启服务），
 * 塞进弹层里点错就要重来；独立一页能放说明、能显示当前模式。
 */
@Composable
fun HeroDetailScreen(
    metrics: Metrics,
    onBack: () -> Unit,
    /** 选完立刻告诉上一层：状态页的模式胶囊读的是 App 持有的模式，不回传就会显示旧值 */
    onModeChange: (RunMode) -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // 用户真正选的模式（存过就用存的），与状态页同一个表达式
    var curMode by remember { mutableStateOf(ModeStore.get(ctx) ?: ModeStore.initial(ctx)) }
    var msg by remember { mutableStateOf("") }
    // 只在真出错时显示：常规切换不打扰，被拦下才提醒
    var errMsg by remember { mutableStateOf("") }
    var probe by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val tight = PlatformTextStyle(includeFontPadding = false)

    val askNotif = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) msg = "没给通知权限，通知栏看不到运行状态"
    }

    // 读文件 + 解析 JSON 放 IO：组合期一次，不在每次重组执行
    var botCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        botCount = withContext(Dispatchers.IO) { BotStore.load(ctx).count { it.enabled } }
    }

    fun pick(m: RunMode) {
        if (m.key == "root" && !RootHost.hasRoot()) {
            errMsg = "请授予 root 权限后重启应用"
            return
        }
        errMsg = ""
        msg = ""
        // ADB 模式是一次完整流程（授权 → 绑定），成不成以绑定结果为准：
        // 没授权 / 没绑上就不写模式，并把原因显示出来，不做「写了 ADB 但用不了」的静默切换
        if (m == RunMode.ADB) {
            if (busy) return
            busy = true
            msg = "正在申请 Shizuku 授权…"
            ShizukuShell.switchToAdb(ctx) { r ->
                busy = false
                if (r.ok) {
                    curMode = RunMode.ADB
                    onModeChange(RunMode.ADB)
                    msg = "已切到「ADB 模式」；" + r.text
                } else {
                    msg = ""
                    errMsg = "没切到 ADB 模式（模式仍是「" + curMode.label + "」）；" + r.text +
                        "。下一步：" + r.hint
                }
            }
            return
        }
        curMode = m
        ModeStore.set(ctx, m)
        onModeChange(m)
        msg = "已切到「" + m.label + "」"
    }

    Scaffold(
        containerColor = PanelColors.surface,
        topBar = {
            SecondaryTopBar(
                title = "运行控制",
                subtitle = "当前：" + curMode.label,
                onBack = onBack,
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = PanelTokens.PageHorizontal,
                end = PanelTokens.PageHorizontal,
                top = PanelTokens.PageTop,
                bottom = PanelTokens.BottomBarSafetyGap,
            ),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                PanelTokens.CardSpacing
            ),
        ) {
            if (errMsg.isNotBlank() || msg.isNotBlank()) {
                item(key = "notice") {
                    PanelAnimatedNotice(visible = true) {
                        Column {
                            if (errMsg.isNotBlank()) PanelNotice(errMsg, isError = true)
                            if (msg.isNotBlank()) PanelNotice(msg)
                        }
                    }
                }
            }

            item(key = "modes") {
                PanelGroupCard {
                    Text(
                        text = "换模式后要重启服务才生效",
                        fontSize = PanelTokens.InfoValueSize,
                        color = PanelColors.onSurfaceVariantSummary,
                        modifier = Modifier.padding(bottom = PanelTokens.ContentGap),
                        style = TextStyle(platformStyle = tight),
                    )
                    RunMode.entries.forEachIndexed { index, m ->
                        PanelChoiceRow(
                            title = m.label,
                            subtitle = m.summary,
                            selected = m == curMode,
                            divider = index != RunMode.entries.lastIndex,
                            onClick = { pick(m) },
                        )
                    }
                }
            }

            item(key = "control") {
                PanelGroupCard {
                    Text(
                        text = "启用中的 bot：" + botCount + " 个",
                        fontSize = PanelTokens.InfoValueSize,
                        color = PanelColors.onSurfaceVariantSummary,
                        modifier = Modifier.padding(bottom = PanelTokens.ButtonRowTopGap),
                        style = TextStyle(platformStyle = tight),
                    )
                    // 按钮行起始对齐、间隔 8dp，不拉满宽度
                    PanelPillRow(fillWidth = false) {
                        PanelPrimaryButton(
                            text = "启动",
                            onClick = {
                                if (botCount == 0) {
                                    msg = "没有启用中的 bot，去「Bot」页添加"
                                } else {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                    BotHost.init(ctx)
                                    BotService.startByUser(ctx)
                                    msg = "已拉起服务（" + botCount + " 个 bot）"
                                }
                            },
                        )
                        PanelSecondaryButton(
                            text = "停止",
                            onClick = {
                                // 同状态页：先写停顿标记再停服务，看门狗才不会拉回
                                BotService.stopByUser(ctx)
                                BotHost.stopAll()
                                msg = "已停止"
                            },
                        )
                        PanelSecondaryButton(
                            text = "重启",
                            onClick = {
                                // 重启也是手动意图：停这一下同样写标记（停的瞬间看门狗不去拉回），
                                // 800ms 后再清标记拉服务 —— 结束时状态是「用户要它跑」
                                BotService.stopByUser(ctx)
                                BotHost.stopAll()
                                scope.launch {
                                    delay(800)
                                    BotHost.init(ctx)
                                    BotService.startByUser(ctx)
                                    msg = "已重启"
                                }
                            },
                        )
                    }
                    PanelPillRow(
                        modifier = Modifier.padding(top = PanelTokens.ButtonGap),
                        fillWidth = false,
                    ) {
                        PanelTertiaryButton(
                            text = if (busy) "自检中…" else "自检",
                            enabled = !busy,
                            onClick = {
                                busy = true
                                scope.launch {
                                    probe = withContext(Dispatchers.Default) { BotHost.probe() }
                                    busy = false
                                }
                            },
                        )
                        PanelTertiaryButton(
                            text = "读日志",
                            onClick = {
                                scope.launch {
                                    val t = withContext(Dispatchers.IO) { BotHost.logTail(120) }
                                    msg = if (t.isBlank()) "日志是空的" else t.takeLast(600)
                                }
                            },
                        )
                    }
                }
            }

            if (probe.isNotBlank()) {
                item(key = "probe") {
                    PanelGroupCard {
                        Text(
                            text = probe,
                            fontSize = PanelTokens.InfoValueSize,
                            fontFamily = FontFamily.Monospace,
                            color = PanelColors.onSurfaceVariantSummary,
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = PanelTokens.ButtonGap),
                            thickness = PanelTokens.Divider,
                            color = PanelColors.dividerLine,
                        )
                        PanelPillRow(fillWidth = false) {
                            PanelTertiaryButton(text = "收起", onClick = { probe = "" })
                        }
                    }
                }
            }
        }
    }
}
