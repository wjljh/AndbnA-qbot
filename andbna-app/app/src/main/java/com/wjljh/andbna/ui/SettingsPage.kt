package com.wjljh.andbna.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wjljh.andbna.data.OnboardStore
import com.wjljh.andbna.local.BotHost
import com.wjljh.andbna.local.BotRestarts
import com.wjljh.andbna.local.ModeStore
import com.wjljh.andbna.local.PullBack
import com.wjljh.andbna.local.RunMode
import com.wjljh.andbna.local.RunNotification
import com.wjljh.andbna.local.SecureStore
import com.wjljh.andbna.ui.component.PanelAnimatedNotice
import com.wjljh.andbna.ui.component.PanelConfirmDialog
import com.wjljh.andbna.ui.component.PanelMetrics
import com.wjljh.andbna.ui.component.PanelNotice
import com.wjljh.andbna.ui.component.PanelTokens
import com.wjljh.andbna.ui.component.material.SegmentedArrowItem
import com.wjljh.andbna.ui.component.material.SegmentedDropdownItem
import com.wjljh.andbna.ui.component.material.SegmentedGroup
import com.wjljh.andbna.ui.component.material.SegmentedMetrics
import com.wjljh.andbna.ui.component.material.segmentedGroupInsets
import com.wjljh.andbna.ui.component.material.SegmentedSwitchItem
import com.wjljh.andbna.ui.component.material.SegmentedValueItem
import com.wjljh.andbna.ui.component.miuix.EditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 设置页 —— 两种界面模式各写一套结构，都照 SukiSU 源码来：
 *
 *  · miuix（SukiSU SettingsMiuix.kt）：LazyColumn（页边距 horizontal 12dp）+ 每组一张
 *    Card(Modifier.fillMaxWidth().padding(top = 12.dp))，卡内**没有**自定义 padding，
 *    行一律是 kit 原生 preference：ArrowPreference / SwitchPreference / OverlayDropdownPreference
 *    （数值项用 kit 的 TextField 行 EditText，与 preference 同一套 16dp 内边距与图标列）。
 *  · material（SukiSU SettingsMaterial.kt + ui/component/material/SegmentedList.kt）：
 *    每组一个 SegmentedGroup（组内每行独立圆角块 + 2dp 空隙），
 *    组间距 padding(start = 16, end = 16, bottom = 13dp)，行是 Segmented*Item；
 *    数值项（闲聊概率/每日上限/模型三件套）是 SegmentedValueItem：
 *    标签 + 右对齐的行内可编辑值，不带描边、不带浮动 label。
 *
 * 业务读写仍走 SettingsPrefs 与 BotStore，一行没改。
 */
@Composable
fun SettingsPage(
    metrics: Metrics,
    visible: Boolean,
    bottomInset: Dp = 0.dp,
    onReselectMode: () -> Unit = {},
    onOpenLog: () -> Unit = {},
    onOpenTheme: () -> Unit = {},
    onOpenTexts: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenModelConfig: () -> Unit = {},
    onReplayOnboarding: () -> Unit = {},
    /** 当前运行模式由 App 统一持有：切换后立即重组，「自动拉回」那行该显示/隐藏不用重启 */
    mode: RunMode? = null,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val vm = remember { SettingsVm(ctx, scope) }
    // 不传就退回 ModeStore 的落盘值（老调用点行为不变）
    val curMode = mode ?: ModeStore.get(ctx) ?: ModeStore.initial(ctx)
    // onReselectMode 仍由 App 传进来（模式选择页 Route.ModePick 的入口已挪到「状态」页，见 StatusPage），
    // 这里留着不动 App 的调用点。
    val actions = SettingsActions(
        onReselectMode, onOpenLog, onOpenTheme, onOpenTexts, onOpenAbout, onOpenModelConfig,
        onReplayOnboarding,
    )

    // 「备份与恢复」小面板：就地弹，不新开二级页（导出 / 导入都在面板里完成）
    var showBackup by remember { mutableStateOf(false) }

    // 「消息存档」：同一套就地弹面板（占用 + 导出 + 清空），清空再走一层二次确认
    var showArchive by remember { mutableStateOf(false) }
    var confirmClearArchive by remember { mutableStateOf(false) }

    // 导出走系统文件选择器（SAF CreateDocument）：不申请存储权限，存哪儿由用户挑
    val archiveExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val line = withContext(Dispatchers.IO) {
                try {
                    val text = BotHost.archiveExportText(ctx)
                    if (text.isBlank()) {
                        "存档里还没有消息，先聊几句再导出"
                    } else {
                        ctx.contentResolver.openOutputStream(uri)?.use { os ->
                            os.write(text.toByteArray(Charsets.UTF_8))
                        }
                        "已导出（" + text.lineSequence().count() + " 行）"
                    }
                } catch (e: Exception) {
                    "导出失败：" + (e.message ?: e.javaClass.simpleName)
                }
            }
            vm.msg = line
        }
    }

    // 进设置页重算一次存档占用（要过 Python / 读目录，放 IO，不在组合期做）
    LaunchedEffect(visible) {
        if (visible) {
            vm.refreshArchive()
        }
    }

    if (LocalUiMode.current == UiMode.Material) {
        MaterialSettingsBody(
            vm, actions, bottomInset, curMode,
            onOpenBackup = { showBackup = true },
            onOpenArchive = { showArchive = true },
        )
    } else {
        MiuixSettingsBody(
            vm, actions, bottomInset, curMode,
            onOpenBackup = { showBackup = true },
            onOpenArchive = { showArchive = true },
        )
    }

    BackupPanel(show = showBackup, onDismiss = { showBackup = false })

    ArchiveDialog(
        show = showArchive,
        summary = vm.archiveSummary,
        onExport = {
            showArchive = false
            archiveExportLauncher.launch("andbna-messages.txt")
        },
        onClear = {
            showArchive = false
            confirmClearArchive = true
        },
        onDismiss = { showArchive = false },
    )
    PanelConfirmDialog(
        show = confirmClearArchive,
        title = "清空消息存档",
        body = "确认清空本机的聊天存档？清空后不可恢复（记忆与配置不受影响）。",
        confirmLabel = "清空",
        onConfirm = {
            confirmClearArchive = false
            vm.clearArchive()
        },
        onDismiss = { confirmClearArchive = false },
    )
}

/** 页面回调集合（两种结构共用，不重复拆参数）。 */
private class SettingsActions(
    val onReselectMode: () -> Unit,
    val onOpenLog: () -> Unit,
    val onOpenTheme: () -> Unit,
    val onOpenTexts: () -> Unit,
    val onOpenAbout: () -> Unit,
    val onOpenModelConfig: () -> Unit,
    val onReplayOnboarding: () -> Unit,
)

/** 设置项的可变状态 + 落盘动作（两种结构共用同一份，切模式不会丢已填内容）。 */
private class SettingsVm(val ctx: Context, val scope: CoroutineScope) {
    var msg by mutableStateOf("")
    var judge by mutableStateOf(if (SettingsPrefs.ui(ctx, "reply_judge") == "random") 1 else 0)
    // 群里自动接话：默认关（群里只有 @ 才回）。与 BotStore.groupAutochat 同一个落盘键
    var autochat by mutableStateOf(SettingsPrefs.ui(ctx, "group_autochat").trim() == "1")
    var chatProb by mutableStateOf(SettingsPrefs.ui(ctx, "chat_prob").ifBlank { "0.3" })
    var dailyLimit by mutableStateOf(SettingsPrefs.ui(ctx, "daily_limit").ifBlank { "50" })
    var autoStart by mutableStateOf(SettingsPrefs.ui(ctx, "auto_start").ifBlank { "1" } == "1")
    // 运行中的通知栏提醒：默认开（与 RunNotification / BotService 同一份落盘值）
    var runNotif by mutableStateOf(RunNotification.enabled(ctx))
    // 自动拉回：默认开，落盘键 auto_pull（与 PullBack.autoEnabled 同一份值）
    var autoPull by mutableStateOf(PullBack.autoEnabled(ctx))

    val judgeOptions = listOf("模型判断", "随机")
    val numberKeys = KeyboardOptions(keyboardType = KeyboardType.Number)
    val curModel: String get() = SettingsPrefs.ui(ctx, "llm_model").ifBlank { "deepseek-chat" }
    val curVision: String get() = SettingsPrefs.ui(ctx, "llm_vision_model")
    val keyConfigured: Boolean get() = SettingsPrefs.ui(ctx, "llm_key").isNotBlank()

    /** 入口行的小字：当前主模型 + 识图模型（留空就写「同主模型」） */
    val llmSummary: String
        get() = if (!keyConfigured) {
            "未配置（只回连通测试）"
        } else {
            "当前：" + curModel + " · 识图：" + curVision.ifBlank { "同主模型" }
        }

    /**
     * 切「群里自动接话」：落盘 + 立刻推给 Python（_autochat 是每条消息现读的，不用重启 bot）。
     * 关着时群里只有 @ 才回 —— 状态页那行小字也读这个落盘键。
     */
    fun switchAutochat(on: Boolean) {
        autochat = on
        SettingsPrefs.put(ctx, "group_autochat", if (on) "1" else "0")
        scope.launch {
            withContext(Dispatchers.IO) { BotHost.setGroupAutochat(on) }
            msg = if (on) "群里自动接话已打开：它会自己判断要不要插话" else "群里自动接话已关闭：群里只有 @ 才回"
        }
    }

    /**
     * 切「回复判定」：落盘 + 重推给 Python + 重启所有启用中的 bot。
     *
     * 判定模式只在进程内存里（bot_runner._judge），光改 prefs 运行中的 bot 不会变，
     * 所以走和模型配置保存同一条路：推配置 + 重启（1.5 秒内连点只重启一次）。
     */
    fun pickJudge(index: Int) {
        val changed = judge != index
        judge = index
        SettingsPrefs.put(ctx, "reply_judge", if (index == 1) "random" else "llm")
        val n = if (changed) BotRestarts.requestAllEnabled(ctx, "设置保存") else 0
        msg = "已切到「" + judgeOptions.getOrElse(index) { "" } + "」" +
            if (n > 0) "，正在重启 " + n + " 个 bot…" else ""
    }

    // 消息存档：占用字节 + 条数（-1 = 还没读到 / 条数未知）
    var archiveBytes by mutableStateOf(-1L)
    var archiveRows by mutableStateOf(-1)

    /** 一行里写清「占用 3.2 MB · 12,345 条」（条数要问 Python，没起来就写未知） */
    val archiveSummary: String
        get() = if (archiveBytes < 0L) {
            "正在统计…"
        } else {
            "占用 " + fmtSize(archiveBytes) + " · " + (
                if (archiveRows < 0) "条数未知"
                else String.format(java.util.Locale.US, "%,d", archiveRows) + " 条"
                )
        }

    fun refreshArchive() {
        scope.launch {
            val st = withContext(Dispatchers.IO) { BotHost.archiveStats(ctx) }
            archiveBytes = st.first
            archiveRows = st.second
        }
    }

    /** 清空之后重算一遍占用，界面上的数立刻跟着掉下去 */
    fun clearArchive() {
        scope.launch {
            val res = withContext(Dispatchers.IO) { BotHost.archiveClear(ctx) }
            val st = withContext(Dispatchers.IO) { BotHost.archiveStats(ctx) }
            archiveBytes = st.first
            archiveRows = st.second
            msg = if (res.first <= 0) "存档本来就是空的" else "已清空存档（" + res.first + " 条）"
        }
    }

    fun switchAutoStart(on: Boolean) {
        autoStart = on
        SettingsPrefs.put(ctx, "auto_start", if (on) "1" else "0")
        msg = if (on) "开机自启已打开" else "开机自启已关闭"
    }

    /**
     * 切「自动拉回」：落盘 + 立刻生效（开=看门狗马上上岗，关=脚本写 off 退出），不用重启 App。
     * 关的时候要过 su / Shizuku 通道去 kill，放 IO 线程，别卡 UI。
     */
    fun switchAutoPull(on: Boolean) {
        autoPull = on
        scope.launch {
            withContext(Dispatchers.IO) { PullBack.setAutoEnabled(ctx, on) }
            msg = if (on) "自动拉回：已开启，看门狗已上岗" else "自动拉回：已关闭，看门狗已退出"
        }
    }

    /** 切通知开关：立刻改渠道重要性（开 LOW / 关 NONE）+ 撤通知，服务不用重启 */
    fun switchRunNotif(on: Boolean) {
        runNotif = on
        RunNotification.set(ctx, on)
        msg = if (on) "运行通知：开" else "运行通知：关"
    }

}

// ── miuix 结构（SukiSU SettingsMiuix.kt）─────────────────────────────────

@Composable
private fun MiuixSettingsBody(
    vm: SettingsVm,
    actions: SettingsActions,
    bottomInset: Dp,
    curMode: RunMode,
    onOpenBackup: () -> Unit,
    onOpenArchive: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = bottomInset),
    ) {
        item(key = "notice") {
            PanelAnimatedNotice(visible = vm.msg.isNotBlank()) { PanelNotice(vm.msg) }
        }

        // ① 外观：只剩「主题设置」（外观/语种两行已按需求删掉，不提供切换）
        item(key = "look") {
            SettingsMiuixGroup {
                ArrowPreference(
                    title = "主题设置",
                    // 单行行（无副标题）：图标不要 -9dp 偏移，与标题同高
                    startAction = { SettingsRowIcon(Icons.Filled.Tune) },
                    onClick = actions.onOpenTheme,
                )
            }
        }

        // ② 模型配置：入口行（当前模型名挂在 summary），点进独立的二级页
        item(key = "llm") {
            SettingsMiuixGroup {
                ArrowPreference(
                    title = "模型配置",
                    summary = vm.llmSummary,
                    startAction = { SettingsRowIcon(Icons.Filled.Key) },
                    onClick = actions.onOpenModelConfig,
                )
            }
        }

        // ③ 回复节奏（首行「群里自动接话」是全局默认；分组标题要对用户可见）
        item(key = "reply") {
            SettingsGroupTitle("回复节奏")
            SettingsMiuixGroup {
                SwitchPreference(
                    title = "群里自动接话",
                    summary = "关着时群里只有 @ 才会回；打开后它会自己判断要不要插话（默认关）",
                    startAction = { SettingsRowIcon(Icons.Filled.Forum) },
                    checked = vm.autochat,
                    onCheckedChange = { vm.switchAutochat(it) },
                )
                OverlayDropdownPreference(
                    title = "回复判定",
                    summary = "非 @ 消息是否插话",
                    startAction = { SettingsRowIcon(Icons.Filled.Notes) },
                    items = vm.judgeOptions,
                    selectedIndex = vm.judge,
                    onSelectedIndexChange = { vm.pickJudge(it) },
                )
                EditText(
                    title = "闲聊概率",
                    value = vm.chatProb,
                    enabled = vm.judge == 1,
                    keyboardOptions = vm.numberKeys,
                    leadingIcon = Icons.Filled.Forum,
                    onValueChange = {
                        vm.chatProb = it
                        SettingsPrefs.put(vm.ctx, "chat_prob", it)
                    },
                )
                EditText(
                    title = "每日上限",
                    value = vm.dailyLimit,
                    keyboardOptions = vm.numberKeys,
                    leadingIcon = Icons.Filled.Speed,
                    onValueChange = {
                        vm.dailyLimit = it
                        SettingsPrefs.put(vm.ctx, "daily_limit", it)
                    },
                )
            }
        }

        // ④ 启动
        item(key = "boot") {
            SettingsMiuixGroup {
                SwitchPreference(
                    title = "开机自动启动",
                    summary = if (vm.autoStart) "已开启" else "已关闭",
                    startAction = { SettingsRowIcon(Icons.Filled.PlayArrow) },
                    checked = vm.autoStart,
                    onCheckedChange = { vm.switchAutoStart(it) },
                )
                SwitchPreference(
                    title = "运行中的通知栏提醒",
                    summary = "在通知栏显示一条运行中消息，也可以点击跳转至软件",
                    startAction = { SettingsRowIcon(Icons.Filled.Notifications) },
                    checked = vm.runNotif,
                    onCheckedChange = { vm.switchRunNotif(it) },
                )
                // 自动拉回只在真有保活通道的模式下有意义（普通模式没有任何拉回手段），所以那两档之外整行不出现
                if (curMode != RunMode.NORMAL) {
                    SwitchPreference(
                        title = "自动拉回",
                        summary = if (vm.autoPull) "已开启 · 被杀后自动拉回来" else "已关闭 · 被杀后不会拉起",
                        startAction = { SettingsRowIcon(Icons.Filled.Refresh) },
                        checked = vm.autoPull,
                        onCheckedChange = { vm.switchAutoPull(it) },
                    )
                }
            }
        }

        // ⑤ 二级页入口
        item(key = "pages") {
            SettingsMiuixGroup {
                ArrowPreference(
                    title = "人格 / 文案",
                    summary = "切换角色预设、直接编辑文本文件",
                    startAction = { SettingsRowIcon(Icons.Filled.Person) },
                    onClick = actions.onOpenTexts,
                )
                ArrowPreference(
                    title = "日志",
                    summary = "查看 / 导出到系统文件",
                    startAction = { SettingsRowIcon(Icons.Filled.Notes) },
                    onClick = actions.onOpenLog,
                )
                ArrowPreference(
                    title = "备份与恢复",
                    summary = "导出 / 导入配置，一键备份与换机迁移",
                    startAction = { SettingsRowIcon(Icons.Rounded.Backup) },
                    onClick = onOpenBackup,
                )
            }
        }

        // ⑥ 关于（原来的「版本」只读行改成二级页入口）
        item(key = "about") {
            SettingsMiuixGroup {
                ArrowPreference(
                    title = "关于",
                    summary = "版本、技术信息与开源致谢",
                    startAction = { SettingsRowIcon(Icons.Filled.Info) },
                    onClick = actions.onOpenAbout,
                )
            }
        }

        // ⑦ 重看使用引导：引导只有 debug 包有，所以这一行也只在 debug 出现（release 设置页一行不变）
        if (OnboardStore.available) {
            item(key = "onboard") {
                SettingsMiuixGroup {
                    ArrowPreference(
                        title = "重看使用引导",
                        summary = "清掉完成标记，重新走一遍 5 步引导",
                        startAction = { SettingsRowIcon(Icons.Filled.Refresh) },
                        onClick = actions.onReplayOnboarding,
                    )
                }
            }
        }

        // ⑧ 消息存档：整页最底下一行（占用 + 条数挂 summary，导出/清空在面板里）
        item(key = "archive") {
            SettingsMiuixGroup {
                ArrowPreference(
                    title = "消息存档",
                    summary = vm.archiveSummary,
                    startAction = { SettingsRowIcon(Icons.Filled.Forum) },
                    onClick = onOpenArchive,
                )
            }
        }
    }
}

/**
 * 分组可见标题（17sp Medium）：设置页原先只有代码注释里的「③ 回复节奏」，
 * 用户翻不到这个分组 —— 现在标题真的画出来，紧跟其后的卡片就是该组的第一行。
 */
@Composable
private fun SettingsGroupTitle(text: String) {
    Text(
        text = text,
        fontSize = PanelMetrics.CardTitleSize,
        fontWeight = FontWeight.Medium,
        color = PanelColors.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 6.dp),
    )
}

/** miuix 的一组：一张卡，卡内不加任何自定义 padding（间距由 preference 自己的 insideMargin 给）。 */
@Composable
private fun SettingsMiuixGroup(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        content()
    }
}

/**
 * preference 行首的 24dp 图标（SukiSU 的 startAction 位置，图标后 6dp）。
 *
 * offsetY 默认 0dp：kit 的 preference 已经把 startAction 槽按「标题（+ 副标题）整块」垂直居中，
 * 所以有灰色小字时图标落在整块中间、单行时落在单行中间 —— 这两种情况都不需要再补偏移。
 * 旧版 -9dp 的 hack 已去掉（那会把单行图标顶高 8.8dp）。
 */
@Composable
private fun SettingsRowIcon(icon: ImageVector, offsetY: Dp = 0.dp) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MiuixTheme.colorScheme.onBackground,
        modifier = Modifier.padding(end = 6.dp).offset(y = offsetY),
    )
}

// ── material 结构（SukiSU SettingsMaterial.kt + SegmentedList.kt）────────

@Composable
private fun MaterialSettingsBody(
    vm: SettingsVm,
    actions: SettingsActions,
    bottomInset: Dp,
    curMode: RunMode,
    onOpenBackup: () -> Unit,
    onOpenArchive: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = bottomInset),
    ) {
        item(key = "notice") {
            PanelAnimatedNotice(visible = vm.msg.isNotBlank()) {
                Column(Modifier.padding(horizontal = SegmentedMetrics.GroupHorizontal)) {
                    PanelNotice(vm.msg)
                }
            }
        }

        // ① 外观：只剩「主题设置」（外观/语种两行已按需求删掉，不提供切换）
        item(key = "look") {
            SegmentedGroup(modifier = settingsGroupPadding()) {
                item {
                    SegmentedArrowItem(
                        title = "主题设置",
                        icon = Icons.Filled.Tune,
                        onClick = actions.onOpenTheme,
                    )
                }
            }
        }

        // ② 模型配置：入口行（当前模型名挂在 summary），点进独立的二级页
        item(key = "llm") {
            SegmentedGroup(modifier = settingsGroupPadding()) {
                item {
                    SegmentedArrowItem(
                        title = "模型配置",
                        summary = vm.llmSummary,
                        icon = Icons.Filled.Key,
                        onClick = actions.onOpenModelConfig,
                    )
                }
            }
        }

        // ③ 回复节奏（首行「群里自动接话」是全局默认；分组标题要对用户可见）
        item(key = "reply") {
            SettingsGroupTitle("回复节奏")
            SegmentedGroup(modifier = settingsGroupPadding()) {
                item {
                    SegmentedSwitchItem(
                        title = "群里自动接话",
                        summary = "关着时群里只有 @ 才会回；打开后它会自己判断要不要插话（默认关）",
                        icon = Icons.Filled.Forum,
                        checked = vm.autochat,
                        onCheckedChange = { vm.switchAutochat(it) },
                    )
                }
                item {
                    SegmentedDropdownItem(
                        title = "回复判定",
                        summary = "非 @ 消息是否插话",
                        icon = Icons.Filled.Notes,
                        items = vm.judgeOptions,
                        selectedIndex = vm.judge,
                        onSelectedIndexChange = { vm.pickJudge(it) },
                    )
                }
                item {
                    SegmentedValueItem(
                        title = "闲聊概率",
                        value = vm.chatProb,
                        onValueChange = {
                            vm.chatProb = it
                            SettingsPrefs.put(vm.ctx, "chat_prob", it)
                        },
                        icon = Icons.Filled.Forum,
                        enabled = vm.judge == 1,
                        numeric = true,
                    )
                }
                item {
                    SegmentedValueItem(
                        title = "每日上限",
                        value = vm.dailyLimit,
                        onValueChange = {
                            vm.dailyLimit = it
                            SettingsPrefs.put(vm.ctx, "daily_limit", it)
                        },
                        icon = Icons.Filled.Speed,
                        numeric = true,
                    )
                }
            }
        }

        // ④ 启动
        item(key = "boot") {
            SegmentedGroup(modifier = settingsGroupPadding()) {
                item {
                    SegmentedSwitchItem(
                        title = "开机自动启动",
                        summary = if (vm.autoStart) "已开启" else "已关闭",
                        icon = Icons.Filled.PlayArrow,
                        checked = vm.autoStart,
                        onCheckedChange = { vm.switchAutoStart(it) },
                    )
                }
                item {
                    SegmentedSwitchItem(
                        title = "运行中的通知栏提醒",
                        summary = "在通知栏显示一条运行中消息，也可以点击跳转至软件",
                        icon = Icons.Filled.Notifications,
                        checked = vm.runNotif,
                        onCheckedChange = { vm.switchRunNotif(it) },
                    )
                }
                // 自动拉回只在 ADB / root 下出现（普通模式没有任何拉回手段）
                if (curMode != RunMode.NORMAL) {
                    item {
                        SegmentedSwitchItem(
                            title = "自动拉回",
                            summary = if (vm.autoPull) "已开启 · 被杀后自动拉回来" else "已关闭 · 被杀后不会拉起",
                            icon = Icons.Filled.Refresh,
                            checked = vm.autoPull,
                            onCheckedChange = { vm.switchAutoPull(it) },
                        )
                    }
                }
            }
        }

        // ⑤ 二级页入口
        item(key = "pages") {
            SegmentedGroup(modifier = settingsGroupPadding()) {
                item {
                    SegmentedArrowItem(
                        title = "人格 / 文案",
                        summary = "切换角色预设、直接编辑文本文件",
                        icon = Icons.Filled.Person,
                        onClick = actions.onOpenTexts,
                    )
                }
                item {
                    SegmentedArrowItem(
                        title = "日志",
                        summary = "查看 / 导出到系统文件",
                        icon = Icons.Filled.Notes,
                        onClick = actions.onOpenLog,
                    )
                }
                item {
                    SegmentedArrowItem(
                        title = "备份与恢复",
                        summary = "导出 / 导入配置，一键备份与换机迁移",
                        icon = Icons.Rounded.Backup,
                        onClick = onOpenBackup,
                    )
                }
            }
        }

        // ⑥ 关于（原来的「版本」只读行改成二级页入口）
        item(key = "about") {
            SegmentedGroup(modifier = settingsGroupPadding()) {
                item {
                    SegmentedArrowItem(
                        title = "关于",
                        summary = "版本、技术信息与开源致谢",
                        icon = Icons.Filled.Info,
                        onClick = actions.onOpenAbout,
                    )
                }
            }
        }

        // ⑦ 重看使用引导（只在 debug 包出现，见 OnboardStore.available）
        if (OnboardStore.available) {
            item(key = "onboard") {
                SegmentedGroup(modifier = settingsGroupPadding()) {
                    item {
                        SegmentedArrowItem(
                            title = "重看使用引导",
                            summary = "清掉完成标记，重新走一遍 5 步引导",
                            icon = Icons.Filled.Refresh,
                            onClick = actions.onReplayOnboarding,
                        )
                    }
                }
            }
        }

        // ⑧ 消息存档：整页最底下一行（占用 + 条数挂 summary，导出/清空在面板里）
        item(key = "archive") {
            SegmentedGroup(modifier = settingsGroupPadding()) {
                item {
                    SegmentedArrowItem(
                        title = "消息存档",
                        summary = vm.archiveSummary,
                        icon = Icons.Filled.Forum,
                        onClick = onOpenArchive,
                    )
                }
            }
        }
    }
}

/** 组间距照 SukiSU SettingsMaterial.kt：start 16 / end 16 / bottom 13dp。 */
private fun settingsGroupPadding(): Modifier = Modifier.segmentedGroupInsets()

private fun fmtSize(bytes: Long): String = when {
    bytes <= 0L -> "0 MB"
    bytes >= 1048576L -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
    else -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
}

/** 从 OpenAI 兼容接口读模型列表；失败返回空 */
/** 设置项读写：密钥类键（llm_key 等）走 SecureStore，读时是明文、落盘是密文 */
private object SettingsPrefs {
    private fun sp(ctx: Context) =
        ctx.getSharedPreferences("andbna_local", Context.MODE_PRIVATE)

    fun ui(ctx: Context, k: String): String = SecureStore.get(sp(ctx), k)

    fun put(ctx: Context, k: String, v: String) {
        SecureStore.put(sp(ctx), k, v)
    }
}

/**
 * 「消息存档」小面板：一行占用 + 导出 / 清空 / 取消。
 *
 * 用 kit 的 WindowDialog（与 PanelConfirmDialog 同一个宿主），不引 material3；
 * 清空不在这里直接干 —— 交给下一层 PanelConfirmDialog 做二次确认，免得手滑清掉。
 */
@Composable
private fun ArchiveDialog(
    show: Boolean,
    summary: String,
    onExport: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    WindowDialog(
        show = show,
        title = "消息存档",
        onDismissRequest = onDismiss,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(PanelTokens.RowSpacing),
            ) {
                Text(
                    text = summary,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onBackground,
                )
                Text(
                    text = "每 20 条或 5 秒落一次盘；默认保留 30 天、每个会话最多 5000 条，"
                        + "超出的自动删最旧的。导出是一行一条纯文本。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onBackground,
                )
                TextButton(
                    text = "导出为文本",
                    onClick = onExport,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    text = "清空",
                    onClick = onClear,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(PanelTokens.ErrorColor),
                )
                TextButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}