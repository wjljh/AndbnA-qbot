package com.wjljh.andbna.ui

import android.content.Context
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wjljh.andbna.data.OnboardStore
import com.wjljh.andbna.local.BotHost
import com.wjljh.andbna.local.ModeStore
import com.wjljh.andbna.local.PullBack
import com.wjljh.andbna.local.RunMode
import com.wjljh.andbna.local.RunNotification
import com.wjljh.andbna.local.SecureStore
import com.wjljh.andbna.ui.component.PanelAnimatedNotice
import com.wjljh.andbna.ui.component.PanelNotice
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
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

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

    // 进设置页重算一次识图缓存占用（要过 Python / 读目录，放 IO，不在组合期做）
    LaunchedEffect(visible) { if (visible) vm.refreshCache() }

    if (LocalUiMode.current == UiMode.Material) {
        MaterialSettingsBody(vm, actions, bottomInset, curMode)
    } else {
        MiuixSettingsBody(vm, actions, bottomInset, curMode)
    }
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

    fun pickJudge(index: Int) {
        judge = index
        SettingsPrefs.put(ctx, "reply_judge", if (index == 1) "random" else "llm")
        msg = "已切到「" + judgeOptions.getOrElse(index) { "" } + "」"
    }

    /** 识图缓存占用（字节）；-1 = 还没读到 */
    var cacheBytes by mutableStateOf(-1L)

    fun refreshCache() {
        scope.launch {
            cacheBytes = withContext(Dispatchers.IO) { BotHost.imageCacheSize(ctx) }
        }
    }

    /** 点一下直接清（不弹确认）：清完把占用重算一遍，再给一句提示。 */
    fun clearCache() {
        scope.launch {
            val (files, bytes) = withContext(Dispatchers.IO) { BotHost.clearImageCache(ctx) }
            cacheBytes = withContext(Dispatchers.IO) { BotHost.imageCacheSize(ctx) }
            msg = if (files <= 0) "暂无缓存可清理" else "已清理 " + fmtSize(bytes)
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

        // ② 识图缓存：副标题挂当前占用，点一下直接清（不弹确认）
        item(key = "cache") {
            SettingsMiuixGroup {
                ArrowPreference(
                    title = "清理识图缓存",
                    summary = cacheSummary(vm.cacheBytes),
                    startAction = { SettingsRowIcon(Icons.Filled.Delete) },
                    onClick = { vm.clearCache() },
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

        // ③ 回复节奏
        item(key = "reply") {
            SettingsMiuixGroup {
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
    }
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

        // ② 识图缓存：副标题挂当前占用，点一下直接清（不弹确认）
        item(key = "cache") {
            SegmentedGroup(modifier = settingsGroupPadding()) {
                item {
                    SegmentedArrowItem(
                        title = "清理识图缓存",
                        summary = cacheSummary(vm.cacheBytes),
                        icon = Icons.Filled.Delete,
                        onClick = { vm.clearCache() },
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

        // ③ 回复节奏
        item(key = "reply") {
            SegmentedGroup(modifier = settingsGroupPadding()) {
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
    }
}

/** 组间距照 SukiSU SettingsMaterial.kt：start 16 / end 16 / bottom 13dp。 */
private fun settingsGroupPadding(): Modifier = Modifier.segmentedGroupInsets()

/** 识图缓存占用的文案：「暂无缓存」/「当前 12.4 MB」/「当前 860 KB」 */
private fun cacheSummary(bytes: Long): String =
    if (bytes <= 0L) "暂无缓存" else "当前 " + fmtSize(bytes)

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