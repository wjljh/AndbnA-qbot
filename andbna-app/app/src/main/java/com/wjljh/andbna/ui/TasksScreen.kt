package com.wjljh.andbna.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wjljh.andbna.local.BotHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 定时消息二级页。
 *
 * 任务表存在 files/localbot/tasks.json，调度在 Python 侧每个 bot 自己的
 * 事件循环里跑（每 30 秒扫一次），所以关掉这个页面也在照常发。
 */
@Composable
fun TasksScreen(metrics: Metrics, appid: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var tasks by remember { mutableStateOf(listOf<JSONObject>()) }
    var tick by remember { mutableStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    // 添加表单
    var target by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var hour by remember { mutableStateOf("9") }
    var minute by remember { mutableStateOf("0") }

    fun reload() {
        scope.launch {
            val raw = withContext(Dispatchers.IO) { BotHost.taskList(ctx) }
            tasks = runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                    .filter { it.optString("appid") == appid }
            }.getOrDefault(emptyList())
        }
    }

    fun save(list: List<JSONObject>, done: String) {
        scope.launch {
            val raw = withContext(Dispatchers.IO) { BotHost.taskList(ctx) }
            val all = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
            // 去掉这个 bot 的旧记录，再把新的拼回去
            val merged = JSONArray()
            for (i in 0 until all.length()) {
                val o = all.optJSONObject(i) ?: continue
                if (o.optString("appid") != appid) merged.put(o)
            }
            list.forEach { merged.put(it) }
            val r = withContext(Dispatchers.IO) { BotHost.taskSave(ctx, merged.toString()) }
            msg = r.ifBlank { done }
            tick++
        }
    }

    LaunchedEffect(tick) { reload() }

    BackHandler { onBack() }

    Scaffold(
        containerColor = PanelColors.surface,
        topBar = {
            SmallTopAppBar(
                title = "定时消息",
                subtitle = appid,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text(
                            "\u2039",
                            fontSize = 30.sp,
                            color = PanelColors.onBackground,
                        )
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()),
        ) {
            PageColumn(metrics) {
                if (msg.isNotBlank()) Note(msg)

                SectionCard(metrics, gap = 2.dp) {
                    ArrowPreference(
                        insideMargin = prefInside,
                        title = "添加一条",
                        summary = "到点自动发到群或私聊",
                        startAction = {
                            RowIcon(Icons.Rounded.Add, PanelColors.onSurfaceVariantSummary)
                        },
                        onClick = {
                            target = ""
                            text = ""
                            adding = true
                        },
                    )
                }

                SectionCard(metrics, title = "已设置") {
                    if (tasks.isEmpty()) {
                        Text(
                            "还没有。加一条就是每天固定时刻发一条消息。",
                            fontSize = 14.sp,
                            color = PanelColors.onSurfaceVariantSummary,
                        )
                    } else {
                        tasks.forEach { t ->
                            val hhmm = runCatching {
                                val due = t.optDouble("next", 0.0)
                                if (due <= 0) "—"
                                else java.text.SimpleDateFormat("HH:mm")
                                    .format(java.util.Date((due * 1000).toLong()))
                            }.getOrDefault("—")
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            ) {
                                androidx.compose.material3.Icon(
                                    Icons.Rounded.Notifications,
                                    contentDescription = null,
                                    tint = PanelColors.onSurfaceVariantSummary,
                                )
                                Spacer(Modifier.padding(end = 10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        t.optString("text").ifBlank { "（空内容）" },
                                        fontSize = 16.sp,
                                        color = PanelColors.onBackground,
                                    )
                                    Text(
                                        "$hhmm · ${t.optString("target").take(12)}…",
                                        fontSize = 13.sp,
                                        color = PanelColors.onSurfaceVariantSummary,
                                    )
                                }
                                TextButton(
                                    text = "删除",
                                    onClick = { save(tasks.filter { it !== t }, "已删除") },
                                    colors = ButtonDefaults.textButtonColors(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (adding) {
        OverlayDialog(
            show = true,
            title = "添加定时消息",
            onDismissRequest = { adding = false },
        ) {
            Column(Modifier.fillMaxWidth()) {
                TextField(
                    value = target,
                    onValueChange = { target = it },
                    label = "目标 openid（群 g 开头 / 私聊 p 开头）",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    label = "要发的内容",
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = hour,
                        onValueChange = { hour = it },
                        label = "时",
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.padding(horizontal = 6.dp))
                    TextField(
                        value = minute,
                        onValueChange = { minute = it },
                        label = "分",
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(16.dp))
                TextButton(
                    text = "保存",
                    onClick = {
                        val h = hour.toIntOrNull()?.coerceIn(0, 23) ?: 9
                        val m = minute.toIntOrNull()?.coerceIn(0, 59) ?: 0
                        val cal = java.util.Calendar.getInstance().apply {
                            set(java.util.Calendar.HOUR_OF_DAY, h)
                            set(java.util.Calendar.MINUTE, m)
                            set(java.util.Calendar.SECOND, 0)
                        }
                        // 今天这个点已经过了就排到明天
                        if (cal.timeInMillis <= System.currentTimeMillis()) {
                            cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
                        }
                        val t = JSONObject()
                            .put("id", System.currentTimeMillis().toString())
                            .put("appid", appid)
                            .put("kind", if (target.trim().startsWith("p")) "c2c" else "group")
                            .put("target", target.trim())
                            .put("text", text.trim())
                            .put("next", cal.timeInMillis / 1000.0)
                            .put("enabled", true)
                        adding = false
                        save(tasks + t, "已添加，每天 ${"%02d:%02d".format(h, m)} 发")
                    },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                TextButton(
                    text = "取消",
                    onClick = { adding = false },
                    colors = ButtonDefaults.textButtonColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
