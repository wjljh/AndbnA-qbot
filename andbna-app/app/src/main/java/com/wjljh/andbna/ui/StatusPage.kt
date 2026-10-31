package com.wjljh.andbna.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import com.wjljh.andbna.ui.design.StatusColors
import com.wjljh.andbna.ui.design.StatusTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
    onOpenMode: () -> Unit = {},
    /** 当前模式由 App 统一持有（改完立刻重组）；不传就退回 ModeStore 的落盘值 */
    mode: RunMode? = null,
) {
    LocalStatusContent(visible, bottomInset, onOpenDetail, onOpenMode, mode)
}

// ── 本机托管（普通 / ADB / root）────────────────────────────────────────────

@Composable
private fun LocalStatusContent(
    visible: Boolean,
    bottomInset: Dp,
    onOpenDetail: () -> Unit,
    onOpenMode: () -> Unit,
    mode: RunMode?,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
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
    var memCount by remember { mutableIntStateOf(-1) }
    var probeText by remember { mutableStateOf("") }
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
            delay(3000)
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

    val running = status.contains("已上线") || status.contains("连接中")
    val hasError = status.contains("失败") || status.contains("异常")
    val tone = when {
        running -> StatusTone.Ok
        hasError -> StatusTone.Error
        else -> StatusTone.Idle
    }
    val heroTitle = if (running) "运行中" else "未运行"
    val heroSubtitle = if (running) "已上线 · " + botCount + " 个 bot" else status.ifBlank { "—" }
    // 胶囊只看 bot 是否真的在线：已上线 / 连接中才写模式名，否则一律「未启动」。
    // 「已拉起前台服务」这类中间态由下面的提示条表达，不塞进胶囊。
    val heroMode = if (running) modeShort(curMode) else "未启动"

    PanelScreen(bottomInset) {
        item(key = "hero") {
            StatusHeroCard(
                title = heroTitle,
                subtitle = heroSubtitle,
                modeLabel = heroMode,
                tone = tone,
                onClick = onOpenDetail,
            )
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

        item(key = "info") {
            PanelGroupCard {
                // 点这一行进模式选择页（设置页那行「运行模式」已删，这里是 Route.ModePick 的入口）
                PanelInfoRow(
                    icon = Icons.Filled.Tune,
                    title = "运行模式",
                    value = modeText(curMode),
                    onClick = onOpenMode,
                )
                PanelInfoRow(
                    icon = Icons.Filled.Refresh,
                    title = "自动拉回",
                    value = pullText.ifBlank { "检测中…" },
                )
                PanelInfoRow(
                    icon = Icons.Filled.Smartphone,
                    title = "启用 bot",
                    value = botCount.toString() + " 个",
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
                                scope.launch {
                                    delay(1500)
                                    status = BotHost.stateText()
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
                                probeText = withContext(Dispatchers.Default) { BotHost.probe() }
                                busy = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    )
                }
                PanelAnimatedNotice(visible = busy || probeText.isNotBlank()) {
                    Column {
                        if (busy) PanelNotice("自检中…")
                        if (probeText.isNotBlank()) MonoText(probeText)
                    }
                }
            }
        }

        if (curMode == RunMode.ROOT) item(key = "root") { RootCard() }
        if (curMode == RunMode.ADB) item(key = "adb") { AdbCard() }
    }
}

// ── 页面内私有拼装 ──────────────────────────────────────────────────────────

/**
 * hero 中等卡：110dp 最小高 + 状态色淡底 + 右下角 92dp 状态图标。
 *
 * 左上角先放运行模式胶囊（SukiSU「Built-in」的位置），底下才是 22sp SemiBold 主标题
 * 与 14sp 副标题。可点（进 hero 详情）→ Tilt 按压反馈、无水波。
 */
@Composable
private fun StatusHeroCard(
    title: String,
    subtitle: String,
    modeLabel: String,
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
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = PanelTokens.CardRadius,
        insideMargin = androidx.compose.foundation.layout.PaddingValues(0.dp),
        colors = CardDefaults.defaultColors(color = StatusColors.surface(tone)),
        pressFeedbackType = PressFeedbackType.Tilt,
        showIndication = false,
        onClick = onClick,
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = PanelTokens.HeroMinHeight)) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = PanelTokens.HeroIconOffsetX, y = PanelTokens.HeroIconOffsetY)
                    .size(PanelTokens.HeroIconSize),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(horizontal = PanelTokens.HeroPadding),
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
        }
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
