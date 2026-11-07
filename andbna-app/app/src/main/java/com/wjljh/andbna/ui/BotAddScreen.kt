package com.wjljh.andbna.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold as M3Scaffold
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.TopAppBar as M3TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.wjljh.andbna.local.BotHost
import com.wjljh.andbna.local.BotStore
import com.wjljh.andbna.local.BotStore.llmApi
import com.wjljh.andbna.local.BotStore.llmKey
import com.wjljh.andbna.local.BotStore.llmModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wjljh.andbna.local.LocalBot
import com.wjljh.andbna.local.PersonaAssets
import com.wjljh.andbna.ui.component.BotFormField
import com.wjljh.andbna.ui.component.BotFormModelSection
import com.wjljh.andbna.ui.component.BotFormPersonaSection
import com.wjljh.andbna.ui.component.BotFormSecretSection
import com.wjljh.andbna.ui.component.BotFormSection
import com.wjljh.andbna.ui.component.BotFormSwitchRow
import com.wjljh.andbna.ui.component.PanelGroupCard
import com.wjljh.andbna.ui.component.PanelPillRow
import com.wjljh.andbna.ui.component.PanelPrimaryButton
import com.wjljh.andbna.ui.component.PanelSecondaryButton
import com.wjljh.andbna.ui.component.PanelTokens
import com.wjljh.andbna.ui.component.rememberPersonaImporter
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 新建 bot 的独立页面（Route.BotAdd）。
 *
 * 结构照抄 [BotEditScreen]：同一套外壳（顶栏返回 + 滚动正文）、同样的区块顺序与尺寸，
 * 区块 / 输入框 / 开关行 / 人格 / 模型全部来自 ui/component/BotFormParts.kt 那份共享组件 ——
 * 这里只多一个「AppID 可填」，少「定时消息 / 目标 openid」两块（那两个是建完之后才有的东西）。
 *
 * 之前「+」弹的是 OverlayDialog（AppID / 名字 / AppSecret 三个裸输入框），现在改成这一页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotAddScreen(metrics: Metrics, onBack: () -> Unit, onCreated: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val title = "添加 bot"

    val scope = rememberCoroutineScope()
    var appid by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(true) }
    var persona by remember { mutableStateOf("") }
    var inherit by remember { mutableStateOf(true) }
    // 模型三件套 + 人格清单也不在组合期读（prefs / assets 都是 IO）
    var llmK by remember { mutableStateOf("") }
    var llmA by remember { mutableStateOf("") }
    var llmM by remember { mutableStateOf("") }
    var globalModel by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }

    var personas by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) {
        val plist = withContext(Dispatchers.IO) { PersonaAssets.listAll(ctx) }
        val k = withContext(Dispatchers.IO) { ctx.llmKey }
        val a = withContext(Dispatchers.IO) { ctx.llmApi }
        val m = withContext(Dispatchers.IO) { ctx.llmModel }
        personas = plist
        // 默认选中第一个人格：否则下拉右侧已显示候选首项、说明却写「还没选」，看着自相矛盾
        persona = plist.firstOrNull().orEmpty()
        llmK = k
        llmA = a
        llmM = m
        globalModel = m
    }
    val customNames = personas.filter { PersonaAssets.isCustom(it) }
        .map { it.removePrefix(PersonaAssets.CUSTOM_PREFIX) }
    val openPersonaPicker = rememberPersonaImporter(
        onImported = { n ->
            personas = PersonaAssets.listAll(ctx)
            persona = PersonaAssets.CUSTOM_PREFIX + n
            msg = "已导入自定义人格：" + n
        },
        onMessage = { msg = it },
    )

    /** 创建 = 按 AppID upsert（同 AppID 等于改它）；密钥留空则沿用旧的。 */
    fun create() {
        val id = appid.trim()
        if (id.isBlank()) {
            msg = "AppID 不能为空"
            return
        }
        // 读盘 / 写盘（含密钥加密）都在 IO：点一下「创建」不再卡主线程
        scope.launch {
            withContext(Dispatchers.IO) {
                val old = BotStore.load(ctx).firstOrNull { it.appid == id }
                BotStore.upsert(
                    ctx,
                    LocalBot(
                        appid = id,
                        secret = secret.trim().ifBlank { old?.secret.orEmpty() },
                        persona = persona,
                        enabled = enabled,
                        name = name.trim().ifBlank { old?.name.orEmpty() },
                        useGlobalLlm = inherit,
                    )
                )
                // 新建即落人格：正文 + 来源标记（persona_meta.json）一起写 ——
                // 只写 localbots.json 的话，Python 侧只能按人格名字猜来源，
                // 导入的同名人格（也叫「鲸鱼娘」）会被当成内置档
                if (persona.isNotBlank()) {
                    PersonaAssets.writeTo(ctx, persona, java.io.File(BotHost.dataDir(ctx), id))
                }
                if (!inherit) {
                    // 没有 per-bot 模型存储：沿用全局三件套（与 Bot 页「模型配置」同一份接口）
                    ctx.llmKey = llmK
                    ctx.llmApi = llmA
                    ctx.llmModel = llmM
                    BotStore.writeLlmJson(ctx)
                }
            }
            onCreated(id)
            onBack()
        }
    }

    val body: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PanelTokens.PageHorizontal, vertical = PanelTokens.PageTop),
        ) {
            if (msg.isNotBlank()) {
                Text(
                    text = msg,
                    fontSize = PanelTokens.InfoValueSize,
                    color = PanelColors.primary,
                    modifier = Modifier.padding(start = 4.dp, bottom = PanelTokens.CardSpacing),
                )
            }

            // ① 基本信息：AppID（可填）+ 名字 + 启用
            BotFormSection(title = "基本信息") {
                BotFormField(
                    label = "AppID",
                    value = appid,
                    onValueChange = { appid = it.trim() },
                    hint = "平台的 AppID（数字）",
                    numeric = true,
                )
                // 名字在添加页就能填（用户反馈「添加界面没有名字编辑选项」：
                // 字段本来就在，只是空值时只剩一行占位符，看不出是个输入框 ——
                // 占位符里写明留空的后果，用户一眼就知道能填、也能不填）
                BotFormField(
                    label = "名字",
                    value = name,
                    onValueChange = { name = it },
                    hint = "给这个 bot 起个名字，留空则显示 AppID",
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    thickness = PanelTokens.Divider,
                    color = PanelColors.dividerLine,
                )
                BotFormSwitchRow(
                    title = "启用",
                    summary = if (enabled) "创建后启用，启动服务即上线" else "先停用，之后在卡片上打开",
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                )
            }

            // ② 密钥
            BotFormSecretSection(saved = false, value = secret, onValueChange = { secret = it })

            // ③ 人格：内置档 + 自定义（导入）
            BotFormPersonaSection(
                personas = personas,
                selected = persona,
                customNames = customNames,
                onSelect = { persona = it },
                onImport = { openPersonaPicker() },
                onDeleteCustom = { full ->
                    PersonaAssets.deleteCustom(ctx, full)
                    personas = PersonaAssets.listAll(ctx)
                    if (persona == full) persona = ""
                    msg = "已删除自定义人格：" + full.removePrefix(PersonaAssets.CUSTOM_PREFIX)
                },
            )

            // ④ 模型：继承开关 / 接口地址 / API Key / 模型下拉
            BotFormModelSection(
                inherit = inherit,
                onInheritChange = { inherit = it },
                globalModel = globalModel,
                api = llmA,
                key = llmK,
                model = llmM,
                onApi = { llmA = it },
                onKey = { llmK = it },
                onModel = { llmM = it },
            )

            // ⑤ 动作
            PanelGroupCard {
                PanelPillRow {
                    PanelPrimaryButton(
                        text = "创建",
                        onClick = { create() },
                        modifier = Modifier.weight(1f),
                    )
                    PanelSecondaryButton(
                        text = "取消",
                        onClick = onBack,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(PanelTokens.CardSpacing))
        }
    }

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
            Box(Modifier.fillMaxSize().padding(pad)) { body() }
        }
    } else {
        Scaffold(
            containerColor = MiuixTheme.colorScheme.surface,
            topBar = {
                TopAppBar(
                    title = title,
                    largeTitle = title,
                    subtitle = "AppID 与已有 bot 相同时等于改它",
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
            Box(Modifier.fillMaxSize().padding(pad)) { body() }
        }
    }
}
