package com.wjljh.andbna.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold as M3Scaffold
import androidx.compose.material3.Switch as M3Switch
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.TopAppBar as M3TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wjljh.andbna.local.GroupAdmin
import com.wjljh.andbna.local.GroupConfig
import com.wjljh.andbna.local.GroupNames
import com.wjljh.andbna.ui.component.BotFormSwitchRow
import com.wjljh.andbna.ui.component.PanelArrowRow
import com.wjljh.andbna.ui.component.PanelEmptyState
import com.wjljh.andbna.ui.component.PanelIconPillButton
import com.wjljh.andbna.ui.component.PanelListRow
import com.wjljh.andbna.ui.component.AppToast
import com.wjljh.andbna.ui.component.PanelNotice
import com.wjljh.andbna.ui.component.PanelPillRow
import com.wjljh.andbna.ui.component.PanelPrimaryButton
import com.wjljh.andbna.ui.component.PanelSecondaryButton
import com.wjljh.andbna.ui.component.PanelTextField
import com.wjljh.andbna.ui.component.PanelTokens
import com.wjljh.andbna.ui.util.PredictiveBackGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「群管理」二级页（bot 详情页「群管理」那张卡进来）：
 * 群列表 →（点某个群）→ 该群的群管理规则页。
 *
 * 规则文件是 <botdir>/group_admin/<group_openid>.json（契约见 [GroupAdmin]），
 * 列表里的群 = 运行期记录到的群（bot_runner.ids_for）∪ 已经有规则文件的群 ——
 * 后者保证「配过的群」不会因为运行记录被清掉就在列表里消失。
 *
 * 样式与「群配置」页同一套：列表用 [PanelArrowRow]，规则页用 [SectionCard] +
 * kit 原生 preference（[OverlayDropdownPreference] / [Switch]）；输入框走 [PanelTextField]
 * （miuix / material 两套样式由它自己分支），页面外壳按 [LocalUiMode] 给两套 body。
 */
/** 「群管理」这一层自己的两条路由：群列表 ↔ 单个群的规则页。 */
private sealed interface AdminSub : NavKey {
    data object List : AdminSub
    data class Rule(val gid: String) : AdminSub
}

@Composable
fun GroupAdminScreen(metrics: Metrics, appid: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var rows by remember(appid) { mutableStateOf<List<AdminRow>>(emptyList()) }
    var names by remember(appid) { mutableStateOf<Map<String, GroupNames.Entry>>(emptyMap()) }
    var loaded by remember(appid) { mutableStateOf(false) }
    var msg by remember(appid) { mutableStateOf("") }
    var tick by remember(appid) { mutableStateOf(0) }
    // 列表 ↔ 规则页走本层的 NavDisplay 栈（与群配置同一口径）：推入式返回能拿到跟手预览，
    // 以前就地 AnimatedContent 切状态，系统返回分发收不到，侧滑只有硬切。
    val subStack = remember(appid) { navBackStackOf(AdminSub.List) }
    fun openSub(route: AdminSub) { if (route !in subStack) subStack.add(route) }
    fun popSub() { if (subStack.size > 1) subStack.removeLastOrNull() }

    // 组合期一律不读盘：群列表要过 Chaquopy，全放 IO
    LaunchedEffect(appid, tick) {
        val (r, n) = withContext(Dispatchers.IO) {
            val ids = LinkedHashSet<String>()
            GroupConfig.rows(ctx, appid).forEach { row -> ids.add(row.id) }
            GroupAdmin.configured(ctx, appid).forEach { gid -> ids.add(gid) }
            val list = ids.map { gid ->
                AdminRow(
                    gid = gid,
                    configured = GroupAdmin.has(ctx, appid, gid),
                    enabled = GroupAdmin.load(ctx, appid, gid).enabled,
                )
            }.sortedWith(compareByDescending<AdminRow> { it.configured }.thenBy { it.gid })
            list to GroupNames.load(ctx, appid)
        }
        rows = r
        names = n
        loaded = true
    }
    NavDisplay(
        backStack = subStack,
        effects = NavDisplayEffects(cornerClipRadius = rememberNavSystemCornerRadius()),
        // 同群配置：本层能弹就弹自己，只在根层交给外层
        onBack = { if (subStack.size > 1) popSub() else onBack() },
    ) {
        // 列表：本层根 entry（这一层的返回是回 bot 编辑页，交给外层 entry 的 gate / 库）
        entry<AdminSub.List>(swipeDismiss = NavSwipeDirection.LeftToRight) {
            AdminListPage(
                metrics = metrics,
                appid = appid,
                rows = rows,
                names = names,
                loaded = loaded,
                msg = msg,
                onPick = { id ->
                    msg = ""
                    openSub(AdminSub.Rule(id))
                },
                onBack = onBack,
            )
        }
        // 单群规则页：推入式 entry，侧滑时下层（列表）跟着露出来
        entry<AdminSub.Rule>(swipeDismiss = NavSwipeDirection.LeftToRight) { route ->
            PredictiveBackGate(canPop = subStack.size > 1) { popSub() }
            AdminRuleScreen(
                metrics = metrics,
                appid = appid,
                gid = route.gid,
                name = names[route.gid]?.name.orEmpty(),
                onBack = {
                    popSub()
                    tick++          // 规则可能刚改过，「已启用 / 未配置」重新算一遍
                },
            )
        }
    }
}

/** 列表里的一行：群 + 有没有规则文件 + 规则里的总开关。 */
private data class AdminRow(
    val gid: String,
    val configured: Boolean,
    val enabled: Boolean,
)

/** 列表页：一张「群（N）」卡，一行一个群（右侧「未配置 / 已启用 / 已停用」）。 */
@Composable
private fun AdminListPage(
    metrics: Metrics,
    appid: String,
    rows: List<AdminRow>,
    names: Map<String, GroupNames.Entry>,
    loaded: Boolean,
    msg: String,
    onPick: (String) -> Unit,
    onBack: () -> Unit,
) {
    AdminShell(
        metrics = metrics,
        title = "群管理",
        subtitle = "AppID " + appid,
        onBack = onBack,
    ) {
        if (msg.isNotBlank()) PanelNotice(msg)

        // 使用前提：常驻、不折叠（这些是平台/群主那边的权限，不是这边的开关）
        SectionCard(metrics, title = PREREQ_TITLE, gap = 8.dp) {
            Text(
                text = PREREQ_INTRO,
                fontSize = 13.sp,
                color = PanelColors.onSurface,
            )
            PREREQ_ITEMS.forEach { line ->
                Text(
                    text = line,
                    fontSize = 13.sp,
                    color = PanelColors.onSurfaceVariantSummary,
                )
            }
        }

        if (loaded && rows.isEmpty()) {
            SectionCard(metrics) {
                PanelEmptyState(
                    icon = Icons.Filled.Forum,
                    title = "还没有记录到群",
                    body = "让 bot 在群里被 @ 一次，再回来就能挑群设规则。",
                )
            }
        }

        if (rows.isNotEmpty()) {
            SectionCard(metrics, title = "群 · " + rows.size, gap = 10.dp) {
                rows.forEach { row ->
                    PanelArrowRow(
                        title = adminGroupTitle(row.gid, names[row.gid]),
                        summary = adminGroupStatus(row),
                        icon = Icons.Filled.Forum,
                        feedback = false,
                        onClick = { onPick(row.gid) },
                    )
                }
            }
        }

        PanelNotice(
            "一个群一份规则：group_admin/<openid>.json。没配过的群我不管",
        )
    }
}

/** 群行标题：有缓存就写群名，没有就写「群 + openid 前 8 位」（与群配置页同一套）。 */
private fun adminGroupTitle(gid: String, name: GroupNames.Entry?): String {
    val cached = name?.name.orEmpty()
    if (cached.isNotBlank()) return cached
    return "群 " + gid.take(8) + if (gid.length > 8) "…" else ""
}

/** 群行副标题：未配置 / 已启用 / 已停用 + openid 前 8 位。 */
private fun adminGroupStatus(row: AdminRow): String {
    val id8 = row.gid.take(8)
    val state = when {
        !row.configured -> "未配置"
        row.enabled -> "已启用"
        else -> "已停用"
    }
    return state + " · " + id8
}

/** 规则页：一个群的 group_admin/<gid>.json 全字段。 */
@Composable
private fun AdminRuleScreen(
    metrics: Metrics,
    appid: String,
    gid: String,
    name: String,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // null = 还没读回来（先不画表单，免得显示一份默认值再跳变）
    var rule by remember(gid) { mutableStateOf<GroupAdmin.Rule?>(null) }
    var baseline by remember(gid) { mutableStateOf<GroupAdmin.Rule?>(null) }
    var stats by remember(gid) { mutableStateOf<GroupAdmin.Stats?>(null) }
    var error by remember(gid) { mutableStateOf("") }
    // 「自定义禁言…」摊开输入框的是第几级（-1 = 没有）
    var customStep by remember(gid) { mutableStateOf(-1) }

    LaunchedEffect(gid) {
        val (r, s) = withContext(Dispatchers.IO) {
            GroupAdmin.load(ctx, appid, gid) to GroupAdmin.stats(ctx, appid, gid)
        }
        rule = r
        baseline = r
        stats = s
    }
    val r = rule
    if (r == null) {
        AdminShell(metrics, "群管理", adminGroupTitle(gid, null), onBack) {
            PanelNotice("读规则…")
        }
        return
    }

    fun edit(next: GroupAdmin.Rule) {
        rule = next
        error = ""
    }

    fun save() {
        val conf = rule ?: return
        scope.launch {
            val err = withContext(Dispatchers.IO) { GroupAdmin.save(ctx, appid, gid, conf) }
            if (err == null) {
                baseline = conf
                error = ""
                AppToast.show("已保存：group_admin/" + gid + ".json")
                stats = withContext(Dispatchers.IO) { GroupAdmin.stats(ctx, appid, gid) }
            } else {
                error = err
            }
        }
    }

    AdminShell(
        metrics = metrics,
        title = "群管理",
        subtitle = if (name.isBlank()) "群 " + gid.take(8) else name + " · " + gid.take(8),
        onBack = onBack,
    ) {
        // ① 三个开关：总开关 / 命中后撤回 / 撤回后 @ 警告
        SectionCard(metrics, title = "开关", gap = 10.dp) {
            AdminSwitchRow(
                title = "启用本群管理",
                summary = if (r.enabled) "开着 · 命中就按下面的阶梯处理"
                else "关着 · 这个群不管，规则还留着",
                checked = r.enabled,
                onCheckedChange = { edit(r.copy(enabled = it)) },
                icon = Icons.Filled.Notes,
            )
            AdminSwitchRow(
                title = "命中后撤回该消息",
                summary = if (r.recallEnabled) "开 · 先撤那条违规消息" else "关 · 只警告，消息留在群里",
                checked = r.recallEnabled,
                onCheckedChange = { edit(r.copy(recallEnabled = it)) },
                icon = Icons.Filled.Tune,
            )
            AdminSwitchRow(
                title = "撤回后 @ 对方警告",
                summary = if (r.warnEnabled) "开 · 撤回后在群里 @ 他一句" else "关 · 不 @ 也不说话，只处罚",
                checked = r.warnEnabled,
                onCheckedChange = { edit(r.copy(warnEnabled = it)) },
                icon = Icons.Filled.Speed,
            )
        }

        // ② 判定方式三选一
        SectionCard(metrics, title = "判定方式", gap = 10.dp) {
            OverlayDropdownPreference(
                insideMargin = PanelTokens.PrefInside,
                title = "怎么判定违规",
                summary = MODE_SUMMARIES[modeIndex(r.mode)],
                startAction = { RowIcon(Icons.Filled.Forum, PanelColors.onSurfaceVariantSummary) },
                items = MODE_LABELS,
                selectedIndex = modeIndex(r.mode),
                onSelectedIndexChange = { i -> edit(r.copy(mode = MODE_VALUES[i])) },
            )
            Text(
                text = if (r.keywords.isEmpty() && r.mode != GroupAdmin.MODE_MODEL) {
                    "词库是空的：加一个词，或者改用「只用模型」"
                } else {
                    "复核在命中后多问一次模型，杀错更少"
                },
                fontSize = 13.sp,
                color = if (r.keywords.isEmpty() && r.mode != GroupAdmin.MODE_MODEL) PanelColors.error
                else PanelColors.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        // ③ 关键词：一行一个词 + 删除按钮 + 底部「添加关键词」
        SectionCard(metrics, title = "关键词 · " + r.keywords.size + " 个", gap = 6.dp) {
            if (r.keywords.isEmpty()) {
                Text(
                    text = "词库是空的：点下面「添加关键词」补一个",
                    fontSize = 13.sp,
                    color = PanelColors.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
            }
            r.keywords.forEachIndexed { i, word ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PanelTextField(
                        value = word,
                        onValueChange = { v ->
                            edit(r.copy(keywords = r.keywords.toMutableList().also { it[i] = v }))
                        },
                        label = "关键词 " + (i + 1),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            edit(r.copy(keywords = r.keywords.toMutableList().also { it.removeAt(i) }))
                        },
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Delete,
                            contentDescription = "删除这个关键词",
                            tint = PanelColors.error,
                        )
                    }
                }
            }
            PanelPillRow {
                PanelIconPillButton(
                    text = "添加关键词",
                    icon = MiuixIcons.Add,
                    onClick = { edit(r.copy(keywords = r.keywords + "")) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ④ 惩罚阶梯：每级一行（第 N 次 → 禁言 X 分钟），末尾一行「第 N 次 → 踢出群」
        SectionCard(metrics, title = "惩罚阶梯", gap = 10.dp) {
            r.steps.forEachIndexed { i, step ->
                val custom = customStep == i && muteIndexOf(step.muteMin) == MUTE_CUSTOM
                OverlayDropdownPreference(
                    insideMargin = PanelTokens.PrefInside,
                    title = "第 " + step.n + " 次",
                    summary = "禁言 " + muteText(step.muteMin) + " · 点这里改，最后一项是删掉",
                    startAction = { RowIcon(Icons.Filled.Speed, PanelColors.onSurfaceVariantSummary) },
                    items = muteItems(step.muteMin),
                    selectedIndex = muteIndexOf(step.muteMin),
                    onSelectedIndexChange = { sel ->
                        when {
                            sel == MUTE_DELETE -> {
                                customStep = -1
                                edit(r.copy(steps = renumber(r.steps.filterIndexed { j, _ -> j != i })))
                            }
                            sel == MUTE_CUSTOM -> customStep = i
                            else -> {
                                customStep = -1
                                edit(
                                    r.copy(
                                        steps = r.steps.toMutableList().also {
                                            it[i] = it[i].copy(muteMin = MUTE_PRESETS[sel])
                                        },
                                    )
                                )
                            }
                        }
                    },
                )
                if (custom) {
                    PanelTextField(
                        value = step.muteMin.toString(),
                        onValueChange = { v ->
                            val m = v.filter { c -> c.isDigit() }.take(5).toIntOrNull() ?: 1
                            edit(
                                r.copy(
                                    steps = r.steps.toMutableList().also {
                                        it[i] = it[i].copy(muteMin = m.coerceIn(1, 43200))
                                    },
                                )
                            )
                        },
                        label = "自定义禁言分钟（1~43200）",
                        numeric = true,
                    )
                }
            }
            PanelPillRow {
                PanelIconPillButton(
                    text = "添加一级",
                    icon = MiuixIcons.Add,
                    onClick = {
                        val next = (r.steps.maxOfOrNull { it.n } ?: 0) + 1
                        edit(
                            r.copy(
                                steps = renumber(
                                    r.steps + GroupAdmin.Step(n = next, muteMin = r.steps.lastOrNull()?.muteMin ?: 10),
                                ),
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // 阶梯用尽后的一档：第 kick_at 次直接踢出
            OverlayDropdownPreference(
                insideMargin = PanelTokens.PrefInside,
                title = "第 " + r.kickAt + " 次 → 踢出群",
                summary = "累计到这一次直接踢，数字可改",
                startAction = { RowIcon(Icons.Filled.Notes, PanelColors.onSurfaceVariantSummary) },
                items = KICK_LABELS,
                selectedIndex = (r.kickAt - 1).coerceIn(0, KICK_LABELS.size - 1),
                onSelectedIndexChange = { i -> edit(r.copy(kickAt = i + 1)) },
            )
        }

        // ⑤ 该群的累计统计（共享计数文件读得到才有数，读不到显示「—」）
        SectionCard(metrics, title = "近期违规", gap = 6.dp) {
            val s = stats
            PanelListRow(
                title = if (s == null) "—" else s.users.toString() + " 人 / 共 " + s.hits + " 次",
                meta = if (s == null) "还没统计到这一行，跑一阵就有了"
                else "本群最近统计：group_admin/_stats.json",
            )
            Text(
                text = "这一页改规则，不动计数",
                fontSize = 13.sp,
                color = PanelColors.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        // 保存结果放最底下：点完「保存」人就在页面底部，提示必须在眼前
        if (error.isNotBlank()) PanelNotice(error, isError = true)

        Text(
            text = "权限不够时我会降级成「撤回 + 警告」，不是坏了",
            fontSize = 13.sp,
            color = PanelColors.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = 4.dp),
        )

        PanelPillRow {
            PanelPrimaryButton(text = "保存", onClick = { save() }, modifier = Modifier.weight(1f))
            PanelSecondaryButton(
                text = "返回",
                onClick = onBack,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 页面外壳：miuix / material 两套 body（顶栏与底色各归自己的设计语言），
 * 中间的内容区共用同一份 [content]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdminShell(
    metrics: Metrics,
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalUiMode.current == UiMode.Material) {
        M3Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                M3TopAppBar(
                    title = { M3Text(text = title, style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            androidx.compose.material3.Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
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
                PageColumn(metrics) { content() }
            }
        }
    } else {
        Scaffold(
            containerColor = MiuixTheme.colorScheme.surface,
            topBar = {
                TopAppBar(
                    title = title,
                    largeTitle = title,
                    subtitle = subtitle,
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
                PageColumn(metrics) { content() }
            }
        }
    }
}

/** 开关行：miuix / material 两套（material 用 M3Switch，miuix 走 kit 的 [Switch]）。 */
@Composable
private fun AdminSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    if (LocalUiMode.current == UiMode.Material) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                M3Text(
                    text = title,
                    fontSize = PanelTokens.InfoTitleSize,
                    fontWeight = FontWeight(550),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                M3Text(
                    text = summary,
                    fontSize = PanelTokens.InfoValueSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = PanelTokens.InfoValueGap),
                )
            }
            M3Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = PanelColors.onSurfaceVariantSummary,
                modifier = Modifier.width(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                BotFormSwitchRow(
                    title = title,
                    summary = summary,
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                )
            }
        }
    }
}

/** 群列表页顶部那张「使用前提」卡的标题与文案（逐字照用户给的稿，别改写语气）。 */
private const val PREREQ_TITLE = "使用前提"

private const val PREREQ_INTRO =
    "这些得平台给权限，不是我坏了："

private val PREREQ_ITEMS = listOf(
    "1. 先给机器人开群管理权限。QQ 开放平台 → 你的机器人 → 权限 / 事件订阅，把「撤回消息」「群管理」勾上。",
    "2. 勾完还不够，群主得在群里再授权一次。没授权时接口直接拒，日志里有错误码。",
    "3. 「进群欢迎」要靠群成员事件。这个不勾就收不到通知，不是坏了。",
    "4. 禁言和踢人只对普通成员有效，群主和管理员动不了。没权限时我降级成「撤回 + 警告」。",
)

/** 判定方式：下标 ↔ 契约值（顺序就是下拉里的顺序）。 */
private val MODE_VALUES = listOf(
    GroupAdmin.MODE_KEYWORD,
    GroupAdmin.MODE_KEYWORD_MODEL,
    GroupAdmin.MODE_MODEL,
)

private val MODE_LABELS = listOf("关键词", "关键词 + 模型复核（推荐）", "只用模型")

private val MODE_SUMMARIES = listOf(
    "只比对词库，最快，可能杀错",
    "先命中词库再让模型确认，更稳",
    "不看词库，每条都问模型，最花钱",
)

private fun modeIndex(mode: String): Int =
    MODE_VALUES.indexOf(GroupAdmin.normMode(mode)).coerceAtLeast(1)

/** 内置禁言档（分钟）。 */
private val MUTE_PRESETS = listOf(10, 30, 60, 120, 720, 1440)

/** 「自定义禁言」在下拉里的下标（预设之后）。 */
private const val MUTE_CUSTOM = 6

/** 「删除这一级」在下拉里的下标（最后）。 */
private const val MUTE_DELETE = 7

private fun muteText(min: Int): String = when {
    min % 1440 == 0 -> (min / 1440).toString() + " 天"
    min % 60 == 0 -> (min / 60).toString() + " 小时"
    else -> min.toString() + " 分钟"
}

private fun muteItems(current: Int): List<String> =
    MUTE_PRESETS.map { "禁言 " + muteText(it) } +
        listOf(if (muteIndexOf(current) == MUTE_CUSTOM) "自定义：禁言 " + muteText(current) else "自定义…") +
        listOf("删除这一级")

private fun muteIndexOf(min: Int): Int =
    MUTE_PRESETS.indexOf(min).takeIf { it >= 0 } ?: MUTE_CUSTOM

/** 踢出档：1~10 次直接选数字。 */
private val KICK_LABELS = (1..10).map { "第 " + it + " 次" }

/** 阶梯按行号重排 n（删掉中间一级后不留空号）。 */
private fun renumber(steps: List<GroupAdmin.Step>): List<GroupAdmin.Step> =
    steps.mapIndexed { i, s -> s.copy(n = i + 1) }
