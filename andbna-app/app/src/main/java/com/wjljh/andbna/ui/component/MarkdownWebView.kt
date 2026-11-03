package com.wjljh.andbna.ui.component

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * release body 的 Markdown 渲染（与 SukiSU 同一路线：WebView + 内联 GitHub 风格 CSS）。
 *
 * 四件事必须做对：
 *  1) 禁止缩放、禁止长按选择（阅读用，不是编辑器）；
 *  2) 链接一律丢给系统浏览器（ACTION_VIEW），不在 WebView 里跳走；
 *  3) 高度跟着内容走（onPageFinished 后读 contentHeight），超过屏幕 55% 才内部滚动；
 *  4) 深/浅色各一套配色，代码块有底色、链接是主题蓝。
 *
 * 渲染的是**转义后**的 HTML（见 [markdownToHtml]），所以注入面为零 —— JS 只为量高度而开。
 */
@Composable
fun MarkdownWebView(markdown: String, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val density = LocalDensity.current
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.55f).dp
    var contentPx by remember { mutableIntStateOf(0) }
    val html = remember(markdown, dark) { markdownToHtml(markdown, dark) }

    val sized = if (contentPx > 0) {
        val h = with(density) { contentPx.toDp() }
        Modifier.heightIn(max = maxHeight).height(if (h > maxHeight) maxHeight else h)
    } else {
        Modifier.heightIn(max = maxHeight).height(maxHeight)
    }

    AndroidView(
        modifier = modifier.fillMaxWidth().then(sized),
        factory = { c ->
            WebView(c).apply {
                setBackgroundColor(Color.TRANSPARENT)
                isLongClickable = false
                isHapticFeedbackEnabled = false
                setOnLongClickListener { true }
                settings.apply {
                    javaScriptEnabled = true // 只为量高度；正文已全量转义
                    setSupportZoom(false)
                    builtInZoomControls = false
                    displayZoomControls = false
                    loadWithOverviewMode = false
                    useWideViewPort = true
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val url: Uri? = request?.url
                        if (url != null) {
                            runCatching { view?.context?.startActivity(Intent(Intent.ACTION_VIEW, url)) }
                        }
                        return true
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        // 高度按内容量：contentHeight 在 WebView 自适应时会失真，直接问 DOM
                        view?.postDelayed({
                            view.evaluateJavascript("Math.ceil(document.body.scrollHeight)") { v ->
                                val cssPx = v?.trim('"', ' ')?.toFloatOrNull() ?: 0f
                                if (cssPx > 0f) {
                                    contentPx = (cssPx * view.resources.displayMetrics.density).toInt()
                                } else {
                                    val h = view.contentHeight
                                    if (h > 0) contentPx = (h * view.resources.displayMetrics.density).toInt()
                                }
                            }
                        }, 80L)
                    }
                }
                loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                tag = html
            }
        },
        // 深浅色切换会换一份 html（配色不同）：这里必须真的重载，否则文字还是旧配色
        update = { view ->
            if ((view.tag as? String) != html) {
                view.tag = html
                view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
    )
}

/** 极简 Markdown -> HTML：标题 / 列表 / 粗体 / 行内 code / 围栏代码块 / 引用 / 链接。够读更新日志。 */
fun markdownToHtml(src: String, dark: Boolean): String {
    val fg = if (dark) "#E6E6E6" else "#1B1B1B"
    val dim = if (dark) "#A8A8A8" else "#5F6368"
    val codeBg = if (dark) "#1E1E1E" else "#F2F3F5"
    val link = if (dark) "#8AB4F8" else "#1A73E8"
    val rule = if (dark) "#2C2C2C" else "#E3E3E3"
    val css = StringBuilder()
        .append("html,body{margin:0;padding:0;background:transparent;color:").append(fg).append(";")
        .append("font-family:-apple-system,'Noto Sans SC','PingFang SC',sans-serif;font-size:14px;line-height:1.65;}")
        .append(".md{padding:2px 0 4px 0;}")
        .append("h1,h2,h3,h4{font-weight:600;line-height:1.35;margin:14px 0 6px;}")
        .append("h1{font-size:20px;}h2{font-size:18px;}h3{font-size:16px;}h4{font-size:15px;}")
        .append("p{margin:8px 0;}ul,ol{margin:6px 0;padding-left:22px;}li{margin:3px 0;}")
        .append("a{color:").append(link).append(";text-decoration:none;}")
        .append("code{background:").append(codeBg)
        .append(";padding:1px 5px;border-radius:4px;font-family:monospace;font-size:13px;}")
        .append("pre{background:").append(codeBg)
        .append(";padding:10px;border-radius:8px;overflow-x:auto;margin:8px 0;}")
        .append("pre code{background:transparent;padding:0;font-size:13px;}")
        .append("blockquote{margin:8px 0;padding:2px 10px;border-left:3px solid ").append(rule)
        .append(";color:").append(dim).append(";}")
        .append("hr{border:none;border-top:1px solid ").append(rule).append(";margin:12px 0;}")
        .append("strong{font-weight:600;}")
        .toString()

    val head = "<head><meta name='viewport' content='width=device-width, initial-scale=1'><style>" + css + "</style></head>"
    val out = StringBuilder()
    var inCode = false
    var inList = ""
    var para = StringBuilder()

    fun closeList() {
        if (inList.isNotEmpty()) {
            out.append("</").append(inList).append(">")
            inList = ""
        }
    }

    fun flushPara() {
        val t = para.toString().trim()
        para = StringBuilder()
        if (t.isNotEmpty()) out.append("<p>").append(inline(t)).append("</p>")
    }

    for (line in src.replace("\r\n", "\n").split('\n')) {
        if (line.trimStart().startsWith("```")) {
            flushPara(); closeList()
            if (!inCode) out.append("<pre><code>") else out.append("</code></pre>")
            inCode = !inCode
            continue
        }
        if (inCode) {
            out.append(escape(line)).append('\n')
            continue
        }
        val t = line.trim()
        when {
            t.isEmpty() -> { flushPara(); closeList() }
            t.startsWith("#") -> {
                flushPara(); closeList()
                val level = t.takeWhile { it == '#' }.length.coerceIn(1, 4)
                out.append("<h").append(level).append(">")
                    .append(inline(t.dropWhile { it == '#' }.trim()))
                    .append("</h").append(level).append(">")
            }
            t.startsWith(">") -> {
                flushPara(); closeList()
                out.append("<blockquote>").append(inline(t.drop(1).trim())).append("</blockquote>")
            }
            t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ") -> {
                flushPara()
                if (inList != "ul") { closeList(); out.append("<ul>"); inList = "ul" }
                out.append("<li>").append(inline(t.drop(2).trim())).append("</li>")
            }
            ORDERED.containsMatchIn(t) -> {
                flushPara()
                if (inList != "ol") { closeList(); out.append("<ol>"); inList = "ol" }
                out.append("<li>").append(inline(ORDERED.replaceFirst(t, ""))).append("</li>")
            }
            t == "---" || t == "***" -> { flushPara(); closeList(); out.append("<hr>") }
            else -> {
                if (inList.isNotEmpty()) closeList()
                if (para.isNotEmpty()) para.append(' ')
                para.append(t)
            }
        }
    }
    flushPara(); closeList()
    if (inCode) out.append("</code></pre>")

    return "<!DOCTYPE html><html>" + head + "<body><div class='md'>" + out + "</div></body></html>"
}

private val ORDERED = Regex("^[0-9]+[.][ ]")

/** 行内 code 的定界符：用字符码拼，源码里不出现反引号，省得和注释/模板打架 */
private val TICK: String = 96.toChar().toString()

private fun escape(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

/** 行内：先把行内 code 摘成占位符，再转义，最后做链接与强调 */
private fun inline(src: String): String {
    val kept = mutableListOf<String>()
    var s = Regex(TICK + "([^" + TICK + "]+)" + TICK).replace(src) { m ->
        kept.add("<code>" + escape(m.groupValues[1]) + "</code>")
        "@@" + (kept.size - 1) + "@@"
    }
    s = escape(s)
    s = Regex("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)").replace(s) { m ->
        "<a href='" + m.groupValues[2] + "'>" + m.groupValues[1] + "</a>"
    }
    s = Regex("\\*\\*([^*]+)\\*\\*").replace(s, "<strong>$1</strong>")
    s = Regex("\\*([^*\\n]+)\\*").replace(s, "<em>$1</em>")
    Regex("@@([0-9]+)@@").findAll(s).toList().forEach { m ->
        val i = m.groupValues[1].toIntOrNull() ?: return@forEach
        s = s.replace(m.value, kept.getOrElse(i) { "" })
    }
    return s
}
