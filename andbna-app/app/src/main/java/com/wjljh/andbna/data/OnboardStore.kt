package com.wjljh.andbna.data

import android.content.Context
import com.wjljh.andbna.BuildConfig

/**
 * 引导的两个落盘标记（release / debug 一样）。
 *
 *  · [seenVc]（键 onboard_seen_vc，Int）= 上一次走完引导时的 versionCode；
 *    老版本没有这个键 → 0。这是**唯一**的判定依据。
 *  · [done]（旧键 onboarded，Bool）= 升级前就有的标记，只为兼容继续写；
 *    有这个键不代表「这一版看过引导」。
 *
 * 判定按版本走：[required] = 记下的 versionCode < 当前 BuildConfig.VERSION_CODE。
 *  · 首次安装 0 < 1040 → 进引导；
 *  · 老用户从 1040 升到 1050 → 再进一次（只是多走一遍，已有 bot / 模型 / 群配置原样保留，
 *    引导里可跳过的步骤照样能跳过）；
 *  · 同一版本第二次打开：相等 → 不进。
 */
object OnboardStore {
    private const val PREF = "andbna_onboard"
    private const val KEY = "onboarded"
    private const val KEY_SEEN_VC = "onboard_seen_vc"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 上一次走完引导时的 versionCode；没走过 / 老版本没这个键 → 0 */
    fun seenVc(ctx: Context): Int = sp(ctx).getInt(KEY_SEEN_VC, 0)

    /** 旧标记（升级前那套）；判定不再用它，只留给兼容代码读 */
    fun done(ctx: Context): Boolean = sp(ctx).getBoolean(KEY, false)

    /** 走完 / 跳过引导：把当前版本的 versionCode 记下来（旧键 onboarded 一并写） */
    fun markDone(ctx: Context) {
        sp(ctx).edit()
            .putBoolean(KEY, true)
            .putInt(KEY_SEEN_VC, BuildConfig.VERSION_CODE)
            .apply()
    }

    /** 「重看引导」：两个键都清掉回引导，引导结束时 markDone 再写回当前版本 */
    fun reset(ctx: Context) {
        sp(ctx).edit().remove(KEY).remove(KEY_SEEN_VC).apply()
    }

    /** 这次启动是否该进引导：记下的版本比当前小（含首次安装的 0）就进一次 */
    fun required(ctx: Context): Boolean = seenVc(ctx) < BuildConfig.VERSION_CODE

    val available: Boolean get() = true
}
