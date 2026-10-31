package com.wjljh.andbna.data

import android.content.Context

/**
 * 首次使用引导的落盘标记（release / debug 都一样）。
 *
 *  · [required] = 还没写过 onboarded=true —— 正式包首次安装会进引导；
 *    老用户（升级前就在用、prefs 里已有 onboarded=true）不受影响；
 *    从更老的版本升上来、从来没有这个标记的，会看到一次引导。
 *  · [available] = 恒 true —— 设置页「重看使用引导」在正式包也能用。
 */
object OnboardStore {
    private const val PREF = "andbna_onboard"
    private const val KEY = "onboarded"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun done(ctx: Context): Boolean = sp(ctx).getBoolean(KEY, false)

    fun markDone(ctx: Context) {
        sp(ctx).edit().putBoolean(KEY, true).apply()
    }

    /** 「重看引导」：清标记，回引导（引导结束时再写回 true） */
    fun reset(ctx: Context) {
        sp(ctx).edit().remove(KEY).apply()
    }

    /** 这次启动是否该进引导 */
    fun required(ctx: Context): Boolean = !done(ctx)

    val available: Boolean get() = true
}
