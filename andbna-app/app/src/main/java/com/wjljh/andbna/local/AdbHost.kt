package com.wjljh.andbna.local

import android.content.pm.PackageManager

/**
 * ADB 模式：靠 Shizuku 拿到 shell（uid 2000）级别的能力，不需要 root。
 *
 * 现状（诚实说明）：
 *  · 已实现：检测 Shizuku 是否在运行、申请/检查授权、显示版本
 *  · 未实现：以 shell 身份执行任意命令。Shizuku 13.x 的 `newProcess` 不是公开 API，
 *    官方做法是写一个 UserService（AIDL + bindUserService）跑在 shell 进程里。
 *    在那之前，「加电池白名单」「掉了拉回」这两件事只有 root 模式能做。
 *
 * 为什么 ADB 模式无法把 bot 当独立进程跑：Python 解释器打包在 APK 里（Chaquopy），
 * shell 用户没有办法把 APK 里的解释器单独拉起来。所以三种本机模式的差别
 * 只在「谁保证进程活着」，bot 始终跑在 App 进程内。
 */
object AdbHost {

    fun available(): Boolean = try {
        rikka.shizuku.Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }

    fun version(): Int = try {
        rikka.shizuku.Shizuku.getVersion()
    } catch (e: Throwable) {
        0
    }

    fun granted(): Boolean = try {
        rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    fun request(code: Int) {
        try {
            rikka.shizuku.Shizuku.requestPermission(code)
        } catch (_: Throwable) {
        }
    }

    fun summary(): String = when {
        !available() -> "Shizuku 没在运行"
        !granted() -> "Shizuku 在运行，本 App 还没被授权"
        else -> "Shizuku v${version()}，已授权"
    }
}
