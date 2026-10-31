package com.wjljh.andbna.ui.component

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wjljh.andbna.ModelCatalog
import com.wjljh.andbna.local.PersonaAssets
import com.wjljh.andbna.ui.LocalUiMode
import com.wjljh.andbna.ui.PanelColors
import com.wjljh.andbna.ui.UiMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

/**
 * Bot 表单的共享积木：「编辑 bot」页（ui/BotEditScreen.kt）与「添加 bot」页（ui/BotAddScreen.kt）
 * 用同一套区块 / 输入框 / 开关行 / 人格区块 / 模型区块，结构参数只在这一份里定：
 *
 *  · 区块：17sp 标题 + 卡（卡内 16dp 由 [PanelGroupCard] 给）+ 12dp 卡间距 —— [BotFormSection]
 *  · 输入框：13sp 标签 + kit 的 miuix [TextField]（自带圆角底 / 边框 / 聚焦高亮）—— [BotFormField]
 *  · 开关行：17sp 标题 + 14sp 说明 + 行尾 Switch —— [BotFormSwitchRow]
 *  · 密钥 / 人格 / 模型三块整块共享（[BotFormSecretSection] / [BotFormPersonaSection] / [BotFormModelSection]）
 *
 * 两页只负责「有哪些值、保存时怎么写」，布局一行都不重复。
 */

/** 人格下拉末尾的导入项（选它 = 拉起系统文件选择器）。 */
const val PERSONA_IMPORT_ITEM = "自定义…（导入文件）"

/** 区块：17sp 标题 + 卡（卡内 16dp 由 [PanelGroupCard] 给）+ 12dp 卡间距。 */
@Composable
fun BotFormSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = title,
            fontSize = 17.sp,
            fontWeight = FontWeight(550),
            color = PanelColors.onSurface,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        PanelGroupCard { content() }
        Spacer(Modifier.height(PanelTokens.CardSpacing))
    }
}

/**
 * 输入框：框上方 13sp 标签 + kit 的 miuix [TextField]（自带圆角底、边框、聚焦高亮）。
 * 占位符走 TextField 的 label 参数，只有值为空时才显示，所以不会盖住已填内容。
 *
 * [secret] 打开时：KeyboardType.Password（输入法走系统安全键盘，不进联想 / 词库）、
 * 默认掩码、行尾一颗眼睛按钮（[SecretEyeButton]）才明文看一眼。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotFormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
    numeric: Boolean = false,
    secret: Boolean = false,
    // 接口地址这种长串：单行 + 尾部省略，聚焦后横向滚动（不再被 TextField 硬裁掉末尾）
    ellipsis: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val keyboard = KeyboardOptions(
        keyboardType = when {
            secret -> KeyboardType.Password
            numeric -> KeyboardType.Number
            else -> KeyboardType.Text
        },
    )
    var revealed by remember { mutableStateOf(false) }
    val mask = if (secret && !revealed) PasswordVisualTransformation()
    else VisualTransformation.None
    val eye: (@Composable () -> Unit)? =
        if (secret) ({ SecretEyeButton(revealed) { revealed = !revealed } }) else null
    Column(modifier.padding(vertical = 6.dp)) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight(550),
            color = PanelColors.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        if (ellipsis) {
            PanelEllipsisField(value = value, onValueChange = onValueChange, hint = hint, secret = secret)
        } else if (LocalUiMode.current == UiMode.Material) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { M3Text(hint) },
                singleLine = singleLine,
                minLines = minLines,
                keyboardOptions = keyboard,
                visualTransformation = mask,
                trailingIcon = eye,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            TextField(
                value = value,
                onValueChange = onValueChange,
                label = if (value.isEmpty()) hint else "",
                singleLine = singleLine,
                minLines = minLines,
                maxLines = if (singleLine) 1 else 5,
                keyboardOptions = keyboard,
                visualTransformation = mask,
                trailingIcon = eye,
                textStyle = TextStyle(fontSize = PanelTokens.ButtonTextSize),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 开关行：左标题 + 副标题，行尾 miuix [Switch]。 */
@Composable
fun BotFormSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = PanelTokens.InfoTitleSize,
                fontWeight = FontWeight(550),
                color = PanelColors.onSurface,
            )
            Text(
                text = summary,
                fontSize = PanelTokens.InfoValueSize,
                color = PanelColors.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = PanelTokens.InfoValueGap),
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 密钥区块：AppSecret（掩码 + 眼睛 + 安全键盘）+ 一行落盘说明。
 *
 * [saved] 只影响提示语：编辑页里已存过就不再要求重填（留空 = 不改）。
 */
@Composable
fun BotFormSecretSection(saved: Boolean, value: String, onValueChange: (String) -> Unit) {
    BotFormSection(title = "密钥") {
        BotFormField(
            label = "AppSecret",
            value = value,
            onValueChange = onValueChange,
            hint = if (saved) "已保存，留空则不修改" else "填入 AppSecret",
            secret = true,
            ellipsis = true,
        )
        Text(
            text = if (saved) {
                "已保存（Keystore AES-256/GCM 加密，落盘看不到明文）；留空保存 = 不改"
            } else {
                "还没填 AppSecret"
            },
            fontSize = 13.sp,
            color = PanelColors.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = 4.dp, top = 6.dp),
        )
    }
}

/**
 * 人格区块：miuix 下拉（内置档 + 自定义档），末尾固定一项 [PERSONA_IMPORT_ITEM]。
 * 选到导入项就 [onImport]（由 [rememberPersonaImporter] 提供的启动器拉起文件选择器）。
 * 已导入的自定义档在下面单独列一行，行尾删除图标 —— 内置档不列，也删不掉。
 */
@Composable
fun BotFormPersonaSection(
    personas: List<String>,
    selected: String,
    customNames: List<String>,
    onSelect: (String) -> Unit,
    onImport: () -> Unit,
    onDeleteCustom: (String) -> Unit,
    note: String = "",
) {
    val items = remember(personas) { personas + PERSONA_IMPORT_ITEM }
    BotFormSection(title = "人格") {
        OverlayDropdownPreference(
            insideMargin = PanelTokens.PrefInside,
            title = "人格",
            // 没选中时右侧会显示候选首项（dropdown 自身行为），说明里点明「点开才算选中」
            summary = if (selected.isBlank()) "还没选，点右侧展开" else "当前：" + selected,
            items = items,
            selectedIndex = personas.indexOf(selected).coerceAtLeast(0),
            onSelectedIndexChange = { i ->
                if (i == items.lastIndex) onImport() else onSelect(items.getOrElse(i) { "" })
            },
        )
        if (customNames.isNotEmpty()) {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                thickness = PanelTokens.Divider,
                color = PanelColors.dividerLine,
            )
            Text(
                text = "已导入的自定义人格（点一行选中，行尾图标删除）",
                fontSize = 13.sp,
                color = PanelColors.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )
            customNames.forEach { n ->
                val full = PersonaAssets.CUSTOM_PREFIX + n
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(full) },
                ) {
                    Text(
                        text = full,
                        fontSize = PanelTokens.InfoTitleSize,
                        fontWeight = if (full == selected) FontWeight(600) else FontWeight(450),
                        color = if (full == selected) PanelColors.primary else PanelColors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp),
                    )
                    IconButton(onClick = { onDeleteCustom(full) }) {
                        Icon(
                            imageVector = MiuixIcons.Delete,
                            contentDescription = "删除这个自定义人格",
                            tint = PanelColors.error,
                        )
                    }
                }
            }
        }
        if (note.isNotBlank()) {
            Text(
                text = note,
                fontSize = 13.sp,
                color = PanelColors.primary,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            )
        } else {
            Text(
                text = "导入的 md / txt 复制到应用私有目录 files/personas/，只读原文件，不写 /sdcard；上限 256KB。",
                fontSize = 13.sp,
                color = PanelColors.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            )
        }
    }
}

/**
 * 模型区块：继承开关；关掉才显示「接口地址 / API Key / 模型」三件套 +「获取模型」。
 * 取模型走 [ModelCatalog]（GET {base}/v1/models），结果再进一个下拉里选。
 *
 * 组件自己持有「拉取中 / 失败原因 / 候选模型」三个临时态 —— 两页都不需要重复这段。
 */
@Composable
fun BotFormModelSection(
    inherit: Boolean,
    onInheritChange: (Boolean) -> Unit,
    globalModel: String,
    api: String,
    key: String,
    model: String,
    onApi: (String) -> Unit,
    onKey: (String) -> Unit,
    onModel: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var fetching by remember { mutableStateOf(false) }
    var fetchErr by remember { mutableStateOf("") }

    BotFormSection(title = "模型") {
        BotFormSwitchRow(
            title = "继承模型配置",
            summary = if (inherit) "用全局模型：" + globalModel.ifBlank { "deepseek-chat" }
            else "本 bot 单独填，写入全局模型配置",
            checked = inherit,
            onCheckedChange = onInheritChange,
        )
        if (!inherit) {
            Spacer(Modifier.height(12.dp))
            BotFormField(
                label = "接口地址",
                value = api,
                onValueChange = onApi,
                hint = "OpenAI 兼容的 base url",
                ellipsis = true,
            )
            BotFormField(
                label = "API Key",
                value = key,
                onValueChange = onKey,
                hint = "sk-… 开头的密钥",
                secret = true,
                ellipsis = true,
            )
            BotFormField(
                label = "模型",
                value = model,
                onValueChange = onModel,
                hint = "如 deepseek-chat",
            )
            Spacer(Modifier.height(6.dp))
            PanelPillRow {
                PanelSecondaryButton(
                    text = if (fetching) "获取中…" else "获取模型",
                    enabled = !fetching && api.isNotBlank() && key.isNotBlank(),
                    onClick = {
                        fetching = true
                        fetchErr = ""
                        scope.launch {
                            val out = withContext(Dispatchers.IO) { ModelCatalog.fetch(api, key) }
                            fetching = false
                            models = out.models
                            fetchErr = out.error
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (api.isBlank() || key.isBlank()) {
                Text(
                    text = "填好「接口地址 + API Key」才能获取模型",
                    fontSize = 13.sp,
                    color = PanelColors.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                )
            }
            if (fetchErr.isNotBlank()) {
                Text(
                    text = fetchErr,
                    fontSize = 13.sp,
                    color = PanelTokens.ErrorColor,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                )
            }
            if (models.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                OverlayDropdownPreference(
                    insideMargin = PanelTokens.PrefInside,
                    title = "从列表选模型",
                    summary = "共 " + models.size + " 个（" +
                        (if (models.indexOf(model) >= 0) "当前已选中" else "还没选") + "）",
                    items = models,
                    selectedIndex = models.indexOf(model).coerceAtLeast(0),
                    onSelectedIndexChange = { i -> onModel(models.getOrElse(i) { model }) },
                )
            }
        }
    }
}

/**
 * 人格导入器：返回一个「拉起系统文件选择器」的 lambda。
 *
 * ActivityResultContracts.OpenDocument + mime 用 text 通配：只读（不写回、不申请存储权限），
 * 扩展名 .md/.txt 在 [PersonaAssets.importFrom] 里再校验一次；
 * 读 → 校验 256KB → 复制到 files/personas/<安全文件名>.txt，成功回调新名字。
 */
@Composable
fun rememberPersonaImporter(
    onImported: (String) -> Unit,
    onMessage: (String) -> Unit,
): () -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            onMessage("没有选择文件")
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val res = withContext(Dispatchers.IO) { PersonaAssets.importFrom(ctx, uri) }
            if (res.ok) onImported(res.name) else onMessage(res.message)
        }
    }
    return { launcher.launch(arrayOf("text/*")) }
}
