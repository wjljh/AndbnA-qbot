package com.wjljh.andbna.ui.component

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wjljh.andbna.ui.PanelColors

/**
 * 更新说明 / 更新详情的**原生** Markdown 渲染，替代原来的 WebView（component/MarkdownWebView.kt 已删除）。
 *
 * 为什么不留 WebView：更新说明是静态 Markdown，WebView 一开就是一套 renderer + GL 表面。
 * 实测（同一弹窗、同一份正文）：WebView 版 EGL 57,612，关掉后仍留 ~9MB native；
 * 原生版 52,124，关掉回落到 35,020、残留 0。52,124 里 17MB 是 miuix 弹窗自己的整屏窗口层
 * （空正文时同样是 52,124），markdown 内容本身几乎不占 GPU 内存。
 *
 * 支持的子集（刻意只有这些，不做通用 Markdown）：
 *   ## / ### 标题、`- ` 无序列表、`1. ` 有序列表、`**粗体**`、`\`行内代码\``、`[文字](链接)`、
 *   `> 引用`、围栏代码块、空行分段。
 * 链接走系统浏览器（ACTION_VIEW），不在 App 内开页面。
 *
 * 正文超长时自己滚动（heightIn + verticalScroll），**不是** LazyColumn：内容就是几十行文本，
 * 惰性列表反而是负收益（还要额外处理嵌套滚动）。
 */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val colors = mdColors()
    // 解析失败绝不白屏：整段纯文本兜底（还能选中复制）。
    val blocks = remember(markdown) { runCatching { parseMarkdown(markdown) }.getOrNull() }
    if (blocks == null) {
        SelectionContainer {
            Text(
                text = markdown,
                color = colors.text,
                fontSize = 14.sp,
                lineHeight = 22.sp,
                modifier = modifier.fillMaxWidth(),
            )
        }
        return
    }

    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.55f).dp
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    text = inline(buildAnnotatedString { appendInline(block.text, colors, 0) }, colors),
                    color = colors.text,
                    fontSize = headingSize(block.level),
                    lineHeight = (headingSize(block.level).value * 1.35f).sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth(),
                )

                is MdBlock.Para -> LinkText(
                    annotated = inline(buildAnnotatedString { appendInline(block.text, colors, 0) }, colors),
                    colors = colors,
                    fontSize = 14.sp,
                )

                is MdBlock.Item -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = block.marker,
                        color = colors.dim,
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                    )
                    LinkText(
                        annotated = inline(buildAnnotatedString { appendInline(block.text, colors, 0) }, colors),
                        colors = colors,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f),
                    )
                }

                is MdBlock.Quote -> Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(colors.rule, RoundedCornerShape(2.dp)),
                    )
                    LinkText(
                        annotated = inline(buildAnnotatedString { appendInline(block.text, colors, 0) }, colors),
                        colors = colors.copy(text = colors.dim),
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f),
                    )
                }

                is MdBlock.Code -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.codeBg, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = block.text,
                        color = colors.text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 配色

/** 一次组合里取一遍主题色，省得每行都读 CompositionLocal。 */
private data class MdColors(
    val text: Color,
    val dim: Color,
    /** 行内代码 / 代码块底色：直接由文字色兑低透明度得来 —— 主题的容器色和弹窗底色经常是同一档，
     *  用 surfaceContainerHigh 会在弹窗里「糊掉」，按文字色反兑则深浅两套主题下都看得见。 */
    val codeBg: Color,
    val link: Color,
    val rule: Color,
)

@Composable
private fun mdColors(): MdColors = MdColors(
    text = PanelColors.onSurface,
    dim = PanelColors.onSurfaceVariantSummary,
    codeBg = PanelColors.onSurface.copy(alpha = 0.10f),
    link = PanelColors.primary,
    rule = PanelColors.dividerLine,
)

private fun headingSize(level: Int): TextUnit = when {
    level <= 1 -> 21.sp
    level == 2 -> 19.sp
    level == 3 -> 16.sp
    else -> 15.sp
}

// ---------------------------------------------------------------- 行内

private const val LINK_TAG = "md-link"

/** 行内样式补齐（链接色 + 下划线在这里统一加，解析阶段只管切出范围）。 */
private fun inline(src: AnnotatedString, colors: MdColors): AnnotatedString = buildAnnotatedString {
    append(src)
    src.getStringAnnotations(LINK_TAG, 0, src.length).forEach { range ->
        addStyle(
            SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline),
            range.start,
            range.end,
        )
    }
}

private fun AnnotatedString.Builder.appendInline(src: String, colors: MdColors, depth: Int) {
    var i = 0
    while (i < src.length) {
        val c = src[i]
        // 行内代码：整段原子，里面的 ** / [ 不再解析
        if (c == '`') {
            val end = src.indexOf('`', i + 1)
            if (end > i + 1) {
                pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = colors.codeBg, fontSize = 13.sp))
                append(src.substring(i + 1, end))
                pop()
                i = end + 1
                continue
            }
        }
        if (depth < 4 && src.startsWith("**", i)) {
            val end = src.indexOf("**", i + 2)
            if (end > i + 2) {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                    appendInline(src.substring(i + 2, end), colors, depth + 1)
                }
                i = end + 2
                continue
            }
        }
        if (c == '[') {
            val labelEnd = src.indexOf(']', i + 1)
            if (labelEnd > i && labelEnd + 1 < src.length && src[labelEnd + 1] == '(') {
                val urlEnd = src.indexOf(')', labelEnd + 2)
                if (urlEnd > labelEnd + 2) {
                    val url = src.substring(labelEnd + 2, urlEnd).trim()
                    if (url.isNotEmpty()) {
                        pushStringAnnotation(LINK_TAG, url)
                        appendInline(src.substring(i + 1, labelEnd), colors, depth + 1)
                        pop()
                        i = urlEnd + 1
                        continue
                    }
                }
            }
        }
        append(c)
        i++
    }
}

/**
 * 一行文字 + 链接点击：命中链接就丢给系统浏览器。
 *
 * 用 pointerInput + getOffsetForPosition（而不是 ClickableText）：后者已被标弃用，
 * 而 LinkAnnotation 的自动行为依赖 Compose 版本，自己算点击位置最稳。
 */
@Composable
private fun LinkText(
    annotated: AnnotatedString,
    colors: MdColors,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var layout by remember(annotated) { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = annotated,
        color = colors.text,
        fontSize = fontSize,
        lineHeight = (fontSize.value * 1.6f).sp,
        onTextLayout = { layout = it },
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(annotated) {
                detectTapGestures { position ->
                    val result = layout ?: return@detectTapGestures
                    val offset = result.getOffsetForPosition(position).coerceIn(0, annotated.length)
                    val url = annotated.getStringAnnotations(LINK_TAG, offset, offset).firstOrNull()?.item
                        ?: return@detectTapGestures
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                }
            },
    )
}

// ---------------------------------------------------------------- 解析

internal sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Para(val text: String) : MdBlock

    /** [marker] 是「•」或「1.」这种行首符号，渲染时单独一列，保持列表缩进的层次感。 */
    data class Item(val marker: String, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
}

private const val FENCE = "```"
private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val ORDERED = Regex("^([0-9]{1,3})[.)]\\s+(.*)$")
private val BULLET = Regex("^[-*+]\\s+(.*)$")
private val RULE = Regex("^(-{3,}|\\*{3,}|_{3,})$")

/**
 * Markdown -> 块列表。只认上面那套子集，遇到不认识的语法就当普通段落文字（不抛、不丢内容）。
 * 纯函数，不碰 Compose，方便单测；[MarkdownText] 外面还包了 runCatching 兜底。
 */
internal fun parseMarkdown(src: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val para = StringBuilder()
    var code: StringBuilder? = null

    fun flushPara() {
        val t = para.toString().trim()
        para.setLength(0)
        if (t.isNotEmpty()) out += MdBlock.Para(t)
    }

    for (raw in src.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
        val trimmed = raw.trim()

        val fenceOpen = code
        if (fenceOpen != null) {
            if (trimmed.startsWith(FENCE)) {
                val body = fenceOpen.toString().trimEnd('\n')
                if (body.isNotEmpty()) out += MdBlock.Code(body)
                code = null
            } else {
                fenceOpen.append(raw).append('\n')
            }
            continue
        }
        if (trimmed.startsWith(FENCE)) {
            flushPara()
            code = StringBuilder()
            continue
        }

        if (trimmed.isEmpty() || RULE.matches(trimmed)) {
            flushPara()
            continue
        }

        // 标题
        val heading = HEADING.find(trimmed)
        if (heading != null) {
            flushPara()
            out += MdBlock.Heading(heading.groupValues[1].length.coerceIn(1, 4), heading.groupValues[2].trim())
            continue
        }

        // 引用
        if (trimmed.startsWith(">")) {
            flushPara()
            val body = trimmed.drop(1).trim()
            if (body.isNotEmpty()) out += MdBlock.Quote(body)
            continue
        }

        // 有序列表
        val ordered = ORDERED.find(trimmed)
        if (ordered != null) {
            flushPara()
            out += MdBlock.Item(ordered.groupValues[1] + ".", ordered.groupValues[2].trim())
            continue
        }

        // 无序列表
        val bullet = BULLET.find(trimmed)
        if (bullet != null) {
            flushPara()
            out += MdBlock.Item("\u2022", bullet.groupValues[1].trim())
            continue
        }

        // 普通段落：连续行用空格接起来（与 GitHub 一致）
        if (para.isNotEmpty()) para.append(' ')
        para.append(trimmed)
    }
    flushPara()
    code?.let { open ->
        val body = open.toString().trimEnd('\n')
        if (body.isNotEmpty()) out += MdBlock.Code(body)
    }
    return out
}
