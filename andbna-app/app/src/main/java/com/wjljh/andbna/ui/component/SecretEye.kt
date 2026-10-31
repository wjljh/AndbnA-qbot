package com.wjljh.andbna.ui.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import com.wjljh.andbna.ui.PanelColors

/**
 * 密钥输入框的「显示/隐藏」眼睛按钮（三处密钥框共用：全局 API Key、bot 的 AppSecret、面板 TOKEN）。
 * 默认掩码，点一下才明文 —— 只看一次，不改变已保存的值。
 */
@Composable
fun SecretEyeButton(revealed: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            imageVector = if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
            contentDescription = if (revealed) "隐藏密钥" else "显示密钥",
            tint = PanelColors.onSurfaceVariantSummary,
        )
    }
}
