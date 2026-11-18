package com.wjljh.andbna.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationEventHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.wjljh.andbna.data.PrefsWatch
import com.wjljh.andbna.data.SettingsRepositoryImpl

/**
 * 预测性返回开关「关」时的返回拦截层。
 *
 * 跟手预览是库自己在 NavDisplay **所在那层** NavigationEventDispatcher 上注册的
 * PredictiveBackHandlerWithSessions 画的。NavDisplay 会给每个 entry 单独铺一个子 dispatcher
 * （rememberNavEntryDispatcherOwner），子层回调优先命中，所以把回调注册进 entry 里就能抢在库那条
 * 之前接住返回：关闭态下库收不到 back 事件 → 不画预览；松手这里直接弹一层，一次侧滑只退一层。
 *
 * 打开态整段不注册（isBackEnabled=false），返回照旧交给库，跟手预览不变。
 */
@Composable
fun PredictiveBackGate(canPop: Boolean, onBack: () -> Unit) {
    PrefsWatch.observed()
    val repo = remember { SettingsRepositoryImpl() }
    val state = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationEventHandler(
        state = state,
        isBackEnabled = canPop && !repo.enablePredictiveBack,
        onBackCompleted = onBack,
    )
}
