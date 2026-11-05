package com.wjljh.andbna.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wjljh.andbna.BuildConfig
import com.wjljh.andbna.ui.component.MarkdownWebView
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 升级后的「本版更新说明」（单页，替代原 6 步引导）。
 *
 * 判定在 App.kt：首次安装（[com.wjljh.andbna.data.OnboardStore.done] == false）仍走 6 步引导；
 * 老用户 seenVc < VERSION_CODE 时弹这一页。点「知道了」→ [onDismiss]（App.kt 里 markDone 写回
 * onboard_seen_vc，同一版本只弹一次）。
 *
 * 正文是编译期常量（[BODY]），走已有的 [MarkdownWebView]（WebView 内联 HTML），全程不发网络请求；
 * 深/浅色由 MarkdownWebView 自己按系统主题换配色。内容超出屏高时 WebView 内部滚动。
 */
private const val BODY = """
## 本次更新

- **判定更主动**：叫它名字不再需要 @，直接回；判定默认 `normal` 档，拿不准时倾向于接话（模型配置页可改 conservative / eager）
- **保存即生效**：模型配置、Bot 编辑、群配置保存后自动重启对应 bot，不用再手动重启
- **密钥输入用系统安全键盘**：API Key / AppSecret 输入时不再进输入法词库
- **模型可以 @ 人**：群聊里回复需要点名时会真的 @ 到对方
- **@ 语义更清楚**：它现在能分辨「@你」「@作者本人」「@其他人」
- **移除**：设置页「清理识图缓存」（识图不落盘，本来就没有缓存）
"""

@Composable
fun WhatsNewScreen(onDismiss: () -> Unit) {
    WindowDialog(
        show = true,
        title = "AndbnA " + BuildConfig.VERSION_NAME + " 更新说明",
        onDismissRequest = onDismiss,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MarkdownWebView(markdown = BODY.trim(), modifier = Modifier.fillMaxWidth())
                TextButton(
                    text = "知道了",
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}
