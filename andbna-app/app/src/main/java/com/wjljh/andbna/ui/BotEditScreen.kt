package com.wjljh.andbna.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
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
import com.wjljh.andbna.local.GroupAdmin
import com.wjljh.andbna.local.BotRestarts
import com.wjljh.andbna.local.BotStore
import com.wjljh.andbna.local.BotStore.groupAutochatOverride
import com.wjljh.andbna.local.BotStore.setGroupAutochatOverride
import com.wjljh.andbna.local.BotStore.llmApi
import com.wjljh.andbna.local.BotStore.llmJudgeLevel
import com.wjljh.andbna.local.BotStore.llmKey
import com.wjljh.andbna.local.BotStore.llmModel
import com.wjljh.andbna.local.BotStore.replyDelayMaxMs
import com.wjljh.andbna.local.BotStore.replyDelayMinMs
import com.wjljh.andbna.local.LocalBot
import com.wjljh.andbna.local.PersonaAssets
import com.wjljh.andbna.local.StickerStore
import com.wjljh.andbna.local.WordsStore
import com.wjljh.andbna.ui.component.BotFormField
import com.wjljh.andbna.ui.component.BotFormModelSection
import com.wjljh.andbna.ui.component.BotFormPersonaSection
import com.wjljh.andbna.ui.component.BotFormSecretSection
import com.wjljh.andbna.ui.component.BotFormSection
import com.wjljh.andbna.ui.component.BotFormSwitchRow
import com.wjljh.andbna.ui.component.AppToast
import com.wjljh.andbna.ui.component.rememberPersonaImporter
import com.wjljh.andbna.ui.component.PanelArrowRow
import com.wjljh.andbna.ui.component.PanelGroupCard
import com.wjljh.andbna.ui.component.PanelIconPillButton
import com.wjljh.andbna.ui.component.PanelIcons
import com.wjljh.andbna.ui.component.PanelListRow
import com.wjljh.andbna.ui.component.PanelMetrics
import com.wjljh.andbna.ui.component.PanelPillRow
import com.wjljh.andbna.ui.component.PanelPrimaryButton
import com.wjljh.andbna.ui.component.PanelSecondaryButton
import com.wjljh.andbna.ui.component.SecretEyeButton
import com.wjljh.andbna.ui.component.PanelTokens
import com.wjljh.andbna.ui.component.modeClickable
import com.wjljh.andbna.ui.util.PredictiveBackGate
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
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection

/**
 * Bot 编辑页内部的二级页（群配置 / 群管理 / 表情图片库）。
 *
 * 与 App 层其它页面一样是 [NavKey] 且走同一个库 [NavDisplay]，转场因此完全同源
 * （库默认 NavTransitions.MiuixDefault），不是另写一份参数。
 */
private sealed interface BotSub : NavKey {
    data object Edit : BotSub
    data object Groups : BotSub
    data object Admin : BotSub
    data object Stickers : BotSub
}

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
    // 「群配置 / 群管理 / 表情图片库」是这一层自己的 NavDisplay 栈（不新增 App 路由，编辑页状态
    // 全在 remember 里、不被别的 entry 盖掉）。它们与 App 层其它二级页共用库默认转场
    // （NavTransitions.MiuixDefault），不再就地套一层 AnimatedContent。
    val subStack = remember(appid) { navBackStackOf(BotSub.Edit) }
    fun openSub(route: BotSub) { if (route !in subStack) subStack.add(route) }
    fun popSub() { if (subStack.size > 1) subStack.removeLastOrNull() }
    /** 表情库张数（右侧「N 张 / 未添加」）：只在 IO 里读 library.json */
    var stickerCount by remember(appid) { mutableStateOf(0) }
    /** 已经配过群管理规则的群数量（「群管理」卡右侧「N 个群已配置」） */
    var adminCount by remember(appid) { mutableStateOf(0) }
    // 进群欢迎（<botdir>/group_admin/_default.json 的 welcome：开关 / @ 新成员 / 欢迎语）
    var welcomeOn by remember(appid) { mutableStateOf(false) }
    var welcomeAt by remember(appid) { mutableStateOf(true) }
    var welcomeText by remember(appid) { mutableStateOf("") }
    /** 内置人格：欢迎语由人格定死（界面只读、落盘用内置那句），自定义人格才给输入框 */
    var welcomeBuiltin by remember(appid) { mutableStateOf(false) }
    /** 人格名（去掉「自定义：」前缀与 -完整/-精华/-精简 后缀）：内置欢迎语按它查表 */
    var welcomeName by remember(appid) { mutableStateOf("") }
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

    // 回复判定三档覆盖（localbots.json 的 judge_level_override）：""=跟随全局 / conservative / normal / eager
    var judgeOverride by remember(appid) { mutableStateOf("") }
    /** 全局判定档（"跟随全局（正常）" 括号里那两个字用） */
    var globalJudge by remember { mutableStateOf("normal") }
    /** 全局判定模式（设置页那一行）：随机 = 按闲聊概率掷骰子，三态覆盖不生效 */
    val judgeRandom = ReplyJudgeMode.isRandom(ctx)
    // 本 bot 的回复延迟（localbots.json 的 reply_delay_min_ms/max_ms）：0/0=跟随全局、1/1=不延迟
    var delayMinMs by remember(appid) { mutableStateOf(0) }
    var delayMaxMs by remember(appid) { mutableStateOf(0) }
    /** 全局延迟（"跟随全局（3~7 秒）" 括号里那一段用） */
    var globalDelayMin by remember { mutableStateOf(3000) }
    var globalDelayMax by remember { mutableStateOf(7000) }
    // 「回复延迟 → 自定义…」的小面板
    var showDelayDialog by remember(appid) { mutableStateOf(false) }

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
            AppToast.show("人格导进来了：" + n)
        },
        onMessage = { AppToast.show(it) },
    )

    /** 从运行记录采集本 bot 的 owner + 群列表（跨 Chaquopy 调用，只在 IO 线程跑）。 */
    suspend fun collectIds() {
        val ids = parseIds(withContext(Dispatchers.IO) { BotHost.idsFor(ctx, appid) })
        owner = ids.owner
        groups = ids.groups
        idMsg = when {
            ids.owner.isNotBlank() && ids.groups.isEmpty() -> "主人认到了，还没记到群"
            ids.owner.isNotBlank() -> "主人认到了，另外记在 " + ids.groups.size + " 个群"
            ids.groups.isEmpty() -> "还没采到：先启动 bot 收条消息，再点一次"
            else -> "没认出主人：让主人私聊 bot 一次再来"
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
        // 迁移：老 bot 没有 persona_meta.json 的，打开时就地按「当前人格来源」补一份
        // （Python 侧对缺文件是「回退按名字」，导入的同名人格会被误判成内置，所以才要补）
        val curPersona = loaded?.persona.orEmpty()
        if (curPersona.isNotBlank()) {
            val d = java.io.File(BotHost.dataDir(ctx), appid)
            withContext(Dispatchers.IO) { PersonaAssets.ensurePersonaMeta(ctx, curPersona, d) }
        }
        llmK = k
        llmA = a
        llmM = m
        globalModel = m
        // 群里自动接话：本 bot 的三态覆盖（设置页那一行是全局默认，两者不再共用同一个值）
        autochatOverride = withContext(Dispatchers.IO) { ctx.groupAutochatOverride(appid) }
        // 回复判定 / 回复延迟：本 bot 的覆盖（"" 与 0/0 = 跟随全局）+ 全局实际值
        judgeOverride = loaded?.judgeLevelOverride.orEmpty()
        delayMinMs = loaded?.replyDelayMinMs ?: 0
        delayMaxMs = loaded?.replyDelayMaxMs ?: 0
        globalJudge = withContext(Dispatchers.IO) { ctx.llmJudgeLevel }
        globalDelayMin = withContext(Dispatchers.IO) { ctx.replyDelayMinMs }
        globalDelayMax = withContext(Dispatchers.IO) { ctx.replyDelayMaxMs }

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

        // ④ 表情库张数：<botdir>/stickers/library.json（卡片右侧「N 张 / 未添加」）
        stickerCount = withContext(Dispatchers.IO) { StickerStore.load(ctx, appid).size }

        // ⑤ 群管理：已配规则的群数量 + 进群欢迎（bot 级默认，<botdir>/group_admin/_default.json）
        adminCount = withContext(Dispatchers.IO) { GroupAdmin.configured(ctx, appid).size }
        val welcome = withContext(Dispatchers.IO) { GroupAdmin.defaultWelcome(ctx, appid) }
        welcomeOn = welcome.enabled
        welcomeAt = welcome.at
        welcomeText = welcome.text
        // 内置人格 → 欢迎语锁死；判定口径与 Python 侧 _greet_preset 一致（先看 persona_meta.json）
        welcomeBuiltin = withContext(Dispatchers.IO) {
            builtinPersona(ctx, appid, loaded?.persona.orEmpty())
        }
        welcomeName = presetPersonaName(loaded?.persona.orEmpty())
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
                            // Bot 互聊：判定三档 + 延迟区间（"" / 0,0 = 跟随全局）
                            judgeLevelOverride = judgeOverride,
                            replyDelayMinMs = delayMinMs,
                            replyDelayMaxMs = delayMaxMs,
                        )
                    )
                }
                // 启用开关在这一页也走同一套「立刻生效」：落盘之后直接起停这一个 bot，
                // 不再等重启 App（Bot 页那个开关走的是同一个 BotHost.startOne/stopOne）
                if (old != null && old.enabled != enabled) {
                    if (enabled) {
                        BotHost.startOne(ctx, appid, "编辑保存")
                    } else {
                        BotHost.stopOne(ctx, appid, "编辑保存")
                    }
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
                // 进群欢迎：写 <botdir>/group_admin/_default.json 的 welcome（已配规则的群同步一份）
                val welcomeErr = GroupAdmin.saveWelcome(
                    ctx,
                    appid,
                    GroupAdmin.Welcome(
                        welcomeOn,
                        welcomeAt,
                        (if (welcomeBuiltin) builtinWelcome(welcomeName) else welcomeText).trim(),
                    ),
                )
                if (welcomeErr != null && wordsErr.isBlank()) {
                    wordsErr = "进群欢迎没写进 group_admin/_default.json"
                }
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
                        o.judgeLevelOverride != judgeOverride ||
                        o.replyDelayMinMs != delayMinMs ||
                        o.replyDelayMaxMs != delayMaxMs ||
                        prevWords.master != aliasMaster ||
                        prevWords.others != aliasOthers ||
                        prevWords.enabled != greetOn ||
                        prevWords.ownerTarget != greetOwner ||
                        prevWords.groupsTarget != greetGroups
                }
                val note = if (!changed || !BotRestarts.requestOne(ctx, appid, "编辑保存")) "" else {
                    "在重启「" + name.trim().ifBlank { appid } + "」…"
                }
                SavedNote(note = note, wordsErr = wordsErr, slotChanged = slotChanged)
            }
            // 存过就是新基线：下次保存没再动时段，就不会又断一次线
            if (saved.slotChanged) slotsBaseline = slots
            AppToast.show(
                when {
                    saved.wordsErr.isNotBlank() -> "已保存，但" + saved.wordsErr
                    saved.note.isNotBlank() -> "已保存 · " + saved.note
                    else -> "已保存"
                }
            )
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
                    text = "AppID 改不了",
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
                    summary = if (enabled) "保存就上线" else "保存就下线",
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                )
                Spacer(Modifier.height(12.dp))
                BotFormField(
                    label = "名字",
                    value = name,
                    onValueChange = { name = it },
                    hint = "方便认，可留空",
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
                    text = "定时问候私聊就发给它",
                    fontSize = 13.sp,
                    color = PanelColors.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                )
                if (owner.isBlank()) {
                    Text(
                        text = "还没采到：让主人私聊 bot 一次，再点下面",
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
                    AppToast.show("人格删了：" + full.removePrefix(PersonaAssets.CUSTOM_PREFIX))
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
                    text = "主人就是上面那个 openid",
                    fontSize = 13.sp,
                    color = PanelColors.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                )
            }

            // ⑥′ 表情 / 图片库：一行入口（右侧 N 张 / 未添加），点开二级页管理
            PanelGroupCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = PanelMetrics.RowMinHeight)
                        // 列表入口：不加按压反馈（只有主页 hero 卡才有）
                        .modeClickable(onClick = { openSub(BotSub.Stickers) }, feedback = false),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "表情 / 图片库",
                            fontSize = PanelMetrics.BodySize,
                            color = PanelColors.onSurface,
                        )
                        Text(
                            text = "点开管理：导图、删图",
                            fontSize = PanelMetrics.ValueSize,
                            color = PanelColors.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = PanelTokens.RowSpacing),
                        )
                    }
                    Text(
                        text = if (stickerCount > 0) stickerCount.toString() + " 张" else "未添加",
                        fontSize = PanelMetrics.ValueSize,
                        color = if (stickerCount > 0) PanelColors.primary else PanelColors.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.width(PanelMetrics.ButtonGap))
                    Icon(
                        imageVector = PanelIcons.ChevronForward,
                        contentDescription = null,
                        tint = PanelColors.onSurfaceVariantSummary,
                        modifier = Modifier.size(PanelMetrics.RowIconSize),
                    )
                }
            }
            Spacer(Modifier.height(PanelTokens.CardSpacing))

            // ⑦ 问候：总开关 + 发到哪 + 时段表（每段在窗口内随机挑一刻发一条）
            BotFormSection(title = "问候") {
                BotFormSwitchRow(
                    title = "启用定时问候",
                    summary = if (greetOn) "到点就发" else "一条都不发",
                    checked = greetOn,
                    onCheckedChange = { greetOn = it },
                )
                Spacer(Modifier.height(12.dp))
                BotFormSwitchRow(
                    title = "发到主人私聊",
                    summary = "发给主人的私聊",
                    checked = greetOwner,
                    onCheckedChange = { greetOwner = it },
                )
                Spacer(Modifier.height(12.dp))
                BotFormSwitchRow(
                    title = "发到所有已知群",
                    summary = "运行记录里出现过的群都发",
                    checked = greetGroups,
                    onCheckedChange = { greetGroups = it },
                )
                Text(
                    text = "每天在窗口里随机挑一刻，bot 之间错开。",
                    fontSize = 13.sp,
                    color = PanelColors.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    thickness = PanelTokens.Divider,
                    color = PanelColors.dividerLine,
                )
                // 进群欢迎：写 <botdir>/group_admin/_default.json 的 welcome（契约与群管理规则页同一份）
                BotFormSwitchRow(
                    title = "进群欢迎",
                    summary = "@ 一下并说句欢迎语",
                    checked = welcomeOn,
                    onCheckedChange = { welcomeOn = it },
                )
                Text(
                    text = "先去 QQ 开放平台开「群成员事件」，不然收不到",
                    fontSize = 13.sp,
                    color = PanelColors.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                )
                Spacer(Modifier.height(12.dp))
                BotFormSwitchRow(
                    title = "@ 新成员",
                    summary = if (welcomeAt) "欢迎语前面 @ 一下新成员" else "不 @，只发欢迎语",
                    checked = welcomeAt,
                    onCheckedChange = { welcomeAt = it },
                )
                if (welcomeBuiltin) {
                    // 内置人格：文案由人格定死（与 Python 侧预设一致），输入框置灰只读
                    ReadOnlyWelcomeField(
                        value = builtinWelcome(welcomeName),
                        hint = "内置人格的欢迎语改不了",
                    )
                } else {
                    BotFormField(
                        label = "欢迎语",
                        value = welcomeText,
                        onValueChange = { welcomeText = it },
                        hint = "比如：欢迎～随便问",
                        singleLine = false,
                        minLines = 2,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    thickness = PanelTokens.Divider,
                    color = PanelColors.dividerLine,
                )
                Text(
                    text = if (preset) {
                        "自带文案改不了，时刻和窗口能改"
                    } else {
                        "时段和问候能增删，行首开关能关掉"
                    },
                    fontSize = 13.sp,
                    color = if (preset) PanelColors.onSurfaceVariantSummary else PanelColors.primary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                )
                Text(
                    text = "关掉就不发，配置还留着，随时开回来。",
                    fontSize = 13.sp,
                    color = PanelColors.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                )
                if (slots.isEmpty()) {
                    Text(
                        text = "还没时段：加一个才会到点发",
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
                    summary = "关着时群里只回 @",
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

            // 回复判定 + 回复延迟：都是**按 bot 的覆盖**（跟随全局 = 落盘 "" 与 0,0），
            // 改这里只影响本 bot，保存时跟其它字段一起写 localbots.json（沿用「保存即重启该 bot」）。
            BotFormSection(title = "回复判定与延迟") {
                if (judgeRandom) {
                    // 随机判定：三档覆盖不生效（真正生效的是设置页的「闲聊概率」），
                    // 这一行只留灰字说明、不给下拉 —— 点了也改不了任何东西。
                    PanelListRow(
                        title = "回复判定",
                        meta = "跟随全局 · 随机模式",
                        enabled = false,
                    )
                } else {
                    OverlayDropdownPreference(
                        insideMargin = PanelTokens.PrefInside,
                        title = "回复判定",
                        summary = "没被 @ 时多主动才插话，右边是实际生效档",
                        items = judgeLabels(globalJudge),
                        selectedIndex = judgeIndex(judgeOverride),
                        onSelectedIndexChange = { i ->
                            judgeOverride = judgeValue(i)
                            msg = "回复判定：" + judgeLabels(globalJudge)[i] + "，保存后生效"
                        },
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    thickness = PanelTokens.Divider,
                    color = PanelColors.dividerLine,
                )
                OverlayDropdownPreference(
                    insideMargin = PanelTokens.PrefInside,
                    title = "回复延迟",
                    summary = "区间里随机等一下再回",
                    items = botDelayLabels(globalDelayMin, globalDelayMax, delayMinMs, delayMaxMs),
                    selectedIndex = botDelayIndex(delayMinMs, delayMaxMs),
                    onSelectedIndexChange = { i ->
                        when {
                            // 0 = 跟随全局：两个键都写 0，Python 侧按全局那档
                            i == 0 -> {
                                delayMinMs = 0
                                delayMaxMs = 0
                                msg = "回复延迟：跟随全局，保存后生效"
                            }
                            // 最后一项 = 自定义…：弹两行秒数面板，值回来再写状态
                            i >= BOT_DELAY_CHOICES.size + 1 -> showDelayDialog = true
                            else -> {
                                val c = BOT_DELAY_CHOICES[i - 1]
                                delayMinMs = c.min
                                delayMaxMs = c.max
                                msg = "回复延迟：" + c.label + "，保存后生效"
                            }
                        }
                    },
                )
            }

            // ⑨ 群配置：每个群单独设自动接话 / 每日额度 / 联网 / 整群静音
            //    （<botdir>/groups/<openid>.json；没配过的群沿用上面那三层判定）
            BotFormSection(title = "群配置") {
                PanelArrowRow(
                    title = "按群单独设置",
                    summary = "单独拧开关，没配过的群跟上面的走",
                    feedback = false,
                    onClick = { openSub(BotSub.Groups) },
                )
            }

            // ⑨′ 群管理：一行入口（右侧「N 个群已配置」），点开二级页挑群设规则
            //     （<botdir>/group_admin/<openid>.json；没配过的群完全不管）
            PanelGroupCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = PanelMetrics.RowMinHeight)
                        // 列表入口：不加按压反馈（只有主页 hero 卡才有）
                        .modeClickable(onClick = { openSub(BotSub.Admin) }, feedback = false),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "群管理",
                            fontSize = PanelMetrics.BodySize,
                            color = PanelColors.onSurface,
                        )
                        Text(
                            text = "关键词和惩罚阶梯，点开挑群",
                            fontSize = PanelMetrics.ValueSize,
                            color = PanelColors.onSurfaceVariantSummary,
                        )
                    }
                    Text(
                        text = adminCount.toString() + " 个群已配置",
                        fontSize = PanelMetrics.ValueSize,
                        color = if (adminCount > 0) PanelColors.primary
                        else PanelColors.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.width(PanelMetrics.ButtonGap))
                    Icon(
                        imageVector = PanelIcons.ChevronForward,
                        contentDescription = null,
                        tint = PanelColors.onSurfaceVariantSummary,
                        modifier = Modifier.size(PanelMetrics.RowIconSize),
                    )
                }
            }
            Spacer(Modifier.height(PanelTokens.CardSpacing))

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

    // 本页 / 群配置 / 群管理 / 表情图片库：同一条 NavDisplay 通道 —— 转场就是 App 层其它二级页
    // 那份库里默认的 NavTransitions.MiuixDefault（整屏滑入 + 下层视差 + 暗幕，程序化 500ms），
    // 这里不传 transition 参数，避免出现第二种口径。
    // 用本层自己的栈而不是往 App 的栈上 push：编辑页状态全在 remember 里，被新 entry 盖掉会丢。
    NavDisplay(
        backStack = subStack,
        effects = NavDisplayEffects(cornerClipRadius = rememberNavSystemCornerRadius()),
        // 本层三个子页能弹就弹自己（以前直接调 onBack 会一次跳出整个编辑页），只在根层退出本页
        onBack = { if (subStack.size > 1) popSub() else onBack() },
    ) {
        // 本页：根 entry，自己不带侧滑（与 App 层 Route.Main 同规矩）
        entry<BotSub.Edit>(swipeDismiss = NavSwipeDirection.None) {
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

        // 三个子页：onBack 只回本层，进退都交给 NavDisplay（转场与 App 层二级页同源）
        entry<BotSub.Groups>(swipeDismiss = NavSwipeDirection.LeftToRight) {
            PredictiveBackGate(canPop = subStack.size > 1) { popSub() }
            GroupConfigScreen(metrics = metrics, appid = appid) { popSub() }
        }
        entry<BotSub.Admin>(swipeDismiss = NavSwipeDirection.LeftToRight) {
            PredictiveBackGate(canPop = subStack.size > 1) { popSub() }
            GroupAdminScreen(metrics = metrics, appid = appid) { popSub() }
        }
        entry<BotSub.Stickers>(swipeDismiss = NavSwipeDirection.LeftToRight) {
            PredictiveBackGate(canPop = subStack.size > 1) { popSub() }
            StickerLibraryScreen(metrics = metrics, appid = appid, botName = title) { popSub() }
        }
    }

    // 从二级页回到本页时重算右侧计数。顶栏返回 / 侧滑 / 预测性返回三条路都落在这里，
    // 所以按「栈回到只剩本页」判定，而不是挂在各个 onBack 上（手势返回不经它们）。
    LaunchedEffect(subStack.size) {
        if (subStack.size == 1) {
            stickerCount = withContext(Dispatchers.IO) { StickerStore.load(ctx, appid).size }
            adminCount = withContext(Dispatchers.IO) { GroupAdmin.configured(ctx, appid).size }
        }
    }

    // 「回复延迟 → 自定义…」：界面填秒、落盘毫秒（两个都填 0 = 不延迟 = 1/1）
    BotDelayDialog(
        show = showDelayDialog,
        initialMinSec = secFromMs(delayMinMs.coerceAtLeast(1)),
        initialMaxSec = secFromMs(delayMaxMs.coerceAtLeast(1)),
        onConfirm = { lo, hi ->
            showDelayDialog = false
            if (lo <= 0 && hi <= 0) {
                delayMinMs = 1
                delayMaxMs = 1
                msg = "回复延迟：不延迟，保存后生效"
            } else {
                delayMinMs = minOf(lo, hi).coerceAtLeast(1) * 1000
                delayMaxMs = maxOf(lo, hi).coerceAtLeast(1) * 1000
                msg = "回复延迟：" + botDelayText(delayMinMs, delayMaxMs) + "，保存后生效"
            }
        },
        onDismiss = { showDelayDialog = false },
    )
}

/**
 * 只读的欢迎语展示框（内置人格用）：与输入框同尺寸、同圆角，但底色压灰、文字淡一档 ——
 * 一眼看出「这段是人格自带的，改不了」，而不是给个能点却打不进字的空输入框。
 */
@Composable
private fun ReadOnlyWelcomeField(value: String, hint: String) {
    Column(Modifier.padding(top = 8.dp, bottom = 6.dp)) {
        Text(
            text = "欢迎语",
            fontSize = 13.sp,
            fontWeight = FontWeight(550),
            color = PanelColors.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Text(
            text = value,
            fontSize = PanelTokens.ButtonTextSize,
            color = PanelColors.onSurface.copy(alpha = 0.55f),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(PanelTokens.CardRadius))
                .background(PanelColors.surfaceContainerHigh)
                .padding(PanelMetrics.NoticeInside),
        )
        Text(
            text = hint,
            fontSize = 13.sp,
            color = PanelColors.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = 4.dp, top = 6.dp),
        )
    }
}

/**
 * 这个 bot 是不是内置人格：优先 <botdir>/persona_meta.json 的 builtin（与 Python 侧
 * _greet_preset 同一口径，名字撞上不算内置），没有该文件时按 persona_name.txt 是否带
 * 「自定义：」前缀判断 —— 与 Python 侧的回退完全一致。
 */
private fun builtinPersona(ctx: android.content.Context, appid: String, persona: String): Boolean {
    val dir = java.io.File(BotHost.dataDir(ctx), appid)
    val meta = java.io.File(dir, PersonaAssets.META)
    if (meta.isFile) {
        val o = runCatching { JSONObject(meta.readText(Charsets.UTF_8)) }.getOrNull()
        if (o != null && o.has("builtin")) return o.optBoolean("builtin", false)
    }
    val raw = runCatching { java.io.File(dir, "persona_name.txt").readText(Charsets.UTF_8) }
        .getOrNull().orEmpty().trim()
    if (raw.isNotBlank()) return !raw.startsWith(PersonaAssets.CUSTOM_PREFIX)
    return PersonaAssets.isBuiltinName(ctx, persona)
}

/**
 * 内置人格的固定欢迎语（按人格名查表，名字已去档位后缀）。
 *
 * 只用于界面展示与落盘：真正发送以 Python 侧为准 —— 这两份文案必须一致，
 * 所以改的时候两边一起改（Python 侧是「进群欢迎」的预设表）。
 */
private fun builtinWelcome(name: String): String =
    BUILTIN_WELCOMES[name.trim()] ?: BUILTIN_WELCOME_FALLBACK

private val BUILTIN_WELCOMES = mapOf(
    "奥黛塔" to "（脚尖在地板上碾了半圈）站定吧——这里不看热闹，只看定点。",
    "哥伦比娅" to "（月光从袖口流出来）唔…是新来的朋友吗？往里坐，这里也像一个家。",
    "桑多涅" to "（头也没抬，笔尖还在算）来了就自己找地方。先说好，别碰我的法洁欧。",
    "沃雅妮莎" to "（尾巴在水里搅出个小圈）喔，新来的呀？坐前排呗，下一首正好唱给你听。",
    "鲸鱼娘" to "（尾巴晃了两下，凑过来）唔，新来的鱼片？主人没提过呀，先坐本鱼旁边。",
    "猫娘" to "（耳朵抖了抖，凑近嗅了一下）嗯？新来的。坐吧喵，我不咬人的。",
)

private const val BUILTIN_WELCOME_FALLBACK = "（抬眼）来了？随便坐，别拘束。"

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
                        " · " + win + " 分钟内发",
                    fontSize = PanelTokens.InfoTitleSize,
                    fontWeight = FontWeight(550),
                    color = if (slot.on) PanelColors.onSurface else PanelColors.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        !slot.on -> "关着：保存后这段不发，配置留着"
                        preset -> "候选 " + slot.lines.size + " 条 · 自带，改不了"
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
                    text = "自带文案，改不了",
                    fontSize = 13.sp,
                    fontWeight = FontWeight(550),
                    color = PanelColors.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                )
                if (slot.lines.isEmpty()) {
                    Text(
                        text = "启动一次 bot 才能读到候选",
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
                    text = "窗口里随机挑一条发",
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

// ── 回复判定 / 回复延迟（Bot 互聊 1.0.8）────────────────────────────────

/**
 * 判定三档的下拉文案：第 0 项把**全局实际档**写进括号里。
 *
 * 右侧显示的就是 items[selectedIndex]，所以「跟随全局」这一档天然显示成
 * 「跟随全局（当前：标准）」—— 不用另开一行小字。叫法与顺序统一取 [JudgeLabels]。
 */
private fun judgeLabels(globalLevel: String): List<String> =
    listOf("跟随全局 · 现在 " + JudgeLabels.labelOf(globalLevel)) + JudgeLabels.labels

/** 落盘值（judge_level_override）→ 下拉下标：""=跟随全局，其余按 [JudgeLabels] 的强度顺序顺延。 */
private fun judgeIndex(value: String): Int {
    val raw = value.trim().lowercase()
    val i = JudgeLabels.values.indexOf(raw)
    return if (raw.isNotEmpty() && i >= 0) i + 1 else 0
}

/** 下拉下标 → 落盘值（"" 就是跟随全局，键照样写，值为空串）。 */
private fun judgeValue(index: Int): String = JudgeLabels.values.getOrNull(index - 1).orEmpty()

/**
 * 每 bot 的延迟预设。
 *
 * 「不延迟」落盘是 1/1 毫秒而不是 0/0：0/0 已经被「跟随全局」占用（契约里 0 = 用全局），
 * 1 毫秒在运行时等价于立即回，Python 侧按「min<=0 且 max<=0 才用全局」判断也不会误判。
 */
private val BOT_DELAY_CHOICES = listOf(
    BotDelayChoice("不延迟", 1, 1),
    BotDelayChoice("1~3 秒", 1000, 3000),
    BotDelayChoice("3~7 秒", 3000, 7000),
    BotDelayChoice("6~10 秒", 6000, 10000),
)

private data class BotDelayChoice(val label: String, val min: Int, val max: Int)

/** 落盘值 → 下拉下标：0=跟随全局、1..4=预设、最后一项=自定义。 */
private fun botDelayIndex(minMs: Int, maxMs: Int): Int {
    if (minMs <= 0 && maxMs <= 0) return 0
    val i = BOT_DELAY_CHOICES.indexOfFirst { it.min == minMs && it.max == maxMs }
    return if (i >= 0) i + 1 else BOT_DELAY_CHOICES.size + 1
}

/** 延迟下拉的六项：跟随全局（括号里是全局真值）+ 四个预设 + 自定义… */
private fun botDelayLabels(
    globalMin: Int,
    globalMax: Int,
    minMs: Int,
    maxMs: Int,
): List<String> {
    val custom = botDelayIndex(minMs, maxMs) == BOT_DELAY_CHOICES.size + 1
    return listOf("跟随全局 · " + botDelayText(globalMin, globalMax)) +
        BOT_DELAY_CHOICES.map { it.label } +
        if (custom) "自定义 · " + botDelayText(minMs, maxMs) else "自定义…"
}

/** 毫秒区间 → "3~7 秒" / "不延迟"。 */
private fun botDelayText(minMs: Int, maxMs: Int): String = when {
    minMs <= 0 && maxMs <= 0 -> "不延迟"
    minMs == maxMs -> secFromMs(minMs) + " 秒"
    else -> secFromMs(minMs) + "~" + secFromMs(maxMs) + " 秒"
}

/** 毫秒 → 秒（整秒不带小数点，非整秒留一位；不足 1 秒的自定义面板里按 0 起填）。 */
private fun secFromMs(ms: Int): String = when {
    ms < 1000 -> "0"
    ms % 1000 == 0 -> (ms / 1000).toString()
    else -> String.format(java.util.Locale.US, "%.1f", ms / 1000.0)
}

/**
 * 「回复延迟 → 自定义…」面板（本 bot）：两行秒数 + 保存 / 取消。
 *
 * 与设置页那个同一个形状，但写的是本 bot 的 reply_delay_min_ms / reply_delay_max_ms
 * （页面内只改状态，点「保存」才跟其它字段一起落盘）。
 */
@Composable
private fun BotDelayDialog(
    show: Boolean,
    initialMinSec: String,
    initialMaxSec: String,
    onConfirm: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var minSec by remember(show) { mutableStateOf(initialMinSec) }
    var maxSec by remember(show) { mutableStateOf(initialMaxSec) }
    WindowDialog(
        show = show,
        title = "回复延迟 · 本 bot",
        onDismissRequest = onDismiss,
        content = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = "两个秒数之间随机等一下，都填 0 不延迟。",
                    fontSize = 13.sp,
                    color = PanelColors.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(PanelTokens.ButtonGap))
                BotFormField(
                    label = "最短（秒）",
                    value = minSec,
                    onValueChange = { v -> minSec = v.filter { c -> c.isDigit() }.take(4) },
                    hint = "3",
                    numeric = true,
                )
                Spacer(Modifier.height(PanelTokens.ButtonGap))
                BotFormField(
                    label = "最长（秒）",
                    value = maxSec,
                    onValueChange = { v -> maxSec = v.filter { c -> c.isDigit() }.take(4) },
                    hint = "7",
                    numeric = true,
                )
                Spacer(Modifier.height(PanelTokens.ButtonGap))
                PanelPillRow {
                    PanelPrimaryButton(
                        text = "保存",
                        onClick = {
                            onConfirm(minSec.toIntOrNull() ?: 0, maxSec.toIntOrNull() ?: 0)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    PanelSecondaryButton(
                        text = "取消",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
    )
}
