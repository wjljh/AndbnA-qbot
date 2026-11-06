package com.wjljh.andbna.ui

import android.Manifest
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wjljh.andbna.BuildConfig
import com.wjljh.andbna.local.AdbHost
import com.wjljh.andbna.local.BotHost
import com.wjljh.andbna.local.BotService
import com.wjljh.andbna.local.BotStore
import com.wjljh.andbna.local.ModeStore
import com.wjljh.andbna.local.PullBack
import com.wjljh.andbna.local.RootHost
import com.wjljh.andbna.local.RunMode
import com.wjljh.andbna.local.ShizukuAuth
import com.wjljh.andbna.local.ShizukuShell
import com.wjljh.andbna.local.ShizukuState
import com.wjljh.andbna.local.UpdateDownloadService
import com.wjljh.andbna.local.Updater
import com.wjljh.andbna.ui.component.PanelAnimatedNotice
import com.wjljh.andbna.ui.component.PanelGroupCard
import com.wjljh.andbna.ui.component.PanelInfoRow
import com.wjljh.andbna.ui.component.PanelModePill
import com.wjljh.andbna.ui.component.PanelNotice
import com.wjljh.andbna.ui.component.PanelPillRow
import com.wjljh.andbna.ui.component.PanelPrimaryButton
import com.wjljh.andbna.ui.component.PanelScreen
import com.wjljh.andbna.ui.component.PanelSecondaryButton
import com.wjljh.andbna.ui.component.PanelStatCard
import com.wjljh.andbna.ui.component.PanelTertiaryButton
import com.wjljh.andbna.ui.component.PanelTokens
import com.wjljh.andbna.ui.component.cardOutline
import com.wjljh.andbna.ui.design.StatusColors
import com.wjljh.andbna.ui.design.StatusTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 状态页，骨架照 SukiSU HomeMiuix.kt：
 *   hero（110dp 中等卡 + 模式胶囊 + 22sp 标题 + 14sp 副标题 + 92dp 图标，可点走 Tilt）
 *   → 双数字卡（58dp / 30sp / 14sp）
 *   → 信息卡（每行 图标 24dp + 17sp 标题 / 14sp 值，行下 24dp、行间 0.5dp 细线）
 *   → 控制卡（无卡标题，胶囊动作行 35dp/40dp，最多一个 primary 实心）
 *
 * 业务数据只读（BotHost / BotStore / ModeStore），一行没改。
 */

/** hero 状态图标：Material 的圆环+勾 / 圆环+叹号（与 SukiSU 同款），顶层 val 只建一次。 */
private val HeroIconRunning: ImageVector = Icons.Rounded.CheckCircleOutline
private val HeroIconStopped: ImageVector = Icons.Rounded.ErrorOutline

@Composable
fun StatusPage(
    visible: Boolean,
    bottomInset: Dp = 0.dp,
    onOpenDetail: () -> Unit = {},
    /** 当前模式由 App 统一持有（改完立刻重组）；不传就退回 ModeStore 的落盘值 */
    mode: RunMode? = null,
) {
    // 模式入口只剩 hero（点卡片进「运行控制」页），信息卡里那行「运行模式」已删
    LocalStatusContent(visible, bottomInset, onOpenDetail, mode)
}

// ── 本机托管（普通 / ADB / root）────────────────────────────────────────────

@Composable
private fun LocalStatusContent(
    visible: Boolean,
    bottomInset: Dp,
    onOpenDetail: () -> Unit,
    mode: RunMode?,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // 自动检查更新：一个进程最多查一次（真正的节流在 Updater.autoCheck 里：最短 30 分钟），
    // 开关关着就一次网络请求都不发。查到新版本 → hero 下面出一张提示卡。
    LaunchedEffect(Unit) {
        UpdateRuntime.triggerOnce(ctx, BuildConfig.VERSION_NAME)
    }
    val pendingUpdate = UpdateRuntime.release
    var updateDialog by remember { mutableStateOf(false) }

    // 用户真正选的模式：存过就用存的，没选过才回退到 initial() 的 root 探测推断
    // （不用 remember：ModeStore.get 只是一次内存里的 prefs 读，initial 的 su 探测在 RootHost 里也只跑一次）
    val curMode = mode ?: ModeStore.get(ctx) ?: ModeStore.initial(ctx)

    // 开屏先用上次缓存的状态，避免等 Python 起来才显示「运行中」
    var status by remember {
        mutableStateOf(
            BotHost.stateText().takeIf { it.isNotBlank() }
                ?: Prefs.get(ctx, "last_state").ifBlank {
                    if (BotService.alive) "连接中" else ""
                }
        )
    }
    var botCount by remember { mutableIntStateOf(0) }
    // 今日用量/花费（成本统计）：全部来自上游（usage + 余额差值），复用下面那条轮询，每 3 轮 ≈10 秒取一次
    var cost by remember { mutableStateOf<CostSnapshot?>(null) }
    var costTick by remember { mutableIntStateOf(0) }
    // hero 副标题显示运行时长：第一次判定为 running 的时刻（毫秒）
    var runSince by remember { mutableLongStateOf(0L) }
    // 服务启动时间（落盘）：来自 BotService.onCreate，跨 Compose 重组/切页/进程重启都不丢
    var svcSince by remember { mutableLongStateOf(0L) }
    // 每轮轮询都变的自增 tick：给「运行时长」这类依赖墙钟的文案制造重组（值不变时 Compose 不会重组）
    var tick by remember { mutableLongStateOf(0L) }
    // 用户刚点「启动/停止」时先给即时反馈，别等下一轮轮询（否则体感很慢）
    var pending by remember { mutableStateOf("") }
    var memCount by remember { mutableIntStateOf(-1) }
    // 自检只留一行结论（完整依赖清单进日志），30 秒后自动收起
    var probeLine by remember { mutableStateOf("") }
    var probeOk by remember { mutableStateOf(true) }
    // 「自动拉回」那行：三态文案由 PullBack 自己的状态文件算出来（读文件，别在主线程）
    var pullText by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var msgOk by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }

    val askNotif = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            msgOk = false
            msg = "没给通知权限：bot 仍在跑，但通知栏看不到，也更容易被系统清掉"
        }
    }

    LaunchedEffect(Unit) {
        // 「默认开启」：有启用中的 bot 且服务没在跑，就直接拉起来
        val list = withContext(Dispatchers.IO) { BotStore.load(ctx) }
        botCount = list.count { it.enabled }
        val hasEnabled = list.any { it.enabled && it.appid.isNotBlank() && it.secret.isNotBlank() }
        if (hasEnabled && !BotHost.isRunning()) {
            BotHost.init(ctx)
            BotService.start(ctx)
            // 自动拉起时也给即时反馈，别让用户以为没启动
            pending = "启动中"
        }
    }

    // 只有本页在显示时才轮询状态；顺带刷新启用 bot 数。
    // key 里必须带 curMode：只 key visible 的话协程捕获的是旧模式，切完模式「自动拉回」那行文案不会跟着变
    LaunchedEffect(visible, curMode) {
        if (!visible) return@LaunchedEffect
        while (true) {
            status = BotHost.stateText().also {
                if (it.isNotBlank()) Prefs.put(ctx, "last_state", it)
            }
            botCount = withContext(Dispatchers.IO) { BotStore.load(ctx).count { it.enabled } }
            pullText = withContext(Dispatchers.IO) { PullBack.statusText(ctx, curMode) }
            svcSince = withContext(Dispatchers.IO) { Prefs.get(ctx, "svc_started_at").toLongOrNull() ?: 0L }
            tick = System.currentTimeMillis()
            // 成本快照：3 轮取一次（轮询一格 3 秒），用计数取模，不新起循环
            costTick++
            if (costTick % 3 == 1) cost = withContext(Dispatchers.IO) { loadCost(ctx) }
            // 启动阶段更快：还没跑起来时 1 秒一次，跑起来后 3 秒一次，省电
            val up = status.contains("已上线") || status.contains("连接中")
            if (up && runSince == 0L) runSince = System.currentTimeMillis()
            if (up && pending.isNotBlank()) pending = ""
            if (!up && runSince != 0L && !BotHost.isRunning()) runSince = 0L
            val starting = !up && BotHost.isRunning()
            delay(if (up) 3000L else if (starting) 800L else 1000L)
        }
    }

    // 记忆条数：要过 Python 才读得到，频率降到 15 秒一次；拿不到就显示「—」
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        while (true) {
            memCount = withContext(Dispatchers.IO) {
                runCatching {
                    BotStore.load(ctx).filter { it.enabled }.sumOf { b ->
                        JSONArray(BotHost.memoryList(ctx, b.appid)).length()
                    }
                }.getOrDefault(-1)
            }
            delay(15000)
        }
    }

    LaunchedEffect(probeLine) {
        if (probeLine.isBlank()) return@LaunchedEffect
        delay(30000)
        probeLine = ""
    }

    val running = status.contains("已上线") || status.contains("连接中")
    val hasError = status.contains("失败") || status.contains("异常")
    val tone = when {
        running -> StatusTone.Ok
        hasError -> StatusTone.Error
        else -> StatusTone.Idle
    }
    val heroTitle = when {
        running -> "运行中"
        pending.isNotBlank() -> pending
        BotHost.isRunning() -> "启动中"
        else -> "未运行"
    }
    // 兜底来源：本进程启动时长（SystemClock.elapsedRealtime - Process.getStartElapsedRealtime）
    // 服务与应用同进程，二者同起点；这样即使 prefs 读不到也会随轮询正常走字
    val procSince = System.currentTimeMillis() -
        (android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime())
    val heroSubtitle = tick.let { _ -> when {
        running && svcSince > 0L -> "已运行 " + uptimeText(svcSince)
        running && procSince > 0L -> "已运行 " + uptimeText(procSince)
        running && runSince > 0L -> "已运行 " + uptimeText(runSince)
        pending == "启动中" -> "正在拉起 Python 与 " + botCount + " 个 bot…"
        running -> "已运行"
        BotHost.isRunning() -> "正在连接…"
        else -> status.ifBlank { "点这里开始" }
        }
    }
    // 胶囊只看 bot 是否真的在线：已上线 / 连接中才写模式名，否则一律「未启动」。
    // 「已拉起前台服务」这类中间态由下面的提示条表达，不塞进胶囊。
    val heroMode = if (running) modeShort(curMode) else "未启动"

    PanelScreen(bottomInset) {
        item(key = "hero") {
            StatusHeroCard(
                title = heroTitle,
                subtitle = heroSubtitle,
                modeLabel = heroMode,
                costCandidates = costCandidates(cost),
                tone = tone,
                onClick = onOpenDetail,
            )
        }

        // 发现新版本：临时提示卡紧贴 hero（Notice 级 = tertiaryContainer，与错误红卡分开）。
        // 卡上只留一行字，点它弹详情对话框（不直接开始下载）；
        // 「忽略此版本」只在对话框里给（见下方 UpdateDialog.onIgnore），卡上不再放第二个入口。
        if (pendingUpdate != null) item(key = "update") {
            Box(Modifier.fillMaxWidth().clickable { updateDialog = true }) {
                Note(
                    text = "发现新版本 " + pendingUpdate.versionName +
                        "（当前 " + BuildConfig.VERSION_NAME + "）· 点击查看",
                    ok = true,
                )
            }
        }

        if (msg.isNotBlank()) item(key = "notice") {
            PanelAnimatedNotice(visible = true) {
                PanelNotice(msg, isError = !msgOk)
            }
        }

        item(key = "stats") {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PanelTokens.StatGap),
            ) {
                PanelStatCard(
                    number = countText(botCount),
                    label = "启用 bot",
                    modifier = Modifier.weight(1f),
                )
                PanelStatCard(
                    number = countText(memCount),
                    label = "条记忆",
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // 资源占用卡：root / ADB 两种模式显示（普通模式没有额外保活权限、也不该多耗），
        // 位置固定排在「运行模式」那张卡上面
        if (curMode != RunMode.NORMAL) item(key = "resource") {
            ResourceUsageCard(visible = visible)
        }

        item(key = "info") {
            PanelGroupCard {
                PanelInfoRow(
                    icon = Icons.Filled.Refresh,
                    title = "自动拉回",
                    value = pullText.ifBlank { "检测中…" },
                )
                PanelInfoRow(
                    icon = Icons.Filled.Folder,
                    title = "数据目录",
                    value = BotHost.dataDir(ctx).absolutePath,
                    last = true,
                )
            }
        }

        item(key = "control") {
            PanelGroupCard {
                PanelPillRow {
                    // 启动必须在「未运行」时可用，否则永远起不来；停止/自检跟着运行态走
                    PanelPrimaryButton(
                        text = "启动",
                        onClick = {
                            if (botCount == 0) {
                                msgOk = false
                                msg = "还没有启用中的 bot，去「Bot」页添加并启用"
                            } else {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                BotHost.init(ctx)
                                BotService.start(ctx)
                                // 用户想跑：清掉「停止全部」的停顿标记，让自动拉回重新上岗
                                PullBack.setUserStopped(ctx, false)
                                msgOk = true
                                msg = "已拉起前台服务（" + botCount + " 个 bot）"
                                pending = "启动中"
                                scope.launch {
                                    listOf(400L, 1200L, 2500L).forEach { wait ->
                                        delay(wait)
                                        status = BotHost.stateText()
                                    }
                                    pending = ""
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !running && !busy && botCount > 0,
                    )
                    PanelSecondaryButton(
                        text = "停止",
                        onClick = {
                            BotService.stop(ctx)
                            BotHost.stopAll()
                            // 用户主动停：写停顿标记，自动拉回不能把用户刚停掉的 App 又拉起来
                            PullBack.setUserStopped(ctx, true)
                            msgOk = true
                            msg = "已请求停止"
                        },
                        modifier = Modifier.weight(1f),
                        enabled = running && !busy,
                    )
                    PanelSecondaryButton(
                        text = "自检",
                        onClick = {
                            busy = true
                            scope.launch {
                                val raw = withContext(Dispatchers.Default) { BotHost.probe() }
                                // 完整依赖清单只进日志，界面只留一行结论
                                Log.i("AndbnA", "自检：" + raw)
                                val summary = probeSummary(raw)
                                probeOk = summary.first
                                probeLine = summary.second
                                busy = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    )
                }
                PanelAnimatedNotice(visible = busy || probeLine.isNotBlank()) {
                    if (busy) PanelNotice("自检中…") else PanelNotice(probeLine, isError = !probeOk)
                }
            }
        }

        if (curMode == RunMode.ROOT) item(key = "root") { RootCard() }
        if (curMode == RunMode.ADB) item(key = "adb") { AdbCard() }
    }

    // 更新详情：标题「发现新版本 X（当前 Y）」+ Markdown 正文 + 更新 / 取消 / 忽略此版本
    if (updateDialog && pendingUpdate != null) {
        UpdateDialog(
            current = BuildConfig.VERSION_NAME,
            newer = pendingUpdate,
            onUpdate = {
                updateDialog = false
                UpdateDownloadService.start(
                    ctx, pendingUpdate.apkUrl, pendingUpdate.apkName, pendingUpdate.size,
                )
            },
            onIgnore = {
                Updater.ignoreVersion(ctx, pendingUpdate.versionName)
                UpdateRuntime.clear()
                updateDialog = false
            },
            onDismiss = { updateDialog = false },
        )
    }
}

// ── 页面内私有拼装 ──────────────────────────────────────────────────────────

/**
 * hero 中等卡：110dp 最小高 + 状态色淡底 + 右上角 92dp 状态图标 + 底部通栏用量小字。
 *
 * 左上角先放运行模式胶囊（SukiSU「Built-in」的位置），底下才是 22sp SemiBold 主标题
 * 与 14sp 副标题。可点（进 hero 详情）→ Tilt 按压反馈、无水波。
 */
@Composable
private fun StatusHeroCard(
    title: String,
    subtitle: String,
    modeLabel: String,
    /** 下段那行用量小字的候选（从全到简，宽度够的取第一条；空表就不显示） */
    costCandidates: List<String>,
    tone: StatusTone,
    onClick: () -> Unit,
) {

    val icon = if (tone == StatusTone.Ok) HeroIconRunning else HeroIconStopped
    val accent = StatusColors.accent(tone)
    val iconTint = when (tone) {
        StatusTone.Ok -> StatusColors.OkAccent
        StatusTone.Error -> PanelColors.error
        StatusTone.Warn -> StatusColors.WarnAccent
        else -> PanelColors.onSurfaceVariantSummary
    }
    Card(
        modifier = Modifier.fillMaxWidth().cardOutline(PanelTokens.CardRadius),
        cornerRadius = PanelTokens.CardRadius,
        insideMargin = androidx.compose.foundation.layout.PaddingValues(0.dp),
        colors = CardDefaults.defaultColors(color = StatusColors.surface(tone)),
        pressFeedbackType = PressFeedbackType.Tilt,
        showIndication = false,
        onClick = onClick,
    ) {
        // 图标贴右上角（垂直对齐胶囊那一行）；下面每一行都是**同一宽度**（卡内左右各 16dp），
        // 所以上下两段的左右边界完全一致，不会出现「上窄下宽」。
        Box(Modifier.fillMaxWidth().heightIn(min = PanelTokens.HeroMinHeight)) {
            // 图标完整落在卡内：距上边、右边各 16dp（= HeroPadding），不靠卡片圆角裁切。
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = PanelTokens.HeroPadding, end = PanelTokens.HeroPadding)
                    .size(PanelTokens.HeroIconInnerSize),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = PanelTokens.HeroPadding,
                        end = PanelTokens.HeroPadding,
                        top = PanelTokens.HeroPadding,
                        bottom = PanelTokens.HeroCostBottomPadding,
                    ),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(PanelTokens.HeroTextGap),
                ) {
                    PanelModePill(text = modeLabel, tint = accent)
                    Text(
                        text = title,
                        fontSize = PanelTokens.HeroTitleSize,
                        lineHeight = PanelTokens.HeroTitleLineHeight,
                        fontWeight = FontWeight.SemiBold,
                        color = PanelColors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = subtitle,
                        fontSize = PanelTokens.HeroSubtitleSize,
                        color = PanelColors.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (costCandidates.isNotEmpty()) {
                    CostLineText(
                        costCandidates,
                        Modifier.padding(top = PanelTokens.HeroCostTopGap),
                    )
                }
            }
        }
    }
}

/**
 * hero 下段那行用量：**永远单行**、通栏（与上面几行同宽），不做任何折行 / 换行。
 *
 * 候选按「全 → 简」排好，逐个用真 Text 量一次（不受字体度量差异影响），取第一条放得下的；
 * 都放不下就退到最短那条（只剩次数）。maxLines=1 + clip 保证任何数据下都不折行、不越界。
 */
@Composable
private fun CostLineText(candidates: List<String>, modifier: Modifier = Modifier) {
    SubcomposeLayout(modifier.fillMaxWidth().clipToBounds()) { constraints ->
        val avail = constraints.maxWidth
        var placeable: Placeable? = null
        for ((i, s) in candidates.withIndex()) {
            val p = subcompose(i) {
                Text(
                    text = s,
                    fontSize = PanelTokens.HeroCostSize,
                    lineHeight = PanelTokens.HeroCostLineHeight,
                    color = PanelColors.onSurfaceVariantSummary,
                    maxLines = 1,
                )
            }.first().measure(Constraints())
            placeable = p
            if (p.width <= avail) break
        }
        val h = placeable?.height ?: 0
        layout(avail, h) { placeable?.place(0, 0) }
    }
}

@Composable
private fun MonoText(text: String) {
    Text(
        text = text,
        fontSize = PanelTokens.HeroSubtitleSize,
        fontFamily = FontFamily.Monospace,
        color = PanelColors.onSurfaceVariantSummary,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun countText(n: Int): String = if (n < 0) "—" else n.toString()

// ── 成本统计（今日用量）────────────────────────────────────────────────────
//
// 数据全部来自上游，本地不存价格表：
//   · 次数 / token ← chat/completions 响应的 usage（Python 侧按日期+用途累加到 usage.json）
//   · 金额 ← 今日首次余额 − 当前余额（GET /user/balance，每 10 分钟最多查一次）
// 余额查不到就是 null，这一行只显示次数与 token。

/** bot_runner.cost_snapshot 的解析结果（今日）。 */
internal data class CostSnapshot(
    val calls: Int,
    val inTokens: Long,
    val outTokens: Long,
    /** 今日花费（元）；null = 没有余额数据 / 上次查询失败 */
    val yuan: Double?,
    val searchCalls: Int,
)



internal fun parseCost(json: String): CostSnapshot? = runCatching {
    if (json.isBlank()) return null
    val o = JSONObject(json)
    CostSnapshot(
        calls = o.optInt("calls"),
        inTokens = o.optLong("in_tokens"),
        outTokens = o.optLong("out_tokens"),
        yuan = if (o.isNull("today_cost_yuan")) null else o.optDouble("today_cost_yuan"),
        searchCalls = o.optInt("search_calls"),
    )
}.getOrNull()

/**
 * 把所有启用中的 bot 的快照加起来：次数与 token 求和，金额取第一个非空值
 * （金额是账号级的余额差值，多个 bot 共用一个 Key 时不能相加，否则重复计算）。
 */
private fun loadCost(ctx: Context): CostSnapshot? {
    var calls = 0
    var tin = 0L
    var tout = 0L
    var searches = 0
    var yuan: Double? = null
    var any = false
    for (b in BotStore.load(ctx).filter { it.enabled }) {
        val s = parseCost(BotHost.costSnapshot(ctx, b.appid)) ?: continue
        any = true
        calls += s.calls
        tin += s.inTokens
        tout += s.outTokens
        searches += s.searchCalls
        if (yuan == null) yuan = s.yuan
    }
    return if (any) CostSnapshot(calls, tin, tout, yuan, searches) else null
}

/** token 数：≥10000 用「X.X万」（hero 一行放得下的紧凑写法），否则原样。 */
internal fun tokText(n: Long): String = when {
    n >= 100_000_000L -> String.format(Locale.US, "%.1f亿", n / 100_000_000.0)
    n >= 10_000L -> String.format(Locale.US, "%.1f万", n / 10_000.0)
    else -> n.toString()
}

/**
 * hero 下段那行的候选，从「全都有」到「只留次数+金额」，按顺序试到放得下为止：
 *   今日 42 次 · 12.7万/2.2万 tok · 约 ¥0.42（余额差值估算） · 联网 6 次
 * 数字太长就省掉 tokens 段（次数 / 金额 / 联网优先），永远单行。
 * 金额为 null（余额查不到）时金额段消失；整句口径说明在「设置 → 关于」页底部。
 */
internal fun costCandidates(c: CostSnapshot?): List<String> {
    if (c == null) return emptyList()
    if (c.calls <= 0 && c.inTokens == 0L && c.outTokens == 0L) return listOf("今日暂无调用")
    val head = "今日 ${c.calls} 次"
    val toks = "${tokText(c.inTokens)}/${tokText(c.outTokens)} tok"
    val toksShort = "${tokText(c.inTokens)}/${tokText(c.outTokens)}"
    val money = c.yuan?.let { String.format(Locale.US, "约 ¥%.2f", it) }
    val net = if (c.searchCalls > 0) "联网 ${c.searchCalls} 次" else null
    fun line(vararg parts: String?): String =
        parts.filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
    // 通栏宽度下正常态能全放下；放不下才按序省：
    //   ① 去掉「 tok」两个字 ② 去掉「今日」 ③ 省 tokens 段（次数/金额/联网优先）④ 省联网 ⑤ 只剩次数
    // 字号与图标位一律不动（拍板要求）。
    return listOf(
        line(head, toks, money, net),
        line(head, toksShort, money, net),
        line("${c.calls} 次", toksShort, money, net),
        line(head, money, net),
        line(head, money),
        line(head),
    ).distinct()
}

/**
 * 把 Python 自检的多行依赖清单压成一行结论；完整清单只写日志（前缀「自检：」）。
 *
 * 原文形如：
 *   python   3.13.16
 *   platform android
 *   aiohttp  3.11.18
 *   botpy    ok
 * 失败的那一行末尾是「✗ 原因」；Python 没起来时 bot_runner 直接返回「Python 没起来」。
 */
private fun probeSummary(raw: String): Pair<Boolean, String> {
    val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    if (lines.isEmpty()) return false to "自检失败：没有输出"

    val bad = lines.firstOrNull { it.contains("✗") }
    if (bad != null) {
        val name = bad.substringBefore(' ').trim()
        val why = bad.substringAfter("✗", "").trim()
        return false to "自检失败：" +
            (if (name.isBlank()) "依赖导入出错" else name + " 导入失败") +
            (if (why.isBlank()) "" else "（" + why + "）")
    }
    if (raw.startsWith("Python 没起来") || raw.startsWith("自检失败")) {
        return false to "自检失败：" + lines.first().removePrefix("自检失败：").take(40)
    }

    fun value(name: String): String? = lines.firstOrNull { it.startsWith(name) }
        ?.removePrefix(name)?.trim()?.takeIf { it.isNotEmpty() }

    val py = value("python")
    val botpy = value("botpy")
    val parts = ArrayList<String>()
    if (py != null) parts += "python " + py
    parts += when {
        botpy == null -> "botpy 未见"
        botpy == "ok" -> "botpy 正常"
        else -> "botpy " + botpy
    }
    return true to "自检通过：" + parts.joinToString(" · ")
}

/** 运行模式的显示用语（业务层 label 是「root 模式」，这里统一成「×× 托管」）。 */
internal fun modeText(mode: RunMode): String = when (mode) {
    RunMode.ROOT -> "root 托管"
    RunMode.ADB -> "ADB 托管"
    RunMode.NORMAL -> "本机托管"
}

/** hero 胶囊里的短模式名：普通 / ADB / root（胶囊放不下「×× 托管」，与控制页的选项名对齐）。 */
internal fun modeShort(mode: RunMode): String = when (mode) {
    RunMode.ROOT -> "root"
    RunMode.ADB -> "ADB"
    RunMode.NORMAL -> "普通"
}

/**
 * root 模式专属卡：电池白名单（开机自启已交给 BOOT_COMPLETED，不再依赖 root）。
 * 逻辑与旧版一致；版式换成信息行 + 胶囊动作行，无卡标题。
 */
@Composable
private fun RootCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rootOk by remember { mutableStateOf<Boolean?>(null) }
    var wlOk by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        busy = true
        val res = withContext(Dispatchers.IO) {
            Pair(RootHost.available(), RootHost.isWhitelisted(ctx.packageName))
        }
        rootOk = res.first
        wlOk = res.second
        busy = false
    }

    PanelGroupCard {
        PanelInfoRow(
            icon = Icons.Filled.Security,
            title = "root 托管",
            value = when (rootOk) {
                null -> "检测中…"
                true -> "已获得 root（uid 0）"
                false -> "没有拿到 root 权限"
            },
        )
        PanelInfoRow(
            icon = Icons.Filled.Smartphone,
            title = "电池白名单",
            value = if (wlOk) "已加入" else "未加入",
            last = true,
        )
        // 读数行与按钮行之间 12dp；按钮行起始对齐、间隔 8dp，不给 weight（不拉成一排等宽）
        PanelPillRow(
            modifier = Modifier.padding(top = PanelTokens.ButtonRowTopGap),
            fillWidth = false,
        ) {
            PanelPrimaryButton(
                text = "加白名单",
                onClick = {
                    busy = true
                    scope.launch {
                        msg = withContext(Dispatchers.IO) { RootHost.whitelist(ctx.packageName) }.out
                        wlOk = withContext(Dispatchers.IO) { RootHost.isWhitelisted(ctx.packageName) }
                        busy = false
                    }
                },
                enabled = !busy && rootOk != false,
            )
            // 移出白名单是破坏性操作 → 只有它走 error 系
            PanelSecondaryButton(
                text = "移出",
                onClick = {
                    busy = true
                    scope.launch {
                        withContext(Dispatchers.IO) { RootHost.unwhitelist(ctx.packageName) }
                        wlOk = withContext(Dispatchers.IO) { RootHost.isWhitelisted(ctx.packageName) }
                        msg = "已移出白名单"
                        busy = false
                    }
                },
                danger = true,
                enabled = !busy,
            )
        }
        PanelAnimatedNotice(visible = msg.isNotBlank()) { PanelNotice(msg) }
    }
}

/**
 * ADB 模式专属卡：靠 Shizuku 拿 shell 权限做「后台豁免 + 掉了能拉回」。
 * bot 仍跑在 App 进程内（Python 在 APK 里，shell 没法单独拉起它）。
 */
@Composable
private fun AdbCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var shizukuOk by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }

    // Shizuku 的授权回调统一在 ShizukuShell 里注册：页面只读状态 + 调 requestPermission，不再自己挂监听
    LaunchedEffect(tick) {
        busy = true
        val st = withContext(Dispatchers.IO) { ShizukuShell.state(ctx) }
        shizukuOk = st != ShizukuState.NOT_RUNNING
        granted = st == ShizukuState.GRANTED
        busy = false
    }

    PanelGroupCard {
        PanelInfoRow(
            icon = Icons.Filled.Smartphone,
            title = "ADB 托管（Shizuku）",
            value = when {
                busy -> "检测中…"
                !shizukuOk -> "Shizuku 没在运行"
                !granted -> "Shizuku 在运行，本 App 未授权"
                else -> "已授权，可以做后台豁免"
            },
        )
        PanelInfoRow(
            icon = Icons.Filled.Security,
            title = "shell 服务",
            value = if (ShizukuShell.bound) "已连上" else "未连接",
            last = true,
        )
        PanelPillRow(
            modifier = Modifier.padding(top = PanelTokens.ButtonRowTopGap),
            fillWidth = false,
        ) {
            PanelPrimaryButton(
                text = "授权",
                onClick = {
                    // 一次走完「申请 → 绑定」：三种结果（未运行 / 已拒绝 / 已授权+bound）都以 ShizukuResult 为准
                    busy = true
                    msg = "正在申请 Shizuku 授权…"
                    ShizukuShell.ensureAuthorized(ctx) { r ->
                        busy = false
                        shizukuOk = r.auth != ShizukuAuth.NOT_RUNNING
                        granted = r.auth == ShizukuAuth.GRANTED
                        msg = if (r.ok) r.text else r.text + "。下一步：" + r.hint
                        tick++
                    }
                },
                enabled = !busy,
            )
            PanelSecondaryButton(
                text = "连接 shell",
                onClick = {
                    busy = true
                    scope.launch {
                        withContext(Dispatchers.IO) { ShizukuShell.bind(ctx.packageName) }
                        delay(1200)
                        msg = if (ShizukuShell.bound) "UserService 已连上（bound=true）"
                        else "UserService 没连上（bound=false）；" +
                            ShizukuShell.lastError.ifBlank { "需 Shizuku v13+ 且已授权" }
                        busy = false
                        tick++
                    }
                },
                enabled = !busy && granted,
            )
        }
        PanelPillRow(
            modifier = Modifier.padding(top = PanelTokens.ButtonGap),
            fillWidth = false,
        ) {
            PanelTertiaryButton(
                text = "加白名单",
                onClick = {
                    busy = true
                    scope.launch {
                        msg = withContext(Dispatchers.IO) {
                            ShizukuShell.whitelist(ctx.packageName)
                        }.ifBlank { "已执行" }
                        busy = false
                        tick++
                    }
                },
                enabled = !busy && ShizukuShell.bound,
            )
            PanelTertiaryButton(
                text = "重新检测",
                onClick = { tick++ },
                enabled = !busy,
            )
        }
        // 版本建议一句话（原来的「先起 Shizuku 再开 App」那串说明已删），
        // 「换版本后要重启 App 才重新绑定」压成一行小字留着
        Text(
            text = "建议使用 Shizuku 13.7 及以上版本（旧版在 Android 17 上服务会自行退出）",
            fontSize = PanelTokens.OnboardListNoteSize,
            lineHeight = PanelTokens.OnboardHeroBodyLineHeight,
            color = PanelColors.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = PanelTokens.ButtonRowTopGap),
        )
        Text(
            text = "换过 Shizuku 版本后要重启本 App 才会重新绑定（Shizuku 只在新进程里下发通道）",
            fontSize = PanelTokens.OnboardListNoteSize,
            color = PanelColors.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = PanelTokens.InfoValueGap),
        )
        PanelAnimatedNotice(visible = msg.isNotBlank()) { MonoText(msg) }
    }
}

/** hero 副标题用的运行时长文案：<1 分钟 / N 分钟 / N 小时 M 分 / N 天 M 小时 */
private fun uptimeText(sinceMs: Long): String {
    val min = ((System.currentTimeMillis() - sinceMs) / 60000L).coerceAtLeast(0L)
    return when {
        min < 1L -> "不到 1 分钟"
        min < 60L -> min.toString() + " 分钟"
        min < 1440L -> (min / 60L).toString() + " 小时"
        else -> (min / 1440L).toString() + " 天"
    }
}
