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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wjljh.andbna.ModelCatalog
import com.wjljh.andbna.local.BotStore
import com.wjljh.andbna.local.SecureStore
import com.wjljh.andbna.ui.component.PanelAnimatedNotice
import com.wjljh.andbna.ui.component.PanelEllipsisField
import com.wjljh.andbna.ui.component.PanelNotice
import com.wjljh.andbna.ui.component.PanelPillRow
import com.wjljh.andbna.ui.component.PanelPrimaryButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
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
 */
@Composable
fun ModelConfigScreen(metrics: Metrics, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var api by remember { mutableStateOf(ModelPrefs.read(ctx, "llm_api")) }
    var key by remember { mutableStateOf(ModelPrefs.read(ctx, "llm_key")) }
    var mainModel by remember { mutableStateOf(ModelPrefs.read(ctx, "llm_model")) }
    var visionModel by remember { mutableStateOf(ModelPrefs.read(ctx, "llm_vision_model")) }

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
        ModelPrefs.save(ctx, api = api, key = key, model = mainModel, vision = visionModel)
        error = ""
        notice = if (visionModel.isBlank()) {
            "已保存；识图模型留空 → 识图沿用主模型"
        } else {
            "已保存（主模型 " + mainModel.trim().ifBlank { "deepseek-chat" } + "，识图 " + visionModel.trim() + "）"
        }
    }

    fun pick(name: String) {
        if (sheetFor == "main") mainModel = name else visionModel = name
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

                // ③ 识图模型：留空 = 沿用主模型
                SectionCard(metrics, title = "识图模型") {
                    PanelEllipsisField(
                        value = visionModel,
                        onValueChange = { visionModel = it },
                        hint = "留空则沿用主模型",
                        mono = false,
                    )
                    Text(
                        text = "留空则沿用主模型；收到图片时用这个模型识图。",
                        fontSize = 13.sp,
                        color = PanelColors.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    PanelPillRow(modifier = Modifier.padding(top = 10.dp)) {
                        PanelPrimaryButton(
                            text = if (busy == "vision") "读取中…" else "读取模型",
                            modifier = Modifier.fillMaxWidth(),
                            enabled = busy.isEmpty(),
                            onClick = { readInto("vision") },
                        )
                    }
                }

                // ④ 保存：只在这里落盘（先读取后保存的顺序也成立）
                SectionCard(metrics) {
                    PanelPrimaryButton(
                        text = "保存",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { save() },
                    )
                    Text(
                        text = "保存会同时写入接口地址、API Key、主模型与识图模型；没点保存前改的值不落盘。",
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
            title = if (sheetFor == "vision") "选择识图模型" else "选择主模型",
            models = models,
            current = if (sheetFor == "vision") visionModel else mainModel,
            onPick = { pick(it) },
            onDismiss = { sheetFor = "" },
        )
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

/** 模型配置的读写：Key 走 SecureStore（密文落盘），其余三个字段是普通 prefs。 */
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

    fun save(ctx: Context, api: String, key: String, model: String, vision: String) {
        sp(ctx).edit()
            .putString("llm_api", api.trim())
            .putString("llm_model", model.trim())
            // 识图模型留空就存空串：Python 侧按「空 = 用主模型」回退
            .putString("llm_vision_model", vision.trim())
            // 老键读完就删：只保留一个识图模型键
            .remove("vision_model")
            .apply()
        SecureStore.writePref(sp(ctx), "llm_key", key)
        BotStore.writeLlmJson(ctx)
    }
}