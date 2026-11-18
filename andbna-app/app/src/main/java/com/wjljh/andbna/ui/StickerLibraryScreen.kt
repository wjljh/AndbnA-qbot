package com.wjljh.andbna.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold as M3Scaffold
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.TopAppBar as M3TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wjljh.andbna.local.BotStore
import com.wjljh.andbna.local.BotStore.stickerCooldownMin
import com.wjljh.andbna.local.BotStore.stickerEnabled
import com.wjljh.andbna.local.BotStore.stickerEveryN
import com.wjljh.andbna.local.StickerStore
import com.wjljh.andbna.ui.component.BotFormField
import com.wjljh.andbna.ui.component.BotFormSection
import com.wjljh.andbna.ui.component.BotFormSwitchRow
import com.wjljh.andbna.ui.component.PanelConfirmDialog
import com.wjljh.andbna.ui.component.PanelEmptyState
import com.wjljh.andbna.ui.component.PanelGroupCard
import com.wjljh.andbna.ui.component.PanelMetrics
import com.wjljh.andbna.ui.component.AppToast
import com.wjljh.andbna.ui.component.PanelPillRow
import com.wjljh.andbna.ui.component.PanelPrimaryButton
import com.wjljh.andbna.ui.component.PanelSecondaryButton
import com.wjljh.andbna.ui.component.PanelTokens
import com.wjljh.andbna.ui.component.SecondaryTopBar
import com.wjljh.andbna.ui.component.StickerCoolDialog
import com.wjljh.andbna.ui.component.material.SegmentedDropdownItem
import com.wjljh.andbna.ui.component.modeClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 「表情 / 图片库」二级页（Bot 编辑页「称呼」下面那张卡片进来）。
 *
 * 落盘：图片库在 [StickerStore]（<botdir>/stickers/ + library.json），表情节奏两行在
 * <botdir>/sticker_conf.json（null = 跟随全局）。本页只负责：
 * 顶部开关（llm.json 的 sticker_enabled，默认开）→ 节奏两行 → 容量小字 → 每行 3 个的网格
 * → 导入 / 编辑 / 删除。
 *
 * 导入走 SAF 的 OpenMultipleDocuments（image/ 通配），不申请存储权限：选中的 Uri 逐个校验格式与
 * ≤5 MB / 整库 ≤200 MB，复制进 stickers/<id>.<ext>，然后**立刻弹编辑框要求写描述** —— 描述为空的
 * 条目 Python 侧不进候选，所以不写描述等于白导。
 *
 * 缩略图只解码 .thumb/<id>.jpg（[StickerStore.decodeThumb] 负责按需生成），页面里用一份
 * id → ImageBitmap 的内存缓存垫着：滚动时既不重解原图，也不重解缩略图。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerLibraryScreen(metrics: Metrics, appid: String, botName: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var items by remember(appid) { mutableStateOf<List<StickerStore.Item>>(emptyList()) }
    var enabled by remember(appid) { mutableStateOf(true) }
    // 全局那两档（只读）：写在「跟随全局（现在：…）」那段里，不用跳回设置页看
    var globalEvery by remember(appid) { mutableStateOf(10) }
    var globalCool by remember(appid) { mutableStateOf(10) }
    // 本 bot 的覆盖：null = 跟随全局（sticker_conf.json 里是显式 null）
    var overEvery by remember(appid) { mutableStateOf<Int?>(null) }
    var overCool by remember(appid) { mutableStateOf<Int?>(null) }
    // 冷却「自定义…」：就地弹一格分钟数
    var showCoolCustom by remember(appid) { mutableStateOf(false) }
    // 编辑框：showEdit 单独一个状态，关的时候先把 show 收起来、条目还留着，才有退出动画
    var editing by remember(appid) { mutableStateOf<StickerStore.Item?>(null) }
    var showEdit by remember(appid) { mutableStateOf(false) }
    var deleting by remember(appid) { mutableStateOf<StickerStore.Item?>(null) }
    var showDelete by remember(appid) { mutableStateOf(false) }
    // 缩略图内存缓存（id → 已解码的 ImageBitmap）：滚动不重解
    val thumbs = remember(appid) { mutableStateMapOf<String, ImageBitmap>() }

    LaunchedEffect(appid) {
        // 一次 IO 里把库和三个配置值都读出来（prefs 已缓存在内存里，这里只是别切线程）
        val (list, conf) = withContext(Dispatchers.IO) {
            StickerStore.load(ctx, appid) to
                Triple(ctx.stickerEnabled, ctx.stickerEveryN, ctx.stickerCooldownMin)
        }
        // 覆盖读不出来（文件不在 / 半截 JSON）就是全跟随全局
        val over = withContext(Dispatchers.IO) { BotStore.stickerOverride(ctx, appid) }
        items = list
        enabled = conf.first
        globalEvery = conf.second
        globalCool = conf.third
        overEvery = over.everyN
        overCool = over.cooldownMin
    }

    // 只给「还没有缓存」的条目补缩略图（在 IO 里解码，一次补齐）
    LaunchedEffect(items) {
        val missing = items.filter { thumbs[it.id] == null }
        if (missing.isEmpty()) return@LaunchedEffect
        val got = withContext(Dispatchers.IO) {
            missing.mapNotNull { one ->
                StickerStore.decodeThumb(ctx, appid, one)?.let { bmp -> one.id to bmp.asImageBitmap() }
            }
        }
        got.forEach { pair -> thumbs[pair.first] = pair.second }
    }

    BackHandler { onBack() }

    /** 开关：prefs + llm.json 各一份，Python 侧每条消息重读文件 —— 不用重启 bot。 */
    fun switchEnabled(on: Boolean) {
        enabled = on
        scope.launch {
            withContext(Dispatchers.IO) {
                ctx.stickerEnabled = on
                BotStore.writeLlmJson(ctx)
            }
            AppToast.show(if (on) "已允许 bot 自己发表情" else "已关闭：一条都不发，库还留着")
        }
    }

    /**
     * 两个下拉：只写本 bot 的 <botdir>/sticker_conf.json（null = 跟随全局），
     * Python 侧每次现读这份文件 —— 改完下一次回复就按新节奏走。
     */
    fun saveRhythm(nextEvery: Int?, nextCool: Int?) {
        overEvery = nextEvery
        overCool = nextCool
        scope.launch {
            withContext(Dispatchers.IO) {
                BotStore.setStickerOverride(
                    ctx, appid, BotStore.StickerOverride(nextEvery, nextCool),
                )
            }
            AppToast.show("已保存，下一次回复就按新节奏")
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val (out, list) = withContext(Dispatchers.IO) {
                val o = StickerStore.importUris(ctx, appid, uris)
                o to StickerStore.load(ctx, appid)
            }
            items = list
            AppToast.show(
                when {
                    out.added.isNotEmpty() && out.errors.isEmpty() ->
                        "已导入 " + out.added.size + " 张：给每张写个描述，空着不进候选"
                    out.added.isNotEmpty() ->
                        "导入 " + out.added.size + " 张，" + out.errors.size + " 张没成：" + out.errors.first()
                    else -> "没导入：" + (out.errors.firstOrNull() ?: "没有可用的图片")
                }
            )
            // 导入后立刻要求写描述（多张就先填第一张，其余的网格里点开补）
            val first = out.added.firstOrNull()
            if (first != null) {
                editing = first
                showEdit = true
            }
        }
    }

    // 频率那行：跟随全局 + 6 档（-1 就是模型判断），首项把全局值写出来
    val curEvery = overEvery
    val everyItems: List<String> =
        listOf(followEveryLabel(globalEvery)) + BotStore.stickerEveryOptions.map { BotStore.stickerEveryLabel(it) }
    val everyIndex: Int = if (curEvery == null) 0 else {
        val p = BotStore.stickerEveryOptions.indexOf(curEvery)
        if (p >= 0) p + 1 else 0
    }

    // 冷却那行：跟随全局 + 5 档 + 自定义（正用自定义就把真实分钟数写进最后一项）
    val curCool = overCool
    val coolCustomIndex: Int = BotStore.stickerCooldownOptions.size + 1
    val coolItems: List<String> =
        listOf(followCoolLabel(globalCool)) +
            BotStore.stickerCooldownOptions.map { BotStore.stickerCooldownLabel(it) } +
            if (curCool != null && BotStore.stickerCooldownOptions.indexOf(curCool) < 0) {
                "自定义 " + BotStore.stickerCooldownLabel(curCool)
            } else {
                "自定义…"
            }
    val coolIndex: Int = if (curCool == null) 0 else {
        val p = BotStore.stickerCooldownOptions.indexOf(curCool)
        if (p >= 0) p + 1 else coolCustomIndex
    }

    val used = StickerStore.usedBytes(items)

    val body: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = PanelTokens.PageHorizontal, vertical = PanelTokens.PageTop),
            ) {
                // ① 开关 + 容量
                BotFormSection(title = "表情 / 图片库") {
                    BotFormSwitchRow(
                        title = "允许 bot 自己发表情",
                        summary = if (enabled) "开着 · 它会偶尔挑一张发" else "关着 · 一条都不发，图还留着",
                        checked = enabled,
                        onCheckedChange = { switchEnabled(it) },
                    )
                    Spacer(Modifier.height(PanelTokens.ContentGap))
                    StickerRhythmRow(
                        title = "自己发图的频率",
                        summary = "隔几条回一张，模型也能自己挑",
                        items = everyItems,
                        selectedIndex = everyIndex,
                        onSelectedIndexChange = { i ->
                            if (i == 0) {
                                saveRhythm(null, overCool)
                            } else {
                                BotStore.stickerEveryOptions.getOrNull(i - 1)?.let { v ->
                                    saveRhythm(v, overCool)
                                }
                            }
                        },
                    )
                    StickerRhythmRow(
                        title = "同一张图的冷却",
                        summary = "同一张图多久内不重复发",
                        items = coolItems,
                        selectedIndex = coolIndex,
                        onSelectedIndexChange = { i ->
                            when {
                                // 首项「跟随全局」：把这个键写成 null
                                i == 0 -> saveRhythm(overEvery, null)
                                i == coolCustomIndex -> showCoolCustom = true
                                else -> BotStore.stickerCooldownOptions.getOrNull(i - 1)?.let { v ->
                                    saveRhythm(overEvery, v)
                                }
                            }
                        },
                    )
                    Spacer(Modifier.height(PanelTokens.ContentGap))
                    Text(
                        text = "已用 " + StickerStore.mbText(used) + " MB / 200 MB",
                        fontSize = 13.sp,
                        fontWeight = FontWeight(550),
                        color = PanelColors.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                    Text(
                        text = if (items.isEmpty()) "共 0 张" else "共 " + items.size + " 张 · 单张 ≤5 MB",
                        fontSize = 13.sp,
                        color = PanelColors.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    )
                }

                // ② 网格（每行 3 个）或空态
                if (items.isEmpty()) {
                    PanelGroupCard {
                        PanelEmptyState(
                            icon = Icons.Rounded.Image,
                            title = "还没有表情：点右下角导入",
                            body = "png / jpg / gif / webp / bmp 都能用。单张 ≤5 MB，整库 ≤200 MB。",
                        )
                    }
                } else {
                    items.chunked(3).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                        ) {
                            row.forEach { one ->
                                StickerTile(
                                    item = one,
                                    thumb = thumbs[one.id],
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        editing = one
                                        showEdit = true
                                    },
                                )
                            }
                            // 末行不满 3 个：用等宽空位占住，缩略图不会被拉大
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }

                // 给右下角那颗导入按钮留位置，免得最后一行被它压住
                Spacer(Modifier.height(ImportClearance))
            }

            // 右下角导入（空态文案里说的就是它）
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = PanelTokens.PageHorizontal,
                        bottom = PanelTokens.PageTop + PanelTokens.CardSpacing,
                    ),
            ) {
                PanelPrimaryButton(text = "导入图片", onClick = { picker.launch(arrayOf("image/*")) })
            }
        }
    }

    if (LocalUiMode.current == UiMode.Material) {
        M3Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                M3TopAppBar(
                    title = { M3Text(text = "表情 / 图片库", style = MaterialTheme.typography.titleLarge) },
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
            Box(Modifier.fillMaxSize().padding(pad)) { body() }
        }
    } else {
        Scaffold(
            containerColor = MiuixTheme.colorScheme.surface,
            topBar = { SecondaryTopBar(title = "表情 / 图片库", subtitle = botName, onBack = onBack) },
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) { body() }
        }
    }

    // 冷却「自定义…」：填分钟数，只改本 bot 的 cooldown_min（1~10080）
    StickerCoolDialog(
        show = showCoolCustom,
        initial = curCool ?: globalCool,
        onConfirm = { m ->
            showCoolCustom = false
            saveRhythm(overEvery, m.coerceIn(1, BotStore.STICKER_COOLDOWN_MAX))
        },
        onDismiss = { showCoolCustom = false },
    )

    // 编辑描述 / 标签（+ 删除入口）
    val editItem = editing
    if (editItem != null) {
        StickerEditDialog(
            show = showEdit,
            item = editItem,
            thumb = thumbs[editItem.id],
            onSave = { desc, tags ->
                showEdit = false
                scope.launch {
                    withContext(Dispatchers.IO) {
                        StickerStore.update(ctx, appid, editItem.id, desc, tags)
                    }
                    items = withContext(Dispatchers.IO) { StickerStore.load(ctx, appid) }
                    AppToast.show("已保存描述：" + desc.trim())
                }
            },
            onDelete = {
                showEdit = false
                deleting = editItem
                showDelete = true
            },
            onDismiss = { showEdit = false },
        )
    }

    PanelConfirmDialog(
        show = showDelete,
        title = "删除这张表情",
        body = deleting?.let { "原图和缩略图一起删，删了没法恢复。" } ?: "",
        confirmLabel = "删除",
        onConfirm = {
            val target = deleting
            showDelete = false
            if (target != null) {
                scope.launch {
                    withContext(Dispatchers.IO) { StickerStore.delete(ctx, appid, target.id) }
                    thumbs.remove(target.id)
                    items = withContext(Dispatchers.IO) { StickerStore.load(ctx, appid) }
                    AppToast.show("已删除：" + target.desc.ifBlank { target.id })
                }
            }
        },
        onDismiss = { showDelete = false },
    )
}

/** 右下角导入按钮占掉的高度：正文最后留这么多，免得末行被按钮压住。 */
private val ImportClearance: Dp = 72.dp

/** 首项文案：跟随全局，顺手把全局那档写出来（-1 = 模型判断）。 */
private fun followEveryLabel(globalEvery: Int): String =
    "跟随全局（现在：" + (if (globalEvery < 0) "模型判断" else "每 " + globalEvery + " 条") + "）"

/** 首项文案：跟随全局，顺手把全局的冷却分钟数写出来。 */
private fun followCoolLabel(globalCool: Int): String =
    "跟随全局（现在：" + BotStore.stickerCooldownLabel(globalCool) + "）"

/**
 * 一行下拉：两个模式共用同一份内容 —— miuix 走 kit 原生 [OverlayDropdownPreference]，
 * material 走 [SegmentedDropdownItem]（点开 DropdownMenu）。不要换成一行按钮。
 */
@Composable
private fun StickerRhythmRow(
    title: String,
    summary: String,
    items: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
) {
    if (LocalUiMode.current == UiMode.Material) {
        SegmentedDropdownItem(
            title = title,
            summary = summary,
            items = items,
            selectedIndex = selectedIndex.coerceAtLeast(0),
            onSelectedIndexChange = onSelectedIndexChange,
        )
    } else {
        OverlayDropdownPreference(
            insideMargin = PanelTokens.PrefInside,
            title = title,
            summary = summary,
            items = items,
            selectedIndex = selectedIndex.coerceAtLeast(0),
            onSelectedIndexChange = onSelectedIndexChange,
        )
    }
}

/** 网格里的一格：缩略图（方图）+ 描述首行 + 标签；描述空着就盖一条「待补充描述」角标。 */
@Composable
private fun StickerTile(
    item: StickerStore.Item,
    thumb: ImageBitmap?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .padding(4.dp)
            // 网格里的图片格子：不加按压反馈（只有主页 hero 卡才有）
            .modeClickable(onClick, feedback = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(PanelMetrics.CardRadius))
                .background(PanelColors.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (thumb != null) {
                Image(
                    bitmap = thumb,
                    contentDescription = item.desc,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(text = "…", fontSize = 13.sp, color = PanelColors.onSurfaceVariantSummary)
            }
            if (item.desc.isBlank()) {
                Text(
                    text = "待补充描述",
                    fontSize = 10.sp,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(PanelTokens.ErrorColor.copy(alpha = 0.85f))
                        .padding(vertical = 2.dp),
                )
            }
        }
        Text(
            text = item.desc.ifBlank { "未填写" }.substringBefore('\n'),
            fontSize = 13.sp,
            fontWeight = FontWeight(550),
            color = if (item.desc.isBlank()) PanelTokens.ErrorColor else PanelColors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, top = 4.dp),
        )
        if (item.tags.isNotEmpty()) {
            Text(
                text = item.tags.joinToString(" / "),
                fontSize = 11.sp,
                color = PanelColors.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 2.dp, top = 2.dp),
            )
        }
    }
}

/**
 * 编辑一条：描述（必填）+ 标签（逗号分隔，可空）+ 删除。
 *
 * 描述空着不许保存：Python 侧跳过空描述条目，导入了却发不出去更让人困惑。
 */
@Composable
private fun StickerEditDialog(
    show: Boolean,
    item: StickerStore.Item,
    thumb: ImageBitmap?,
    onSave: (String, List<String>) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var desc by remember(item.id, show) { mutableStateOf(item.desc) }
    var tags by remember(item.id, show) { mutableStateOf(item.tags.joinToString("，")) }
    var err by remember(item.id, show) { mutableStateOf("") }

    WindowDialog(
        show = show,
        title = if (item.desc.isBlank()) "填写描述，必填" else "编辑表情",
        onDismissRequest = onDismiss,
        content = {
            Column(Modifier.fillMaxWidth()) {
                if (thumb != null) {
                    Image(
                        bitmap = thumb,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .heightIn(max = 160.dp)
                            .clip(RoundedCornerShape(PanelMetrics.CardRadius)),
                    )
                    Spacer(Modifier.height(PanelTokens.ButtonGap))
                }
                // 尺寸 + 体积：一眼看出这张图有多大
                Text(
                    text = item.w.toString() + "×" + item.h + " · " + StickerStore.mbText(item.size) + " MB",
                    fontSize = 12.sp,
                    color = PanelColors.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
                BotFormField(
                    label = "描述",
                    value = desc,
                    onValueChange = {
                        desc = it
                        if (err.isNotBlank()) err = ""
                    },
                    hint = "例如：无语地翻了个白眼",
                    singleLine = false,
                    minLines = 2,
                )
                Spacer(Modifier.height(PanelTokens.ButtonGap))
                BotFormField(
                    label = "标签",
                    value = tags,
                    onValueChange = { tags = it },
                    hint = "能留空，逗号分隔：无语，嫌弃",
                )
                if (err.isNotBlank()) {
                    Spacer(Modifier.height(PanelTokens.ButtonGap))
                    Text(
                        text = err,
                        fontSize = 13.sp,
                        color = PanelTokens.ErrorColor,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                Spacer(Modifier.height(PanelTokens.ButtonRowTopGap))
                PanelPillRow {
                    PanelPrimaryButton(
                        text = "保存",
                        onClick = {
                            if (desc.isBlank()) {
                                err = "没写描述：空着不进候选。写一句再保存"
                            } else {
                                onSave(desc, splitTags(tags))
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    PanelSecondaryButton(
                        text = "取消",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(PanelTokens.ButtonGap))
                PanelPillRow {
                    PanelSecondaryButton(
                        text = "删除这张",
                        danger = true,
                        onClick = onDelete,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
    )
}

/** 标签输入：中英文逗号 / 顿号 / 空格都当分隔符，去空去重。 */
private fun splitTags(raw: String): List<String> = raw
    .split(',', '，', '、', ' ', '\n')
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .distinct()
