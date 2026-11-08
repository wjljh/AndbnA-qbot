package com.wjljh.andbna.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wjljh.andbna.local.BotHost
import com.wjljh.andbna.local.BotStore
import com.wjljh.andbna.local.BotStore.logLevel
import com.wjljh.andbna.ui.component.material.SegmentedArrowItem
import com.wjljh.andbna.ui.component.material.SegmentedContentItem
import com.wjljh.andbna.ui.component.material.SegmentedDropdownItem
import com.wjljh.andbna.ui.component.material.SegmentedGroup
import com.wjljh.andbna.ui.component.material.segmentedGroupInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 日志类别（Python 侧统一成 `[时间] [bot] 类别 · 内容`）。
 *
 * 第 0 项「全部」只是界面上的筛选项，不是 token —— [logCategoryOf] 只认后六个。
 */
private val LOG_CATEGORIES = listOf("全部", "消息", "判定", "回复", "系统", "更新", "错误")
private val LOG_CATEGORY_TOKENS = listOf("消息", "判定", "回复", "系统", "更新", "错误")

/** 详细程度：下标 0 = 简洁（默认）/ 1 = 详细；落盘就是 simple / detail。 */
private val LOG_LEVEL_LABELS = listOf("简洁", "详细")
private val LOG_LEVEL_VALUES = listOf("simple", "detail")

/**
 * 从一行日志里取两字类别：`[时间] [bot] 类别 · 内容` → 「类别」。
 *
 * 「] 」用最后一次出现的位置（时间与 bot 各有一个方括号），「 · 」用第一个：
 * 取不到 / 长度不是 2 / 不在六类里 —— 一律返回 null（旧格式、Kotlin 侧写的行都走这条）。
 */
internal fun logCategoryOf(line: String): String? {
    val head = line.substringAfterLast("] ", "").substringBefore(" · ").trim()
    return head.takeIf { it in LOG_CATEGORY_TOKENS }
}

/**
 * 客户端筛选：0 = 全部原样返回；否则只留该类别。
 *
 * 解析不出类别的行在「全部」时照常显示，被筛时归入「系统」——
 * 这样「系统」不会因为旧行没类别就整段消失。
 */
internal fun filterLogs(raw: String, categoryIndex: Int): String {
    val want = LOG_CATEGORIES.getOrNull(categoryIndex) ?: "全部"
    if (want == "全部") return raw
    return raw.lineSequence()
        .filter { (logCategoryOf(it) ?: "系统") == want }
        .joinToString("\n")
}

/**
 * 日志二级页。
 *
 * 布局照 SukiSU「发送日志」那个弹窗来的：顶部两个下拉（详细程度 / 类别筛选），
 * 中间三行带图标的动作（保存 / 分享 / 刷新），底部一条整宽的返回按钮，再下面是日志正文。
 *
 * 详细程度只写 llm.json 的 log_level（真正过滤在 Python 写日志时做），
 * 类别筛选在**客户端**做：读到的原始日志按类别 token 过滤后再显示。
 */
@Composable
fun LogScreen(metrics: Metrics, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    // 落盘值现读一次，界面上跟着它走（下拉的选中项）
    var level by remember { mutableStateOf(ctx.logLevel) }
    var category by remember { mutableIntStateOf(0) }
    val material = LocalUiMode.current == UiMode.Material

    LaunchedEffect(tick) {
        text = withContext(Dispatchers.IO) { BotHost.logTail(400) }
    }

    val shown = remember(text, category) { filterLogs(text, category) }
    val levelIndex = LOG_LEVEL_VALUES.indexOf(level).coerceAtLeast(0)

    // 导出：走系统文件选择器，不申请存储权限；导出的是**当前筛出来的**那份
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            msg = try {
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(shown.toByteArray(Charsets.UTF_8))
                    }
                }
                "已导出（${shown.length} 字）"
            } catch (e: Exception) {
                "导出失败：${e.message}"
            }
        }
    }

    fun saveLog() = exportLauncher.launch("andbna_log_${System.currentTimeMillis()}.txt")

    fun shareLog() {
        val it = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shown.ifBlank { "（日志是空的）" })
        }
        runCatching { ctx.startActivity(Intent.createChooser(it, "分享日志")) }
    }

    fun refresh() {
        msg = ""
        tick++
    }

    /**
     * 切详细程度：prefs + llm.json 各一份（Python 每次写日志前重读文件），
     * 切完立刻重读一次日志，界面不用等下一次进入。
     */
    fun pickLevel(index: Int) {
        val v = LOG_LEVEL_VALUES.getOrNull(index) ?: LOG_LEVEL_VALUES[0]
        level = v
        ctx.logLevel = v
        scope.launch {
            withContext(Dispatchers.IO) { BotStore.writeLlmJson(ctx) }
            msg = if (v == "detail") "详细程度：详细（新日志才带上细节）"
            else "详细程度：简洁"
            tick++
        }
    }

    fun pickCategory(index: Int) {
        category = index.coerceIn(0, LOG_CATEGORIES.lastIndex)
        msg = "类别筛选：" + LOG_CATEGORIES[category]
    }

    Scaffold(
        containerColor = PanelColors.surface,
        topBar = {
            SmallTopAppBar(
                title = "日志",
                subtitle = "最近 400 行 · " + LOG_CATEGORIES[category],
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text(
                            "\u2039",
                            fontSize = 30.sp,
                            color = PanelColors.onBackground,
                        )
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()),
        ) {
            PageColumn(metrics) {
                if (msg.isNotBlank()) Note(msg)

                if (material) {
                    LogBodyMaterial(
                        levelIndex = levelIndex,
                        categoryIndex = category,
                        shown = shown,
                        onPickLevel = { pickLevel(it) },
                        onPickCategory = { pickCategory(it) },
                        onSave = { saveLog() },
                        onShare = { shareLog() },
                        onRefresh = { refresh() },
                    )
                } else {
                    LogBodyMiuix(
                        metrics = metrics,
                        levelIndex = levelIndex,
                        categoryIndex = category,
                        shown = shown,
                        onPickLevel = { pickLevel(it) },
                        onPickCategory = { pickCategory(it) },
                        onSave = { saveLog() },
                        onShare = { shareLog() },
                        onRefresh = { refresh() },
                    )
                }

                // 底部整宽按钮，和 SukiSU 弹窗里的「取消」一个形态
                TextButton(
                    text = "返回",
                    onClick = onBack,
                    colors = ButtonDefaults.textButtonColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** miuix 那一套 body：preference 行，两个下拉都是 OverlayDropdownPreference。 */
@Composable
private fun LogBodyMiuix(
    metrics: Metrics,
    levelIndex: Int,
    categoryIndex: Int,
    shown: String,
    onPickLevel: (Int) -> Unit,
    onPickCategory: (Int) -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onRefresh: () -> Unit,
) {
    val iconTint = PanelColors.onSurfaceVariantSummary

    SectionCard(metrics, gap = 2.dp) {
        OverlayDropdownPreference(
            insideMargin = prefInside,
            title = "详细程度",
            summary = "简洁只留一行结论，详细带判定与出错细节",
            startAction = { RowIcon(Icons.Rounded.Speed, iconTint) },
            items = LOG_LEVEL_LABELS,
            selectedIndex = levelIndex,
            onSelectedIndexChange = onPickLevel,
        )
        OverlayDropdownPreference(
            insideMargin = prefInside,
            title = "类别筛选",
            summary = "只看某一类；解析不出类别的行算「系统」",
            startAction = { RowIcon(Icons.Rounded.FilterList, iconTint) },
            items = LOG_CATEGORIES,
            selectedIndex = categoryIndex,
            onSelectedIndexChange = onPickCategory,
        )
    }

    SectionCard(metrics, gap = 2.dp) {
        ArrowPreference(
            insideMargin = prefInside,
            title = "保存日志",
            summary = "存到系统里自己挑的位置",
            startAction = { RowIcon(Icons.Rounded.CheckCircle, iconTint) },
            onClick = onSave,
        )
        ArrowPreference(
            insideMargin = prefInside,
            title = "分享日志",
            summary = "扔给别的 App",
            startAction = { RowIcon(Icons.Rounded.Share, iconTint) },
            onClick = onShare,
        )
        ArrowPreference(
            insideMargin = prefInside,
            title = "刷新",
            summary = "重新读最新日志",
            startAction = { RowIcon(Icons.Rounded.Refresh, iconTint) },
            onClick = onRefresh,
        )
    }

    SectionCard(metrics, title = "内容") {
        LogText(shown)
    }
}

/** material 那一套 body：同一份行换成 Segmented*Item。 */
@Composable
private fun LogBodyMaterial(
    levelIndex: Int,
    categoryIndex: Int,
    shown: String,
    onPickLevel: (Int) -> Unit,
    onPickCategory: (Int) -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onRefresh: () -> Unit,
) {
    SegmentedGroup(modifier = Modifier.segmentedGroupInsets()) {
        item {
            SegmentedDropdownItem(
                title = "详细程度",
                summary = "简洁只留一行结论，详细带判定与出错细节",
                icon = Icons.Filled.Speed,
                items = LOG_LEVEL_LABELS,
                selectedIndex = levelIndex,
                onSelectedIndexChange = onPickLevel,
            )
        }
        item {
            SegmentedDropdownItem(
                title = "类别筛选",
                summary = "只看某一类；解析不出类别的行算「系统」",
                icon = Icons.Filled.FilterList,
                items = LOG_CATEGORIES,
                selectedIndex = categoryIndex,
                onSelectedIndexChange = onPickCategory,
            )
        }
    }
    SegmentedGroup(modifier = Modifier.segmentedGroupInsets()) {
        item {
            SegmentedArrowItem(
                title = "保存日志",
                summary = "存到系统里自己挑的位置",
                icon = Icons.Filled.CheckCircle,
                onClick = onSave,
            )
        }
        item {
            SegmentedArrowItem(
                title = "分享日志",
                summary = "扔给别的 App",
                icon = Icons.Filled.Share,
                onClick = onShare,
            )
        }
        item {
            SegmentedArrowItem(
                title = "刷新",
                summary = "重新读最新日志",
                icon = Icons.Filled.Refresh,
                onClick = onRefresh,
            )
        }
    }
    SegmentedGroup(modifier = Modifier.segmentedGroupInsets()) {
        item { SegmentedContentItem { LogText(shown) } }
    }
}

/** 日志正文：等宽小字；两套 body 共用同一份排版。 */
@Composable
private fun LogText(shown: String) {
    if (shown.isBlank()) {
        Text(
            "日志是空的",
            fontSize = 14.sp,
            color = PanelColors.onSurfaceVariantSummary,
        )
    } else {
        Text(
            shown,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = PanelColors.onSurfaceVariantSummary,
        )
    }
}
