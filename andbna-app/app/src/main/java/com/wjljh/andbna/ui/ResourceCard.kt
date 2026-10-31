package com.wjljh.andbna.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wjljh.andbna.local.ModeStore
import com.wjljh.andbna.local.RootHost
import com.wjljh.andbna.local.RunMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 资源占用卡，只在 root 模式显示。
 *
 * 默认折叠 —— 不点开就一次 root 命令都不跑，避免每次进主页都拉起 su 进程。
 */
@Composable
fun ResourceCard(metrics: Metrics) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // 非 root 模式整张卡不出现
    val isRoot = remember {
        runCatching { ModeStore.initial(ctx) }
            .getOrNull()?.key == RunMode.entries.firstOrNull { it.key == "root" }?.key
    }
    if (!isRoot) return

    var expanded by remember { mutableStateOf(false) }
    var stats by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var tried by remember { mutableStateOf(false) }

    suspend fun load() {
        busy = true
        stats = withContext(Dispatchers.IO) { RootStats.read() }
        busy = false
        tried = true
    }

    LaunchedEffect(expanded) {
        if (expanded && !tried) load()
    }

    SectionCard(metrics, gap = 2.dp) {
        SwitchPreference(
            insideMargin = prefInside,
            title = "资源占用",
            summary = if (expanded) {
                val mem = stats.firstOrNull { it.first == "内存" }?.second
                mem ?: if (busy) "读取中…" else "点开查看"
            } else {
                "点开看内存 / 负载 / CPU"
            },
            checked = expanded,
            startAction = {
                RowIcon(Icons.Rounded.Info, PanelColors.onSurfaceVariantSummary)
            },
            onCheckedChange = { expanded = it },
        )

        if (expanded) {
            when {
                busy -> Text(
                    "读取中…",
                    fontSize = 14.sp,
                    color = PanelColors.onSurfaceVariantSummary,
                )

                stats.isEmpty() -> Text(
                    "读不到数据（root 授权了吗）",
                    fontSize = 14.sp,
                    color = PanelColors.onSurfaceVariantSummary,
                )

                else -> {
                    stats.forEach { (k, v) -> KeyValue(k, v) }
                    Row {
                        ActionChip("刷新") { scope.launch { load() } }
                    }
                }
            }
        }
    }
}

/** 直接读 /proc，不引第三方库 */
object RootStats {

    fun read(): List<Pair<String, String>> {
        val r = RootHost.sh(
            "cat /proc/meminfo; echo '@@'; cat /proc/loadavg; echo '@@'; nproc; " +
                "echo '@@'; cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null",
            timeoutMs = 6000,
        )
        if (!r.ok) return emptyList()
        val seg = r.out.split("@@")
        if (seg.size < 3) return emptyList()

        val out = ArrayList<Pair<String, String>>()

        // 内存
        val mem = seg[0]
        val totalKb = mem.lineSequence()
            .firstOrNull { it.startsWith("MemTotal:") }
            ?.filter { it.isDigit() }?.toLongOrNull()
        val availKb = mem.lineSequence()
            .firstOrNull { it.startsWith("MemAvailable:") }
            ?.filter { it.isDigit() }?.toLongOrNull()
        if (totalKb != null && availKb != null && totalKb > 0) {
            val usedKb = totalKb - availKb
            val pct = (usedKb * 100 / totalKb).toInt()
            out += "内存" to "${gb(usedKb)} / ${gb(totalKb)}（${pct}%）"
            out += "可用" to gb(availKb)
        }

        // 负载
        val load = seg[1].trim().split(" ").take(3)
        if (load.size == 3) out += "负载" to load.joinToString(" / ")

        // 核数
        seg[2].trim().toIntOrNull()?.let { out += "CPU" to "$it 核" }

        // 温度（有的机器 thermal_zone0 不是 CPU，读不到就不显示）
        seg.getOrNull(3)?.trim()?.toLongOrNull()?.let { t ->
            if (t in 1000..150000) out += "温度" to "%.1f ℃".format(t / 1000.0)
        }

        return out
    }

    private fun gb(kb: Long): String =
        "%.1f GB".format(kb / 1024.0 / 1024.0)
}
