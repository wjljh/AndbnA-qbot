package com.wjljh.andbna.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.wjljh.andbna.ModelCatalog
import com.wjljh.andbna.local.BotStore
import com.wjljh.andbna.local.ModeStore
import com.wjljh.andbna.local.RootHost
import com.wjljh.andbna.local.RunMode
import com.wjljh.andbna.local.SecureStore
import com.wjljh.andbna.local.ShizukuShell
import com.wjljh.andbna.local.ShizukuState
import com.wjljh.andbna.ui.component.PanelChoiceRow
import com.wjljh.andbna.ui.component.PanelEllipsisField
import com.wjljh.andbna.ui.component.PanelGroupCard
import com.wjljh.andbna.ui.component.PanelNotice
import com.wjljh.andbna.ui.component.PanelPillRow
import com.wjljh.andbna.ui.component.PanelPrimaryButton
import com.wjljh.andbna.ui.component.PanelSecondaryButton
import com.wjljh.andbna.ui.component.PanelTokens
import com.wjljh.andbna.ui.component.panelStepTransition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Community
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Timer
import top.yukonga.miuix.kmp.icon.extended.Tune

/**
 * 首次使用引导（6 步）。同一版只强制走一次，判定见 OnboardStore.required。
 *
 * 状态机是一个 Int：0 欢迎 → 1 @ 规则说明（不阻塞，点「知道了」就往下走）
 * → 2 选保活方式 → 3 真检测 → 4 模型配置（可跳过）→ 5 完成。
 * 「跳过引导」与「开始使用」都只是回调 [onFinish]（由 App.kt 写 onboarded=true 再回主页），
 * 所以引导本身不落任何盘，模式走 ModeStore.set、模型走 llm_api/llm_key/llm_model 同一套键。
 *
 * 第 ④ 步不是动画：按第 ③ 步选的方式真去问系统 ——
 *  ADB → ShizukuShell.bind(pkg) + ShizukuShell.isWhitelisted(pkg)（真连 UserService 跑 dumpsys）
 *  root → RootHost.checkRoot() + RootHost.isWhitelisted(pkg)（真 su 一次再查白名单）
 *  普通 → 不需要额外权限（显式写明，不假检测）
 * 三态：通过 / 未通过 / 未检测，未通过都带一句「下一步做什么」，并且可以「重新检测」。
 *
 * 版式（只换视觉，逻辑与文案事实不变）：
 *  · 顶上 3dp 细进度条，宽度 animateFloatAsState(spring(StiffnessLow))；
 *  · 每步「大图标 + 27sp 大标题 + 15sp/22sp 说明」的 hero 区，上下各 24dp；
 *  · 步骤切换走 AnimatedContent，方向由 targetState 与 initialState 比较：
 *    slideInHorizontally(±宽/6) + fadeIn / slideOutHorizontally(∓宽/6) + fadeOut，tween(300)；
 *  · 第 ④ 步结果卡三态配色，卡高变化 animateContentSize(spring)，
 *    原始返回值收进默认收起的「详细信息」（PanelNotice mono）；
 *  · 第 ⑥ 步一张三行小结卡（保活方式 / 电池白名单 / 模型），行首通过或不通过的图标。
 */
private const val STEPS = 6
private val STEP_TITLES = listOf("欢迎", "@ 规则", "保活方式", "权限检测", "模型配置", "完成")

/** 每一步 hero 的图形与三态图标：全部取自 MiuixIcons，顶层建一次（不在 Composable 里现场构造 Vector）。 */
private object OnboardIcons {
    val Welcome: ImageVector = MiuixIcons.Community
    val AtRule: ImageVector = MiuixIcons.Messages
    val KeepAlive: ImageVector = MiuixIcons.Timer
    val Probe: ImageVector = MiuixIcons.Lock
    val Model: ImageVector = MiuixIcons.Tune
    val Done: ImageVector = MiuixIcons.Ok
    val Pass: ImageVector = MiuixIcons.Ok
    val Fail: ImageVector = MiuixIcons.Close
    val Unknown: ImageVector = MiuixIcons.Info
    val Expand: ImageVector = MiuixIcons.ChevronForward
}

/** 检测三态 */
private enum class ProbeState(val label: String) {
    NOT_TESTED("未检测"),
    PASS("通过"),
    FAIL("未通过"),
}

/**
 * 通知权限三态（引导第 ④ 步底部的「通知权限」块）。
 * API < 33 没有这个运行时权限，如实写「系统不需要单独授权」，不假装检测过。
 */
private enum class NotifState(val label: String) {
    GRANTED("已授予"),
    DENIED("未授予"),
    NOT_NEEDED("系统不需要"),
}

private fun readNotifState(ctx: Context): NotifState {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return NotifState.NOT_NEEDED
    val granted = ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    return if (granted) NotifState.GRANTED else NotifState.DENIED
}

/** 跳系统通知设置页：拒绝过之后不再弹系统框，改成用户自己在这里打开 */
private fun openNotifSettings(ctx: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val ok = runCatching { ctx.startActivity(intent) }.isSuccess
    if (!ok) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

/** 一次检测的结果：结论 + 证据（调用点与原始返回）+ 下一步 */
private class ProbeResult(
    val state: ProbeState = ProbeState.NOT_TESTED,
    val headline: String = "还没检测",
    val detail: String = "",
    val hint: String = "",
)

@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val metrics = rememberMetrics()

    var step by remember { mutableIntStateOf(0) }
    // 第 ③ 步选的方式：默认取已落盘的值（没有就按 root 探测结果，与 ModeStore.initial 一致）
    var mode by remember { mutableStateOf(ModeStore.get(ctx) ?: ModeStore.initial(ctx)) }

    var probe by remember { mutableStateOf(ProbeResult()) }
    // 第 ③ 步选 ADB 时的一句话说明（ADB 要等第 ④ 步授权+绑定成功才落盘）
    var modeNotice by remember { mutableStateOf("") }
    var detecting by remember { mutableStateOf(false) }

    val vm = remember { ModelStepVm(ctx) }

    // ③-b 通知权限（被动申请）：三态，拒绝也不阻塞 —— 下一步照走
    var notif by remember { mutableStateOf(readNotifState(ctx)) }
    // 弹过一次系统框（无论允许还是拒绝）之后，按钮改成「去系统设置开启」，不再反复弹
    var notifAsked by remember { mutableStateOf(false) }
    // 从系统设置页回来时重读一次，状态跟着变
    LifecycleResumeEffect(Unit) {
        notif = readNotifState(ctx)
        onPauseOrDispose { }
    }
    val askNotif = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notifAsked = true
        notif = if (granted) NotifState.GRANTED else NotifState.DENIED
    }

    fun detect() {
        if (detecting) return
        detecting = true
        probe = ProbeResult(ProbeState.NOT_TESTED, "正在检测…")
        scope.launch {
            val r = withContext(Dispatchers.IO) { runProbe(ctx, mode) }
            probe = r
            detecting = false
        }
    }

    /**
     * 进 ADB 模式的完整流程：授权（没授权就弹 Shizuku 自己的确认框）→ 立刻绑定 →
     * 只有**绑定成功**才把模式落盘为 ADB（ShizukuShell.switchToAdb 里写）；
     * 没成则模式保持不变，三种结果都写进卡片，原因 + 下一步都能照着做。
     */
    fun requestShizuku() {
        if (detecting) return
        probe = ProbeResult(
            ProbeState.NOT_TESTED,
            "正在申请 Shizuku 授权…",
            hint = "弹出的确认框点「允许」，结果回来会自动重新检测。",
        )
        ShizukuShell.switchToAdb(ctx) { r ->
            if (r.ok) {
                mode = RunMode.ADB
                detect()
            } else {
                probe = ProbeResult(
                    ProbeState.FAIL,
                    "没进 ADB 模式：" + r.auth.label,
                    r.text + "；state=" + ShizukuShell.state().name,
                    r.hint + "。弄好以后回来点「申请 Shizuku 授权」；下面「用电脑 adb」那栏的命令可以直接复制。",
                )
            }
        }
    }

    /** 未通过时的手动补救：真去加电池白名单，加完重检一次 */
    fun grantWhitelist() {
        if (detecting) return
        detecting = true
        probe = ProbeResult(ProbeState.NOT_TESTED, "正在写入电池白名单…")
        scope.launch {
            withContext(Dispatchers.IO) {
                val pkg = ctx.packageName
                when (mode) {
                    RunMode.ROOT -> RootHost.whitelist(pkg)
                    RunMode.ADB -> ShizukuShell.whitelist(pkg)
                    RunMode.NORMAL -> Unit
                }
            }
            detecting = false
            detect()
        }
    }

    // 进到「权限检测」那一步（或换了模式再回来）就真检测一次
    LaunchedEffect(step, mode) {
        if (step == 3) detect()
    }

    fun next() {
        if (step == 4) vm.save(ctx)
        if (step < STEPS - 1) step += 1 else onFinish()
    }

    // 第 ② 步（@ 规则）这页没别的动作，底部那颗主按钮就直接说「知道了」，点了照常下一页
    val primaryLabel = when (step) {
        1 -> "知道了"
        STEPS - 1 -> "开始使用"
        else -> "下一步"
    }

    Scaffold(
        containerColor = PanelColors.surface,
        topBar = { OnboardHeader(step = step, onSkip = onFinish) },
        bottomBar = {
            // 这里用 Row 而不是 PanelPillRow：Arrangement.spacedBy 的间隔不会跟着「上一步」一起收起，
            // 第 1 步主按钮就不是满宽了。间距改由按钮外侧的 padding 给，跟 AnimatedVisibility 一起消失。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = PanelTokens.PageHorizontal, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimatedVisibility(
                    visible = step > 0,
                    enter = fadeIn(tween(PanelTokens.OnboardAnimMs)),
                    exit = fadeOut(tween(PanelTokens.OnboardAnimMs)),
                ) {
                    Box(Modifier.padding(end = PanelTokens.ButtonGap)) {
                        PanelSecondaryButton(text = "上一步", onClick = { if (step > 0) step -= 1 })
                    }
                }
                PanelPrimaryButton(
                    text = primaryLabel,
                    modifier = Modifier.weight(1f),
                    onClick = { next() },
                )
            }
        },
    ) { pad ->
        AnimatedContent(
            targetState = step,
            transitionSpec = { onboardStepTransition() },
            modifier = Modifier.fillMaxSize().padding(pad),
            label = "onboard-step",
        ) { s ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = PanelTokens.PageHorizontal)
                    .padding(bottom = PanelTokens.BottomBarSafetyGap),
                verticalArrangement = Arrangement.spacedBy(PanelTokens.CardSpacing),
            ) {
                when (s) {
                    0 -> WelcomeStep()
                    1 -> AtRuleStep()
                    2 -> ModeStep(current = mode, notice = modeNotice) { picked ->
                        probe = ProbeResult()
                        // ADB 只是「打算用」：模式要不要真落盘由第 ④ 步的 ShizukuShell.switchToAdb
                        // 决定（授权 + 绑定都成功才写），这里写了就是没权限也自称 ADB 的半死状态
                        if (picked == RunMode.ADB) {
                            mode = picked
                            modeNotice = "ADB 模式要先拿到 Shizuku 授权：第 ④ 步点「申请 Shizuku 授权」，" +
                                "授权并绑定成功后才真正切过去；没成会保持原来的模式。"
                        } else {
                            ModeStore.set(ctx, picked)
                            mode = picked
                            modeNotice = ""
                        }
                    }
                    3 -> ProbeStep(
                        mode = mode,
                        probe = probe,
                        detecting = detecting,
                        notif = notif,
                        notifAsked = notifAsked,
                        onRetest = { detect() },
                        onWhitelist = { grantWhitelist() },
                        onRequestPermission = { requestShizuku() },
                        onAskNotif = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                notif = NotifState.NOT_NEEDED
                            }
                        },
                        onOpenNotifSettings = { openNotifSettings(ctx) },
                    )
                    4 -> ModelStep(
                        metrics = metrics,
                        vm = vm,
                        scope = scope,
                        onSkip = { step = STEPS - 1 },
                    )
                    else -> DoneStep(mode = mode, probe = probe, modelConfigured = vm.configured)
                }
            }
        }
    }
}

// ── 顶部：3dp 细进度条 + 第 N / 6 步 · 名字 + 跳过 ──────────────────────

@Composable
private fun OnboardHeader(step: Int, onSkip: () -> Unit) {
    // 进度 = (step + 1) / 5，低刚度 spring：走一步条子慢慢推过去，不弹
    val progress by animateFloatAsState(
        targetValue = (step + 1).toFloat() / STEPS.toFloat(),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "onboard-progress",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(
                start = PanelTokens.PageHorizontal,
                end = PanelTokens.PageHorizontal,
                top = PanelTokens.OnboardProgressTop,
                bottom = PanelTokens.OnboardHeaderBottom,
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(PanelTokens.OnboardProgressHeight)
                .clip(CircleShape)
                .background(PanelColors.surfaceContainerHigh),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(PanelColors.primary),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = PanelTokens.OnboardProgressGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "第 " + (step + 1) + " / " + STEPS + " 步 · " + STEP_TITLES[step],
                fontSize = PanelTokens.OnboardStepLabelSize,
                color = PanelColors.onSurfaceVariantSummary,
                modifier = Modifier.weight(1f),
            )
            // 跳过 = 直接记 onboarded 进主页（设置里还能「重看引导」）
            Text(
                text = "跳过",
                fontSize = PanelTokens.OnboardSkipSize,
                color = PanelColors.onSurfaceVariantSummary,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onSkip)
                    .padding(
                        horizontal = PanelTokens.OnboardSkipPaddingH,
                        vertical = PanelTokens.OnboardSkipPaddingV,
                    ),
            )
        }
    }
}

// ── 方向感：前进往左推、后退往右推，各走 1/6 屏宽 ────────────────────────

private fun AnimatedContentTransitionScope<Int>.onboardStepTransition(): ContentTransform =
    // 过渡本体在 component/PanelTransition.kt：群配置的二级页用的是同一份（别自造第二套）
    panelStepTransition(forward = targetState > initialState)

// ── hero：大图标 + 大标题 + 说明，上下各 24dp ─────────────────────────────

@Composable
private fun StepHero(icon: ImageVector, title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = PanelTokens.OnboardHeroTop, bottom = PanelTokens.OnboardHeroBottom),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = PanelColors.primary,
            modifier = Modifier.size(PanelTokens.OnboardHeroIcon),
        )
        Text(
            text = title,
            fontSize = PanelTokens.OnboardHeroTitleSize,
            lineHeight = PanelTokens.OnboardHeroTitleLineHeight,
            fontWeight = FontWeight.SemiBold,
            color = PanelColors.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = PanelTokens.OnboardHeroGap),
        )
        if (body.isNotBlank()) {
            Text(
                text = body,
                fontSize = PanelTokens.OnboardHeroBodySize,
                lineHeight = PanelTokens.OnboardHeroBodyLineHeight,
                color = PanelColors.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = PanelTokens.OnboardHeroBodyGap),
            )
        }
    }
}

// ── ① 欢迎：不讲步骤，只讲这个 App 是干什么的 ────────────────────────

/** 欢迎页的三条简介（标题 to 说明），不再列「第几步做什么」的清单。 */
private val WELCOME_POINTS = listOf(
    "QQ 机器人跑在本机" to "Python 运行时打包在 App 内，不依赖模块，也不用开电脑",
    "不收集任何数据" to "聊天与凭据只存在本机，App 不上报任何内容",
    "多个 bot 一起挂" to "人格、记忆、定时消息按 bot 各自独立",
)

@Composable
private fun ColumnScope.WelcomeStep() {
    StepHero(
        icon = OnboardIcons.Welcome,
        title = "欢迎使用 AndbnA",
        body = "这是一个把 QQ 机器人整套跑在手机里的 App：Python 运行时打包在 APK 内，由本机前台服务托管，不依赖模块，也不收集任何数据。",
    )
    PanelGroupCard {
        WELCOME_POINTS.forEachIndexed { index, point ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (index == 0) 0.dp else PanelTokens.ContentGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = OnboardIcons.Pass,
                    contentDescription = null,
                    tint = PanelColors.primary,
                    modifier = Modifier.size(PanelTokens.OnboardDetailGlyph),
                )
                Column(modifier = Modifier.padding(start = PanelTokens.InfoIconGap)) {
                    Text(
                        text = point.first,
                        fontSize = PanelTokens.OnboardListTitleSize,
                        fontWeight = FontWeight.Medium,
                        color = PanelColors.onBackground,
                    )
                    Text(
                        text = point.second,
                        fontSize = PanelTokens.OnboardListNoteSize,
                        lineHeight = PanelTokens.OnboardHeroBodyLineHeight,
                        color = PanelColors.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = PanelTokens.InfoValueGap),
                    )
                }
            }
        }
    }
    Text(
        text = "随时可以在「设置 → 重看使用引导」里重看本引导。",
        fontSize = PanelTokens.OnboardWelcomeSize,
        lineHeight = PanelTokens.OnboardHeroBodyLineHeight,
        color = PanelColors.onSurfaceVariantSummary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = PanelTokens.ContentGap),
    )
}

// ── ② @ 规则说明：群里默认只回 @，两个开关各在哪（不阻塞，点「知道了」就走） ────

/**
 * 用户反馈「bot 必须 @ 才回」：那是设计（群里「自动接话」默认关），但入口藏得深。
 * 这页只讲清楚默认行为和两个开关的位置，不替用户改任何设置 —— 点「知道了」就下一页。
 */
private val AT_RULE_POINTS = listOf(
    "群里默认只有 @ 它时才回" to
        "不 @ 就不出声，免得刷屏 —— 不是坏了，默认就是这样。",
    "想让它自己判断要不要接话" to
        "Bot → 编辑 → 打开「群里自动接话」",
    "只想让某些群活跃" to
        "Bot → 编辑 → 群配置 → 单独给那个群开，别的群保持安静。",
)

@Composable
private fun ColumnScope.AtRuleStep() {
    StepHero(
        icon = OnboardIcons.AtRule,
        title = "群聊里它默认只回 @",
        body = "群里被 @ 才开口，这是默认行为；要不要它自己接话，开关在 bot 编辑页里。",
    )
    PanelGroupCard {
        AT_RULE_POINTS.forEachIndexed { index, point ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (index == 0) 0.dp else PanelTokens.ContentGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = OnboardIcons.Pass,
                    contentDescription = null,
                    tint = PanelColors.primary,
                    modifier = Modifier.size(PanelTokens.OnboardDetailGlyph),
                )
                Column(modifier = Modifier.padding(start = PanelTokens.InfoIconGap)) {
                    Text(
                        text = point.first,
                        fontSize = PanelTokens.OnboardListTitleSize,
                        fontWeight = FontWeight.Medium,
                        color = PanelColors.onBackground,
                    )
                    Text(
                        text = point.second,
                        fontSize = PanelTokens.OnboardListNoteSize,
                        lineHeight = PanelTokens.OnboardHeroBodyLineHeight,
                        color = PanelColors.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = PanelTokens.InfoValueGap),
                    )
                }
            }
        }
    }
    Text(
        text = "点「知道了」继续，这两处开关随时能在 Bot 编辑页里改。",
        fontSize = PanelTokens.OnboardWelcomeSize,
        lineHeight = PanelTokens.OnboardHeroBodyLineHeight,
        color = PanelColors.onSurfaceVariantSummary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = PanelTokens.ContentGap),
    )
}

// ── ③ 选保活方式 ──────────────────────────────────────────────────────

@Composable
private fun ColumnScope.ModeStep(
    current: RunMode,
    notice: String = "",
    onPick: (RunMode) -> Unit,
) {
    StepHero(
        icon = OnboardIcons.KeepAlive,
        title = "挑一种保活方式",
        body = "运行内核三种模式完全一样（Python 打包在 APK 里，bot 跑在 App 进程内），差别只在于有没有额外保活权限。",
    )
    if (notice.isNotBlank()) {
        PanelNotice(text = notice)
    }
    PanelGroupCard {
        RunMode.entries.forEachIndexed { index, m ->
            PanelChoiceRow(
                title = m.label,
                subtitle = m.summary,
                selected = current == m,
                divider = index != RunMode.entries.lastIndex,
                onClick = { onPick(m) },
            )
        }
    }
    PanelGroupCard {
        Text(
            text = "为什么这样选",
            fontSize = PanelTokens.OnboardSummaryLabelSize,
            color = PanelColors.onSurfaceVariantSummary,
        )
        Text(
            text = current.detail,
            fontSize = PanelTokens.OnboardListTitleSize,
            lineHeight = PanelTokens.OnboardHeroBodyLineHeight,
            color = PanelColors.onBackground,
            modifier = Modifier.padding(top = PanelTokens.InfoValueGap),
        )
    }
}

// ── ④ 真检测 ──────────────────────────────────────────────────────────

@Composable
private fun stateTint(state: ProbeState): Color = when (state) {
    ProbeState.PASS -> PanelColors.primary
    ProbeState.FAIL -> PanelColors.error
    ProbeState.NOT_TESTED -> PanelColors.onSurfaceVariantSummary
}

private fun stateIcon(state: ProbeState): ImageVector = when (state) {
    ProbeState.PASS -> OnboardIcons.Pass
    ProbeState.FAIL -> OnboardIcons.Fail
    ProbeState.NOT_TESTED -> OnboardIcons.Unknown
}

@Composable
private fun ColumnScope.ProbeStep(
    mode: RunMode,
    probe: ProbeResult,
    detecting: Boolean,
    notif: NotifState,
    notifAsked: Boolean,
    onRetest: () -> Unit,
    onWhitelist: () -> Unit,
    onRequestPermission: () -> Unit,
    onAskNotif: () -> Unit,
    onOpenNotifSettings: () -> Unit,
) {
    StepHero(
        icon = OnboardIcons.Probe,
        title = "权限检测",
        body = "按第 ③ 步选的方式，真的去问系统要一次结论 —— 原始返回值都留着，不是预估。",
    )
    var detailOpen by remember { mutableStateOf(false) }
    val tint = stateTint(probe.state)

    // 三态换色 + 卡高变化都平滑：换状态时卡片自己 animateContentSize 长高/缩短
    PanelGroupCard(
        modifier = Modifier.animateContentSize(
            animationSpec = spring(stiffness = Spring.StiffnessLow),
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(PanelTokens.OnboardStateIcon)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = PanelTokens.OnboardStateTintAlpha)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = stateIcon(probe.state),
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(PanelTokens.OnboardStateGlyph),
                )
            }
            Column(modifier = Modifier.padding(start = PanelTokens.InfoIconGap)) {
                Text(
                    text = probe.state.label,
                    fontSize = PanelTokens.InfoTitleSize,
                    fontWeight = FontWeight.Medium,
                    color = tint,
                )
                Text(
                    text = "当前方式：" + mode.label,
                    fontSize = PanelTokens.OnboardListNoteSize,
                    color = PanelColors.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = PanelTokens.InfoValueGap),
                )
            }
        }
        Text(
            text = probe.headline,
            fontSize = PanelTokens.OnboardSummaryValueSize,
            lineHeight = PanelTokens.OnboardHeroBodyLineHeight,
            color = PanelColors.onBackground,
            modifier = Modifier.padding(top = PanelTokens.ContentGap),
        )
        if (probe.hint.isNotBlank()) {
            Text(
                text = "下一步：" + probe.hint,
                fontSize = PanelTokens.OnboardListNoteSize,
                lineHeight = PanelTokens.OnboardHeroBodyLineHeight,
                color = PanelColors.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = PanelTokens.InfoValueGap * 3),
            )
        }
        // 原始返回值仍然原样显示，只是默认收起来，避免一屏全是等宽文本
        if (probe.detail.isNotBlank()) {
            // 收起 = 箭头朝下（90°），展开 = 朝上（270°），角度自己转过去
            val arrow by animateFloatAsState(
                targetValue = if (detailOpen) 270f else 90f,
                animationSpec = tween(PanelTokens.OnboardAnimMs),
                label = "onboard-detail-arrow",
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = PanelTokens.ContentGap)
                    .clip(CircleShape)
                    .clickable { detailOpen = !detailOpen }
                    .padding(vertical = PanelTokens.OnboardDetailGlyph / 2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "详细信息",
                    fontSize = PanelTokens.OnboardDetailToggleSize,
                    color = PanelColors.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = OnboardIcons.Expand,
                    contentDescription = null,
                    tint = PanelColors.onSurfaceVariantSummary,
                    modifier = Modifier
                        .size(PanelTokens.OnboardDetailGlyph)
                        .rotate(arrow),
                )
            }
            AnimatedVisibility(
                visible = detailOpen,
                enter = fadeIn(tween(PanelTokens.OnboardAnimMs)) +
                    expandVertically(tween(PanelTokens.OnboardAnimMs)),
                exit = fadeOut(tween(PanelTokens.OnboardAnimMs)) +
                    shrinkVertically(tween(PanelTokens.OnboardAnimMs)),
            ) {
                Box(Modifier.padding(top = PanelTokens.ButtonGap)) {
                    PanelNotice(
                        text = probe.detail,
                        isError = probe.state == ProbeState.FAIL,
                        mono = true,
                    )
                }
            }
        }
    }
    // ADB 模式才有 Shizuku 授权可申请：主按钮主动弹确认框，不用等系统自己想起来
    if (mode == RunMode.ADB) {
        PanelPillRow(modifier = Modifier.padding(top = PanelTokens.ButtonRowTopGap)) {
            PanelPrimaryButton(
                text = "申请 Shizuku 授权",
                modifier = Modifier.weight(1f),
                enabled = !detecting,
                onClick = onRequestPermission,
            )
        }
        // 电脑 adb 那一栏：Shizuku 还没起来时靠它启动服务 + 加白名单，只在 ADB 模式出现
        AdbCommandCard()
    }
    PanelPillRow {
        PanelPrimaryButton(
            text = if (detecting) "检测中…" else "重新检测",
            modifier = Modifier.weight(1f),
            enabled = !detecting,
            onClick = onRetest,
        )
        // 普通模式没有白名单可加，这一颗只在 ADB / root 下出现（功能事实不变）
        if (mode != RunMode.NORMAL) {
            PanelSecondaryButton(
                text = "加入电池白名单",
                modifier = Modifier.weight(1f),
                enabled = !detecting,
                onClick = onWhitelist,
            )
        }
    }
    NotifPermCard(
        state = notif,
        asked = notifAsked,
        onAsk = onAskNotif,
        onOpenSettings = onOpenNotifSettings,
    )
}

/**
 * 第 ④ 步的「用电脑 adb」栏（只在 ADB 模式显示）：Shizuku 没跑起来时用电脑把服务启动起来，
 * 顺手把本应用加进电池白名单。
 *
 * 包名取 ctx.packageName —— release 是 com.wjljh.andbna.qbot，debug 是另一个包名，
 * 写死一个的话另一个变体复制出去就是错命令。
 */
@Composable
private fun ColumnScope.AdbCommandCard() {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val cmd = "adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh\n" +
        "adb shell dumpsys deviceidle whitelist +" + ctx.packageName
    PanelGroupCard {
        Text(
            text = "用电脑 adb 授权 / 保活",
            fontSize = PanelTokens.InfoTitleSize,
            fontWeight = FontWeight.Medium,
            color = PanelColors.onBackground,
        )
        Box(Modifier.padding(top = PanelTokens.ContentGap)) {
            PanelNotice(text = cmd, mono = true)
        }
        PanelPillRow(
            modifier = Modifier.padding(top = PanelTokens.ButtonRowTopGap),
            fillWidth = false,
        ) {
            PanelSecondaryButton(
                text = "复制全部",
                onClick = {
                    clipboard.setText(AnnotatedString(cmd))
                    copied = true
                },
            )
            if (copied) {
                Text(
                    text = "已复制",
                    fontSize = PanelTokens.OnboardListNoteSize,
                    color = PanelColors.primary,
                )
            }
        }
        Text(
            text = "建议使用 Shizuku 13.7 及以上版本（旧版在 Android 17 上服务会自行退出）",
            fontSize = PanelTokens.OnboardListNoteSize,
            lineHeight = PanelTokens.OnboardHeroBodyLineHeight,
            color = PanelColors.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = PanelTokens.InfoValueGap * 3),
        )
        // 两条命令各自干什么，压成一行小字（原来的长句已按需求删掉）
        Text(
            text = "第一条启动 Shizuku 服务（手机重启后要再来一次）；第二条加电池白名单。",
            fontSize = PanelTokens.OnboardListNoteSize,
            color = PanelColors.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = PanelTokens.InfoValueGap),
        )
    }
}

// ── ④-b 通知权限（被动申请，不阻塞流程）────────────────────────────────

/** 拒绝之后显示的声明：两句话，说清「只用来显示运行状态、关掉不影响 bot」。 */
private const val NOTIF_STATEMENT =
    "通知只用来显示「bot 运行中」状态，方便你确认服务还在跑；关掉不影响 bot 工作，可在系统设置里随时改。"

/**
 * 引导里的「通知权限」块。
 *
 * 被动申请：先摆出理由和状态，用户点「允许通知」才弹系统框（Android 11+ 权限最佳实践是
 * 先给理由再申请，而不是一进页面就弹）。拒绝后按钮改成「去系统设置开启」，不再反复弹框。
 */
@Composable
private fun ColumnScope.NotifPermCard(
    state: NotifState,
    asked: Boolean,
    onAsk: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val tint = when (state) {
        NotifState.GRANTED -> PanelColors.primary
        NotifState.NOT_NEEDED -> PanelColors.onSurfaceVariantSummary
        NotifState.DENIED -> PanelColors.error
    }
    val glyph = when (state) {
        NotifState.GRANTED -> OnboardIcons.Pass
        NotifState.NOT_NEEDED -> OnboardIcons.Unknown
        NotifState.DENIED -> OnboardIcons.Fail
    }
    PanelGroupCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(PanelTokens.OnboardStateIcon)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = PanelTokens.OnboardStateTintAlpha)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = glyph,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(PanelTokens.OnboardStateGlyph),
                )
            }
            Column(modifier = Modifier.padding(start = PanelTokens.InfoIconGap)) {
                Text(
                    text = "通知权限",
                    fontSize = PanelTokens.InfoTitleSize,
                    fontWeight = FontWeight.Medium,
                    color = PanelColors.onBackground,
                )
                Text(
                    text = state.label,
                    fontSize = PanelTokens.OnboardListNoteSize,
                    color = tint,
                    modifier = Modifier.padding(top = PanelTokens.InfoValueGap),
                )
            }
        }
        val note = when (state) {
            NotifState.GRANTED -> "运行时通知栏会显示「bot 运行中」，方便确认服务还在跑。"
            NotifState.NOT_NEEDED -> "当前系统不需要单独授权：低于 Android 13，通知默认可用。"
            NotifState.DENIED -> if (asked) {
                NOTIF_STATEMENT
            } else {
                "运行时会在通知栏挂一条「bot 运行中」，方便你确认服务还在跑。"
            }
        }
        Text(
            text = note,
            fontSize = PanelTokens.OnboardListNoteSize,
            lineHeight = PanelTokens.OnboardHeroBodyLineHeight,
            color = PanelColors.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = PanelTokens.ContentGap),
        )
    }
    // 已授予 / 系统不需要就没什么可点的；未授予才给按钮（第一次「允许通知」，拒绝过之后去系统设置）
    if (state == NotifState.DENIED) {
        PanelPillRow(modifier = Modifier.padding(top = PanelTokens.ButtonRowTopGap)) {
            PanelPrimaryButton(
                text = if (asked) "去系统设置开启" else "允许通知",
                modifier = Modifier.weight(1f),
                onClick = if (asked) onOpenSettings else onAsk,
            )
        }
    }
}

// ── ⑤ 模型配置（可跳过）────────────────────────────────────────────────

/** 与 ModelConfigScreen 写的是同一套键（llm_api / llm_key / llm_model），识图模型不在这里碰。 */
private class ModelStepVm(ctx: Context) {
    var api by mutableStateOf(OnboardModelPrefs.read(ctx, "llm_api"))
    var key by mutableStateOf(OnboardModelPrefs.read(ctx, "llm_key"))
    var model by mutableStateOf(OnboardModelPrefs.read(ctx, "llm_model"))
    var models by mutableStateOf<List<String>>(emptyList())
    var reading by mutableStateOf(false)
    var msg by mutableStateOf("")

    /** ⑤ 小结用：Key 是「配没配」的唯一判据（与设置页 llmSummary 一致） */
    val configured: Boolean get() = key.isNotBlank()

    fun read(scope: CoroutineScope) {
        if (api.isBlank() || key.isBlank()) {
            msg = "先填「接口地址」和「API Key」，再读取模型"
            return
        }
        reading = true
        msg = ""
        scope.launch {
            val out = withContext(Dispatchers.IO) { ModelCatalog.fetch(api, key) }
            reading = false
            if (out.ok) {
                models = out.models
                msg = "读到 " + out.models.size + " 个模型，点一个填进主模型"
            } else {
                models = emptyList()
                msg = "读取模型失败：" + out.error
            }
        }
    }

    /** 离开这一步时落盘（三个框全空 = 用户什么都没填，不写） */
    fun save(ctx: Context) {
        if (api.isBlank() && key.isBlank() && model.isBlank()) return
        OnboardModelPrefs.save(ctx, api, key, model)
        msg = "已保存：接口地址 / API Key / 主模型"
    }
}

@Composable
private fun ColumnScope.ModelStep(
    metrics: Metrics,
    vm: ModelStepVm,
    scope: CoroutineScope,
    onSkip: () -> Unit,
) {
    StepHero(
        icon = OnboardIcons.Model,
        title = "接入模型",
        body = "这一步现在就能配，也可以稍后再配：没配 Key 的 bot 只回连通测试，不会真的回消息。",
    )
    SectionCard(metrics, title = "接口") {
        PanelEllipsisField(
            value = vm.api,
            onValueChange = { vm.api = it },
            hint = "https://api.deepseek.com",
            label = "接口地址",
        )
        PanelEllipsisField(
            value = vm.key,
            onValueChange = { vm.key = it },
            hint = "sk-…",
            secret = true,
            label = "API Key",
            modifier = Modifier.padding(top = 10.dp),
        )
    }
    SectionCard(metrics, title = "主模型") {
        PanelEllipsisField(
            value = vm.model,
            onValueChange = { vm.model = it },
            hint = "deepseek-chat",
            mono = false,
        )
        if (vm.models.isNotEmpty()) {
            PanelGroupCard(modifier = Modifier.padding(top = 10.dp)) {
                vm.models.forEachIndexed { index, name ->
                    PanelChoiceRow(
                        title = name,
                        selected = vm.model == name,
                        divider = index != vm.models.lastIndex,
                        onClick = { vm.model = name },
                    )
                }
            }
        }
        PanelPillRow(modifier = Modifier.padding(top = 10.dp)) {
            PanelPrimaryButton(
                text = if (vm.reading) "读取中…" else "读取模型",
                modifier = Modifier.weight(1f),
                enabled = !vm.reading,
                onClick = { vm.read(scope) },
            )
            PanelSecondaryButton(
                text = "跳过，稍后再配",
                modifier = Modifier.weight(1f),
                onClick = onSkip,
            )
        }
        if (vm.msg.isNotBlank()) {
            Text(
                text = vm.msg,
                fontSize = PanelTokens.OnboardListNoteSize,
                color = PanelColors.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = PanelTokens.ButtonGap),
            )
        }
    }
}

// ── ⑥ 完成：三行小结卡 + 欢迎语 ────────────────────────────────────────

@Composable
private fun SummaryRow(
    icon: ImageVector,
    tint: Color,
    label: String,
    value: String,
    topGap: Dp,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(PanelTokens.OnboardDetailGlyph),
        )
        Column(modifier = Modifier.padding(start = PanelTokens.InfoIconGap)) {
            Text(
                text = label,
                fontSize = PanelTokens.OnboardSummaryLabelSize,
                color = PanelColors.onSurfaceVariantSummary,
            )
            Text(
                text = value,
                fontSize = PanelTokens.OnboardSummaryValueSize,
                lineHeight = PanelTokens.OnboardHeroBodyLineHeight,
                color = PanelColors.onBackground,
                modifier = Modifier.padding(top = PanelTokens.InfoValueGap),
            )
        }
    }
}

@Composable
private fun ColumnScope.DoneStep(mode: RunMode, probe: ProbeResult, modelConfigured: Boolean) {
    StepHero(icon = OnboardIcons.Done, title = "可以开始了", body = "")
    PanelGroupCard {
        Text(
            text = "这次记下的三件事",
            fontSize = PanelTokens.InfoTitleSize,
            fontWeight = FontWeight.Medium,
            color = PanelColors.onBackground,
        )
        SummaryRow(
            icon = OnboardIcons.Pass,
            tint = PanelColors.primary,
            label = "保活方式",
            value = mode.label + "（" + mode.summary + "）",
            topGap = PanelTokens.ContentGap,
        )
        val whitelistPass = probe.state == ProbeState.PASS
        SummaryRow(
            icon = if (whitelistPass) OnboardIcons.Pass else stateIcon(probe.state),
            tint = if (whitelistPass) PanelColors.primary else stateTint(probe.state),
            label = "电池白名单",
            value = when {
                mode == RunMode.NORMAL -> "不需要额外权限"
                whitelistPass -> "已加入，通过检测"
                probe.state == ProbeState.FAIL -> probe.headline + "（可回上一步处理）"
                else -> "未检测"
            },
            topGap = PanelTokens.ContentGap,
        )
        SummaryRow(
            icon = if (modelConfigured) OnboardIcons.Pass else OnboardIcons.Unknown,
            tint = if (modelConfigured) PanelColors.primary else PanelColors.onSurfaceVariantSummary,
            label = "模型",
            value = if (modelConfigured) "已配置" else "未配置（只回连通测试，可在「设置 → 模型配置」补）",
            topGap = PanelTokens.ContentGap,
        )
    }
    Text(
        text = "点「开始使用」进主页；想重看这段引导，在「设置 → 重看使用引导」里点一下即可。",
        fontSize = PanelTokens.OnboardWelcomeSize,
        lineHeight = PanelTokens.OnboardHeroBodyLineHeight,
        color = PanelColors.onSurfaceVariantSummary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = PanelTokens.ContentGap),
    )
}

// ── 真检测：三条路径各自问系统要一次结论 ───────────────────────────────

/** 在 IO 线程跑：root 要等 su，Shizuku 要等 binder 回来，都不能占 UI 线程 */
private fun runProbe(ctx: Context, mode: RunMode): ProbeResult = when (mode) {
    RunMode.NORMAL -> ProbeResult(
        ProbeState.PASS,
        "普通模式：不需要额外权限",
        "托管方式 normal；没有申请电池白名单，也没有 su / Shizuku 授权。",
        "不用装任何东西。被系统清理后新消息会收不到，想更稳就回上一步换 ADB 或 root。",
    )

    RunMode.ADB -> probeAdb(ctx)
    RunMode.ROOT -> probeRoot(ctx.packageName)
}

private fun probeAdb(ctx: Context): ProbeResult {
    val pkg = ctx.packageName
    // ShizukuShell.state(ctx) 会主动要一次 binder：服务比本进程晚上线时也判得准
    val st = ShizukuShell.state(ctx)
    val running = st != ShizukuState.NOT_RUNNING
    val granted = st == ShizukuState.GRANTED
    // 与运行时同一条绑定路径：bind → 等 binder → 真跑 dumpsys 查白名单
    ShizukuShell.bind(pkg)
    var waited = 0L
    while (!ShizukuShell.bound && waited < 4000L) {
        Thread.sleep(100L)
        waited += 100L
    }
    val whitelisted = ShizukuShell.isWhitelisted(pkg)
    val dump = ShizukuShell.exec("dumpsys deviceidle whitelist")
    val raw = dump.lines().map { it.trim() }.filter { it.contains(pkg) }
        .joinToString(" / ").ifBlank { dump.trim().take(80).ifBlank { "（空输出）" } }
    val detail = "ShizukuShell.state()=" + st.name + "（running=" + running + "，granted=" + granted + "）" +
        "；ShizukuShell.bind(" + pkg + ") → bound=" + ShizukuShell.bound +
        "；ShizukuShell.isWhitelisted(" + pkg + ")=" + whitelisted +
        (if (ShizukuShell.lastError.isNotBlank()) "；lastError=" + ShizukuShell.lastError else "") +
        "；whitelist 返回：" + raw
    return when {
        !running -> ProbeResult(
            ProbeState.FAIL, "Shizuku 没在运行（未连接）", detail,
            "去装 Shizuku 并启动它（用 adb 启动或打开 Shizuku App），然后点「重新检测」。",
        )
        !granted -> ProbeResult(
            ProbeState.FAIL, "Shizuku 在运行，但本 App 还没被授权", detail,
            "在 Shizuku 里给「AndbnA」授权，然后点「重新检测」。",
        )
        !ShizukuShell.bound -> ProbeResult(
            ProbeState.FAIL, "Shizuku 已授权，但 UserService 没连上", detail,
            "把 Shizuku 重启一次（Shizuku App 里停止再启动），然后点「重新检测」。",
        )
        !whitelisted -> ProbeResult(
            ProbeState.FAIL, "Shizuku 已连上，但还没进电池白名单", detail,
            "点下面的「加入电池白名单」，会以 shell 身份执行 dumpsys deviceidle whitelist。",
        )
        else -> ProbeResult(
            ProbeState.PASS, "Shizuku 已连上，且已在电池白名单", detail, "",
        )
    }
}

private fun probeRoot(pkg: String): ProbeResult {
    val hasRoot = RootHost.checkRoot()
    if (!hasRoot) {
        return ProbeResult(
            ProbeState.FAIL, "没拿到 root", "RootHost.checkRoot()=false（su 被拒绝或没有授权）",
            "在 KernelSU / Magisk 里给本 App 授权 root，然后点「重新检测」。",
        )
    }
    val whitelisted = RootHost.isWhitelisted(pkg)
    val detail = "RootHost.checkRoot()=true；uid=" + RootHost.versionText() +
        "；RootHost.isWhitelisted(" + pkg + ")=" + whitelisted
    return if (whitelisted) {
        ProbeResult(ProbeState.PASS, "root 已可用，且已在电池白名单", detail, "")
    } else {
        ProbeResult(
            ProbeState.FAIL, "root 已可用，但还没进电池白名单", detail,
            "点下面的「加入电池白名单」，会以 root 身份执行 dumpsys deviceidle whitelist +" + pkg + "。",
        )
    }
}

/** 与 ModelConfigScreen 的 ModelPrefs 同一套键：Key 走 SecureStore，其余是普通 prefs。 */
private object OnboardModelPrefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("andbna_local", Context.MODE_PRIVATE)

    fun read(ctx: Context, k: String): String = SecureStore.get(sp(ctx), k)

    fun save(ctx: Context, api: String, key: String, model: String) {
        sp(ctx).edit()
            .putString("llm_api", api.trim())
            .putString("llm_model", model.trim())
            .apply()
        SecureStore.writePref(sp(ctx), "llm_key", key)
        BotStore.writeLlmJson(ctx)
    }
}
