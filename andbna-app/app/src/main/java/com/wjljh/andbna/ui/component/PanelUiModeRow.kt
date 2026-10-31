package com.wjljh.andbna.ui

import android.content.Context

/**
 * 「界面模式」下拉的取值 / 写入三个小函数。
 *
 * 行本身不再由这个文件渲染：miuix 走 OverlayDropdownPreference、material 走
 * SegmentedDropdownItem，各自写在设置页对应结构里（SukiSU 也是两套各写一份）。
 * 这里只留两种结构都要用的那份换算，避免两边各写一遍字符串。
 */
fun uiModeLabels(): List<String> = UiMode.entries.map { it.display }

fun uiModeIndex(mode: UiMode): Int = UiMode.entries.indexOf(mode).coerceAtLeast(0)

fun setUiMode(ctx: Context, index: Int) {
    UiMode.entries.getOrNull(index)?.let { UiModeStore.set(ctx, it) }
}
