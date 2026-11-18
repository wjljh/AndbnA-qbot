package com.wjljh.andbna.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wjljh.andbna.ui.LocalUiMode
import com.wjljh.andbna.ui.UiMode
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 全局底部提示条：一条「操作结果」（已保存 / 已删除 / 导出完成…），可选一个胶囊按钮（撤销…）。
 *
 * 用法：页面里直接 [AppToast.show]，不用自己留 state —— 宿主 [AppToastHost] 挂在
 * ui/App.kt 的最外层 Box 里（浮在所有页面之上、底栏上方），全 App 只有一份。
 *
 * 三条生命周期规则：
 *  · 8 秒自动消失（按钮与条同寿）；
 *  · 当前页 key 一变（切 tab / 进二级页 / 退二级页）立即消失；
 *  · 点按钮 = 先消失，再执行回调。
 */
object AppToast {

    /** 提示条存活时长，也是「撤销」窗口：8 秒。 */
    const val DURATION_MS = 8_000L

    /** 一条提示。seq 只用来区分「是不是同一条」，两次内容相同也要重新计时。 */
    internal data class Message(
        val seq: Long,
        val text: String,
        val action: String?,
        val onAction: (() -> Unit)?,
    )

    private var seq = 0L

    /** 当前显示中的那条；null = 不显示（退出动画期间也已经是 null，见 [last]）。 */
    internal var visible by mutableStateOf<Message?>(null)
        private set

    /** 最近一条：只为退出动画还有东西可画（visible 先变 null，动画才跑）。 */
    internal var last by mutableStateOf<Message?>(null)
        private set

    /**
     * 弹一条提示。
     *
     * @param action 右侧胶囊按钮的文案（如「撤销」）；null = 没有按钮
     * @param onAction 点按钮的回调（点完条立即消失，回调里可以再 [show] 一条结果）
     */
    fun show(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
        if (text.isBlank()) return
        seq += 1
        val m = Message(seq, text, action?.takeIf { it.isNotBlank() }, onAction)
        last = m
        visible = m
    }

    /** 手动关掉（切页、点按钮时用）。 */
    fun hide() {
        visible = null
    }

    /** 现在这条有没有按钮：页面用它判断「撤销窗口还开着吗」，别把可撤销的提示顶掉。 */
    fun hasAction(): Boolean = visible?.action != null

    internal fun dismiss(id: Long) {
        if (visible?.seq == id) visible = null
    }
}

/**
 * 提示条宿主：全 App 一份，放在 App.kt 根 Box 的最后一个子节点（浮在最上层）。
 *
 * @param pageKey 当前页 key（一级 tab + 二级路由）。变了就清空 —— 切页不该留着上一页的结果。
 * @param dockHeight 底栏实测高度：条浮在它上方 12dp，不推挤任何内容、不改变布局高度。
 */
@Composable
fun AppToastHost(
    pageKey: Any?,
    modifier: Modifier = Modifier,
    dockHeight: Dp = 0.dp,
) {
    // 切页 / 换 tab 立即消失
    LaunchedEffect(pageKey) { AppToast.hide() }

    val live = AppToast.visible
    // 8 秒到点自己走；被新的一条顶掉时 key 变了，旧的计时作废
    LaunchedEffect(live?.seq) {
        val id = live?.seq ?: return@LaunchedEffect
        delay(AppToast.DURATION_MS)
        AppToast.dismiss(id)
    }

    // 轻量动画：fade + 上滑 8dp
    val slide = with(LocalDensity.current) { 8.dp.roundToPx() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = dockHeight + 12.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = live != null,
            enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { slide },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(140)) { slide },
        ) {
            ToastBar(live ?: AppToast.last)
        }
    }
}

/** 反色配色：深色模式白底黑字 / 浅色模式黑底白字 —— 任何背景上都跳得出来。 */
private val schemeIsLight: Boolean
    @Composable @ReadOnlyComposable get() =
        (if (LocalUiMode.current == UiMode.Material) MaterialTheme.colorScheme.surfaceContainer
        else MiuixTheme.colorScheme.surfaceContainer).luminance() >= 0.5f

@Composable
private fun ToastBar(msg: AppToast.Message?) {
    if (msg == null) return
    val bg = if (schemeIsLight) Color.Black else Color.White
    val fg = if (schemeIsLight) Color.White else Color.Black
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(10.dp, shape, clip = false)
            .clip(shape)
            .background(bg)
            .padding(
                start = 16.dp,
                end = if (msg.action != null) 8.dp else 16.dp,
                top = 12.dp,
                bottom = 12.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = msg.text,
            color = fg,
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (msg.action != null) {
            Text(
                text = msg.action,
                color = fg,
                fontSize = 14.sp,
                fontWeight = FontWeight(600),
                maxLines = 1,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .clip(RoundedCornerShape(50))
                    .background(fg.copy(alpha = 0.16f))
                    .clickable {
                        AppToast.hide()
                        msg.onAction?.invoke()
                    }
                    .padding(horizontal = 16.dp, vertical = 7.dp),
            )
        }
    }
}
