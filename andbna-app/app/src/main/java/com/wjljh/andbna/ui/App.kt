package com.wjljh.andbna.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wjljh.andbna.data.OnboardStore
import com.wjljh.andbna.data.PrefsWatch
import com.wjljh.andbna.data.SettingsRepositoryImpl
import com.wjljh.andbna.local.ModeStore
import com.wjljh.andbna.local.PullBack
import com.wjljh.andbna.local.RunMode
import com.wjljh.andbna.ui.component.PanelTokens
import com.wjljh.andbna.ui.component.SecondaryTopBar
import com.wjljh.andbna.ui.component.bottombar.BottomBarDestination
import com.wjljh.andbna.ui.component.bottombar.BottomBarMiuix
import com.wjljh.andbna.ui.component.bottombar.rememberMainPagerState
import com.wjljh.andbna.ui.screen.colorpalette.ColorPaletteScreen
import com.wjljh.andbna.ui.screen.colorpalette.LocalPaletteBack
import com.wjljh.andbna.ui.theme.LocalEnableBlur
import com.wjljh.andbna.ui.theme.LocalEnableFloatingBottomBar
import com.wjljh.andbna.ui.theme.LocalEnableFloatingBottomBarBlur
import com.wjljh.andbna.ui.design.Size as DesignSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 二级页路由。骨架与 SukiSU MainActivity.kt:211-269 一致：
 * NavDisplay 渲染整条 back stack，转场用 kit 的 MiuixDefault（进层 +width→0，下层 -0.25×width 且
 * alpha 1→0.9，程序化 tween(500ms)+NavSettleEasing(0.8, 0.95)，手势 spring(1.0, 146)）。
 */
sealed interface Route : NavKey {
    data object Main : Route
    data object HeroDetail : Route
    data object Log : Route
    // 旧的主题页（ui/ThemeScreen.kt）保留成死代码不再入口；主题设置走搬来的 kit ColorPaletteScreen
    data object ThemePalette : Route
    data object ModePick : Route
    data object Texts : Route

    /** 设置页「关于」进来的二级页：图标、版本、技术信息、致谢。 */
    data object About : Route

    /** 设置页「模型配置」进来的二级页：地址 / Key / 主模型 / 识图模型 + 读取模型 + 保存。 */
    data object ModelConfig : Route
    data class Tasks(val appid: String) : Route
    data class MemDetail(val appid: String) : Route

    /** Bot 详细配置二级页（卡片上的「编辑」进来）：只带 appid，字段每次现读。 */
    data class BotEdit(val appid: String) : Route

    /** 新建 bot 的独立页面（Bot 页的「+」/「添加」进来），结构与 BotEdit 同款。 */
    data object BotAdd : Route
}

/**
 * 底部胶囊导航栏是否显示：页面自己置 false（记忆页进多选），退出时置回 true。
 *
 * 只用一个 MutableState 挂 CompositionLocal —— 写入方是页面，读取方是这里的壳，
 * 两边都不必知道对方存在；页面切走时记得在 onDispose 里回收成 true。
 */
val LocalBottomBarVisible: ProvidableCompositionLocal<MutableState<Boolean>> =
    compositionLocalOf { mutableStateOf(true) }

@Composable
fun PanelApp() {
    // 这三个 CompositionLocal 决定底栏形态与玻璃效果，值来自「主题设置」页的三个开关：
    //   悬浮底栏 → LocalEnableFloatingBottomBar
    //   模糊     → LocalEnableBlur（页面 / 顶栏）
    //   液态玻璃 → LocalEnableFloatingBottomBarBlur（底栏背后的模糊层）
    // observed() 读一次 revision：主题页改完开关根层就重组，不用重启。
    // 这三个开关由搬来的主题页写在 "settings" 里（kit 键：enable_blur /
    // enable_floating_bottom_bar / enable_floating_bottom_bar_blur），这里按 kit 的读取方式读同一份
    val repo = remember { SettingsRepositoryImpl() }
    PrefsWatch.observed()
    val bottomBarVisible = remember { mutableStateOf(true) }
    CompositionLocalProvider(
        LocalEnableFloatingBottomBar provides repo.enableFloatingBottomBar,
        LocalEnableFloatingBottomBarBlur provides repo.enableFloatingBottomBarBlur,
        LocalEnableBlur provides repo.enableBlur,
        LocalBottomBarVisible provides bottomBarVisible,
    ) {
        PanelAppContent()
    }
}

@Composable
private fun PanelAppContent() {
    val ctx = LocalContext.current
    val metrics = rememberMetrics()
    var mode by remember { mutableStateOf(ModeStore.initial(ctx)) }

    // 首次使用引导：只有 debug 包 + 没写过 onboarded=true 才拦在最前面（见 OnboardStore.required）。
    // release 包 required() 恒为 false —— 老用户升级 release 不会突然弹引导。
    var onboarding by remember { mutableStateOf(OnboardStore.required(ctx)) }
    // 每次进引导换一个 key：这个 if 分支在两段布局中间，不换 key 的话引导内部的 remember
    // 会按位置沿用上一次的值（重看引导会直接落在上次停的那一步）
    var onboardSession by remember { mutableIntStateOf(0) }
    if (onboarding) {
        key(onboardSession) {
            OnboardingScreen(onFinish = {
                OnboardStore.markDone(ctx)
                onboarding = false
                // 引导里可能改过保活方式，回主页时重读一次，并立刻按新模式换一次通道
                mode = ModeStore.initial(ctx)
                PullBack.poke(ctx)
            })
        }
        return
    }

    // miuix-nav 的后栈：in-memory 即可（不涉及进程死亡恢复），所以不用 rememberNavBackStack
    // 新建 bot 后 +1：Bot 页据此重读 localbots.json（BotsPage 常驻，不重进就不会自己刷新）
    var botsRev by remember { mutableIntStateOf(0) }
    val backStack = remember { navBackStackOf(Route.Main) }
    fun push(route: Route) {
        if (route !in backStack) backStack.add(route)
    }
    fun pop() {
        if (backStack.size > 1) backStack.removeLastOrNull()
    }

    // 分页状态放在导航之上：底栏选中态与 pager 都由它一家驱动
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(
        initialPage = 0, pageCount = { 4 }
    )
    val mainPagerState = rememberMainPagerState(pagerState)
    LaunchedEffect(pagerState.currentPage) { mainPagerState.syncPage() }

    CompositionLocalProvider(LocalMainPagerState provides mainPagerState) {
        NavDisplay(
            backStack = backStack,
            effects = NavDisplayEffects(cornerClipRadius = rememberNavSystemCornerRadius()),
            onBack = { pop() },
        ) {
            // 主页面自己不接受右滑关闭（根页），二级页统一 LeftToRight 手势返回
            entry<Route.Main>(swipeDismiss = NavSwipeDirection.None) {
                MainTabs(
                    metrics = metrics,
                    mode = mode,
                    pagerState = pagerState,
                    onOpenDetail = { push(Route.HeroDetail) },
                    onOpenTasks = { push(Route.Tasks(it)) },
                    onOpenMemDetail = { push(Route.MemDetail(it)) },
                    onOpenBotEdit = { push(Route.BotEdit(it)) },
                    onOpenBotAdd = { push(Route.BotAdd) },
                    botsRev = botsRev,
                    onOpenMode = { push(Route.ModePick) },
                    onOpenLog = { push(Route.Log) },
                    onOpenTheme = { push(Route.ThemePalette) },
                    onOpenTexts = { push(Route.Texts) },
                    onOpenAbout = { push(Route.About) },
                    onOpenModelConfig = { push(Route.ModelConfig) },
                    // 设置页「重看使用引导」：清标记 → 重新进引导（只 debug 包有这个入口）
                    onReplayOnboarding = {
                        OnboardStore.reset(ctx)
                        onboardSession += 1
                        onboarding = true
                    },
                )
            }
            entry<Route.HeroDetail>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                HeroDetailScreen(
                    metrics = metrics,
                    onBack = { pop() },
                    onModeChange = {
                        mode = it
                        // 换了模式就立刻让看门狗按新通道换岗（不等监督线程下一个 20s）
                        PullBack.poke(ctx)
                    },
                )
            }
            entry<Route.Log>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                LogScreen(metrics) { pop() }
            }
            // 主题设置：kit 的 ColorPaletteScreen（它不是自带 Scaffold 的二级页，只是把 pop 灌进 composition local）
            entry<Route.ThemePalette>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                CompositionLocalProvider(LocalPaletteBack provides { pop() }) {
                    ColorPaletteScreen()
                }
            }
            entry<Route.ModePick>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                ModePickScreen(
                    metrics = metrics,
                    current = mode,
                    allowBack = true,
                    onPick = { picked ->
                        ModeStore.set(ctx, picked)
                        mode = picked
                        // 模式一落盘就 poke：ADB ↔ root ↔ 普通 之间换通道/退出都是立刻的，
                        // 旧脚本用旧通道收掉，同一时刻只有一份在跑
                        PullBack.poke(ctx)
                        pop()
                    },
                    onBack = { pop() },
                )
            }
            entry<Route.About>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                AboutScreen(metrics) { pop() }
            }
            // 模型配置：外壳由 ModelConfigScreen 自己给（顶栏 = 细线 Back + 标题，与关于页同款）
            entry<Route.ModelConfig>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                ModelConfigScreen(metrics) { pop() }
            }
            entry<Route.Texts>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                // 顶栏由 TextsPage 自己给（细线 MiuixIcons.Back + 标题，与关于页同款）
                TextsPage(bottomInset = 0.dp) { pop() }
            }
            entry<Route.Tasks>(swipeDismiss = NavSwipeDirection.LeftToRight) { route ->
                TasksScreen(metrics, route.appid) { pop() }
            }
            // Bot 详细配置：外壳由 BotEditScreen 自己给（顶栏 = 细线 Back + bot 名，与主题页同款）
            entry<Route.BotEdit>(swipeDismiss = NavSwipeDirection.LeftToRight) { route ->
                // 返回时 +1：编辑页可能改了人格 / 名字，Bot 页那张卡要跟着重读
                BotEditScreen(metrics = metrics, appid = route.appid) {
                    pop()
                    botsRev++
                }
            }
            // 新建 bot：独立页面（不再弹老对话框），结构与编辑页同款
            entry<Route.BotAdd>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                BotAddScreen(
                    metrics = metrics,
                    onBack = { pop() },
                    onCreated = { botsRev++ },
                )
            }
            entry<Route.MemDetail>(swipeDismiss = NavSwipeDirection.LeftToRight) { route ->
                var botTitle by remember(route.appid) { mutableStateOf("记忆") }
                LaunchedEffect(route.appid) {
                    botTitle = withContext(Dispatchers.IO) {
                        com.wjljh.andbna.local.BotStore.load(ctx)
                            .firstOrNull { it.appid == route.appid }?.title ?: "记忆"
                    }
                }
                SecondaryPage(title = botTitle, subtitle = "这个 bot 的全部记忆", onBack = { pop() }) {
                    MemoryDetailContent(metrics = metrics, appid = route.appid, bottomInset = 0.dp)
                }
            }
        }
    }
}

/** 没有自带 Scaffold 的二级页套一层小标题栏（转场由 NavDisplay 给），外壳按模式二选一。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SecondaryPage(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (LocalUiMode.current == UiMode.Material) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                androidx.compose.material3.TopAppBar(
                    title = {
                        Column {
                            Text(text = title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                )
            },
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) { content() }
        }
    } else {
        Scaffold(
            containerColor = MiuixTheme.colorScheme.surface,
            topBar = {
                SecondaryTopBar(title = title, subtitle = subtitle, onBack = onBack)
            },
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) { content() }
        }
    }
}

/**
 * 四个 tab 的壳（NavDisplay 的根 entry）：
 * 顶栏 + pager + 悬浮胶囊底栏；底栏实测高度 + 12dp 作为每页 LazyColumn 的底部 contentPadding。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTabs(
    metrics: Metrics,
    mode: RunMode,
    pagerState: androidx.compose.foundation.pager.PagerState,
    onOpenDetail: () -> Unit,
    onOpenTasks: (String) -> Unit,
    onOpenMemDetail: (String) -> Unit,
    onOpenBotEdit: (String) -> Unit,
    onOpenBotAdd: () -> Unit,
    botsRev: Int,
    onOpenMode: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenTheme: () -> Unit,
    onOpenTexts: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenModelConfig: () -> Unit,
    onReplayOnboarding: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // 启用 bot 数：给顶栏副标题用。文件读放在 IO，别在组合里做。
    var enabledBotCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            enabledBotCount = withContext(Dispatchers.IO) {
                com.wjljh.andbna.local.BotStore.load(ctx).count { it.enabled }
            }
            delay(3000)
        }
    }
    val subtitle = modeText(mode) + " · " + enabledBotCount + " 个 bot"

    // 顶栏小标题跟着当前 tab 走（largeTitle 保留 AndbnA 作展开态大标题）
    val tabTitle = listOf("状态", "记忆", "Bot", "设置")
        .getOrElse(pagerState.currentPage) { "状态" }

    // 顶栏的折叠状态：TopAppBar 与内容各自挂在 scaffold 的两侧，nestedScroll 只能手动接
    // （SukiSU 也是这么接的：scrollBehavior 建在页面级，TopAppBar 收它，列表 .nestedScroll 它）
    val appBarScroll = MiuixScrollBehavior()
    // material 那一支的折叠行为：LargeTopAppBar 展开 ↔ 收起（exitUntilCollapsed）
    val materialScroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    // 液体玻璃要「取到身后的内容」，所以内容树画进这个 backdrop 层
    val surfaceColor = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }

    // 悬浮底栏：量一次它的真实占位（含导航条 inset），再 +12dp 安全距，
    // 作为「底部 content padding」交给每个页面的 LazyColumn（PanelScreen）。
    var barHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val bottomInset = (if (barHeightPx > 0) with(density) { barHeightPx.toDp() }
    else DesignSize.Bar + PanelTokens.BottomBarSafetyGap) + PanelTokens.BottomBarSafetyGap
    // 底栏被页面收走时（记忆页多选），底部留白只剩那 12dp 安全距，否则底栏那条空着的高度会变成一截白
    val barVisible = LocalBottomBarVisible.current.value
    val miuixPageInset = if (barVisible) bottomInset else PanelTokens.BottomBarSafetyGap

    val materialMode = LocalUiMode.current == UiMode.Material

    // 页面内容只有一份：两套外壳共用这同一个 pager（页面文件里没有任何模式分支）。
    // pageInset = 本模式外壳给「底栏占位」算出来的额外底部留白，由外壳分别传入：
    //   miuix  → 悬浮胶囊实测高度 + 12dp（胶囊浮在内容上，要自己让开）
    //   material → Scaffold innerPadding.bottom（底栏已占位）+ 12dp（所以这里只要 12dp 这一截增量；
    //              实际 reserve = innerPadding.bottom + 12dp，见下方 Scaffold 分支）
    val pages: @Composable (Dp) -> Unit = { pageInset ->
        // 内容层按模式接不同顶栏：miuix 要液体玻璃的 backdrop 层 + kit 的折叠连接；
        // material 直接挂在 M3 exitUntilCollapsed 的连接上，不需要 backdrop（也没有玻璃要取）。
        val contentModifier = if (materialMode) {
            Modifier.fillMaxSize().nestedScroll(materialScroll.nestedScrollConnection)
        } else {
            Modifier.fillMaxSize()
                .layerBackdrop(backdrop)
                .nestedScroll(appBarScroll.nestedScrollConnection)
        }
        Box(contentModifier) {
            androidx.compose.foundation.pager.HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                // 不预组合相邻页：滑动时只处理当前页，减少组合负担
                beyondViewportPageCount = 0,
            ) { page ->
                val cur = pagerState.currentPage
                // 每页套一层 fillMaxSize 的 Box：否则内容会被垂直居中，上方留一大块空白
                Box(Modifier.fillMaxSize()) {
                    when (page) {
                        0 -> StatusPage(
                            visible = cur == 0,
                            bottomInset = pageInset,
                            onOpenDetail = onOpenDetail,
                            onOpenMode = onOpenMode,
                            // App 持有的 mode 状态：模式胶囊与「运行模式」行都读它，改完立刻一致
                            mode = mode,
                        )
                        1 -> LocalTextsContent(
                            metrics = metrics,
                            bottomInset = pageInset,
                            onOpen = onOpenMemDetail,
                        )
                        2 -> BotsPage(
                            metrics = metrics,
                            visible = cur == 2,
                            bottomInset = pageInset,
                            revision = botsRev,
                            onOpenTasks = onOpenTasks,
                            onOpenBotEdit = onOpenBotEdit,
                            onOpenBotAdd = onOpenBotAdd,
                        )
                        else -> SettingsPage(
                            metrics = metrics,
                            visible = true,
                            bottomInset = pageInset,
                            // 当前模式传进去：「自动拉回」那行只在 ADB / root 显示，切完立刻刷新
                            mode = mode,
                            onReselectMode = onOpenMode,
                            onOpenLog = onOpenLog,
                            onOpenTheme = onOpenTheme,
                            onOpenTexts = onOpenTexts,
                            onOpenAbout = onOpenAbout,
                            onOpenModelConfig = onOpenModelConfig,
                            onReplayOnboarding = onReplayOnboarding,
                        )
                    }
                }
            }
        }
    }

    if (materialMode) {
        // material3 外壳：Scaffold + LargeTopAppBar + NavigationBar 底栏
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                LargeTopAppBar(
                    title = {
                        Column {
                            Text(text = tabTitle, style = MaterialTheme.typography.headlineSmall)
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    scrollBehavior = materialScroll,
                    actions = {
                        IconButton(onClick = {
                            scope.launch { pagerState.animateScrollToPage(3) }
                        }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                        }
                    },
                )
            },
            bottomBar = {
                // 多选态（记忆页）把底栏收走：NavigationBar 不组合，Scaffold 的 innerPadding 只剩 0
                if (barVisible) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        BottomBarDestination.entries.forEachIndexed { index, destination ->
                            NavigationBarItem(
                                selected = pagerState.currentPage == index,
                                onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                                icon = { Icon(destination.icon, contentDescription = null) },
                                label = { Text(stringResource(destination.label)) },
                            )
                        }
                    }
                }
            },
        ) { pad ->
            // 底栏是 Scaffold 的 bottomBar，占位已经算进 innerPadding；
            // 这里把 innerPadding 的 bottom（+12dp 安全距）当作页面的 contentPadding 传下去，
            // 而不是「外面 padding(pad) 一遍 + 页面里再猜一个数」—— 滚到底时最后一行一定完整露出。
            val layoutDir = LocalLayoutDirection.current
            val inset = pad.calculateBottomPadding() + PanelTokens.BottomBarSafetyGap
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(
                        start = pad.calculateLeftPadding(layoutDir),
                        top = pad.calculateTopPadding(),
                        end = pad.calculateRightPadding(layoutDir),
                        bottom = 0.dp,
                    ),
            ) {
                pages(inset)
            }
        }
    } else {
        Scaffold(
            containerColor = surfaceColor,
            // 横向 insets 自己收窄，纵向留给 TopAppBar 和悬浮底栏各自处理
            contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
                .only(WindowInsetsSides.Horizontal),
            topBar = {
                TopAppBar(
                    title = tabTitle,
                    // 用户要求：顶栏任何状态下都显示当前页名，不再出现 AndbnA
                    largeTitle = tabTitle,
                    // 用户要求：去掉标题下的小字说明（root 托管 · N 个 bot 这类），状态页自己有 hero 展示
                    subtitle = "",
                    scrollBehavior = appBarScroll,
                    actions = {
                        IconButton(onClick = {
                            scope.launch { pagerState.animateScrollToPage(3) }
                        }) {
                            Icon(MiuixIcons.More, contentDescription = "更多")
                        }
                    },
                )
            },
        ) { pad ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(pad),
            ) {
                // 内容层：覆盖「整个内容区」——包括底栏那一条。
                // 底栏被页面收走时（记忆页多选）留白只剩 12dp 安全距，不再空出胶囊那一截。
                pages(miuixPageInset)

                // kit 的液体玻璃胶囊底栏（FloatingBottomBar，内部由 BottomBarMiuix 驱动）。
                // 外面这层 Box 只负责量它的真实占位高度，底栏自己不带背景，四周不会出现黑条。
                if (barVisible) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .onSizeChanged { if (it.height != barHeightPx) barHeightPx = it.height },
                    ) {
                        BottomBarMiuix(
                            blurBackdrop = null,
                            backdrop = backdrop,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            }
        }
    }
}