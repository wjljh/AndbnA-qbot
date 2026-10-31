package com.wjljh.andbna.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wjljh.andbna.local.ResourceSnapshot
import com.wjljh.andbna.local.ResourceStats
import com.wjljh.andbna.ui.component.PanelGroupCard
import com.wjljh.andbna.ui.component.PanelInfoRow
import com.wjljh.andbna.ui.component.PanelMetrics
import com.wjljh.andbna.ui.component.PanelTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text

/** 刷新周期：5 秒（采样本身再花 ~0.3s，所以按实际耗时扣掉，保持节拍）。 */
private const val REFRESH_MS = 5000L

/**
 * 状态页「资源占用」卡（只在 root 模式显示，由调用方判断）。
 *
 * 版式照旧版模块控制台：标题「资源占用」
 * → CPU / 内存 各一行「左标签 + 右百分比」+ 一条进度条
 * → 机器人占用 / 代理占用 两个读数行。
 * 刷新固定 5 秒，标题右边不再挂「每 5 秒刷新」的小字。
 *
 * 数据只从 [ResourceStats] 取，读不到就是 null，这里一律显示「—」，不显示 0、不崩。
 * 轮询挂在 [LaunchedEffect] 上：页面不可见 / 卡离开组合 → 协程取消，采样立刻停，
 * 不占常驻线程。
 */
@Composable
fun ResourceUsageCard(visible: Boolean) {
    val ctx = LocalContext.current
    var snap by remember { mutableStateOf(ResourceSnapshot()) }

    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        while (true) {
            val started = SystemClock.elapsedRealtime()
            snap = withContext(Dispatchers.IO) { ResourceStats.sample(ctx) }
            val spent = SystemClock.elapsedRealtime() - started
            delay((REFRESH_MS - spent).coerceAtLeast(0L))
        }
    }

    PanelGroupCard {
        Text(
            text = "资源占用",
            fontSize = PanelMetrics.CardTitleSize,
            fontWeight = FontWeight.Medium,
            color = PanelColors.onSurface,
        )

        UsageMeter(
            label = if (snap.cpuIsApp) "CPU（本应用）" else "CPU",
            valueText = pctText(snap.cpuPct),
            fraction = snap.cpuPct,
            caption = if (snap.cpuIsApp) "整机 CPU 读数被系统拦下，这里显示本应用 CPU" else null,
        )
        UsageMeter(
            label = "内存",
            valueText = pctText(snap.memPct),
            fraction = snap.memPct,
            caption = if (snap.memTotalMb > 0L) {
                "已用 " + gbText(snap.memUsedMb) + " / " + gbText(snap.memTotalMb)
            } else {
                null
            },
        )

        Column(modifier = Modifier.padding(top = PanelMetrics.ContentGap)) {
            PanelInfoRow(
                icon = Icons.Filled.Memory,
                title = "机器人占用",
                value = mbText(snap.botPssMb),
            )
            PanelInfoRow(
                icon = Icons.Filled.Build,
                title = "代理占用",
                value = mbText(snap.proxyRssMb),
                last = true,
            )
        }
    }
}

/**
 * 一行「左标签 / 右百分比 + 下方进度条」。
 *
 * 进度条只用库组件（material3 的 LinearProgressIndicator），不自绘；
 * fraction 为 null（读不到）时条子停在 0，右边显示「—」。
 */
@Composable
private fun UsageMeter(
    label: String,
    valueText: String,
    fraction: Int?,
    caption: String?,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = PanelMetrics.ContentGap)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                fontSize = PanelMetrics.BodySize,
                color = PanelColors.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueText,
                fontSize = PanelMetrics.BodySize,
                fontWeight = FontWeight.Medium,
                color = PanelColors.onSurface,
            )
        }
        LinearProgressIndicator(
            progress = { (fraction ?: 0).coerceIn(0, 100) / 100f },
            modifier = Modifier.fillMaxWidth().padding(top = PanelTokens.RowSpacing),
            color = PanelColors.primary,
            trackColor = PanelColors.surfaceContainerHigh,
            gapSize = 0.dp,
            // M3 新版会在末端画一个停顿圆点 + 留缺口：两样都关掉，只要一条干净的圆角条
            drawStopIndicator = {},
            strokeCap = StrokeCap.Round,
        )
        if (caption != null) {
            Text(
                text = caption,
                fontSize = PanelMetrics.HintSize,
                color = PanelColors.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = PanelTokens.InfoValueGap),
            )
        }
    }
}

private fun pctText(v: Int?): String = (if (v == null) "—" else v.toString()) + "%"

private fun mbText(v: Int?): String = if (v == null) "—" else v.toString() + " MB"

/** MB 转文案：够 1 GB 就按 GB 显示一位小数，否则 MB。 */
private fun gbText(mb: Long): String =
    if (mb >= 1024L) "%.1f GB".format(mb / 1024.0) else mb.toString() + " MB"
