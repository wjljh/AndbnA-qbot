package com.wjljh.andbna.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.wjljh.andbna.ui.LocalUiMode
import com.wjljh.andbna.ui.UiMode
import com.wjljh.andbna.ui.component.material.TonalCard
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/** 面板状态色：与 kit 里既有的绿/红一致。 */
private val OkColor = Color(0xFF36D167)

/**
 * 设置页的「面板」信息卡：只读显示当前地址与 TOKEN 配置状态。
 * Miuix 模式用 kit 的 miuix Card，Material 模式用 kit 的 [TonalCard]，两种模式都不自造样式。
 */
@Composable
fun PanelInfoCard(
    title: String,
    baseUrl: String,
    configured: Boolean,
    rows: List<Pair<String, String>> = emptyList(),
    modifier: Modifier = Modifier,
) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> PanelInfoCardMiuix(title, baseUrl, configured, rows, modifier)
        UiMode.Material -> PanelInfoCardMaterial(title, baseUrl, configured, rows, modifier)
    }
}

@Composable
private fun PanelInfoCardMiuix(
    title: String,
    baseUrl: String,
    configured: Boolean,
    rows: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(PanelTokens.CardInside),
            verticalArrangement = Arrangement.spacedBy(PanelTokens.RowSpacing),
        ) {
            MiuixText(
                text = title,
                style = MiuixTheme.textStyles.headline1,
                color = colorScheme.onBackground,
            )
            InfoRowMiuix("面板地址", baseUrl)
            InfoRowMiuix(
                "TOKEN",
                if (configured) "已配置" else "未配置",
                if (configured) OkColor else PanelTokens.ErrorColor,
            )
            rows.forEach { (label, value) -> InfoRowMiuix(label, value) }
        }
    }
}

@Composable
private fun InfoRowMiuix(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        MiuixText(
            text = label,
            style = MiuixTheme.textStyles.body2,
            color = colorScheme.onBackground.copy(alpha = 0.7f),
        )
        MiuixText(
            text = value,
            style = MiuixTheme.textStyles.body2,
            color = if (valueColor == Color.Unspecified) colorScheme.onBackground else valueColor,
        )
    }
}

@Composable
private fun PanelInfoCardMaterial(
    title: String,
    baseUrl: String,
    configured: Boolean,
    rows: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    TonalCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(PanelTokens.CardInside),
            verticalArrangement = Arrangement.spacedBy(PanelTokens.RowSpacing),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            InfoRowMaterial("面板地址", baseUrl)
            InfoRowMaterial(
                "TOKEN",
                if (configured) "已配置" else "未配置",
                if (configured) OkColor else PanelTokens.ErrorColor,
            )
            rows.forEach { (label, value) -> InfoRowMaterial(label, value) }
        }
    }
}

@Composable
private fun InfoRowMaterial(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (valueColor == Color.Unspecified) MaterialTheme.colorScheme.onSurface else valueColor,
        )
    }
}
