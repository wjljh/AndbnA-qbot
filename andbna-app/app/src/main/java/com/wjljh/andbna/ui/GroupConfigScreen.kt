package com.wjljh.andbna.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wjljh.andbna.local.BotStore.dailyLimit
import com.wjljh.andbna.local.GroupConfig
import com.wjljh.andbna.local.GroupNames
import com.wjljh.andbna.ui.component.PanelArrowRow
import com.wjljh.andbna.ui.component.PanelEmptyState
import com.wjljh.andbna.ui.component.PanelNotice
import com.wjljh.andbna.ui.component.PanelPillRow
import com.wjljh.andbna.ui.component.PanelPrimaryButton
import com.wjljh.andbna.ui.component.PanelSecondaryButton
import com.wjljh.andbna.ui.component.PanelTextField
import com.wjljh.andbna.ui.component.PanelTokens
import com.wjljh.andbna.ui.component.panelStepTransition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wjljh.andbna.local.BotRestarts
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「群配置」二级页（bot 详情页那一行进来）：列出 known_chats 里见过的群，一行一个，点开编辑。
 *
 * 数据全部来自运行期真落下来的记录（bot_runner.ids_for 的 groups = known_chats.json 的群
 * + memory.json 的 g+openid 键 + last_sender 的群）。
 *
 * 群名走官方 GET /v2/groups/{openid}/info（30 QPM），由 Python 侧换 token 调；取到的结果缓存在
 * <botdir>/group_names.json（7 天），列表打开时只给「没有缓存」的群补一次（最多 5 个，串行 +
 * 隔 200ms）。取不到名字/人数就只用 openid 前 8 位兜底 —— 绝不因为查不到而挡住整页。
 *
 * 行一律 [PanelArrowRow]（[feedback] = false：可点、但没有按压动画），编辑页每行是 kit 原生
 * preference（[OverlayDropdownPreference] / [SwitchPreference]），与设置页、模型配置页同一套。
 */
@Composable
fun GroupConfigScreen(metrics: Metrics, appid: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var rows by remember(appid) { mutableStateOf<List<GroupConfig.Row>>(emptyList()) }
    var names by remember(appid) { mutableStateOf<Map<String, GroupNames.Entry>>(emptyMap()) }
    var loaded by remember(appid) { mutableStateOf(false) }
    // 点开的是哪个群（null = 列表页）。两个状态切走同一套过渡动画，返回即回列表。
    var editing by remember(appid) { mutableStateOf<String?>(null) }
    var msg by remember(appid) { mutableStateOf("") }
    var tick by remember(appid) { mutableStateOf(0) }

    // 组合期不读盘：群列表要过 Chaquopy，一律放 IO
    LaunchedEffect(appid, tick) {
        val (r, n) = withContext(Dispatchers.IO) {
            GroupConfig.rows(ctx, appid) to GroupNames.load(ctx, appid)
        }
        rows = r
        names = n
        loaded = true
        // 打开列表时补一次群名：只有「没有有效缓存」的群会真的发请求
        val got = withContext(Dispatchers.IO) { GroupNames.fill(ctx, appid, r.map { it.id }) }
        if (got > 0) names = withContext(Dispatchers.IO) { GroupNames.load(ctx, appid) }
    }
    BackHandler { if (editing != null) editing = null else onBack() }

    AnimatedContent(
        targetState = editing,
        transitionSpec = { panelStepTransition(forward = targetState != null) },
        label = "group-config-step",
    ) { gid ->
        if (gid == null) {
            GroupListPage(
                metrics = metrics,
                appid = appid,
                rows = rows,
                names = names,
                loaded = loaded,
                msg = msg,
                onPick = { id ->
                    msg = ""
                    editing = id
                },
                onBack = onBack,
            )
        } else {
            GroupEditScreen(
                metrics = metrics,
                appid = appid,
                gid = gid,
                name = names[gid]?.name.orEmpty(),
                onBack = { editing = null },
                onSaved = { note ->
                    msg = note
                    editing = null
                    tick++          // 覆盖状态变了，列表重新算一遍
                },
            )
        }
    }
}

/** 列表页：一张「群（N）」卡，一行一个群。 */
@Composable
private fun GroupListPage(
    metrics: Metrics,
    appid: String,
    rows: List<GroupConfig.Row>,
    names: Map<String, GroupNames.Entry>,
    loaded: Boolean,
    msg: String,
    onPick: (String) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        containerColor = PanelColors.surface,
        topBar = { GroupConfigHeader(appid, onBack) },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState()),
        ) {
            PageColumn(metrics) {
                if (msg.isNotBlank()) PanelNotice(msg)

                if (loaded && rows.isEmpty()) {
                    SectionCard(metrics) {
                        PanelEmptyState(
                            icon = Icons.Filled.Forum,
                            title = "还没有记录到群",
                            body = "让 bot 在群里被 @ 一次（或收到一条群消息），再回来这里就能看到。" +
                                "没配置过的群完全沿用 bot 全局设置，与升级前一致。",
                        )
                    }
                }

                if (rows.isNotEmpty()) {
                    SectionCard(metrics, title = "群（" + rows.size + "）", gap = 10.dp) {
                        rows.forEach { row ->
                            PanelArrowRow(
                                title = groupTitle(row.id, names[row.id]),
                                summary = groupSummary(row.id, names[row.id]),
                                icon = Icons.Filled.Forum,
                                feedback = false,
                                onClick = { onPick(row.id) },
                            )
                        }
                    }
                }

                PanelNotice(
                    "每个群一份覆盖（<botdir>/groups/<openid>.json）；没配过的群完全按 bot 全局设置走" +
                        " —— 与升级前一模一样。",
                )
            }
        }
    }
}

/** 群行标题：有缓存就写群名，没有就写「群 + openid 前 8 位」。 */
private fun groupTitle(gid: String, name: GroupNames.Entry?): String {
    val cached = name?.name.orEmpty()
    if (cached.isNotBlank()) return cached
    return "群 " + gid.take(8) + if (gid.length > 8) "…" else ""
}

/** 群行副标题：「openid 前 8 位 · N 人」；人数取不到就只写 openid 前 8 位。 */
private fun groupSummary(gid: String, name: GroupNames.Entry?): String {
    val id8 = gid.take(8)
    val members = name?.members ?: 0
    return if (members > 0) id8 + " · " + members + " 人" else id8
}

/** 单群编辑页。 */
@Composable
private fun GroupEditScreen(
    metrics: Metrics,
    appid: String,
    gid: String,
    name: String,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // 三态：null = 跟随（该 bot 的覆盖 / 全局），落盘写 null，不是 false
    var autoReply by remember(gid) { mutableStateOf<Boolean?>(null) }
    var webSearch by remember(gid) { mutableStateOf<Boolean?>(null) }
    var enabled by remember(gid) { mutableStateOf(true) }
    // 每日额度三态：null = 跟随全局（设置页「每日上限」）、0 = 本群不限、>0 = 本群额度；
    // custom = 选的是「自定义…」（就地输入数字）
    var limit by remember(gid) { mutableStateOf<Int?>(null) }
    var custom by remember(gid) { mutableStateOf(false) }
    var error by remember(gid) { mutableStateOf("") }
    // 读盘时的原值：保存时用它判断「真的改了」，改了才重启这个 bot
    var baseline by remember(gid) { mutableStateOf<GroupConfig.Conf?>(null) }

    LaunchedEffect(gid) {
        val conf = withContext(Dispatchers.IO) { GroupConfig.load(ctx, appid, gid) }
        baseline = conf
        autoReply = conf.autoReply
        webSearch = conf.webSearch
        enabled = conf.enabled
        limit = conf.dailyLimit
        custom = conf.dailyLimit?.let { limitPresetIndex(it) == LIMIT_CUSTOM } ?: false
    }
    BackHandler { onBack() }

    fun save() {
        val conf = GroupConfig.Conf(
            autoReply = autoReply,
            dailyLimit = limit?.coerceAtLeast(0),
            webSearch = webSearch,
            enabled = enabled,
        )
        scope.launch {
            val err = withContext(Dispatchers.IO) { GroupConfig.save(ctx, appid, gid, conf) }
            if (err == null) {
                // 群配置是按 mtime 热读的，但「保存完自动重启」统一走一遍更省心；
                // 值没变就不重启（点开又关掉不该把 bot 踢下线）。
                val restarted = conf != baseline && BotRestarts.requestOne(ctx, appid, "群配置保存")
                onSaved(
                    (if (conf.isDefault) "已保存：所有项都是跟随，覆盖已删除（这个群回到 bot 全局）"
                    else "已保存：" + groupTitle(gid, null) + " 的覆盖") +
                        if (restarted) "，正在重启该 bot…" else "",
                )
            } else {
                error = err
            }
        }
    }

    // 「跟随全局」括号里显示设置页当前那一档（0 = 无上限），下拉文案与设置页同一套叫法
    val globalLimit = ctx.dailyLimit
    val limitIndex = if (custom) LIMIT_CUSTOM else limitIndexOf(limit)
    val limitLabels = limitItems(
        globalLimit,
        if (limitIndex == LIMIT_CUSTOM && (limit ?: 0) > 0) limit else null,
    )

    Scaffold(
        containerColor = PanelColors.surface,
        topBar = {
            TopAppBar(
                title = "群配置",
                largeTitle = "群配置",
                subtitle = if (name.isBlank()) "群 " + gid.take(8) else name + " · " + gid.take(8),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = "返回",
                            tint = MiuixTheme.colorScheme.onBackground,
                        )
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState()),
        ) {
            PageColumn(metrics) {
                if (error.isNotBlank()) PanelNotice(error, isError = true)

                SectionCard(metrics, title = "接话与联网", gap = 10.dp) {
                    OverlayDropdownPreference(
                        insideMargin = PanelTokens.PrefInside,
                        title = "自动接话",
                        summary = AUTO_SUMMARIES[autoIndex(autoReply)],
                        startAction = { RowIcon(Icons.Filled.Forum, PanelColors.onSurfaceVariantSummary) },
                        items = AUTO_LABELS,
                        selectedIndex = autoIndex(autoReply),
                        onSelectedIndexChange = { i ->
                            autoReply = when (i) {
                                1 -> true
                                2 -> false
                                else -> null
                            }
                        },
                    )
                    OverlayDropdownPreference(
                        insideMargin = PanelTokens.PrefInside,
                        title = "联网",
                        summary = WEB_SUMMARIES[autoIndex(webSearch)],
                        startAction = { RowIcon(Icons.Filled.Tune, PanelColors.onSurfaceVariantSummary) },
                        items = WEB_LABELS,
                        selectedIndex = autoIndex(webSearch),
                        onSelectedIndexChange = { i ->
                            webSearch = when (i) {
                                1 -> true
                                2 -> false
                                else -> null
                            }
                        },
                    )
                    // 每日额度：弹出列表（跟随全局 / 不限 / 5 / 10 / 20 / 50 / 自定义…）；
                    // 「跟随全局」不写自己的覆盖值，读设置页「每日上限」那一档
                    OverlayDropdownPreference(
                        insideMargin = PanelTokens.PrefInside,
                        title = "每日额度",
                        summary = when (limitIndex) {
                            LIMIT_FOLLOW -> "跟设置页「每日上限」走，本群不单独设"
                            LIMIT_UNLIMITED -> "这个群不限每日条数"
                            else -> "超过后该群当天不再回复"
                        },
                        startAction = { RowIcon(Icons.Filled.Speed, PanelColors.onSurfaceVariantSummary) },
                        items = limitLabels,
                        selectedIndex = limitIndex,
                        onSelectedIndexChange = { i ->
                            if (i == LIMIT_CUSTOM) {
                                custom = true          // 就地输入：下面立刻出现输入框，数字沿用当前值
                            } else {
                                custom = false
                                limit = when (i) {
                                    LIMIT_FOLLOW -> null
                                    LIMIT_UNLIMITED -> 0
                                    else -> LIMIT_PRESETS[i - 2]
                                }
                            }
                        },
                    )
                    if (limitIndex == LIMIT_CUSTOM) {
                        PanelTextField(
                            value = (limit ?: 0).let { if (it > 0) it.toString() else "" },
                            onValueChange = { v ->
                                limit = v.filter { c -> c.isDigit() }.take(4).toIntOrNull() ?: 0
                            },
                            label = "自定义额度（条/天，留空 = 不限）",
                            numeric = true,
                        )
                    }
                }

                SectionCard(metrics, title = "本群", gap = 10.dp) {
                    SwitchPreference(
                        insideMargin = PanelTokens.PrefInside,
                        title = "整群静音",
                        summary = if (enabled) "关闭 · 这个群照常回（其它项照生效）"
                        else "已开启 · 该群完全静默（@ 也不回，问候 / 定时群发也跳过）",
                        startAction = { RowIcon(Icons.Filled.Notes, PanelColors.onSurfaceVariantSummary) },
                        checked = !enabled,
                        onCheckedChange = { muted -> enabled = !muted },
                    )
                }

                PanelPillRow {
                    PanelPrimaryButton(text = "保存", onClick = { save() }, modifier = Modifier.weight(1f))
                    PanelSecondaryButton(text = "返回", onClick = onBack, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** 群配置页顶栏：细线返回箭头 + 大标题（与关于页 / 主题页 / 模型配置页同一套）。 */
@Composable
private fun GroupConfigHeader(appid: String, onBack: () -> Unit) {
    TopAppBar(
        title = "群配置",
        largeTitle = "群配置",
        subtitle = "AppID " + appid,
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = MiuixIcons.Back,
                    contentDescription = "返回",
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            }
        },
    )
}

/** 「跟随全局」在每日额度列表里的下标：不写覆盖值，读设置页「每日上限」那一档。 */
private const val LIMIT_FOLLOW = 0

/** 「不限」：这个群自己不限（落盘 0），与「跟随全局」是两件事。 */
private const val LIMIT_UNLIMITED = 1

/** 具体数字档（列表下标从 2 起，与 [limitItems] 一一对应）。 */
private val LIMIT_PRESETS = listOf(5, 10, 20, 50)

/** 「自定义…」在列表里的下标：跟随 + 不限 + 四个预设之后。 */
private const val LIMIT_CUSTOM = 2 + 4

/** 额度文案：与设置页「每日上限」同一套叫法。 */
private fun limitText(v: Int?): String = when {
    v == null -> "跟随全局"
    v <= 0 -> "无上限"
    else -> v.toString() + " 条/天"
}

/** 下拉文案：首项括号里是**设置页当前那一档**，一眼看出跟随的是多少。 */
private fun limitItems(global: Int, customValue: Int?): List<String> =
    listOf("跟随全局（" + limitText(global) + "）", "不限") +
        LIMIT_PRESETS.map { it.toString() + " 条/天" } +
        listOf(if (customValue != null) "自定义（" + limitText(customValue) + "）" else "自定义…")

/** 落盘值 → 下拉下标（null = 跟随全局，0 = 不限，预设档落在 2..5，其它算「自定义…」）。 */
private fun limitIndexOf(value: Int?): Int = when {
    value == null -> LIMIT_FOLLOW
    value <= 0 -> LIMIT_UNLIMITED
    else -> LIMIT_PRESETS.indexOf(value).takeIf { it >= 0 }?.plus(2) ?: LIMIT_CUSTOM
}

/** 预设之外的数字 = 停在「自定义…」那行（读盘时决定输入框摊不摊开）。 */
private fun limitPresetIndex(value: Int): Int = limitIndexOf(value)

private val AUTO_LABELS = listOf("跟随 bot 设置", "开（该群自动接话）", "关（只回 @）")
private val AUTO_SUMMARIES = listOf(
    "跟随 bot 设置",
    "开着 · 没 @ 也会按「回复判定」插话",
    "关着 · 只回 @ 的消息",
)
private val WEB_LABELS = listOf("跟随 bot 设置", "开（允许联网）", "关（该群禁止联网）")
private val WEB_SUMMARIES = listOf(
    "跟随 bot 设置",
    "开着 · 自判搜索与 #搜 / #读 都能用",
    "关着 · 自判搜索与 #搜 / #读 都不发请求（主人也不行）",
)

/** 三态值 → 下拉下标（0 跟随 / 1 开 / 2 关）。 */
private fun autoIndex(v: Boolean?): Int = when (v) {
    null -> 0
    true -> 1
    false -> 2
}
