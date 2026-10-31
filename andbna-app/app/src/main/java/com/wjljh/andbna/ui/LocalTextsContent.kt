package com.wjljh.andbna.ui

import android.content.Context
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Smartphone
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wjljh.andbna.local.BotHost
import com.wjljh.andbna.local.LocalBot
import com.wjljh.andbna.local.MemoryRepo
import com.wjljh.andbna.local.BotStore
import com.wjljh.andbna.ui.component.PanelAnimatedNotice
import com.wjljh.andbna.ui.component.PanelCheckCircle
import com.wjljh.andbna.ui.component.PanelChip
import com.wjljh.andbna.ui.component.PanelConfirmDialog
import com.wjljh.andbna.ui.component.PanelEntityRow
import com.wjljh.andbna.ui.component.PanelGroupCard
import com.wjljh.andbna.ui.component.PanelInfoRow
import com.wjljh.andbna.ui.component.PanelNotice
import com.wjljh.andbna.ui.component.PanelPillRow
import com.wjljh.andbna.ui.component.PanelScreen
import com.wjljh.andbna.ui.component.PanelSelectBar
import com.wjljh.andbna.ui.component.PanelSecondaryButton
import com.wjljh.andbna.ui.component.PanelTertiaryButton
import com.wjljh.andbna.ui.component.PanelTokens
import com.wjljh.andbna.ui.component.material.SegmentedContentItem
import com.wjljh.andbna.ui.component.material.SegmentedGroup
import com.wjljh.andbna.ui.component.material.SegmentedInfoItem
import com.wjljh.andbna.ui.component.material.SegmentedRowItem
import com.wjljh.andbna.ui.component.material.segmentedGroupInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 多选的选中键：同一个会话 key 在不同 bot 那里是两份记忆，必须带上所属 bot 才分得清。
 * data class 的 equals 正好是「appid + key」两段，集合去重 / 命中判断都直接用它。
 */
private data class SelectedKey(val appid: String, val key: String)

/**
 * 「记忆」页（本机模式）：两套外壳共用同一份数据与业务调用。
 *  · material：每个 bot 一组 SegmentedGroup（行内各自圆角块 + 2dp 空隙），行是 SegmentedRowItem；
 *  · miuix：列表语言照 SukiSU SuperUserMiuix.kt:550-624：
 *  · 行 = 前置图标 40dp（圆角 12dp）+ 主标题 17sp(550) + 副标题 14sp + 右侧动作，行高 ≥64dp、行间 0.5dp
 *  · 批量操作收在底部胶囊行（35dp / 40dp 圆角 / 间隔 8dp）
 *  · 会话展开用 animateContentSize()；卡片无卡标题；提示条走 AnimatedVisibility
 *
 * 为什么按 bot 分开：QQ 的 openid 是按 bot 分配的 —— 同一个人在不同 bot 那里
 * openid 完全不同，所以记忆、称呼、群记录必须每个 bot 一份，混在一起就全乱了。
 * 读走 BotHost.memoryList；写（删 / 清）走 MemoryRepo —— 它按运行模式分流到本机数据或面板。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LocalTextsContent(
    metrics: Metrics,
    bottomInset: Dp = 0.dp,
    onOpen: (String) -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // 读盘解析放 IO：组合期一次，不阻塞首帧
    var bots by remember { mutableStateOf<List<LocalBot>>(emptyList()) }
    LaunchedEffect(Unit) {
        bots = withContext(Dispatchers.IO) { BotStore.load(ctx) }
    }
    var sessions by remember { mutableStateOf<Map<String, List<org.json.JSONObject>>>(emptyMap()) }
    var msg by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    var expandedBot by remember { mutableStateOf("") }
    // 多选（相册式）：sel 存「哪个 bot 的哪个会话」—— 同一个 key 在不同 bot 那里是两份记忆，
    // 只存 key 分不清，所以带上 appid。长按任意行进入并选中它；进入后点任何 bot 分组里的行
    // 都能加选 / 取消；退到 0 项自动退出（底栏随之回来）。
    var sel by remember { mutableStateOf<Set<SelectedKey>>(emptySet()) }
    val selecting = sel.isNotEmpty()
    var confirmDel by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    // 撤销：undoable 只活 10 秒；undoAppids 记住这次删过哪些 bot（跨 bot 时逐个 bot 撤）
    var undoable by remember { mutableStateOf(false) }
    var undoAppids by remember { mutableStateOf<List<String>>(emptyList()) }
    var undoSeq by remember { mutableIntStateOf(0) }
    LaunchedEffect(undoSeq) {
        if (undoSeq > 0) {
            delay(10_000)
            undoable = false
        }
    }
    // 本机记忆页也要两套外壳：miuix 走 PanelGroupCard，material 走 SegmentedGroup
    // （以前这里只有 PanelGroupCard，material 模式下就是一页 miuix 卡片，跟别处不一致）
    val material = LocalUiMode.current == UiMode.Material

    LaunchedEffect(tick, bots.size) {
        sessions = readLocalSessions(ctx, bots)
    }

    // 多选态把底部胶囊导航栏收走（App.kt 据这个 local 决定画不画底栏），
    // 退出（取消 / 删除完成 / 撤销后 / 离开本页）就还回来。
    val bottomBarVisible = LocalBottomBarVisible.current
    LaunchedEffect(selecting) { bottomBarVisible.value = !selecting }
    DisposableEffect(Unit) { onDispose { bottomBarVisible.value = true } }

    // 多选：退出 / 进入 / 选中切换 / 全选 —— 都只是集合里多一个或少一个 SelectedKey
    fun exitSelect() {
        sel = emptySet()
    }

    fun enterSelect(appid: String, key: String) {
        sel = sel + SelectedKey(appid, key)
    }

    fun toggleSelect(appid: String, key: String) {
        val k = SelectedKey(appid, key)
        sel = if (k in sel) sel - k else sel + k
    }

    /** 全选 = 当前列表里所有 bot 的所有会话 */
    fun selectAll() {
        sel = sessions.entries.flatMap { (appid, list) ->
            list.map { SelectedKey(appid, it.optString("key")) }
        }.toSet()
    }

    /** 行尾那个「删」：单个 bot 的一次批量删除 */
    fun deleteKeys(appid: String, keys: List<String>) {
        if (appid.isBlank() || keys.isEmpty()) return
        scope.launch {
            val r = withContext(Dispatchers.IO) { MemoryRepo.deleteMany(ctx, appid, keys) }
            msg = r.text
            undoable = r.undo
            undoAppids = if (r.undo) listOf(appid) else emptyList()
            undoSeq++
            exitSelect()
            tick++
        }
    }

    /**
     * 多选删除：按 appid 分组，每组一次 MemoryRepo.deleteMany（底层删完会自动重启那个 bot）。
     * 不绕过 MemoryRepo：分流、回执文案、undo 记录都在它里面。
     */
    fun deleteSelected() {
        if (sel.isEmpty()) return
        val groups = sel.groupBy({ it.appid }, { it.key })
        val total = sel.size
        scope.launch {
            val rs = withContext(Dispatchers.IO) {
                groups.map { (appid, keys) -> appid to MemoryRepo.deleteMany(ctx, appid, keys) }
            }
            msg = if (rs.size == 1) {
                rs.first().second.text
            } else {
                rs.joinToString("；") { (appid, r) ->
                    (bots.firstOrNull { it.appid == appid }?.title ?: appid) + "：" + r.text
                }
            }
            undoable = rs.all { it.second.undo }
            undoAppids = if (undoable) rs.map { it.first } else emptyList()
            undoSeq++
            Log.i(TAG, "多选删除：$total 段 / ${groups.size} 个 bot")
            exitSelect()
            tick++
        }
    }

    // 下拉刷新：真去重读 memory.json（BotHost.memoryList），失败也把 refreshing 收回 false
    fun refreshMemory() {
        scope.launch {
            refreshing = true
            try {
                val m = readLocalSessions(ctx, bots)
                sessions = m
                val n = m.values.sumOf { it.size }
                Log.i(TAG, "记忆刷新：$n 段")
                if (!undoable) msg = "记忆刷新：$n 段"
            } catch (e: Throwable) {
                undoable = false
                msg = "刷新失败：" + (e.message ?: "未知错误")
                Log.e(TAG, "记忆刷新失败：" + e.message, e)
            } finally {
                refreshing = false
            }
        }
    }

    val allSessions = sessions.values.flatten()
    val enabledBots = bots.count { it.enabled }
    val totalEntries = allSessions.sumOf { it.optInt("count") }
    // 称呼表：记忆行里出现过的 who，按出现次数排
    val addressBook = remember(allSessions) {
        allSessions
            .flatMap { c ->
                val who = c.optJSONArray("who") ?: JSONArray()
                (0 until who.length()).map { who.optString(it) }
            }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
    }

    // 系统返回键：多选态只退出多选、留在记忆页；非多选态不拦，走默认回退
    BackHandler(enabled = selecting) { exitSelect() }

    // 多选条挂在顶栏槽位（PanelScreen 的 topBar，在 LazyColumn 之外）：滚动列表时纹丝不动
    PanelScreen(
        bottomInset = bottomInset,
        refreshing = refreshing,
        onRefresh = if (selecting) null else ({ refreshMemory() }),
        topBar = if (selecting) ({
            PanelSelectBar(
                count = sel.size,
                onSelectAll = { selectAll() },
                onCancel = { exitSelect() },
                onDelete = { if (sel.isNotEmpty()) confirmDel = true },
            )
        }) else null,
    ) {
        item(key = "notice") {
            PanelAnimatedNotice(visible = msg.isNotBlank()) {
                MemoryNotice(msg, undoable) {
                    scope.launch {
                        val appids = undoAppids
                        val rs = withContext(Dispatchers.IO) { appids.map { MemoryRepo.undo(ctx, it) } }
                        msg = rs.joinToString("；") { it.text }
                        undoable = false
                        undoAppids = emptyList()
                        tick++
                    }
                }
            }
        }

        item(key = "overview") {
            if (material) {
                SegmentedGroup(modifier = Modifier.segmentedGroupInsets()) {
                    item {
                        SegmentedInfoItem(
                            title = "启用 bot",
                            value = "${enabledBots} 个",
                            icon = Icons.Filled.Smartphone,
                        )
                    }
                    item {
                        SegmentedInfoItem(
                            title = "会话",
                            value = "${allSessions.size} 个",
                            icon = Icons.Filled.Forum,
                        )
                    }
                    item {
                        SegmentedInfoItem(
                            title = "条目",
                            value = "${totalEntries} 条",
                            icon = Icons.Filled.Notes,
                        )
                    }
                }
            } else {
                PanelGroupCard {
                    PanelInfoRow(Icons.Filled.Smartphone, "启用 bot", "${enabledBots} 个")
                    PanelInfoRow(Icons.Filled.Forum, "会话", "${allSessions.size} 个")
                    PanelInfoRow(Icons.Filled.Notes, "条目", "${totalEntries} 条", last = true)
                }
            }
        }

        bots.forEach { bot ->
            val chats = sessions[bot.appid].orEmpty()
            val expanded = expandedBot == bot.appid
            // 多选中把每一组都展开：跨 bot 选择时不能有「选了却看不见」的行
            val shown = if (expanded || selecting) chats else chats.take(4)
            item(key = "bot-" + bot.appid) {
                if (material) {
                    LocalBotSegmentedGroup(
                        bot = bot,
                        appid = bot.appid,
                        chats = chats,
                        shown = shown,
                        expanded = expanded,
                        selecting = selecting,
                        selected = sel,
                        onToggleSelect = { key -> toggleSelect(bot.appid, key) },
                        onLongPress = { key -> enterSelect(bot.appid, key) },
                        onToggleExpand = { expandedBot = if (expanded) "" else bot.appid },
                        onDelete = { key -> deleteKeys(bot.appid, listOf(key)) },
                        onOpen = { onOpen(bot.appid) },
                    )
                } else {
                PanelGroupCard {
                    // 卡头：17sp(550) 标题 + 14sp 副标题（SukiSU 的 BasicComponent 头两级）
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = bot.title,
                                fontSize = PanelTokens.EntityTitleSize,
                                fontWeight = FontWeight(550),
                                color = PanelColors.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = buildString {
                                    append(if (bot.enabled) "已启用" else "未启用")
                                    if (bot.persona.isNotBlank()) append(" · ").append(bot.persona)
                                    append(" · ${chats.size} 个会话")
                                },
                                fontSize = PanelTokens.EntitySubtitleSize,
                                color = PanelColors.onSurfaceVariantSummary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(top = PanelTokens.ContentGap),
                        thickness = PanelTokens.Divider,
                        color = PanelColors.dividerLine,
                    )
                    // 会话行：展开/收起走 animateContentSize()
                    Column(Modifier.animateContentSize()) {
                        if (chats.isEmpty()) {
                            Text(
                                text = "这个 bot 还没有记忆",
                                fontSize = PanelTokens.EntitySubtitleSize,
                                color = PanelColors.onSurfaceVariantSummary,
                                modifier = Modifier.padding(vertical = PanelTokens.ContentGap),
                            )
                        }
                        shown.forEach { c ->
                            val key = c.optString("key")
                            val picked = SelectedKey(bot.appid, key)
                            PanelEntityRow(
                                icon = Icons.Filled.Forum,
                                title = "${c.optString("type")} · ${key.take(18)}",
                                subtitle = "${c.optInt("count")} 条 · ${c.optString("last")}",
                                // 多选：行首选择圈（任一分组都看得到）；长按进多选，进入后单击 = 选中/取消
                                leading = {
                                    if (selecting) {
                                        PanelCheckCircle(
                                            checked = picked in sel,
                                            modifier = Modifier.padding(end = PanelTokens.ContentGap),
                                        )
                                    }
                                },
                                onClick = if (selecting) ({ toggleSelect(bot.appid, key) }) else null,
                                onLongClick = { if (!selecting) enterSelect(bot.appid, key) },
                                trailing = {
                                    if (!selecting) {
                                        PanelTertiaryButton(text = "删", onClick = { deleteKeys(bot.appid, listOf(key)) })
                                    }
                                },
                                // 记忆页长按只进多选，不要 Tilt 那一歪
                                feedback = false,
                            )
                        }
                    }
                    if (chats.size > 4) {
                        PanelPillRow {
                            PanelSecondaryButton(
                                text = if (expanded) "收起" else "看全部 ${chats.size} 个",
                                onClick = { expandedBot = if (expanded) "" else bot.appid },
                                modifier = Modifier.weight(1f),
                            )
                            PanelTertiaryButton(
                                text = "详情",
                                onClick = { onOpen(bot.appid) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                }
            }
        }

        if (bots.isEmpty()) {
            item(key = "empty") {
                if (material) {
                    SegmentedGroup(modifier = Modifier.segmentedGroupInsets()) {
                        item {
                            SegmentedRowItem(
                                title = "还没有 bot",
                                subtitle = "去「Bot」页加一个，记忆才有地方落",
                                icon = Icons.Filled.Smartphone,
                            )
                        }
                    }
                } else {
                    PanelGroupCard {
                        Text(
                            text = "还没有 bot",
                            fontSize = PanelTokens.EntityTitleSize,
                            fontWeight = FontWeight(550),
                            color = PanelColors.onSurface,
                        )
                        Text(
                            text = "去「Bot」页加一个，记忆才有地方落",
                            fontSize = PanelTokens.EntitySubtitleSize,
                            color = PanelColors.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = PanelTokens.InfoValueGap),
                        )
                    }
                }
            }
        }

        item(key = "names") {
            if (material) {
                SegmentedGroup(modifier = Modifier.segmentedGroupInsets()) {
                    item {
                        SegmentedRowItem(
                            title = "称呼表",
                            subtitle = "只读",
                            icon = Icons.Filled.Person,
                        )
                    }
                    if (addressBook.isEmpty()) {
                        item {
                            SegmentedRowItem(
                                title = "称呼表还是空的",
                                subtitle = "先聊几句再回来看",
                                icon = Icons.Filled.Person,
                            )
                        }
                    } else {
                        addressBook.take(8).forEach { (name, count) ->
                            item {
                                SegmentedRowItem(
                                    title = name,
                                    subtitle = "记忆里出现 $count 条",
                                    icon = Icons.Filled.Person,
                                )
                            }
                        }
                    }
                }
            } else {
            PanelGroupCard {
                Text(
                    text = "称呼表",
                    fontSize = PanelTokens.EntityTitleSize,
                    fontWeight = FontWeight(550),
                    color = PanelColors.onSurface,
                )
                HorizontalDivider(
                    modifier = Modifier.padding(top = PanelTokens.ContentGap),
                    thickness = PanelTokens.Divider,
                    color = PanelColors.dividerLine,
                )
                if (addressBook.isEmpty()) {
                    Text(
                        text = "记忆里还没有称呼 —— 先聊几句再回来看",
                        fontSize = PanelTokens.EntitySubtitleSize,
                        color = PanelColors.onSurfaceVariantSummary,
                        modifier = Modifier.padding(vertical = PanelTokens.ContentGap),
                    )
                } else {
                    addressBook.take(8).forEachIndexed { i, (name, count) ->
                        PanelEntityRow(
                            icon = Icons.Filled.Person,
                            title = name,
                            subtitle = "记忆里出现 $count 条",
                            divider = i != minOf(addressBook.size, 8) - 1,
                        )
                    }
                }
            }
            }
        }
    }

    // 二次确认：多选删除一定先弹这个；文案写明段数与「10 秒内可撤销」
    PanelConfirmDialog(
        show = confirmDel,
        title = "删除所选记忆",
        body = "将删除 " + sel.size + " 段记忆，10 秒内可撤销。",
        confirmLabel = "删除 " + sel.size + " 段",
        onConfirm = {
            confirmDel = false
            deleteSelected()
        },
        onDismiss = { confirmDel = false },
    )
}

/**
 * material 下「某个 bot 的记忆」那一组：
 * 组内每行各自一个圆角块、行间 2dp 空隙，不画分隔线。
 */
@Composable
private fun LocalBotSegmentedGroup(
    bot: LocalBot,
    appid: String,
    chats: List<org.json.JSONObject>,
    shown: List<org.json.JSONObject>,
    expanded: Boolean,
    selecting: Boolean,
    selected: Set<SelectedKey>,
    onToggleSelect: (String) -> Unit,
    onLongPress: (String) -> Unit,
    onToggleExpand: () -> Unit,
    onDelete: (String) -> Unit,
    onOpen: () -> Unit,
) {
    SegmentedGroup(modifier = Modifier.segmentedGroupInsets()) {
        item {
            SegmentedRowItem(
                title = bot.title,
                subtitle = (if (bot.enabled) "已启用" else "未启用") +
                    (if (bot.persona.isNotBlank()) " · " + bot.persona else "") +
                    " · " + chats.size + " 个会话",
                icon = Icons.Filled.Smartphone,
            )
        }
        if (chats.isEmpty()) {
            item {
                SegmentedRowItem(
                    title = "这个 bot 还没有记忆",
                    subtitle = "先聊几句再回来看",
                    icon = Icons.Filled.Forum,
                )
            }
        }
        shown.forEach { c ->
            val key = c.optString("key")
            item {
                SegmentedRowItem(
                    title = c.optString("type") + " · " + key.take(18),
                    subtitle = c.optInt("count").toString() + " 条 · " + c.optString("last"),
                    icon = Icons.Filled.Forum,
                    // 多选：行首选择圈；长按进多选，进入后单击 = 选中/取消
                    leading = {
                        if (selecting) {
                            PanelCheckCircle(
                                checked = SelectedKey(appid, key) in selected,
                                modifier = Modifier.padding(end = 12.dp),
                            )
                        }
                    },
                    onClick = if (selecting) ({ onToggleSelect(key) }) else null,
                    onLongClick = { if (!selecting) onLongPress(key) },
                    trailing = {
                        if (!selecting) PanelTertiaryButton(text = "删", onClick = { onDelete(key) })
                    },
                )
            }
        }
        if (chats.size > 4) {
            item {
                SegmentedContentItem {
                    PanelPillRow {
                        PanelSecondaryButton(
                            text = if (expanded) "收起" else "看全部 " + chats.size + " 个",
                            onClick = onToggleExpand,
                            modifier = Modifier.weight(1f),
                        )
                        PanelTertiaryButton(
                            text = "详情",
                            onClick = onOpen,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 提示条 +（可选）撤销按钮。
 * 删除成功后 10 秒内可点「撤销」，过期按钮消失（undo 文件本身留到下一次删除）。
 */
@Composable
private fun MemoryNotice(text: String, undoVisible: Boolean, onUndo: () -> Unit) {
    if (!undoVisible) {
        PanelNotice(text)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { PanelNotice(text) }
        PanelTertiaryButton(text = "撤销", onClick = onUndo)
    }
}

/** 记忆详情（二级页）：群 openid / 主人 openid / 全部会话，规则与旧版一致。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemoryDetailContent(
    metrics: Metrics,
    appid: String,
    bottomInset: Dp = 0.dp,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var chats by remember { mutableStateOf<List<org.json.JSONObject>>(emptyList()) }
    var msg by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }
    var undoable by remember { mutableStateOf(false) }
    var undoSeq by remember { mutableIntStateOf(0) }
    LaunchedEffect(undoSeq) {
        if (undoSeq > 0) {
            delay(10_000)
            undoable = false
        }
    }

    LaunchedEffect(appid, tick) {
        chats = withContext(Dispatchers.IO) {
            val list = ArrayList<org.json.JSONObject>()
            try {
                val arr = JSONArray(BotHost.memoryList(ctx, appid))
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { list.add(it) }
            } catch (_: Exception) {
            }
            list
        }
    }

    // 群 openid：记忆键就是 g + group_openid
    val totalCount = chats.sumOf { it.optInt("count") }
    val groups = chats.filter { it.optString("key").startsWith("g") }
    val privates = chats.filter { it.optString("key").startsWith("p") }

    PanelScreen(bottomInset) {
        item(key = "notice") {
            PanelAnimatedNotice(visible = msg.isNotBlank()) {
                MemoryNotice(msg, undoable) {
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { MemoryRepo.undo(ctx, appid) }
                        msg = r.text
                        undoable = false
                        tick++
                    }
                }
            }
        }

        item(key = "groups") {
            PanelGroupCard {
                Text(
                    text = "群 openid（${groups.size}）",
                    fontSize = PanelTokens.EntityTitleSize,
                    fontWeight = FontWeight(550),
                    color = PanelColors.onSurface,
                )
                Text(
                    text = "记忆键就是 g + group_openid",
                    fontSize = PanelTokens.EntitySubtitleSize,
                    color = PanelColors.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = PanelTokens.InfoValueGap),
                )
                if (groups.isEmpty()) {
                    Text(
                        text = "还没有记录到群",
                        fontSize = PanelTokens.EntitySubtitleSize,
                        color = PanelColors.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = PanelTokens.ContentGap),
                    )
                }
                FlowRow(
                    modifier = Modifier.padding(top = PanelTokens.ContentGap),
                    horizontalArrangement = Arrangement.spacedBy(PanelTokens.ButtonGap),
                    verticalArrangement = Arrangement.spacedBy(PanelTokens.ButtonGap),
                ) {
                    groups.forEach { g ->
                        PanelChip(text = g.optString("key").removePrefix("g"), onClick = {})
                    }
                }
            }
        }

        item(key = "sessions") {
            PanelGroupCard {
                Text(
                    text = "记忆（$totalCount 条 / ${chats.size} 个会话）",
                    fontSize = PanelTokens.EntityTitleSize,
                    fontWeight = FontWeight(550),
                    color = PanelColors.onSurface,
                )
                Text(
                    text = "私聊 ${privates.size} 个 · 群 ${groups.size} 个",
                    fontSize = PanelTokens.EntitySubtitleSize,
                    color = PanelColors.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = PanelTokens.InfoValueGap),
                )
                HorizontalDivider(
                    modifier = Modifier.padding(top = PanelTokens.ContentGap),
                    thickness = PanelTokens.Divider,
                    color = PanelColors.dividerLine,
                )
                chats.forEachIndexed { i, c ->
                    val key = c.optString("key")
                    PanelEntityRow(
                        icon = if (key.startsWith("g")) Icons.Filled.Forum else Icons.Filled.Person,
                        title = "${c.optString("type")} · ${key.take(18)}",
                        subtitle = "${c.optInt("count")} 条 · ${c.optString("last")}",
                        divider = i != chats.lastIndex,
                        trailing = {
                            PanelTertiaryButton(
                                text = "删",
                                onClick = {
                                    scope.launch {
                                        val r = withContext(Dispatchers.IO) { MemoryRepo.delete(ctx, appid, key) }
                                        msg = r.text
                                        undoable = r.undo
                                        undoSeq++
                                        tick++
                                    }
                                },
                            )
                        },
                    )
                }
                PanelPillRow(modifier = Modifier.padding(top = PanelTokens.ButtonGap)) {
                    PanelSecondaryButton(
                        text = "刷新",
                        onClick = { tick++ },
                        modifier = Modifier.weight(1f),
                    )
                    PanelTertiaryButton(
                        text = "清空该 bot",
                        onClick = {
                            scope.launch {
                                msg = withContext(Dispatchers.IO) { MemoryRepo.clear(ctx, appid).text }
                                undoable = false
                                tick++
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}


private const val TAG = "LocalTexts"

/** 重读每个 bot 的会话列表（BotHost.memoryList）：首帧和下拉刷新共用同一份读取逻辑。 */
private suspend fun readLocalSessions(
    ctx: Context,
    bots: List<LocalBot>,
): Map<String, List<org.json.JSONObject>> = withContext(Dispatchers.IO) {
    val out = HashMap<String, List<org.json.JSONObject>>()
    bots.forEach { b ->
        val list = ArrayList<org.json.JSONObject>()
        try {
            val arr = JSONArray(BotHost.memoryList(ctx, b.appid))
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { list.add(it) }
        } catch (_: Exception) {
        }
        out[b.appid] = list
    }
    out
}

