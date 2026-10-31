package com.wjljh.andbna.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState

/**
 * 四个 tab 页共用的列表外壳。
 *
 * 底部留白不走"把可视区整体抬高"（那会在底栏外面留一条背景色的黑条），
 * 改成 LazyColumn 的 contentPadding.bottom = [bottomInset]：
 * 滚动中内容从浮动胶囊后面穿过去，滚到底时最后一项完整露出。
 * [bottomInset] = 底栏实测高度 + 12dp，由 App.kt 量出来传给页面。
 *
 * [onRefresh] 不为 null 时整页包一层 kit 的 [PullToRefresh]（照 SukiSU
 * ModuleMiuix.kt:488-497 的结构与参数），[refreshing] 由页面自己的状态驱动；
 * 文案四段是固定的中文，不用 kit 的英文默认串（pulling / release / refresh / complete）。
 * 页面传 onRefresh = null 就是「这一页不接下拉刷新」（多选态、深二级页都这么关掉手势）。
 *
 * [topBar] 渲染在 LazyColumn **之外**（Column { topBar; PullToRefresh { LazyColumn } }），
 * 所以它不随列表滚动 —— 记忆页的多选条就挂在这里（照 SukiSU 顶栏右上角的 action 按钮）。
 * 它自带一圈留白，列表再垫 [PanelTokens.PageTop] 就成了双倍留白，因此有顶栏时列表顶部归零。
 */
@Composable
fun PanelScreen(
    bottomInset: Dp,
    modifier: Modifier = Modifier,
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    topBar: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val listTop = if (topBar == null) PanelTokens.PageTop else 0.dp
    val list: @Composable () -> Unit = {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = PanelTokens.PageHorizontal,
                end = PanelTokens.PageHorizontal,
                top = listTop,
                bottom = bottomInset,
            ),
            verticalArrangement = Arrangement.spacedBy(PanelTokens.CardSpacing),
        ) {
            content()
        }
    }
    val body: @Composable () -> Unit = {
        if (onRefresh == null) {
            list()
        } else {
            PullToRefresh(
                isRefreshing = refreshing,
                onRefresh = onRefresh,
                pullToRefreshState = rememberPullToRefreshState(),
                refreshTexts = RefreshTexts,
                contentPadding = PaddingValues(),
            ) {
                list()
            }
        }
    }
    if (topBar == null) {
        body()
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            topBar()
            Box(Modifier.weight(1f)) { body() }
        }
    }
}

/** 下拉刷新四段文案（kit 默认是英文 pulling / release / refresh / complete，这里全换中文）。 */
private val RefreshTexts = listOf("下拉刷新", "松开刷新", "正在刷新…", "刷新完成")
