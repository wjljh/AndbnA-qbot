package com.wjljh.andbna.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 进程级 Application Context（kit 的 templateApp 等价物；本工程没有自定义 Application）。
 * 在 MainActivity.onCreate 里赋值。SettingsRepositoryImpl 只用它拿 SharedPreferences，
 * 所以搬运文件只改了这一行的来源。
 */
lateinit var appContext: Context
    private set

fun initAppContext(context: Context) {
    appContext = context.applicationContext
    PrefsWatch.install(appContext)
}

/**
 * "settings" 里的值变化后自增，让读它的 Composable 跟着重组。
 *
 * 主题页的 setter 走的是 kit 原样的 SettingsRepositoryImpl，它不认识 Compose；
 * 这里挂一个 OnSharedPreferenceChangeListener，把「落盘」直接变成「可观察」，
 * 搬运文件一个 setter 都不用改。
 * 监听器必须强引用持有：SharedPreferences 内部用 WeakHashMap 存监听器，lambda 会被回收。
 */
object PrefsWatch {
    private var revision by mutableStateOf(0)

    fun observed(): Int = revision

    private val listener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }

    fun install(ctx: Context) {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(listener)
    }
}
