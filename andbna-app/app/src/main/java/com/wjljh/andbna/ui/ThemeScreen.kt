package com.wjljh.andbna.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 主题设置页（照 SukiSU 主题设置那张脸排）：
 * 细线返回箭头 + 独占一行的「主题设置」大标题 → 手机预览图 → 三档深浅分段按钮 →
 * 主题色 / 调色风格 → 开关组（只放真的接进渲染链的项，没实现的开关不摆上来）。
 */
@Composable
fun ThemeScreen(metrics: Metrics, onBack: () -> Unit, onChanged: () -> Unit = {}) {
    val ctx = LocalContext.current
    var mode by remember { mutableStateOf(ThemePrefs.ui(ctx, "theme_mode", "system")) }
    var style by remember { mutableStateOf(ThemePrefs.ui(ctx, "theme_style", "tonal")) }
    var color by remember { mutableStateOf(ThemePrefs.ui(ctx, "theme_color", "")) }
    var floatBar by remember { mutableStateOf(ThemePrefs.flag(ctx, "float_bar")) }
    var blur by remember { mutableStateOf(ThemePrefs.flag(ctx, "enable_blur")) }
    var glass by remember { mutableStateOf(ThemePrefs.flag(ctx, "enable_liquid_glass")) }
    val iconTint = PanelColors.onSurfaceVariantSummary

    BackHandler { onBack() }

    Scaffold(
        containerColor = PanelColors.surface,
        topBar = { ThemeHeader(onBack) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            PageColumn(metrics) {
                PhonePreview()

                // 深浅三档：写进现有 theme_mode，AndbnATheme 读 ThemePrefs.observed() 即时重组
                ThemeModeSegments(mode) { k ->
                    mode = k
                    ThemePrefs.put(ctx, "theme_mode", k)
                    onChanged()
                }

                SectionCard(metrics) {
                    OverlayDropdownPreference(
                        insideMargin = prefInside,
                        title = "主题色",
                        startAction = { RowIcon(Icons.Filled.Palette, iconTint) },
                        items = PaletteColor.entries.map { it.label },
                        selectedIndex = PaletteColor.entries
                            .indexOfFirst { it.hex == color }.coerceAtLeast(0),
                        onSelectedIndexChange = { i ->
                            color = PaletteColor.entries[i].hex
                            ThemePrefs.put(ctx, "theme_color", color)
                            onChanged()
                        },
                    )
                    OverlayDropdownPreference(
                        insideMargin = prefInside,
                        title = "调色风格",
                        startAction = { RowIcon(Icons.Filled.Tune, iconTint) },
                        items = STYLE_LABELS.map { it.second },
                        selectedIndex = STYLE_LABELS.indexOfFirst { it.first == style }.coerceAtLeast(0),
                        onSelectedIndexChange = { i ->
                            style = STYLE_LABELS[i].first
                            ThemePrefs.put(ctx, "theme_style", style)
                            onChanged()
                        },
                    )
                }

                // 开关组：三个都在 App.kt 的 CompositionLocalProvider 上真的生效
                SectionCard(metrics, gap = 2.dp) {
                    SwitchPreference(
                        insideMargin = prefInside,
                        title = "悬浮底栏",
                        summary = if (floatBar) "已开启" else "已关闭",
                        checked = floatBar,
                        startAction = { RowIcon(Icons.Filled.Layers, iconTint) },
                        onCheckedChange = {
                            floatBar = it
                            ThemePrefs.putFlag(ctx, "float_bar", it)
                            onChanged()
                        },
                    )
                    SwitchPreference(
                        insideMargin = prefInside,
                        title = "模糊",
                        summary = if (blur) "已开启" else "已关闭",
                        checked = blur,
                        startAction = { RowIcon(Icons.Filled.BlurOn, iconTint) },
                        onCheckedChange = {
                            blur = it
                            ThemePrefs.putFlag(ctx, "enable_blur", it)
                            onChanged()
                        },
                    )
                    SwitchPreference(
                        insideMargin = prefInside,
                        title = "液态玻璃",
                        summary = if (glass) "已开启" else "已关闭",
                        checked = glass,
                        startAction = { RowIcon(Icons.Filled.Opacity, iconTint) },
                        onCheckedChange = {
                            glass = it
                            ThemePrefs.putFlag(ctx, "enable_liquid_glass", it)
                            onChanged()
                        },
                    )
                }
            }
        }
    }
}

/** 顶栏：细线返回箭头一行，大标题「主题设置」独占下一行（无副标题）。 */
@Composable
private fun ThemeHeader(onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 4.dp, end = 16.dp, bottom = 6.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                // Icons.AutoMirrored.Filled.ArrowBack：细线箭头，不是粗 chevron
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = PanelColors.onBackground,
                modifier = Modifier.size(24.dp),
            )
        }
        Text(
            text = "主题设置",
            fontSize = 28.sp,
            fontWeight = FontWeight(600),
            color = PanelColors.onBackground,
            modifier = Modifier.padding(start = 12.dp, top = 2.dp),
        )
    }
}

/** 手机预览图：纯 Compose 画框，不引图片资源。标题条 / 绿色 hero / 内容块 / 底部四个点。 */
@Composable
private fun PhonePreview() {
    val frame = RoundedCornerShape(24.dp)
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .width(156.dp)
                .height(286.dp)
                .clip(frame)
                .background(PanelColors.surfaceContainer)
                .border(1.dp, PanelColors.outline, frame)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 标题条 / 内容块用 onSurface 的半透明叠色：卡片底与页面底都偏暗，
            // 直接拿 surfaceContainerHigh 会糊成一片（浅色模式下同理）。
            Box(
                Modifier.fillMaxWidth().height(14.dp)
                    .clip(RoundedCornerShape(4.dp)).background(PanelColors.onSurface.copy(alpha = 0.16f))
            )
            Box(
                Modifier.fillMaxWidth().height(84.dp)
                    .clip(RoundedCornerShape(12.dp)).background(Color(0xFF36D167))
            )
            Box(
                Modifier.fillMaxWidth().weight(1f)
                    .clip(RoundedCornerShape(12.dp)).background(PanelColors.onSurface.copy(alpha = 0.10f))
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                repeat(4) {
                    Box(
                        Modifier.size(7.dp).clip(CircleShape)
                            .background(PanelColors.onSurfaceVariantSummary.copy(alpha = 0.55f))
                    )
                }
            }
        }
    }
}

/** 三档分段按钮：选中档实心主色，未选透明。 */
@Composable
private fun ThemeModeSegments(selected: String, onPick: (String) -> Unit) {
    val outer = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(outer)
            .background(PanelColors.surfaceContainerHigh)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MODE_LABELS.forEach { (key, label) ->
            val on = key == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) PanelColors.primary else Color.Transparent)
                    .clickable { onPick(key) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 14.sp,
                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                    color = if (on) PanelColors.onPrimary else PanelColors.onSurface,
                )
            }
        }
    }
}

private val MODE_LABELS = listOf(
    "system" to "跟随系统",
    "light" to "浅色",
    "dark" to "深色",
)

private val STYLE_LABELS = listOf(
    "tonal" to "柔和",
    "vibrant" to "鲜艳",
    "neutral" to "低饱和",
)

/** 主题偏好的读写 */
enum class PaletteColor(val hex: String, val argb: Int, val label: String) {
    AUTO("", 0xFF000000.toInt(), "自动"),
    PINK("#FF8FB1", 0xFFFF8FB1.toInt(), "粉色"),
    BLUE("#4C8DFF", 0xFF4C8DFF.toInt(), "蓝色"),
    GREEN("#36D167", 0xFF36D167.toInt(), "绿色"),
    PURPLE("#9B7BFF", 0xFF9B7BFF.toInt(), "紫色"),
    ORANGE("#FF9F43", 0xFFFF9F43.toInt(), "橙色"),
    TEAL("#2BC4C4", 0xFF2BC4C4.toInt(), "青色"),
    RED("#F0564F", 0xFFF0564F.toInt(), "红色"),
    GREY("#8A8A8E", 0xFF8A8A8E.toInt(), "灰色"),
}

object ThemePrefs {
    // 主题偏好改动后自增，让读它的 Composable（AndbnATheme / PanelApp）跟着重组
    private var revision by mutableStateOf(0)

    fun observed(): Int = revision

    fun sp(ctx: android.content.Context) =
        ctx.getSharedPreferences("andbna_local", android.content.Context.MODE_PRIVATE)

    fun ui(ctx: android.content.Context, k: String, d: String = ""): String =
        sp(ctx).getString(k, d).orEmpty()

    /** 开关型偏好（存 "1"/"0"），默认开：默认值与之前写死的行为一致 */
    fun flag(ctx: android.content.Context, k: String, d: Boolean = true): Boolean =
        ui(ctx, k, if (d) "1" else "0") == "1"

    fun putFlag(ctx: android.content.Context, k: String, on: Boolean) {
        put(ctx, k, if (on) "1" else "0")
    }

    fun put(ctx: android.content.Context, k: String, v: String) {
        sp(ctx).edit().putString(k, v).apply()
        revision++
    }
}
