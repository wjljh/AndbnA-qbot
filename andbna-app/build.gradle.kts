plugins {
    id("com.android.application") version "9.4.1" apply false
    // AGP 9 起内置 Kotlin 支持，不再需要 org.jetbrains.kotlin.android 插件
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("com.chaquo.python") version "17.1.0" apply false
}
