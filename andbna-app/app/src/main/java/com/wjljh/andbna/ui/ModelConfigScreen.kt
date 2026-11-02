package com.wjljh.andbna.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wjljh.andbna.ModelCatalog
import com.wjljh.andbna.local.BotHost
import com.wjljh.andbna.local.BotStore
import com.wjljh.andbna.local.SecureStore
import com.wjljh.andbna.ui.component.PanelAnimatedNotice
import com.wjljh.andbna.ui.component.PanelChip
import com.wjljh.andbna.ui.component.PanelEllipsisField
import com.wjljh.andbna.ui.component.PanelNotice
import com.wjljh.andbna.ui.component.PanelPillRow
import com.wjljh.andbna.ui.component.PanelTokens
import com.wjljh.andbna.ui.component.PanelPrimaryButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「模型配置」二级页（设置页那一行进来）。
 *
 * 版式照「关于 / 主题设置」：细线返回箭头 + 大标题一行（同一个顶栏）。
 * 读写分成两步，互不代替：
 *   · 「读取模型」只问接口要列表（[ModelCatalog] 的 GET {base}/v1/models），
 *     拿到了从**底部弹一张选择卡片**（miuix 的 OverlayBottomSheet + BasicComponent 行，不自绘），
 *     选中 → 卡片消失、值填进对应输入框 —— 这一步**不落盘**；
 *   · 「保存」才把地址 / Key / 主模型 / 识图模型一起写进 prefs + localbot/llm.json。
 *
 * 识图模型留空 = 识图沿用主模型：落盘存空串，不在这里复制主模型的名字（复制了以后改主模型就不同步了）。
 *
 * 思考强度同样是「留空 = 请求体不带该字段（平台默认）」：各家取值不一样，所以是下拉候选
 * （与人格选择器同一个 miuix 下拉，末尾还有「自定义…」），真伪由「验证思考强度」发一次最小请求问平台。
 */
@Composable
fun ModelConfigScreen(metrics: Metrics, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var api by remember { mutableStateOf(ModelPrefs.read(ctx, "llm_api")) }
    var key by remember { mutableStateOf(ModelPrefs.read(ctx, "llm_key")) }
    var mainModel by remember { mutableStateOf(ModelPrefs.read(ctx, "llm_model")) }
    var visionModel by remember { mutableStateOf(ModelPrefs.read(ctx, "llm_vision_model")) }
    var judgeModel by remember { mutableStateOf(ModelPrefs.read(ctx, "llm_judge_model")) }
    var summaryModel by remember { mutableStateOf(ModelPrefs.read(ctx, "llm_summary_model")) }
    var searchModel by remember { mutableStateOf(ModelPrefs.read(ctx, "llm_search_model")) }
    // 上下文窗口（token）：压缩阈值 = 它的 75%，默认 131072（128K）
    var contextWindow by remember {
        mutableStateOf(ModelPrefs.read(ctx, "llm_context_window").ifBlank { "131072" })
    }
    // 联网搜索（DeepSeek 官方 Search / Fetch）：默认开
    var webOn by remember {
        mutableStateOf(ModelPrefs.read(ctx, "web_search_enabled").trim() != "0")
    }
    // 搜索结果人设化加工：默认开（关掉 = 原始列表贴法，便于对比）
    var personaStyle by remember {
        mutableStateOf(ModelPrefs.read(ctx, "search_persona_style").trim() != "0")
    }
    // 让模型自己判断该不该联网查：默认开（关掉 = 只认 #搜/#读 与触发词）
    var autoDecide by remember {
        mutableStateOf(ModelPrefs.read(ctx, "search_auto_decide").trim() != "0")
    }
    // 上下文窗口的「自定义…」：只是把输入框就地摊开，值仍由 contextWindow 持有
    var windowCustom by remember { mutableStateOf(false) }
    // 搜索后端（deepseek / searxng / custom / off）与它的三个字段
    var provider by remember {
        mutableStateOf(ModelPrefs.read(ctx, "search_provider").ifBlank { "deepseek" })
    }
    var searchBase by remember { mutableStateOf(ModelPrefs.read(ctx, "search_base")) }
    var searchFields by remember { mutableStateOf(ModelPrefs.read(ctx, "search_fields")) }
    val authRaw = remember { ModelPrefs.read(ctx, "search_auth") }
    // 鉴权："" = 跟随默认（deepseek 用 bearer、其它用 none）
    var authMode by remember { mutableStateOf(authModeOf(authRaw)) }
    var authName by remember { mutableStateOf(authNameOf(authRaw)) }
    var authValue by remember { mutableStateOf(authValueOf(authRaw)) }
    // 老配置可能存过大写（显示值）：预设项一律归一到小写发送值，自定义值原样保留
    var effort by remember {
        mutableStateOf(
            ModelPrefs.read(ctx, "llm_reasoning_effort").trim().let {
                if (effortPresetIndex(it) >= 0) it.lowercase() else it
            },
        )
    }
    // 「自定义…」：只是把输入框就地摊开，值仍由 effort 持有
    var customOpen by remember { mutableStateOf(false) }

    val effortTrim = effort.trim()
    val presetAt = effortPresetIndex(effortTrim)
    val customShown = customOpen || (effortTrim.isNotEmpty() && presetAt < 0)
    // 列表选中项：空 = 第一项「不指定」，预设值 = 顺位，其余（default 之类）= 末尾「自定义…」
    val effortIndex = when {
        customShown -> EFFORT_ITEMS.lastIndex
        effortTrim.isEmpty() -> 0
        else -> presetAt + 1
    }

    // 验证思考强度：一行结论 + 可展开详情（完整响应在日志里，前缀「思考强度验证：」）
    var verifyBusy by remember { mutableStateOf(false) }
    var verifyLine by remember { mutableStateOf("") }
    var verifyDetail by remember { mutableStateOf("") }
    var verifyFailed by remember { mutableStateOf(false) }
    var verifyOpen by remember { mutableStateOf(false) }

    // busy: 正在读哪一路（"" 表示空闲）；sheetFor: 卡片为哪一路弹着
    var busy by remember { mutableStateOf("") }
    var sheetFor by remember { mutableStateOf("") }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }

    BackHandler { onBack() }

    fun readInto(target: String) {
        if (api.isBlank() || key.isBlank()) {
            error = "先填「接口地址」和「API Key」，再读取模型"
            notice = ""
            return
        }
        busy = target
        error = ""
        notice = ""
        scope.launch {
            val out = withContext(Dispatchers.IO) { ModelCatalog.fetch(api, key) }
            busy = ""
            if (out.ok) {
                models = out.models
                sheetFor = target
            } else {
                models = emptyList()
                sheetFor = ""
                error = "读取模型失败：" + out.error
            }
        }
    }

    fun save() {
        ModelPrefs.save(
            ctx, api = api, key = key, model = mainModel, vision = visionModel,
            judge = judgeModel, summary = summaryModel, search = searchModel,
            window = contextWindow, web = webOn, personaStyle = personaStyle,
            autoDecide = autoDecide, effort = effort,
            searchProvider = provider, searchBase = searchBase,
            searchAuth = authWire(authMode, authName, authValue, provider),
            searchFields = searchFields,
        )
        error = ""
        // 思考强度空串也存：等于「请求体不带该字段」，不是「没保存」
        val v = if (visionModel.isBlank()) "识图留空→主模型" else "识图 " + visionModel.trim()
        val j = if (judgeModel.isBlank()) "判定留空→主模型" else "判定 " + judgeModel.trim()
        val s = if (summaryModel.isBlank()) "摘要留空→主模型" else "摘要 " + summaryModel.trim()
        val q = if (searchModel.isBlank()) "搜索留空→主模型" else "搜索 " + searchModel.trim()
        val e = if (effort.isBlank()) "思考强度留空 → 请求体不带该字段" else "思考强度 " + effort.trim()
        notice = "已保存（主模型 " + mainModel.trim().ifBlank { "deepseek-chat" } + "，" + v +
            "，" + j + "，" + s + "，" + q + "，窗口 " + contextWindow.trim().ifBlank { "131072" } +
            "，" + (if (webOn) "联网开" else "联网关") +
            (if (personaStyle) "，人设化回答开" else "，人设化回答关") +
            (if (autoDecide) "，自判联网开" else "，自判联网关") +
            "，搜索服务 " + providerLabel(provider) + "，" + e + "）"
    }

    /** 验证思考强度：用当前配置发一次最小请求（留空也照发，那就是平台默认行为）。 */
    fun runVerify() {
        if (api.isBlank() || key.isBlank()) {
            error = "先填「接口地址」和「API Key」，再验证思考强度"
            return
        }
        notice = if (effort.isBlank()) "当前未设置思考强度，这次验证等于平台默认行为" else ""
        error = ""
        verifyBusy = true
        verifyLine = ""
        verifyDetail = ""
        verifyOpen = false
        scope.launch {
            val raw = withContext(Dispatchers.IO) {
                BotHost.verifyReasoning(
                    ctx, api.trim(), key.trim(),
                    mainModel.trim().ifBlank { "deepseek-chat" }, effort.trim(),
                )
            }
            val o = runCatching { JSONObject(raw) }.getOrNull()
            verifyBusy = false
            verifyLine = o?.optString("line").orEmpty().ifBlank { "失败：验证没有返回结论" }
            verifyDetail = o?.optString("detail").orEmpty()
            verifyFailed = o?.optString("state").orEmpty() == "fail"
        }
    }

    fun pick(name: String) {
        when (sheetFor) {
            "main" -> mainModel = name
            "vision" -> visionModel = name
            "judge" -> judgeModel = name
            "summary" -> summaryModel = name
            "search" -> searchModel = name
        }
        sheetFor = ""
        notice = "已填入「" + name + "」，点保存才落盘"
        error = ""
    }

    Scaffold(containerColor = PanelColors.surface, topBar = { ModelConfigHeader(onBack) }) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState()),
        ) {
            PageColumn(metrics) {
                PanelAnimatedNotice(visible = notice.isNotBlank()) { PanelNotice(notice) }
                PanelAnimatedNotice(visible = error.isNotBlank()) {
                    Column(Modifier.padding(bottom = 8.dp)) { PanelNotice(error, isError = true) }
                }

                // ① 接口：地址 + Key（掩码 + 眼睛 + 系统安全键盘）
                SectionCard(metrics, title = "接口", gap = 10.dp) {
                    PanelEllipsisField(
                        value = api,
                        onValueChange = { api = it },
                        hint = "https://api.deepseek.com",
                        label = "接口地址",
                    )
                    PanelEllipsisField(
                        value = key,
                        onValueChange = { key = it },
                        hint = "sk-…",
                        secret = true,
                        label = "API Key",
                    )
                }

                // ② 主模型：读列表 → 底部卡片 → 选中回填
                SectionCard(metrics, title = "主模型") {
                    PanelEllipsisField(
                        value = mainModel,
                        onValueChange = { mainModel = it },
                        hint = "deepseek-chat",
                        mono = false,
                    )
                    PanelPillRow(modifier = Modifier.padding(top = 10.dp)) {
                        PanelPrimaryButton(
                            text = if (busy == "main") "读取中…" else "读取模型",
                            modifier = Modifier.fillMaxWidth(),
                            enabled = busy.isEmpty(),
                            onClick = { readInto("main") },
                        )
                    }
                }

                // ③ 三个槽位模型：识图 / 判定 / 摘要。留空 = 沿用主模型（落盘存空串）
                SlotCard(
                    metrics = metrics,
                    title = "识图模型",
                    help = "留空则沿用主模型；收到图片时用这个模型识图。",
                    value = visionModel,
                    main = mainModel,
                    busy = busy == "vision",
                    anyBusy = busy.isNotEmpty(),
                    onValueChange = { visionModel = it },
                    onRead = { readInto("vision") },
                )
                SlotCard(
                    metrics = metrics,
                    title = "判定模型",
                    help = "留空则沿用主模型；「回复判定」只回一个 0/1，配个便宜模型更省。",
                    value = judgeModel,
                    main = mainModel,
                    busy = busy == "judge",
                    anyBusy = busy.isNotEmpty(),
                    onValueChange = { judgeModel = it },
                    onRead = { readInto("judge") },
                )
                SlotCard(
                    metrics = metrics,
                    title = "摘要模型",
                    help = "留空则沿用主模型；上下文压缩时用它把旧对话并成一段摘要。",
                    value = summaryModel,
                    main = mainModel,
                    busy = busy == "summary",
                    anyBusy = busy.isNotEmpty(),
                    onValueChange = { summaryModel = it },
                    onRead = { readInto("summary") },
                )
                SlotCard(
                    metrics = metrics,
                    title = "搜索模型",
                    help = "留空则沿用主模型；#搜 / #读 把结果按人格转述成中文时用它（一次一条，配便宜的就够）。",
                    value = searchModel,
                    main = mainModel,
                    busy = busy == "search",
                    anyBusy = busy.isNotEmpty(),
                    onValueChange = { searchModel = it },
                    onRead = { readInto("search") },
                )

                // ④ 思考强度：点一行 → 弹列表面板（与人格选择器同一个 miuix 下拉）→ 选中回填
                SectionCard(metrics, title = "思考强度", gap = 10.dp) {
                    OverlayDropdownPreference(
                        insideMargin = PanelTokens.PrefInside,
                        title = "取值",
                        // 不指定 = 请求体里不带 reasoning_effort（用平台默认），右侧直接显示选中项
                        summary = if (effortTrim.isEmpty()) "不指定 = 请求体不带该字段"
                        else "发送值 = " + effortTrim,
                        items = EFFORT_ITEMS,
                        selectedIndex = effortIndex,
                        onSelectedIndexChange = { i ->
                            verifyLine = ""
                            verifyDetail = ""
                            verifyOpen = false
                            error = ""
                            when {
                                // 「自定义…」只是就地摊开输入框，不改当前值
                                i == EFFORT_ITEMS.lastIndex -> {
                                    customOpen = true
                                    notice = "填任意取值（各家不同，例如 default），填完点确定"
                                }
                                i == 0 -> {
                                    customOpen = false
                                    effort = ""
                                    notice = "已选「不指定」：请求体里不带 reasoning_effort"
                                }
                                else -> {
                                    customOpen = false
                                    // 显示大写，存/发小写（协议要的就是小写）
                                    effort = effortWire(EFFORT_DISPLAYS[i - 1])
                                    notice = "已选「" + EFFORT_DISPLAYS[i - 1] +
                                        "」，发送值 " + effort + "，点保存才落盘"
                                }
                            }
                        },
                    )
                    // 大写只用于显示：这里把真正会发出去的值写死给用户看，免得以为发了 Upper
                    Text(
                        text = if (effortTrim.isEmpty())
                            "本次发送：不带 reasoning_effort（用平台默认）"
                        else "本次发送值 = " + effortTrim + "（列表里的大写只用于显示）",
                        fontSize = 13.sp,
                        color = PanelColors.primary,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                    if (customShown) {
                        PanelEllipsisField(
                            value = effort,
                            onValueChange = { effort = it },
                            hint = "填任意值，例如 default",
                            label = "自定义思考强度",
                            mono = false,
                        )
                        PanelChip(
                            text = "确定",
                            onClick = { customOpen = false },
                        )
                    }
                    Text(
                        text = "官方 DeepSeek 的取值为 None / Minimal / Low / Medium / High / " +
                            "Xhigh / Ultra / Max（发送时用小写）；不是所有模型都支持，留空最安全。",
                        fontSize = 13.sp,
                        color = PanelColors.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    PanelPillRow(modifier = Modifier.padding(top = 10.dp)) {
                        PanelPrimaryButton(
                            text = if (verifyBusy) "验证中…" else "验证思考强度",
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !verifyBusy && busy.isEmpty(),
                            onClick = { runVerify() },
                        )
                    }
                    PanelAnimatedNotice(visible = verifyLine.isNotBlank()) {
                        PanelNotice(verifyLine, isError = verifyFailed, mono = true)
                    }
                    if (verifyDetail.isNotBlank()) {
                        PanelChip(
                            text = if (verifyOpen) "收起详情" else "展开详情",
                            onClick = { verifyOpen = !verifyOpen },
                        )
                        if (verifyOpen) {
                            Text(
                                text = verifyDetail,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = PanelColors.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }

                // ⑤ 上下文窗口（token）：与思考强度同一套「点一行 → 弹列表」样式
                val winPreset = WINDOW_PRESETS.indexOfFirst { it.second == contextWindow.trim() }
                val winCustomShown = windowCustom || winPreset < 0
                SectionCard(metrics, title = "上下文窗口") {
                    OverlayDropdownPreference(
                        insideMargin = PanelTokens.PrefInside,
                        title = "取值",
                        summary = if (winCustomShown)
                            "当前 " + contextWindow.trim().ifBlank { "131072" } + "（自定义）"
                        else "当前 " + WINDOW_PRESETS[winPreset].first +
                            "（" + WINDOW_PRESETS[winPreset].second + "）",
                        items = WINDOW_ITEMS,
                        selectedIndex = if (winCustomShown) WINDOW_ITEMS.lastIndex else winPreset,
                        onSelectedIndexChange = { i ->
                            error = ""
                            if (i == WINDOW_ITEMS.lastIndex) {
                                windowCustom = true
                                notice = "填任意 token 数（各家上限不同，64K~1M 常见），填完点保存"
                            } else {
                                windowCustom = false
                                contextWindow = WINDOW_PRESETS[i].second
                                notice = "已选「" + WINDOW_PRESETS[i].first + "」（" +
                                    WINDOW_PRESETS[i].second + " tokens），点保存才落盘"
                            }
                        },
                    )
                    if (winCustomShown) {
                        PanelEllipsisField(
                            value = contextWindow,
                            onValueChange = { contextWindow = it },
                            hint = "131072",
                            mono = false,
                        )
                        PanelChip(text = "确定", onClick = { windowCustom = false })
                    }
                    val win = contextWindow.trim().toLongOrNull() ?: 131072L
                    Text(
                        text = "单位 token，默认 131072（128K）。这是估算阈值，不是硬上限：" +
                            "估算达到 " + (win * 75 / 100) + " tokens 才把旧对话压成摘要；" +
                            "真正发出去的只有 system + 摘要 + 最近 12 条原文，所以填大一点不会" +
                            "让每次请求变贵。压缩后如果没有真的变小，这次压缩会被放弃（不落盘）。",
                        fontSize = 13.sp,
                        color = PanelColors.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                // ⑥ 联网：搜索后端可换（DeepSeek 官方 / SearXNG / 自定义 JSON / 关）
                SectionCard(metrics, title = "联网搜索") {
                    SwitchPreference(
                        title = "联网搜索",
                        summary = if (webOn) "已开启 · #搜 / #读 与自动搜索都可用"
                        else "已关闭 · 不发送任何联网请求",
                        checked = webOn,
                        onCheckedChange = { webOn = it },
                    )
                    // 搜索服务：与「思考强度」同一个弹出列表样式
                    OverlayDropdownPreference(
                        insideMargin = PanelTokens.PrefInside,
                        title = "搜索服务",
                        summary = providerLabel(provider) +
                            if (provider == "deepseek") "（用主模型 Key 调 /v1/tools）"
                            else if (provider == "off") "" else "（" +
                                searchBase.ifBlank { "还没填地址" } + "）",
                        items = PROVIDER_LABELS,
                        selectedIndex = PROVIDERS.indexOf(provider).coerceAtLeast(0),
                        onSelectedIndexChange = { i ->
                            provider = PROVIDERS[i]
                            error = ""
                            notice = "搜索服务已选「" + PROVIDER_LABELS[i] + "」，点保存才落盘"
                        },
                    )
                    if (provider == "searxng" || provider == "custom") {
                        PanelEllipsisField(
                            value = searchBase,
                            onValueChange = { searchBase = it },
                            hint = if (provider == "searxng") "https://searx.example.com"
                            else "https://api.example.com/search?q={query}",
                            label = "搜索地址",
                            mono = true,
                        )
                        OverlayDropdownPreference(
                            insideMargin = PanelTokens.PrefInside,
                            title = "鉴权",
                            summary = AUTH_LABELS[AUTH_MODES.indexOf(authModeCoerced(authMode, provider))],
                            items = AUTH_LABELS,
                            selectedIndex = AUTH_MODES.indexOf(authModeCoerced(authMode, provider))
                                .coerceAtLeast(0),
                            onSelectedIndexChange = { i ->
                                authMode = AUTH_MODES[i]
                                notice = "鉴权已选「" + AUTH_LABELS[i] + "」，点保存才落盘"
                            },
                        )
                        if (authModeCoerced(authMode, provider) == "header") {
                            PanelEllipsisField(
                                value = authName, onValueChange = { authName = it },
                                hint = "X-API-Key", label = "Header 名", mono = true,
                            )
                            PanelEllipsisField(
                                value = authValue, onValueChange = { authValue = it },
                                hint = "值（不会写进日志）", label = "Header 值", secret = true,
                            )
                        }
                    }
                    if (provider == "custom") {
                        PanelEllipsisField(
                            value = searchFields,
                            onValueChange = { searchFields = it },
                            hint = "results[].title,url,summary",
                            label = "结果字段路径",
                            mono = true,
                        )
                    }
                    SwitchPreference(
                        title = "让模型自己决定要不要查",
                        summary = if (autoDecide)
                            "已开启 · 聊天时它拿不准/需要最新信息就自己先搜再答（一条最多搜 5 次）"
                        else "已关闭 · 只有 #搜 / #读 和「最近/新闻/价格」这类触发词才联网",
                        checked = autoDecide,
                        onCheckedChange = { autoDecide = it },
                    )
                    SwitchPreference(
                        title = "搜索结果人设化回答",
                        summary = if (personaStyle)
                            "已开启 · 搜完按人格合成一段自然回答（至多 1 条干净链接），不列清单"
                        else "已关闭 · 原始列表贴法（标题｜摘要 + 链接逐条贴，调试/对比用）",
                        checked = personaStyle,
                        onCheckedChange = { personaStyle = it },
                    )
                    Text(
                        text = "不用打前缀：聊天里它不确定就会自己搜（「最近/今天/新闻/多少钱/" +
                            "价格/什么时候/谁是/查一下」这类词也会兜底搜一次），最多连查 5 轮。" +
                            "#搜 / #读 只有主人能用，别人发 # 就是普通聊天。回复都是一段话，" +
                            "最多附一条链接。中转站/第三方模型通常没有 /v1/tools，请改用" +
                            "「SearXNG 自建」或「自定义 JSON 接口」（#读 抓正文只有 DeepSeek 官方" +
                            "支持）。关掉「人设化回答」= 回到原始列表贴法。" +
                            "DeepSeek 官方计费：每 1000 次成功请求 5 元。",
                        fontSize = 13.sp,
                        color = PanelColors.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                // ⑦ 保存：只在这里落盘（先读取后保存的顺序也成立）
                SectionCard(metrics) {
                    PanelPrimaryButton(
                        text = "保存",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { save() },
                    )
                    Text(
                        text = "保存会同时写入接口地址、API Key、主模型、识图/判定/摘要三个槽位、" +
                            "上下文窗口、联网开关与思考强度；没点保存前改的值不落盘。",
                        fontSize = 13.sp,
                        color = PanelColors.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }

    // 底部选择卡片：ModalBottomSheet（现成组件，不自绘）+ miuix 的 BasicComponent 行
    if (sheetFor.isNotBlank()) {
        ModelPickSheet(
            title = when (sheetFor) {
                "vision" -> "选择识图模型"
                "judge" -> "选择判定模型"
                "summary" -> "选择摘要模型"
                "search" -> "选择搜索模型"
                else -> "选择主模型"
            },
            models = models,
            current = when (sheetFor) {
                "vision" -> visionModel
                "judge" -> judgeModel
                "summary" -> summaryModel
                "search" -> searchModel
                else -> mainModel
            },
            onPick = { pick(it) },
            onDismiss = { sheetFor = "" },
        )
    }
}

/**
 * 一个模型槽位（识图 / 判定 / 摘要）的卡片：输入框 +「当前 → 实际使用」+ 留空说明 + 读取模型。
 *
 * 三个槽位版式完全一致，只有标题与说明不同，所以共用这一个 composable。
 * 「实际使用」那一行就是 Python 侧 _conf_for 的回退结果：留空 → 主模型。
 */
@Composable
private fun SlotCard(
    metrics: Metrics,
    title: String,
    help: String,
    value: String,
    main: String,
    busy: Boolean,
    anyBusy: Boolean,
    onValueChange: (String) -> Unit,
    onRead: () -> Unit,
) {
    val cur = value.trim()
    val actual = cur.ifBlank { main.trim().ifBlank { "deepseek-chat" } }
    SectionCard(metrics, title = title) {
        PanelEllipsisField(
            value = value,
            onValueChange = onValueChange,
            hint = "留空则沿用主模型",
            mono = false,
        )
        Text(
            text = "当前：" + cur.ifBlank { "（留空）" } + " → 实际使用：" + actual,
            fontSize = 13.sp,
            color = PanelColors.primary,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = help,
            fontSize = 13.sp,
            color = PanelColors.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 6.dp),
        )
        PanelPillRow(modifier = Modifier.padding(top = 10.dp)) {
            PanelPrimaryButton(
                text = if (busy) "读取中…" else "读取模型",
                modifier = Modifier.fillMaxWidth(),
                enabled = !anyBusy,
                onClick = onRead,
            )
        }
    }
}

/**
 * 从底部弹出的模型选择卡片。
 *
 * 用 Material3 的 ModalBottomSheet（现成组件，不自绘卡片形状 / 拖拽 / 遮罩），
 * 里面的候选行用 miuix 的 BasicComponent —— 与设置页同一个行样式。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickSheet(
    title: String,
    models: List<String>,
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MiuixTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Text(
            text = title,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            color = PanelColors.onBackground,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            items(models, key = { it }) { name ->
                BasicComponent(
                    title = name,
                    summary = if (name == current) "当前已填" else null,
                    onClick = { onPick(name) },
                )
            }
        }
    }
}

/** 顶栏：细线返回箭头一行，大标题「模型配置」独占下一行（与关于页/主题页同一套）。 */
@Composable
private fun ModelConfigHeader(onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 4.dp, end = 16.dp, bottom = 6.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = PanelColors.onBackground,
                modifier = Modifier.size(24.dp),
            )
        }
        Text(
            text = "模型配置",
            fontSize = 28.sp,
            fontWeight = FontWeight(600),
            color = PanelColors.onBackground,
            modifier = Modifier.padding(start = 12.dp, top = 2.dp),
        )
    }
}

/**
 * 「思考强度」下拉的候选：第一项是默认（不发送该字段），最后一项是自定义输入。
 *
 * 列表是**显示**用的首字母大写；协议要小写（DeepSeek 官方大小写敏感，实测发 Low 会 422），
 * 所以选中后存的、发的都是小写发送值 —— llm.json 里也是小写，换平台看文件都清楚。
 * 「自定义…」里的字符串原样透传（大小写由用户负责，验证按钮会告诉他平台认不认）。
 */
private val EFFORT_DISPLAYS = listOf("None", "Minimal", "Low", "Medium", "High", "Xhigh", "Ultra", "Max")

/**
 * 上下文窗口的快捷胶囊：显示名 → 填进输入框的值（token）。
 *
 * 只是「帮用户少打字」，点完仍然可以自由输入 —— 不做区间校验、不锁定，
 * 因为各家模型的真实上限差别很大（64K 到 1M），这里给的是常见档位。
 */
private val WINDOW_PRESETS = listOf(
    "64K" to "65536",
    "128K" to "131072",
    "256K" to "262144",
    "512K" to "524288",
    "1M" to "1048576",
)
private val WINDOW_ITEMS = WINDOW_PRESETS.map { it.first } + listOf("自定义…")

/** 搜索后端（与 Python 侧 _SEARCH_PROVIDERS 一致）。 */
private val PROVIDERS = listOf("deepseek", "searxng", "custom", "off")
private val PROVIDER_LABELS = listOf(
    "DeepSeek 官方（默认）", "SearXNG 自建", "自定义 JSON 接口", "关闭（不联网）",
)

private fun providerLabel(p: String): String {
    val i = PROVIDERS.indexOf(p)
    return if (i >= 0) PROVIDER_LABELS[i] else PROVIDER_LABELS[0]
}

/** 鉴权：none / bearer（用主模型 Key）/ header:Name=Value。 */
private val AUTH_MODES = listOf("none", "bearer", "header")
private val AUTH_LABELS = listOf("无", "Bearer（用主模型 Key）", "自定义 Header…")

/** 空串 = 跟随后端默认：DeepSeek 官方用 bearer，其它用 none。 */
private fun authModeCoerced(mode: String, provider: String): String = when {
    mode == "none" || mode == "bearer" || mode == "header" -> mode
    provider == "deepseek" -> "bearer"
    else -> "none"
}

private fun authModeOf(raw: String): String = when {
    raw.startsWith("header:") -> "header"
    raw.equals("none", true) -> "none"
    raw.equals("bearer", true) -> "bearer"
    else -> ""
}

private fun authNameOf(raw: String): String {
    if (!raw.startsWith("header:") || !raw.contains("=")) return ""
    return raw.removePrefix("header:").substringBefore("=").trim()
}

private fun authValueOf(raw: String): String {
    if (!raw.startsWith("header:") || !raw.contains("=")) return ""
    return raw.removePrefix("header:").substringAfter("=").trim()
}

/** 存进 llm.json 的 search_auth：header 模式下把名与值拼成 header:Name=Value。 */
private fun authWire(mode: String, name: String, value: String, provider: String): String {
    val m = authModeCoerced(mode, provider)
    return if (m == "header") "header:" + name.trim() + "=" + value.trim() else m
}
private val EFFORT_ITEMS = listOf("不指定（用平台默认）") + EFFORT_DISPLAYS + listOf("自定义…")

/** 显示项 → 发送值（小写）。 */
private fun effortWire(display: String): String = display.lowercase()

/** 发送值在候选里的位置：大小写不敏感地认（老配置存过大写也能对回显示项）。 */
private fun effortPresetIndex(wire: String): Int =
    if (wire.isEmpty()) -1 else EFFORT_DISPLAYS.indexOfFirst { it.equals(wire, ignoreCase = true) }

/** 模型配置的读写：Key 走 SecureStore（密文落盘），其余字段是普通 prefs。 */
private object ModelPrefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("andbna_local", Context.MODE_PRIVATE)

    fun read(ctx: Context, k: String): String {
        val v = SecureStore.get(sp(ctx), k)
        // 老版本把识图模型写在 vision_model 里：新键为空时读旧值顶上，保存时再迁到新键
        if (k == "llm_vision_model" && v.isBlank()) {
            return sp(ctx).getString("vision_model", "").orEmpty()
        }
        return v
    }

    fun save(
        ctx: Context,
        api: String,
        key: String,
        model: String,
        vision: String,
        judge: String,
        summary: String,
        search: String,
        window: String,
        web: Boolean,
        personaStyle: Boolean,
        autoDecide: Boolean,
        effort: String,
        searchProvider: String,
        searchBase: String,
        searchAuth: String,
        searchFields: String,
    ) {
        sp(ctx).edit()
            .putString("llm_api", api.trim())
            .putString("llm_model", model.trim())
            // 四个槽位留空就存空串：Python 侧按「空 = 用主模型」回退
            .putString("llm_vision_model", vision.trim())
            .putString("llm_judge_model", judge.trim())
            .putString("llm_summary_model", summary.trim())
            .putString("llm_search_model", search.trim())
            // 上下文窗口：留空就写默认 131072（Python 侧同一口径；老配置里的值不动）
            .putString("llm_context_window", window.trim().ifBlank { "131072" })
            .putString("web_search_enabled", if (web) "1" else "0")
            .putString("search_persona_style", if (personaStyle) "1" else "0")
            .putString("search_auto_decide", if (autoDecide) "1" else "0")
            // 搜索后端：换后端不动上层（统一 [{title,url,summary}]）
            .putString("search_provider", searchProvider.trim().ifBlank { "deepseek" })
            .putString("search_base", searchBase.trim())
            .putString("search_auth", searchAuth.trim())
            .putString("search_fields", searchFields.trim()
                .ifBlank { "results[].title,url,summary" })
            // 思考强度留空也存空串：Python 侧按「空 = 请求体不带 reasoning_effort」
            .putString("llm_reasoning_effort", effort.trim())
            // 老键读完就删：只保留一个识图模型键
            .remove("vision_model")
            .apply()
        SecureStore.writePref(sp(ctx), "llm_key", key)
        BotStore.writeLlmJson(ctx)
    }
}