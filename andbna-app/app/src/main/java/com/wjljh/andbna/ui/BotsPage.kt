package com.wjljh.andbna.ui

import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Switch as M3Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wjljh.andbna.local.BotHost
import com.wjljh.andbna.local.BotService
import com.wjljh.andbna.local.BotStore
import com.wjljh.andbna.local.LocalBot
import com.wjljh.andbna.local.PullBack
import com.wjljh.andbna.ui.component.PanelAnimatedNotice
import com.wjljh.andbna.ui.component.PanelDangerSolidButton
import com.wjljh.andbna.ui.component.PanelGroupCard
import com.wjljh.andbna.ui.component.PanelIconPillButton
import com.wjljh.andbna.ui.component.PanelNotice
import com.wjljh.andbna.ui.component.PanelPillRow
import com.wjljh.andbna.ui.component.PanelPrimaryButton
import com.wjljh.andbna.ui.component.PanelScreen
import com.wjljh.andbna.ui.component.PanelSecondaryButton
import com.wjljh.andbna.ui.component.PanelTokens
import com.wjljh.andbna.ui.component.cardOutline
import com.wjljh.andbna.ui.component.material.SegmentedArrowItem
import com.wjljh.andbna.ui.component.material.SegmentedContentItem
import com.wjljh.andbna.ui.component.material.SegmentedDropdownItem
import com.wjljh.andbna.ui.component.material.SegmentedGroup
import com.wjljh.andbna.ui.component.material.SegmentedRowItem
import com.wjljh.andbna.ui.component.material.SegmentedSwitchItem
import com.wjljh.andbna.ui.component.material.segmentedGroupInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Undo
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * Bot 管理（第 3 页）。
 *
 * miuix：卡片语言照 SukiSU ModuleMiuix.kt:783-1075（每 bot 一张 16dp 圆角卡、标题 17sp(550)、
 * 右上角 Switch、0.5dp 细线、底部 35dp 胶囊动作行，按压 Tilt 无水波）。
 * material：换成 SegmentedGroup —— 每 bot 一组，组内四行各自独立成块（2dp 空隙、不画线）：
 * 行1 bot 名 + 行尾 material3 Switch、行2 人格下拉、行3 继承开关、行4 动作胶囊，
 * 组间距 start/end 16 / bottom 13dp。
 *
 * 业务逻辑（BotStore / BotHost / BotService）一行没改。
 */
@Composable
fun BotsPage(
    metrics: Metrics,
    visible: Boolean,
    bottomInset: Dp = 0.dp,
    revision: Int = 0,
    onOpenTasks: (String) -> Unit = {},
    onOpenBotEdit: (String) -> Unit = {},
    onOpenBotAdd: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // 首帧不读盘：bots 从空表起步，真读文件在下面的 LaunchedEffect 里走 IO 线程。
    // （原来是 remember { mutableStateOf(BotStore.load(ctx)) }：切页那一帧同步读 localbots.json
    //   + 解密 + 解析 JSON，全压在组合期，实测 p99 直接上百毫秒。）
    var bots by remember { mutableStateOf<List<LocalBot>>(emptyList()) }
    // 第一次装完之前显示骨架行，别闪一下「还没有配置 bot」
    var loading by remember { mutableStateOf(true) }
    // 运行状态：Python 快照原文进 state，解析结果按原文缓存（原文没变就不解析、不重组）
    val snapshotRaw = remember { mutableStateOf("[]") }
    val runtime by remember { derivedStateOf { parseRuntime(snapshotRaw.value) } }
    var msg by remember { mutableStateOf("") }
    // 两步确认：同一时刻只允许一张卡处于待确认态（点别的卡的「删除」会切过去）
    var confirmingId by remember { mutableStateOf("") }
    val material = LocalUiMode.current == UiMode.Material

    // snapshot() 是跨 Chaquopy 调用：在 Default 线程取原文，内容真变了才写 state
    suspend fun pullSnapshot() {
        val raw = withContext(Dispatchers.Default) { BotHost.snapshot() }
        if (raw != snapshotRaw.value) snapshotRaw.value = raw
    }

    // revision 由 App.kt 在「新建 bot」页创建成功后 +1：本页常驻，不重进也要重读清单
    LaunchedEffect(visible, revision) {
        if (!visible) return@LaunchedEffect
        bots = withContext(Dispatchers.IO) { BotStore.load(ctx) }
        loading = false
        // 进页面先拉一次：否则前 5 秒一直显示「已启用，待启动」（而 bot 其实早就上线了）
        delay(120)
        pullSnapshot()
        while (true) {
            // snapshot() 是跨 Chaquopy 调用：2 秒一次，够新又不至于压住主线程
            delay(2000)
            pullSnapshot()
        }
    }

    // FAB 浮在内容之上、底栏之上（SukiSU ModuleMiuix.kt:399-401 的参数：
    // 56/60dp 圆、主色实心、icon 40dp onPrimary、end 20dp、bottom = 底栏高度 + 16dp；
    // bottomInset 已经是「底栏实测高度 + 12dp」，所以这里 +4dp 正好补到 +16dp）
    val fabBottom = bottomInset - PanelTokens.BottomBarSafetyGap + 16.dp
    Box(Modifier.fillMaxSize()) {
        // 底部再加 72dp：给右下角 56dp FAB + 16dp 边距留净空，否则最后一张卡会被 FAB 压住
        PanelScreen(bottomInset + 72.dp) {
            item(key = "notice") {
                PanelAnimatedNotice(visible = msg.isNotBlank()) { PanelNotice(msg) }
            }

            if (loading && bots.isEmpty()) {
                // 还没读完盘：先给一行骨架，别闪一下「还没有配置 bot」
                item(key = "loading") {
                    PanelGroupCard {
                        Text(
                            text = "正在读取 bot 清单…",
                            fontSize = PanelTokens.EntityTitleSize,
                            fontWeight = FontWeight(550),
                            color = PanelColors.onSurfaceVariantSummary,
                        )
                    }
                }
            } else if (bots.isEmpty()) {
                item(key = "empty") {
                    if (material) {
                        SegmentedGroup(modifier = Modifier.segmentedGroupInsets()) {
                            item {
                                SegmentedArrowItem(
                                    title = "还没有配置 bot",
                                    summary = "填入 AppID 与 AppSecret 就能加一个",
                                    icon = Icons.Filled.Person,
                                    onClick = { onOpenBotAdd() },
                                )
                            }
                        }
                    } else {
                        PanelGroupCard {
                            Text(
                                text = "还没有配置 bot",
                                fontSize = PanelTokens.EntityTitleSize,
                                fontWeight = FontWeight(550),
                                color = PanelColors.onSurface,
                            )
                            Text(
                                text = "填入 AppID 与 AppSecret 就能加一个",
                                fontSize = PanelTokens.EntitySubtitleSize,
                                color = PanelColors.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = PanelTokens.InfoValueGap),
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = PanelTokens.ButtonGap),
                                thickness = PanelTokens.Divider,
                                color = PanelColors.dividerLine,
                            )
                            PanelPillRow {
                                PanelPrimaryButton(
                                    text = "添加一个",
                                    onClick = { onOpenBotAdd() },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }

            bots.forEach { b ->
                item(key = "bot-" + b.appid) {
                    // 启停开关：不只落盘 —— 落盘之后**立刻**起停这一个 bot（不用重启 App）。
                    // 顺序很讲究：先写 localbots.json 的 enabled，再动进程。Python 侧按「启用中的
                    // bot 数」判断总模型要不要上岗，所以它必须在新状态落盘之后才收到 stop/start。
                    val onToggle: (Boolean) -> Unit = { on ->
                        msg = if (on) "正在启动「" + b.title + "」…" else "正在停止「" + b.title + "」…"
                        scope.launch {
                            val note = withContext(Dispatchers.IO) {
                                BotStore.setEnabled(ctx, b.appid, on)
                                if (on) BotHost.startOne(ctx, b.appid, "手动启停")
                                else BotHost.stopOne(ctx, b.appid, "手动启停")
                            }
                            bots = withContext(Dispatchers.IO) { BotStore.load(ctx) }
                            // 立刻拉一次快照：状态从「已上线」变「未启动」不用等 2 秒轮询
                            pullSnapshot()
                            msg = "手动启停：" + b.title + " " + note
                        }
                    }
                    // 「编辑」= 进二级页做这个 bot 的详细配置（Route.BotEdit），不再复用添加弹层
                    val onEdit: () -> Unit = { onOpenBotEdit(b.appid) }
                    // 第一步：只是把这张卡置为待确认；第二步「确认」才真删
                    val onDelete: () -> Unit = { confirmingId = b.appid }
                    val onCancelDelete: () -> Unit = { confirmingId = "" }
                    val onConfirmDelete: () -> Unit = {
                        confirmingId = ""
                        msg = "已删除 " + b.appid
                        scope.launch {
                            bots = withContext(Dispatchers.IO) {
                                BotStore.remove(ctx, b.appid)
                                BotStore.load(ctx)
                            }
                        }
                    }

                    if (material) {
                        BotSegmentedGroup(
                            bot = b,
                            status = runtime[b.appid].orEmpty(),
                            onToggle = onToggle,
                            onEdit = onEdit,
                            confirming = confirmingId == b.appid,
                            onDelete = onDelete,
                            onCancelDelete = onCancelDelete,
                            onConfirmDelete = onConfirmDelete,
                        )
                    } else {
                        BotCard(
                            bot = b,
                            status = runtime[b.appid].orEmpty(),
                            onToggle = onToggle,
                            onEdit = onEdit,
                            confirming = confirmingId == b.appid,
                            onDelete = onDelete,
                            onCancelDelete = onCancelDelete,
                            onConfirmDelete = onConfirmDelete,
                        )
                    }
                }
            }

            if (bots.isNotEmpty()) {
                item(key = "bulk") {
                    if (material) {
                        SegmentedGroup(modifier = Modifier.segmentedGroupInsets()) {
                            item {
                                SegmentedContentItem {
                                    PanelPillRow {
                                        PanelSecondaryButton(
                                            text = "启动全部",
                                            modifier = Modifier.weight(1f),
                                            onClick = {
                                                // 用户想跑：清「停止全部」的停顿标记，自动拉回重新上岗
                                                PullBack.setUserStopped(ctx, false)
                                                scope.launch {
                                                    msg = withContext(Dispatchers.Default) { BotHost.startAll(ctx) }
                                                        .ifBlank { "没有启用的 bot" }
                                                    pullSnapshot()
                                                }
                                            },
                                        )
                                        PanelSecondaryButton(
                                            text = "停止全部",
                                            modifier = Modifier.weight(1f),
                                            onClick = {
                                                BotService.stop(ctx)
                                                BotHost.stopAll()
                                                // 用户主动停：写停顿标记，自动拉回不会把刚停掉的 App 又拉起来
                                                PullBack.setUserStopped(ctx, true)
                                                scope.launch { pullSnapshot() }
                                                msg = "已停止"
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        PanelGroupCard {
                            PanelPillRow {
                                PanelSecondaryButton(
                                    text = "启动全部",
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        // 用户想跑：清「停止全部」的停顿标记，自动拉回重新上岗
                                        PullBack.setUserStopped(ctx, false)
                                        scope.launch {
                                            msg = withContext(Dispatchers.Default) { BotHost.startAll(ctx) }
                                                .ifBlank { "没有启用的 bot" }
                                            pullSnapshot()
                                        }
                                    },
                                )
                                PanelSecondaryButton(
                                    text = "停止全部",
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        BotService.stop(ctx)
                                        BotHost.stopAll()
                                        // 用户主动停：写停顿标记，自动拉回不会把刚停掉的 App 又拉起来
                                        PullBack.setUserStopped(ctx, true)
                                        scope.launch { pullSnapshot() }
                                        msg = "已停止"
                                    },
                                )
                            }
                        }
                    }
                }
            }

        }
        FloatingActionButton(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = fabBottom, end = 20.dp)
                .size(56.dp)
                .border(
                    width = 0.05.dp,
                    color = PanelColors.outline.copy(alpha = 0.5f),
                    shape = CircleShape,
                ),
            shape = CircleShape,
            containerColor = PanelColors.primary,
            shadowElevation = 4.dp,
            minWidth = 56.dp,
            minHeight = 56.dp,
            // 「+」= 进独立的新建页（Route.BotAdd），不再弹老对话框
            onClick = { onOpenBotAdd() },
        ) {
            Icon(
                imageVector = MiuixIcons.Add,
                contentDescription = "添加 bot",
                modifier = Modifier.size(40.dp),
                tint = PanelColors.onPrimary,
            )
        }
    }


    // 删除走卡上两步确认（删除 → 撤销 | 确认），不再弹确认对话框
}

/**
 * material 下单个 bot 的一组（SukiSU 的 SegmentedListItem 结构）：
 * 标题行（行尾 material3 Switch）+ 动作行；组内每行由 SegmentedGroup 各自套一个圆角 Surface。
 * 人格 / 继承模型这类配置只在编辑页收，卡上不再展开 —— 整卡不可点。
 */
@Composable
private fun BotSegmentedGroup(
    bot: LocalBot,
    status: String,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    confirming: Boolean,
    onDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
) {
    val subtitle = botSubtitle(bot, status)
    SegmentedGroup(modifier = Modifier.segmentedGroupInsets()) {
        item {
            SegmentedRowItem(
                title = bot.title,
                subtitle = subtitle,
                icon = Icons.Filled.Smartphone,
                trailing = {
                    M3Switch(checked = bot.enabled, onCheckedChange = onToggle)
                },
            )
        }
        item {
            SegmentedContentItem {
                BotActionRow(
                    confirming = confirming,
                    onEdit = onEdit,
                    onDelete = onDelete,
                    onCancelDelete = onCancelDelete,
                    onConfirmDelete = onConfirmDelete,
                )
            }
        }
    }
}

/**
 * 单个 bot 的卡（SukiSU 模块卡那张脸的等价物）：
 * 标题 17sp(550) + 三行小字 + 右上开关 + 0.5dp 细线 + 胶囊动作行。
 * 卡本身没有任何点击行为（人格/模型等配置只在编辑页收）。
 */
@Composable
private fun BotCard(
    bot: LocalBot,
    status: String,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    confirming: Boolean,
    onDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
) {
    val running = status.contains("已上线") || status.contains("连接中") ||
        status.contains("正在启动")
    val statusText = when {
        running -> status
        bot.enabled -> "已启用，待启动"
        // 手动关掉 = 真的停了（不再是「重启 App 才生效」那套）
        else -> "未启动"
    }
    val summaryColor = PanelColors.onSurfaceVariantSummary

    Card(
        modifier = Modifier.fillMaxWidth().cardOutline(PanelTokens.CardRadius),
        cornerRadius = PanelTokens.CardRadius,
        insideMargin = PaddingValues(0.dp),
        // 整卡不可点：没有 onClick，也就不给按压反馈
        pressFeedbackType = PressFeedbackType.None,
        showIndication = false,
        onClick = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PanelTokens.CardInside),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(PanelTokens.ButtonGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 4.dp),
                ) {
                    Text(
                        text = bot.title,
                        fontSize = PanelTokens.EntityTitleSize,
                        fontWeight = FontWeight(550),
                        color = PanelColors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = statusText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight(550),
                        color = if (running) PanelColors.primary else summaryColor,
                        modifier = Modifier.padding(top = 2.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "人格：" + bot.persona.ifBlank { "未设置" } +
                            " · 模型：" + if (bot.useGlobalLlm) "继承" else "独立",
                        fontSize = 12.sp,
                        fontWeight = FontWeight(550),
                        color = summaryColor,
                        modifier = Modifier.padding(bottom = 1.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "AppID " + bot.appid + if (bot.secret.isBlank()) " · 缺 AppSecret" else "",
                        fontSize = 14.sp,
                        color = if (bot.secret.isBlank()) PanelTokens.ErrorColor else summaryColor,
                        modifier = Modifier.padding(top = 2.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(checked = bot.enabled, onCheckedChange = onToggle)
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = PanelTokens.ButtonGap),
                thickness = PanelTokens.Divider,
                color = PanelColors.dividerLine,
            )

            BotActionRow(
                confirming = confirming,
                onEdit = onEdit,
                onDelete = onDelete,
                onCancelDelete = onCancelDelete,
                onConfirmDelete = onConfirmDelete,
            )
        }
    }
}

/**
 * Python 快照 [{"appid":…,"status":…}] → appid 到状态文案。
 *
 * 纯函数 + derivedStateOf：快照原文没变就不会重跑，组合期不再重复解析 JSON。
 */
private fun parseRuntime(raw: String): Map<String, String> {
    val map = HashMap<String, String>()
    try {
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            map[o.optString("appid")] = o.optString("status")
        }
    } catch (_: Exception) {
    }
    return map
}

/** 卡上的一行小字：状态 · 人格 · AppID（两种模式共用）。 */
private fun botSubtitle(bot: LocalBot, status: String): String {
    val running = status.contains("已上线") || status.contains("连接中") ||
        status.contains("正在启动")
    val statusText = when {
        running -> status
        bot.enabled -> "已启用，待启动"
        else -> "未启动"
    }
    return statusText + " · 人格：" + bot.persona.ifBlank { "未设置" } +
        " · AppID " + bot.appid + if (bot.secret.isBlank()) "（缺 AppSecret）" else ""
}

/**
 * 动作行（两种模式共用）：平时「编辑 | 删除」；点删除后变成「撤销 | 确认」。
 *
 * 两步确认的状态机在调用方（BotsPage 的 confirmingId，同一时刻只允许一张卡待确认），
 * 这里只负责两态的渲染与回调。
 */
@Composable
private fun BotActionRow(
    confirming: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
) {
    PanelPillRow {
        if (confirming) {
            // 「撤销」= 灰底胶囊 + undo 图标（SukiSU 模块卡片那颗撤销）
            PanelIconPillButton(
                text = "撤销",
                icon = MiuixIcons.Undo,
                onClick = onCancelDelete,
                modifier = Modifier.weight(1f),
            )
            // 「确认」= 红色实心，比软底更显眼
            PanelDangerSolidButton(
                text = "确认",
                onClick = onConfirmDelete,
                modifier = Modifier.weight(1f),
            )
        } else {
            PanelSecondaryButton(
                text = "编辑",
                onClick = onEdit,
                modifier = Modifier.weight(1f),
            )
            PanelIconPillButton(
                text = "删除",
                icon = MiuixIcons.Delete,
                danger = true,
                onClick = onDelete,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
