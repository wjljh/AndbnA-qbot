package com.wjljh.andbna.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

enum class UiMode(val value: String, val display: String) {
    // value 是落盘值，display 才是界面上给用户看的字样（一律 MiuiX）
    Miuix("miuix", "MiuiX"),
    Material("material", "Material");

    companion object {
        fun fromValue(value: String): UiMode = when (value) {
            Material.value -> Material
            else -> Miuix
        }

        val DEFAULT_VALUE = Miuix.value
    }
}

val LocalUiMode = staticCompositionLocalOf { UiMode.Miuix }

/**
 * 界面模式持久化 + 全局可观察状态。
 *
 * 为什么用 Compose State 而不是只读 SharedPreferences：
 * 设置页切换后要**立即重组**（不重启 App），所以模式必须以 State 暴露；
 * 落盘只负责下次冷启动的初值，由 [init] 在 Activity.onCreate 里灌进来。
 */
object UiModeStore {
    private const val PREFS = "andbna_local"
    private const val KEY = "ui_mode"

    var current: UiMode by mutableStateOf(UiMode.Miuix)
        private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        current = UiMode.fromValue(prefs(ctx).getString(KEY, UiMode.DEFAULT_VALUE).orEmpty())
    }

    fun set(ctx: Context, mode: UiMode) {
        current = mode
        prefs(ctx).edit().putString(KEY, mode.value).apply()
    }
}
