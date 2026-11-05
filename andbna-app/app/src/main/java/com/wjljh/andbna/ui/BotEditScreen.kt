package com.wjljh.andbna.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wjljh.andbna.ModelCatalog
import com.wjljh.andbna.local.BotHost
import com.wjljh.andbna.local.BotRestarts
import com.wjljh.andbna.local.BotStore
import com.wjljh.andbna.local.BotStore.groupAutochatOverride
import com.wjljh.andbna.local.BotStore.setGroupAutochatOverride
import com.wjljh.andbna.local.BotStore.llmApi
import com.wjljh.andbna.local.BotStore.llmKey
import com.wjljh.andbna.local.BotStore.llmModel
import com.wjljh.andbna.local.LocalBot
import com.wjljh.andbna.local.PersonaAssets
import com.wjljh.andbna.local.WordsStore
import com.wjljh.andbna.ui.component.BotFormField
import com.wjljh.andbna.ui.component.BotFormModelSection
import com.wjljh.andbna.ui.component.BotFormPersonaSection
import com.wjljh.andbna.ui.component.BotFormSecretSection
import com.wjljh.andbna.ui.component.BotFormSection
import com.wjljh.andbna.ui.component.BotFormSwitchRow
import com.wjljh.andbna.ui.component.rememberPersonaImporter
import com.wjljh.andbna.ui.component.PanelArrowRow
import com.wjljh.andbna.ui.component.PanelGroupCard
import com.wjljh.andbna.ui.component.PanelIconPillButton
import com.wjljh.andbna.ui.component.PanelPillRow
import com.wjljh.andbna.ui.component.PanelPrimaryButton
import com.wjljh.andbna.ui.component.PanelSecondaryButton
import com.wjljh.andbna.ui.component.SecretEyeButton
import com.wjljh.andbna.ui.component.PanelTokens
import com.wjljh.andbna.ui.component.panelStepTransition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Bot 详细配置二级页（Route.BotEdit）。
 *
 * 顶栏：细线返回箭头 (MiuixIcons.Back) + bot 名 + AppID 副标题。
 * 正文区块顺序：基本信息 / 密钥 / 主人 openid / 人格 / 称呼 / 问候 / 模型 / 动作；
 * 老的「按点发一条固定消息」整块已下线（改由「问候」的时段承担），页面里不再出现。
 * 区块标题 17sp，卡间距 12dp，卡内 16dp（[PanelGroupCard] 自带）。
 *
 * 「称呼」「问候」两块是本 bot 自己的（<botdir>/phrases.json、<botdir>/greetings.json），
 * 与全局旧文案无关：口癖下线，称呼 / 问候按 bot 各存一份（见 local/WordsStore.kt）。
 *
 * 输入框一律用 kit 的 miuix [TextField]（自带圆角底 + 边框 + 聚焦高亮），
 * 框上方再给一条 13sp 标签，占位符走 label 参数 —— 一眼能看出哪里能输入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotEditScreen(metrics: Metrics, appid: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // 「群配置」二级页就地接管整屏（不新增 App 路由）：返回就回到本页，编辑页状态全在 remember 里。
    // 进出这一层也用 panelStepTransition（与列表↔单群编辑、引导页同一份），在函数尾部渲染时切换。
    var showGroups by remember(appid) { mutableStateOf(false) }
    // 组合期一律不读盘、不读 prefs：bot 详情 / 模型三件套 / 人格清单 / 定时表 / openid 全在下面的
    // LaunchedEffect 里按 IO 读回来再填状态（这几行以前都是 remember { ... } 里同步读文件）。
    var bot by remember(appid) { mutableStateOf<LocalBot?>(null) }
    var title by remember(appid) { mutableStateOf(appid) }

    var secret by remember(appid) { mutableStateOf("") }
    var name by remember(appid) { mutableStateOf("") }
    var persona by remember(appid) { mutableStateOf("") }
    var enabled by remember(appid) { mutableStateOf(false) }
    var inherit by remember(appid) { mutableStateOf(true) }
    // 人格下拉的数据源：assets/personas 的内置档 + files/personas 的自定义档（导入 / 删除后重算）
    var personas by remember { mutableStateOf<List<String>>(emptyList()) }
    val customNames = personas.filter { PersonaAssets.isCustom(it) }
        .map { it.removePrefix(PersonaAssets.CUSTOM_PREFIX) }

    var llmK by remember { mutableStateOf("") }
    var llmA by remember { mutableStateOf("") }
    var llmM by remember { mutableStateOf("") }
    /** 继承开关打开时显示的全局模型名（也是模型区块的参数，别在组合期读 prefs） */
    var globalModel by remember { mutableStateOf("") }

    // 「自动获取」：本 bot 运行期落下的 openid（来源只有 files/localbot/<appid>/memory.json）
    var groups by remember(appid) { mutableStateOf<List<String>>(emptyList()) }
    var owner by remember(appid) { mutableStateOf("") }
    var idMsg by remember(appid) { mutableStateOf("") }

    var msg by remember { mutableStateOf("") }
    // 群里自动接话的**按 bot 覆盖**（BotStore.group_autochat_override）：""=跟随全局 / "1"=开 / "0"=关。
    // 组合期不读 prefs，等下面 IO 回来再填；生效优先级见 bot_runner._group_autochat_state。
    var autochatOverride by remember(appid) { mutableStateOf("") }

    // 称呼（<botdir>/phrases.json）：只有两个对象，键名写死 master / others
    var aliasMaster by remember(appid) { mutableStateOf("") }
    var aliasOthers by remember(appid) { mutableStateOf("") }

    // 问候（<botdir>/greetings.json）：总开关 + 两个去处 + 时段表
    var greetOn by remember(appid) { mutableStateOf(true) }
    var greetOwner by remember(appid) { mutableStateOf(true) }
    var greetGroups by remember(appid) { mutableStateOf(true) }
    var slots by remember(appid) { mutableStateOf<List<GreetSlot>>(emptyList()) }
    // 内置人格（preset=true）：时段内容由 bot_runner._PRESET_GREETINGS 定死，页面只读
    var preset by remember(appid) { mutableStateOf(false) }
    var openSlot by remember(appid) { mutableStateOf(-1) }
    // 读盘时的原始时段表：保存时用它判断「时段真的改了」，改了才重启 bot
    var slotsBaseline by remember(appid) { mutableStateOf<List<GreetSlot>>(emptyList()) }

    // 「自定义…（导入文件）」：OpenDocument(text/*) → files/personas/<安全名>.txt → 直接选中它
    val openPersonaPicker = rememberPersonaImporter(
        onImported = { n ->
            personas = PersonaAssets.listAll(ctx)
            persona = PersonaAssets.CUSTOM_PREFIX + n
            msg = "已导入自定义人格：" + n
        },
        onMessage = { msg = it },
    )

    /** 从运行记录采集本 bot 的 owner + 群列表（跨 Chaquopy 调用，只在 IO 线程跑）。 */
    suspend fun collectIds() {
        val ids = parseIds(withContext(Dispatchers.IO) { BotHost.idsFor(ctx, appid) })
        owner = ids.owner
        groups = ids.groups
        idMsg = when {
            ids.owner.isNotBlank() && ids.groups.isEmpty() -> "已定位主人（还没记到群）"
            ids.owner.isNotBlank() -> "已定位主人，另记到 " + ids.groups.size + " 个群"
            ids.groups.isEmpty() -> "还没采到：先启动 bot 让它收到一条群消息或私聊，再点一次"
            else -> "还没定位到主人：让主人私聊 bot 一次再来"
        }
    }

    LaunchedEffect(appid) {
        // ① 详情 / 模型三件套 / 人格档：一次 IO 读回来
        val loaded = withContext(Dispatchers.IO) { BotStore.load(ctx).firstOrNull { it.appid == appid } }
        val k = withContext(Dispatchers.IO) { ctx.llmKey }
        val a = withContext(Dispatchers.IO) { ctx.llmApi }
        val m = withContext(Dispatchers.IO) { ctx.llmModel }
        val plist = withContext(Dispatchers.IO) { PersonaAssets.listAll(ctx) }
        bot = loaded
        title = loaded?.title ?: appid
        name = loaded?.name.orEmpty()
        persona = loaded?.persona.orEmpty()
        enabled = loaded?.enabled ?: false
        inherit = loaded?.useGlobalLlm ?: true
        personas = plist
        llmK = k
        llmA = a
        llmM = m
        globalModel = m
        // 群里自动接话：本 bot 的三态覆盖（设置页那一行是全局默认，两者不再共用同一个值）
        autochatOverride = withContext(Dispatchers.IO) { ctx.groupAutochatOverride(appid) }

        // ② 称呼 + 问候：本 bot 自己的 phrases.json / greetings.json（缺文件就是空 / 默认值）
        val words = withContext(Dispatchers.IO) { WordsStore.loadBot(ctx, appid) }
        aliasMaster = words.master
        aliasOthers = words.others
        greetOn = words.enabled
        greetOwner = words.ownerTarget
        greetGroups = words.groupsTarget
        // 内置人格的候选文案只在运行时那张表里（文件里 slots 的 lines 是空的），问它一次只读快照
        val fixedRaw = withContext(Dispatchers.IO) {
            BotHost.presetGreetings(presetPersonaName(loaded?.persona.orEmpty()))
        }
        val fixed = parsePresetSlots(fixedRaw)
        preset = fixed.isNotEmpty() || words.preset
        slots = mergeGreetSlots(fixed, words.slots.map { it.toGreetSlot() })
        slotsBaseline = slots

        // ③ openid：主人（问候发私聊用）+ 群列表（「另记到 N 个群」用）
        collectIds()
    }

    // 老的 tasks.json「定时任务」已下线：编辑页不再读写它，运行侧也不再执行（见 bot_runner._sched_loop）
    fun saveAll() {
        // 时段表先在本线程校验 / 归一化：时刻写错、候选空着都不落盘（也不静默丢时段）
        val (normalized, slotErr) = normalizeGreetSlots(slots, preset)
        if (normalized == null) {
            msg = slotErr
            return
        }
        slots = normalized
        // 读盘 + 写盘（加密、落 JSON）都塞进 IO：点一下保存不该把主线程顶住
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                val old = BotStore.load(ctx).firstOrNull { it.appid == appid }
                // 保存前的现值：真变了才重启（并走 1.5 秒合并窗口），每次点保存都断线没人受得了
                val prevKey = ctx.llmKey
                val prevApi = ctx.llmApi
                val prevModel = ctx.llmModel
                val prevWords = WordsStore.loadBot(ctx, appid)
                if (old != null) {
                    BotStore.upsert(
                        ctx,
                        old.copy(
                            secret = secret.trim().ifBlank { old.secret },
                            persona = persona,
                            enabled = enabled,
                            name = name.trim(),
                            useGlobalLlm = inherit,
                        )
                    )
                }
                if (!inherit) {
                    // 没有 per-bot 模型存储：沿用现有的全局三件套（与 Bot 页「模型配置」同一份接口）
                    ctx.llmKey = llmK
                    ctx.llmApi = llmA
                    ctx.llmModel = llmM
                    BotStore.writeLlmJson(ctx)
                }
                // 保存即落地：Python 每次回复读的都是 <botdir>/persona.txt，
                // 只写 localbots.json 的话运行中的 bot 要等下次 startAll 才换人格。
                if (persona.isNotBlank()) {
                    PersonaAssets.writeTo(ctx, persona, java.io.File(BotHost.dataDir(ctx), appid))
                }
                // ③ 称呼 / 问候：写本 bot 自己的两份文件（<botdir>/phrases.json + greetings.json）
                var wordsErr = ""
                if (!WordsStore.saveBotAliases(ctx, appid, aliasMaster, aliasOthers)) {
                    wordsErr = "称呼没写进 phrases.json"
                }
                if (!WordsStore.saveBotEnabled(ctx, appid, greetOn)) {
                    wordsErr = "启用开关没写进 greetings.json"
                }
                if (!WordsStore.saveBotTargets(ctx, appid, greetOwner, greetGroups)) {
                    wordsErr = "发到哪里没写进 greetings.json"
                }
                val okSlots = if (preset) {
                    // 内置人格：落 name / at / window_min / disabled，preset 保持 true（内容由运行时给）
                    WordsStore.saveBotSlotTimes(ctx, appid, normalized.map { it.toWordsSlot() })
                } else {
                    // 自定义人格：关掉的时段照样写盘（disabled=true），运行侧跳过 —— 不删，随时能开回来
                    WordsStore.saveBotSlots(ctx, appid, normalized.map { it.toWordsSlot() })
                }
                if (!okSlots) wordsErr = "时段没写进 greetings.json"
                // 运行时只在启动时建调度循环、配置是每轮现读的，所以改动要重连一次才生效；
                // 但只有「值确实变了」才重连（人格 / 称呼 / 问候 / 模型三件套 / 启用 / 名字都算）。
                val slotChanged = normalized != slotsBaseline
                val changed = slotChanged || old != null && old.let { o ->
                    o.persona != persona ||
                        o.name.trim() != name.trim() ||
                        o.enabled != enabled ||
                        o.useGlobalLlm != inherit ||
                        o.secret != secret.trim().ifBlank { o.secret } ||
                        (!inherit && (prevKey != llmK.trim() || prevApi != llmA.trim() ||
                            prevModel != llmM.trim())) ||
                        prevWords.master != aliasMaster ||
                        prevWords.others != aliasOthers ||
                        prevWords.enabled != greetOn ||
                        prevWords.ownerTarget != greetOwner ||
                        prevWords.groupsTarget != greetGroups
                }
                val note = if (!changed || !BotRestarts.requestOne(ctx, appid, "编辑保存")) "" else {
                    "正在重启「" + name.trim().ifBlank { appid } + "」…"
                }
                SavedNote(note = note, wordsErr = wordsErr, slotChanged = slotChanged)
            }
            // 存过就是新基线：下次保存没再动时段，就不会又断一次线
            if (saved.slotChanged) slotsBaseline = slots
            msg = when {
                saved.wordsErr.isNotBlank() -> "已保存，但" + saved.wordsErr
                saved.note.isNotBlank() -> "已保存 · " + saved.note
                else -> "已保存"
            }
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

            // ① 基本信息：AppID 只读 + 启用开关 + 名字
            BotFormSection(title = "基本信息") {
                Text(
                    text = "AppID（不可修改）",
                    fontSize = 13.sp,
                    fontWeight = FontWeight(550),
                    color = PanelColors.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 4.dp),
                )
                Text(
                    text = appid,
                    fontSize = PanelTokens.InfoValueSize,
                    color = PanelColors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    thickness = PanelTokens.Divider,
                    color = PanelColors.dividerLine,
                )
                BotFormSwitchRow(
                    title = "启用",
                    summary = if (enabled) "已启用，重启服务后上线" else "已停用",
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                )
                Spacer(Modifier.height(12.dp))
                BotFormField(
                    label = "名字",
                    value = name,
                    onValueChange = { name = it },
                    hint = "方便管理，可留空",
                )
            }

            // ② 密钥：落盘加密（Keystore），输入框默认掩码 + 眼睛切换，留空 = 不修改
            BotFormSecretSection(
                saved = bot?.secret?.isNotBlank() == true,
                value = secret,
                onValueChange = { secret = it },
            )

            // ③ 主人 openid：只留这一项（群 / 私聊 openid 两组按用户要求删掉）
            BotFormSection(title = "主人 openid") {
                Text(
                    text = "问候发私聊时发给这个 openid",
                    fontSize = 13.sp,
                    color = PanelColors.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                )
                if (owner.isBlank()) {
                    Text(
                        text = "还没采到：先让主人私聊 bot 一次，再点下面这个按钮",
                        fontSize = 13.sp,
                        color = PanelColors.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                    )
                } else {
                    // 单行 + 等宽 + 超长省略号，不再用整块大方块占半屏
                    Text(
                        text = owner,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        color = PanelColors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                PanelPillRow {
                    PanelSecondaryButton(
                        text = "从运行记录采集",
                        onClick = { scope.launch { collectIds() } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (idMsg.isNotBlank()) {
                    Text(
                        text = idMsg,
                        fontSize = 13.sp,
                        color = PanelColors.primary,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                    )
                }
            }

            // ⑤ 人格：内置档（assets/personas）+ 自定义档（files/personas），末尾「自定义…（导入文件）」
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

            // ⑥ 称呼：只有两个对象（主人 / 其他人），写 <botdir>/phrases.json 的 aliases
            BotFormSection(title = "称呼") {
                BotFormField(
                    label = "主人",
                    value = aliasMaster,
                    onValueChange = { aliasMaster = it },
                    hint = "怎么称呼主人",
                )
                BotFormField(
                    label = "其他人",
                    value = aliasOthers,
                    onValueChange = { aliasOthers = it },
                    hint = "怎么称呼其他人",
                )
                Text(
                    text = "主人 = 该 bot 的主人 openid（在「主人 openid」区可见）",
                    fontSize = 13.sp,
                    color = PanelColors.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                )
            }

            // ⑦ 问候：总开关 + 发到哪 + 时段表（每段在窗口内随机挑一刻发一条）
            BotFormSection(title = "问候") {
                BotFormSwitchRow(
                    title = "启用定时问候",
                    summary = if (greetOn) "时段到了就发；关掉这一项一条都不发" else "已关闭（时段配置还留着）",
                    checked = greetOn,
                    onCheckedChange = { greetOn = it },
                )
                Spacer(Modifier.height(12.dp))
                BotFormSwitchRow(
                    title = "发到主人私聊",
                    summary = "发给主人 openid 的私聊窗口",
                    checked = greetOwner,
                    onCheckedChange = { greetOwner = it },
                )
                Spacer(Modifier.height(12.dp))
                BotFormSwitchRow(
                    title = "发到所有已知群",
                    summary = "本 bot 在运行记录里出现过的群，逐个发",
                    checked = greetGroups,
                    onCheckedChange = { greetGroups = it },
                )
                Text(
                    text = "在窗口内每天随机一个时刻发，每个 bot 会错开（不是整点群发）。",
                    fontSize = 13.sp,
                    color = PanelColors.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    thickness = PanelTokens.Divider,
                    color = PanelColors.dividerLine,
                )
                Text(
                    text = if (preset) {
                        "内置人格：候选文本自带、不可修改；开始时刻、窗口分钟可以改，行首开关能关掉整个时段"
                    } else {
                        "自定义人格：时段与候选问候都能增删，行首开关能关掉整个时段"
                    },
                    fontSize = 13.sp,
                    color = if (preset) PanelColors.onSurfaceVariantSummary else PanelColors.primary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                )
                Text(
                    text = "关掉后该时段不再发（配置留着，随时能开回来）。",
                    fontSize = 13.sp,
                    color = PanelColors.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                )
                if (slots.isEmpty()) {
                    Text(
                        text = "还没有时段：加一个才会到点发问候",
                        fontSize = 13.sp,
                        color = PanelColors.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                    )
                }
                slots.forEachIndexed { index, s ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 6.dp),
                            thickness = PanelTokens.Divider,
                            color = PanelColors.dividerLine,
                        )
                    }
                    GreetSlotRow(
                        slot = s,
                        preset = preset,
                        expanded = openSlot == index,
                        onToggleExpand = { openSlot = if (openSlot == index) -1 else index },
                        onChange = { next ->
                            slots = slots.toMutableList().also { it[index] = next }
                        },
                        onDelete = {
                            slots = slots.filterIndexed { i, _ -> i != index }
                            if (openSlot >= slots.size) openSlot = -1
                        },
                    )
                }
                if (!preset) {
                    Spacer(Modifier.height(12.dp))
                    PanelPillRow {
                        PanelIconPillButton(
                            text = "添加时段",
                            icon = MiuixIcons.Add,
                            onClick = {
                                slots = slots + GreetSlot(
                                    name = "",
                                    at = "12:00",
                                    windowMin = "30",
                                    lines = listOf(""),
                                )
                                openSlot = slots.size - 1
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // ⑧ 群里接话：按 bot 的三态覆盖（跟随全局 / 开 / 关）。设置页那一行是全局默认，
            //    两者是**不同的存储**：这里改的是 BotStore.group_autochat_override，只影响本 bot。
            BotFormSection(title = "群里接话") {
                OverlayDropdownPreference(
                    insideMargin = PanelTokens.PrefInside,
                    title = "群里自动接话",
                    summary = "关着时群里只有 @ 才会回",
                    items = AUTOCHAT_LABELS,
                    selectedIndex = autochatIndex(autochatOverride),
                    onSelectedIndexChange = { i ->
                        val v = autochatValue(i)
                        autochatOverride = v
                        ctx.setGroupAutochatOverride(appid, v)
                        // 立刻推给 Python（判定是每条消息现读的），不用重启 bot
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                BotHost.setGroupAutochatOverride(ctx, appid, v)
                            }
                            msg = "群里自动接话：" + AUTOCHAT_LABELS[i]
                        }
                    },
                )
            }

            // ⑨ 群配置：每个群单独设自动接话 / 每日额度 / 联网 / 整群静音
            //    （<botdir>/groups/<openid>.json；没配过的群沿用上面那三层判定）
            BotFormSection(title = "群配置") {
                PanelArrowRow(
                    title = "按群单独设置",
                    summary = "自动接话 / 每日额度 / 联网 / 整群静音；没配过的群沿用上面的设置",
                    feedback = false,
                    onClick = { showGroups = true },
                )
            }

            // ⑨ 模型：继承开关 / 接口地址 / API Key / 模型下拉（与「添加 bot」页同一份共享组件）
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

            // ⑩ 动作
            PanelGroupCard {
                PanelPillRow {
                    PanelPrimaryButton(text = "保存", onClick = { saveAll() }, modifier = Modifier.weight(1f))
                    PanelSecondaryButton(text = "返回", onClick = onBack, modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(PanelTokens.CardSpacing))
        }
    }

    // 本页 ↔ 群配置：同一份 panelStepTransition
    //   进入（showGroups=false → true）：forward = true，群配置从右进、本页往左出；
    //   返回（true → false）：forward = false，本页从右进、群配置往左出 —— 与列表↔单群编辑完全一致。
    AnimatedContent(
        targetState = showGroups,
        transitionSpec = { panelStepTransition(forward = targetState) },
        label = "bot-edit-groups",
    ) { groups ->
        if (groups) {
            GroupConfigScreen(metrics = metrics, appid = appid, onBack = { showGroups = false })
        } else if (LocalUiMode.current == UiMode.Material) {
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
                },
            ) { pad ->
                Box(Modifier.fillMaxSize().padding(pad)) { body() }
            }
        }
    }
}

/** bot_runner.ids_for() 的结果（只用 owner + groups）。 */
private data class Ids(val owner: String, val groups: List<String>)

private fun parseIds(raw: String): Ids {
    val o = runCatching { JSONObject(raw) }.getOrNull() ?: return Ids("", emptyList())
    val gs = ArrayList<String>()
    o.optJSONArray("groups")?.let { arr ->
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }?.let { gs.add(it) }
        }
    }
    return Ids(o.optString("owner").orEmpty(), gs)
}

// ── 称呼 / 问候：页面上的中间表示 ──────────────────────────────────────
//
// 落盘格式见 local/WordsStore.kt：
//   <botdir>/phrases.json    {"aliases": {"master": "…", "others": "…"}}
//   <botdir>/greetings.json  {"preset": …, "enabled": …, "targets": {…}, "slots": […]}
// 页面只做「取值 ↔ 文案」的翻译，格式一处都没改。

/**
 * 页面上的一段问候。
 *
 * [on] 内置人格和自定义人格都认：关掉 = 落盘 disabled=true（**不从文件里删**），
 * 运行时读到就跳过这个时段；重新打开就是 disabled=false。
 */
private data class GreetSlot(
    val name: String,
    val at: String,
    val windowMin: String,
    val lines: List<String>,
    val on: Boolean = true,
) {
    fun toWordsSlot(): WordsStore.Slot = WordsStore.Slot(
        name = name.trim(),
        at = at.trim(),
        windowMin = windowMin.trim().toIntOrNull()?.coerceIn(1, 1440) ?: 30,
        lines = lines,
        disabled = !on,
    )
}

private fun WordsStore.Slot.toGreetSlot(): GreetSlot = GreetSlot(
    name = name,
    at = at,
    windowMin = windowMin.toString(),
    lines = lines,
    on = !disabled,
)

/** 保存结果：一句「顺手做了什么」+ 一句「哪一步没写成」（都可能是空串）+ 时段是否变过。 */
private data class SavedNote(
    val note: String,
    val wordsErr: String,
    val slotChanged: Boolean = false,
)

/** bot_runner._PRESET_GREETINGS 里的一段（只读展示用）。 */
private data class PresetSlot(
    val name: String,
    val at: String,
    val windowMin: Int,
    val lines: List<String>,
)

/** bot_runner.preset_greetings 的 JSON → 内置人格的时段；解析不出来当没有。 */
private fun parsePresetSlots(raw: String): List<PresetSlot> = try {
    val arr = JSONArray(raw)
    (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val at = normHm(o.optString("at"))
        if (at.isEmpty()) return@mapNotNull null
        val lines = ArrayList<String>()
        o.optJSONArray("lines")?.let { la ->
            for (j in 0 until la.length()) {
                la.optString(j, "").trim().takeIf { it.isNotEmpty() }?.let { lines.add(it) }
            }
        }
        PresetSlot(
            name = o.optString("name", "").trim().ifEmpty { at },
            at = at,
            windowMin = o.optInt("window_min", 30).let { if (it <= 0) 30 else it },
            lines = lines,
        )
    }
} catch (e: Exception) {
    emptyList()
}

/**
 * 内置人格的时段表：以运行时的代码为准（名字 / 候选都从那边来），
 * 文件里的同名字段只接管「什么时候发」（at / window_min）。
 */
private fun mergeGreetSlots(fixed: List<PresetSlot>, saved: List<GreetSlot>): List<GreetSlot> {
    if (fixed.isEmpty()) return saved
    // 文件里有这个时段 → 用它记下的开关（关掉 = disabled）；文件里没有（首次迁移/还没保存过）→ 开
    return fixed.map { f ->
        val o = saved.firstOrNull { it.name == f.name }
        GreetSlot(
            name = f.name,
            at = o?.at?.takeIf { it.isNotBlank() } ?: f.at,
            windowMin = o?.windowMin?.takeIf { it.isNotBlank() } ?: f.windowMin.toString(),
            lines = f.lines,
            on = o?.on ?: true,
        )
    }
}

/** 校验 + 归一化整个时段表：第一项 null = 有问题，第二项是给人看的原因。 */
private fun normalizeGreetSlots(slots: List<GreetSlot>, preset: Boolean): Pair<List<GreetSlot>?, String> {
    val out = ArrayList<GreetSlot>(slots.size)
    slots.forEachIndexed { i, s ->
        val at = normHm(s.at)
        if (at.isEmpty()) return null to ("第 " + (i + 1) + " 个时段：开始时刻要写成 HH:MM")
        val win = s.windowMin.trim().toIntOrNull()?.coerceIn(1, 1440) ?: 30
        val lines = s.lines.map { it.trim() }.filter { it.isNotEmpty() }
        if (!preset && s.on && lines.isEmpty()) {
            return null to ("第 " + (i + 1) + " 个时段还没有候选问候：补一条，或者把这一行关掉")
        }
        out.add(s.copy(name = s.name.trim().ifEmpty { at }, at = at, windowMin = win.toString(), lines = lines))
    }
    return out to ""
}

/** 人格下拉里的名字 → bot_runner 认的角色名（去掉「自定义：」前缀与 -完整/-精华/-精简 后缀）。 */
private fun presetPersonaName(persona: String): String {
    val n = persona.removePrefix(PersonaAssets.CUSTOM_PREFIX).trim()
    for (suf in listOf("-完整", "-精华", "-精简")) {
        if (n.endsWith(suf)) return n.dropLast(suf.length)
    }
    return n
}

/** "8:00" / "0800" / "08:00" → "08:00"；写不成时刻 → ""。 */
private fun normHm(raw: String): String {
    val s = raw.trim()
    val h: String
    val m: String
    if (s.contains(':')) {
        h = s.substringBefore(':')
        m = s.substringAfter(':').take(2)
    } else {
        val d = s.filter { it.isDigit() }
        if (d.length < 3) return ""
        h = d.dropLast(2)
        m = d.takeLast(2)
    }
    val hh = h.toIntOrNull() ?: return ""
    val mm = m.toIntOrNull() ?: return ""
    if (hh !in 0..23 || mm !in 0..59) return ""
    return "%02d:%02d".format(hh, mm)
}

/** 开始时刻 + 窗口分钟 → 结束时刻（跨零点取模），摘要行用。 */
private fun endHm(at: String, windowMin: Int): String {
    val hm = normHm(at)
    if (hm.isEmpty()) return "—"
    val total = (hm.substringBefore(':').toInt() * 60 + hm.substringAfter(':').toInt() +
        windowMin.coerceIn(0, 24 * 60)) % (24 * 60)
    return "%02d:%02d".format(total / 60, total % 60)
}

/**
 * 一个问候时段一行：「时段名 · 08:00 – 09:00（窗口 60 分钟）」+ 行首开关；
 * 点这一行展开才显示编辑区。内置人格（[preset]）的候选文本只读，只允许改开始时刻 / 窗口分钟 / 开关；
 * 自定义人格另可选候选、改时段名、删掉整条。
 */
@Composable
private fun GreetSlotRow(
    slot: GreetSlot,
    preset: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onChange: (GreetSlot) -> Unit,
    onDelete: () -> Unit,
) {
    val win = slot.windowMin.trim().toIntOrNull()?.coerceIn(1, 1440) ?: 30
    val at = normHm(slot.at).ifEmpty { slot.at.trim() }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 内置人格也给开关：关掉只是「这个时段不发」，候选文本仍由人格自带
            Switch(checked = slot.on, onCheckedChange = { onChange(slot.copy(on = it)) })
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
                    .clickable { onToggleExpand() },
            ) {
                Text(
                    text = slot.name.ifBlank { at } + " · " + at + " – " + endHm(at, win) +
                        "（窗口 " + win + " 分钟）",
                    fontSize = PanelTokens.InfoTitleSize,
                    fontWeight = FontWeight(550),
                    color = if (slot.on) PanelColors.onSurface else PanelColors.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        !slot.on -> "已关闭：保存后该时段不再发（配置留着，能开回来）"
                        preset -> "候选 " + slot.lines.size + " 条 · 该人格自带，不可修改"
                        slot.lines.isEmpty() -> "还没有候选问候"
                        else -> "候选 " + slot.lines.size + " 条 · 窗口内随机挑一条"
                    },
                    fontSize = 12.sp,
                    color = PanelColors.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = if (expanded) "收起" else "展开",
                fontSize = 13.sp,
                color = PanelColors.primary,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clickable { onToggleExpand() },
            )
        }
        if (expanded) {
            Row(Modifier.fillMaxWidth()) {
                BotFormField(
                    label = "开始时刻",
                    value = slot.at,
                    onValueChange = { nv ->
                        onChange(slot.copy(at = nv.filter { c -> c.isDigit() || c == ':' }.take(5)))
                    },
                    hint = "08:00",
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(PanelTokens.ButtonGap))
                BotFormField(
                    label = "窗口分钟",
                    value = slot.windowMin,
                    onValueChange = { nv ->
                        onChange(slot.copy(windowMin = nv.filter { c -> c.isDigit() }.take(4)))
                    },
                    hint = "30",
                    numeric = true,
                    modifier = Modifier.weight(1f),
                )
            }
            if (preset) {
                Text(
                    text = "候选文本（该人格自带，不可修改）",
                    fontSize = 13.sp,
                    fontWeight = FontWeight(550),
                    color = PanelColors.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                )
                if (slot.lines.isEmpty()) {
                    Text(
                        text = "启动一次 bot 后就能从运行时读到候选文本",
                        fontSize = 13.sp,
                        color = PanelColors.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                    )
                } else {
                    slot.lines.forEach { line ->
                        Text(
                            text = "· " + line,
                            fontSize = 13.sp,
                            color = PanelColors.onSurface,
                            modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                        )
                    }
                }
            } else {
                BotFormField(
                    label = "时段名",
                    value = slot.name,
                    onValueChange = { onChange(slot.copy(name = it)) },
                    hint = "早上",
                )
                Text(
                    text = "候选问候（窗口内随机挑一条发）",
                    fontSize = 13.sp,
                    fontWeight = FontWeight(550),
                    color = PanelColors.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                )
                slot.lines.forEachIndexed { j, line ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BotFormField(
                            label = "问候 " + (j + 1),
                            value = line,
                            onValueChange = { nv ->
                                onChange(
                                    slot.copy(
                                        lines = slot.lines.toMutableList().also { it[j] = nv },
                                    )
                                )
                            },
                            hint = "一句话问候",
                            singleLine = false,
                            minLines = 2,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = {
                                onChange(
                                    slot.copy(
                                        lines = slot.lines.toMutableList().also { it.removeAt(j) },
                                    )
                                )
                            }
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Delete,
                                contentDescription = "删除这条问候",
                                tint = PanelColors.error,
                            )
                        }
                    }
                }
                PanelPillRow {
                    PanelSecondaryButton(
                        text = "+ 添加问候",
                        onClick = { onChange(slot.copy(lines = slot.lines + "")) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                PanelPillRow {
                    PanelSecondaryButton(
                        text = "删除这个时段",
                        onClick = onDelete,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** 「群里自动接话」三态的下拉文案：与 BotStore.group_autochat_override 的三个取值一一对应。 */
private val AUTOCHAT_LABELS = listOf("跟随全局", "开", "关")

/** 落盘值 → 下拉下标（"" 跟随 / "1" 开 / "0" 关，脏值一律按跟随）。 */
private fun autochatIndex(value: String): Int = when (value.trim()) {
    "1" -> 1
    "0" -> 2
    else -> 0
}

/** 下拉下标 → 落盘值。 */
private fun autochatValue(index: Int): String = when (index) {
    1 -> "1"
    2 -> "0"
    else -> ""
}
