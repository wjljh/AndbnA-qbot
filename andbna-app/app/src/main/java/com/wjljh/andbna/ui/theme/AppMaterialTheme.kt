package com.wjljh.andbna.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * material3 那一支主题：Material You。
 *
 * 取色规则与系统一致：API 31+ 读壁纸动态色（dynamic*ColorScheme），
 * 低版本回落到 material3 基线色板（不自己编色，免得跟系统设置打架）。
 *
 * 注意这里**不负责** miuix 那一套：外层 [com.wjljh.andbna.ui.AndbnATheme]
 * 会按 LocalUiMode 决定最终挂哪一支（或两支都挂，见那里的注释）。
 */
private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
)

/** 当前系统深色态对应的 material3 色板（动态取色优先）。 */
@Composable
fun rememberAppMaterialScheme(dark: Boolean = isSystemInDarkTheme()): ColorScheme {
    val ctx = LocalContext.current
    return remember(ctx, dark) { appMaterialScheme(ctx, dark) }
}

fun appMaterialScheme(ctx: Context, dark: Boolean): ColorScheme =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }

/** material 模式的根包裹：MaterialTheme（色板 + 字号）。 */
@Composable
fun AppMaterialTheme(
    colorScheme: ColorScheme? = null,
    content: @Composable () -> Unit,
) {
    val scheme = colorScheme ?: rememberAppMaterialScheme()
    MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
}
