package com.wjljh.andbna.ui

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「状态」页在本机托管模式（普通 / ADB / root）下的内容。
 *
 * 它不再是独立页面：四个分页始终都在，只是数据源从面板换成内嵌 Python。
 * 凭据不在这里 —— 那属于某个 bot 的身份，放在「Bot」页。
 */
@Composable
fun LocalStatusContent(metrics: Metrics, onOpenDetail: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // 开屏先用上次缓存的状态，避免等 Python 起来才显示「运行中」
    var status by remember {
        mutableStateOf(
            BotHost.stateText().takeIf { it.isNotBlank() }
                ?: Prefs.get(ctx, "last_state").ifBlank {
                    // 连缓存都没有时，看前台服务在不在，在就先显示运行中
                    if (com.wjljh.andbna.local.BotService.alive) "连接中" else ""
                }
        )
    }
    var probeText by remember { mutableStateOf("") }
    var logText by remember { mutableStateOf("") }
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
        val hasEnabled = BotStore.load(ctx).any {
            it.enabled && it.appid.isNotBlank() && it.secret.isNotBlank()
        }
        // 用户点过「停止」就别自动拉起（这个默认开启只服务「没停过」的情形）
        if (hasEnabled && !BotHost.isRunning() && !PullBack.isUserStopped(ctx)) {
            BotHost.init(ctx)
            BotService.start(ctx)
        }
        while (true) {
            // 状态每 3 秒刷一次就够；日志每 9 秒拉一次
            // —— 每次 logTail 都是一次跨 Chaquopy 的字符串复制，2 秒一次会明显卡
            status = BotHost.stateText().also {
                if (it.isNotBlank()) Prefs.put(ctx, "last_state", it)
            }
            delay(3000)
        }
    }

    // 读文件 + 解析 JSON 放 IO，绝不能放进每次重组都执行的位置
    var botCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        botCount = withContext(Dispatchers.IO) { BotStore.load(ctx).count { it.enabled } }
    }
    val running = status.contains("已上线") || status.contains("连接中")
    val hasError = status.contains("失败") || status.contains("异常")


    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        PageColumn(metrics) {
            // 错误横幅在 hero 上方（spec 11 §2：hero 永远不染红）
            if (hasError) {
                Note("bot 启动异常，看下面日志", ok = false)
            }

            val curMode = ModeStore.get(ctx) ?: ModeStore.initial(ctx)
            HeroCard(
                state = when {
                    running -> HeroState.WORKING
                    hasError -> HeroState.ERROR
                    else -> HeroState.IDLE
                },
                title = when {
                    running -> "运行中"
                    hasError -> "异常"
                    else -> "未运行"
                },
                subtitle = when {
                    running -> "已上线 · $botCount 个 bot"
                    else -> status.ifBlank { "—" }
                },
                modeText = curMode.label,
                onClick = onOpenDetail,
            )

            if (msg.isNotBlank()) Note(msg, msgOk)

            SectionCard(metrics, title = "控制") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionChip("启动") {
                        if (botCount == 0) {
                            msgOk = false
                            msg = "还没有启用中的 bot，去「Bot」页添加并启用"
                        } else {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            BotHost.init(ctx)
                            BotService.startByUser(ctx)
                            msgOk = true
                            msg = "已拉起前台服务（$botCount 个 bot）"
                            scope.launch {
                                delay(1500)
                                status = BotHost.stateText()
                                logText = BotHost.logTail(150)
                            }
                        }
                    }
                    ActionChip("停止") {
                        // 先写停顿标记再停服务：反过来的话看门狗会在中间那一小段把 App 拉回来
                        BotService.stopByUser(ctx)
                        BotHost.stopAll()
                        msgOk = true
                        msg = "已请求停止"
                    }
                    ActionChip("自检") {
                        busy = true
                        scope.launch {
                            probeText = withContext(Dispatchers.Default) { BotHost.probe() }
                            busy = false
                        }
                    }
                }
                if (busy) Note("自检中…")
                if (probeText.isNotBlank()) {
                    Text(
                        probeText,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = PanelColors.onSurfaceVariantSummary,
                    )
                }
            }

            SectionCard(metrics, title = "信息") {
                Text(
                    "启用的 bot：$botCount 个",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = PanelColors.onSurface,
                )
                Text(
                    "凭据与人格在「Bot」页管理；多个 bot 可以同时在线",
                    fontSize = 12.sp,
                    color = PanelColors.onSurfaceVariantSummary,
                )
                Text(
                    BotHost.dataDir(ctx).absolutePath,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = PanelColors.onSurfaceVariantSummary,
                )
            }

            if (ModeStore.initial(ctx) == RunMode.ROOT) {
                RootCard(metrics)
            }
            if (ModeStore.initial(ctx) == RunMode.ADB) {
                AdbCard(metrics)
            }

                    }
    }
}

/** root 模式专属：电池白名单（开机自启已改由 BOOT_COMPLETED 负责，不再依赖 root） */
@Composable
private fun RootCard(metrics: Metrics) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rootOk by remember { mutableStateOf<Boolean?>(null) }
    var wlOk by remember { mutableStateOf(false) }
    var rootMsg by remember { mutableStateOf("") }
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

    SectionCard(
        metrics,
        title = "root 托管",
        hint = when (rootOk) {
            null -> "检测中…"
            true -> "已获得 root（uid 0）"
            false -> "没有拿到 root 权限"
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(
                when (rootOk) {
                    true -> Tokens.Ok
                    false -> Tokens.Bad
                    null -> Tokens.Idle
                }
            )
            Text(
                if (wlOk) "电池白名单：已加入" else "电池白名单：未加入",
                fontSize = 14.sp,
                modifier = Modifier.padding(start = 8.dp),
                color = PanelColors.onSurface,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionChip("加白名单", enabled = !busy && rootOk != false) {
                busy = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) { RootHost.whitelist(ctx.packageName) }
                    rootMsg = r.out
                    wlOk = withContext(Dispatchers.IO) { RootHost.isWhitelisted(ctx.packageName) }
                    busy = false
                }
            }
            ActionChip("移出", enabled = !busy) {
                busy = true
                scope.launch {
                    withContext(Dispatchers.IO) { RootHost.unwhitelist(ctx.packageName) }
                    wlOk = withContext(Dispatchers.IO) { RootHost.isWhitelisted(ctx.packageName) }
                    rootMsg = "已移出白名单"
                    busy = false
                }
            }
        }
        if (rootMsg.isNotBlank()) {
            Text(
                rootMsg,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = PanelColors.onSurfaceVariantSummary,
            )
        }
    }
}

/**
 * ADB 模式专属：靠 Shizuku 拿 shell 权限做「后台豁免 + 掉了能拉回」。
 * 注意 bot 仍跑在 App 进程内 —— Python 在 APK 里，shell 没法单独拉起它。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdbCard(metrics: Metrics) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var shizukuOk by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(false) }
    var wlOk by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }

    // Shizuku 的授权回调统一在 ShizukuShell 里注册：页面只读状态 + 调 requestPermission，不再自己挂监听
    LaunchedEffect(tick) {
        busy = true
        val st = withContext(Dispatchers.IO) { ShizukuShell.state(ctx) }
        shizukuOk = st != ShizukuState.NOT_RUNNING
        granted = st == ShizukuState.GRANTED
        busy = false
    }

    SectionCard(
        metrics,
        title = "ADB 托管（Shizuku）",
        hint = when {
            busy -> "检测中…"
            !shizukuOk -> "Shizuku 没在运行"
            !granted -> "Shizuku 在运行，本 App 未授权"
            else -> "Shizuku v${AdbHost.version()}，已授权"
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(
                when {
                    shizukuOk && granted -> Tokens.Ok
                    shizukuOk -> Tokens.Idle
                    else -> Tokens.Bad
                }
            )
            Text(
                when {
                    !shizukuOk -> "Shizuku 未运行（先装 Shizuku 并用无线调试授权它）"
                    !granted -> "Shizuku 在运行，待本 App 授权"
                    else -> "已授权，可以做后台豁免"
                },
                fontSize = 14.sp,
                modifier = Modifier.padding(start = 8.dp),
                color = PanelColors.onSurface,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionChip("授权", enabled = !busy) {
                // 一次走完「申请 → 绑定」：没授权/没绑上都把可读原因写进 msg
                busy = true
                msg = "正在申请 Shizuku 授权…"
                ShizukuShell.ensureAuthorized(ctx) { r ->
                    busy = false
                    shizukuOk = r.auth != ShizukuAuth.NOT_RUNNING
                    granted = r.auth == ShizukuAuth.GRANTED
                    msg = if (r.ok) r.text else r.text + "。下一步：" + r.hint
                    tick++
                }
            }
            ActionChip("连接 shell", enabled = !busy && granted) {
                busy = true
                scope.launch {
                    withContext(Dispatchers.IO) {
                        com.wjljh.andbna.local.ShizukuShell.bind(ctx.packageName)
                    }
                    delay(1200)
                    val bound = com.wjljh.andbna.local.ShizukuShell.bound
                    msg = if (bound) "UserService 已连上（bound=true）"
                    else "UserService 没连上（bound=false）；" +
                        com.wjljh.andbna.local.ShizukuShell.lastError.ifBlank { "需 Shizuku v13+ 且已授权" }
                    busy = false
                    tick++
                }
            }
            ActionChip("加白名单", enabled = !busy && com.wjljh.andbna.local.ShizukuShell.bound) {
                busy = true
                scope.launch {
                    msg = withContext(Dispatchers.IO) {
                        com.wjljh.andbna.local.ShizukuShell.whitelist(ctx.packageName)
                    }.ifBlank { "已执行" }
                    wlOk = withContext(Dispatchers.IO) {
                        com.wjljh.andbna.local.ShizukuShell.isWhitelisted(ctx.packageName)
                    }
                    busy = false
                    tick++
                }
            }
            ActionChip("重新检测", enabled = !busy) { tick++ }
        }
        if (msg.isNotBlank()) {
            Text(
                msg,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = PanelColors.onSurfaceVariantSummary,
            )
        }
    }
}

/** 极简 prefs 封装 */
object Prefs {
    private fun sp(ctx: Context) =
        ctx.getSharedPreferences("andbna_local", Context.MODE_PRIVATE)

    fun get(ctx: Context, key: String): String =
        sp(ctx).getString(key, "") ?: ""

    fun put(ctx: Context, key: String, value: String) {
        sp(ctx).edit().putString(key, value.trim()).apply()
    }
}
