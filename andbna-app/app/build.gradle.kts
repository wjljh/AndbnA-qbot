plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

android {
    namespace = "com.wjljh.andbna"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        // 与旧版 com.wjljh.andbna.panel.debug 并存：用独立包名，签名不同也不冲突
        applicationId = "com.wjljh.andbna.qbot"
        minSdk = 26
        targetSdk = 37
        versionCode = 1020
        versionName = "1.0.2"

        // Chaquopy 版本：插件版本就是运行时依赖（com.chaquo.python.runtime:chaquopy_java）的版本，
        // 从根构建脚本的插件声明里读，避免在源码里再抄一份常量。
        val chaquopyVersion: String = runCatching {
            Regex("id\\(\\s*\"com\\.chaquo\\.python\"\\s*\\)\\s*version\\s*\"([^\"]+)\"")
                .find(rootProject.file("build.gradle.kts").readText())
                ?.groupValues?.get(1)
        }.getOrNull().orEmpty()
        buildConfigField("String", "CHAQUOPY_VERSION", "\"" + chaquopyVersion + "\"")

        // 只出 arm64-v8a（设备为 arm64 Android 17）
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("andbna") {
            // 用户提供的新签名（PKCS12，CN=AndbnA，有效期到 2126-10-29）。旧 andbna-qbot.jks 已不再使用。
            storeFile = rootProject.file("../signing/wjljh-andbna_sign.jks")
            storeType = "PKCS12"
            // 口令不入库：写在 ~/.gradle/gradle.properties 或环境变量里
            storePassword = (providers.gradleProperty("andbnaStorePassword")
                .orElse(providers.environmentVariable("ANDBN_STORE_PASSWORD")).orNull
                ?: error("缺少签名口令：请在 ~/.gradle/gradle.properties 里设置 andbnaStorePassword"))
            keyAlias = "andbna"
            keyPassword = (providers.gradleProperty("andbnaKeyPassword")
                .orElse(providers.environmentVariable("ANDBN_KEY_PASSWORD")).orNull
                ?: error("缺少签名口令：请在 ~/.gradle/gradle.properties 里设置 andbnaKeyPassword"))
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("andbna")
            isMinifyEnabled = false
        }
        release {
            signingConfig = signingConfigs.getByName("andbna")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        aidl = true
        // 关于页要读真实依赖版本（下面 CHAQUOPY_VERSION 靠它进 BuildConfig）
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        // miuix-nav 的 NavDisplay/entry 是 JVM 21 编的内联函数，本模块必须同 target 才能内联
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
}

/**
 * debug 变体的独立包名：com.wjljh.andbna.debug.qbot（与 release 的 com.wjljh.andbna.qbot 并存，
 * 两者数据互不影响）。注意是 ...debug.qbot，不是 ...qbot.debug。
 *
 * AGP 9 把 buildType 级的 applicationId（和 applicationIdSuffix）整个删掉了，buildTypes.debug 里
 * 写 applicationId 会「Unresolved reference」——只能从 Variant API 改，且任务名与产物路径不变
 * （仍是 :app:assembleDebug → app/build/outputs/apk/debug/app-debug.apk）。
 */
androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.applicationId.set("com.wjljh.andbna.debug.qbot")
    }
}

chaquopy {
    defaultConfig {
        // 设备端是 3.13，Chaquopy 强制 buildPython 必须同版本
        version = "3.13"
        buildPython("/home/wjljh/toolchain/python313/python/bin/python3")
        pip {
            install("qq-botpy==1.2.1")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3:1.5.0-beta01")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.4")
    // 设置行组件（ArrowPreference / SwitchPreference / OverlayDropdownPreference）
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4")
    // kit 的 FloatingBottomBar（液体玻璃胶囊底栏）依赖 miuix 的 blur 模块
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4")
    // 二级页转场：miuix-nav 的 NavDisplay / NavDisplayEffects（与 SukiSU 同一支）
    implementation("top.yukonga.miuix.kmp:miuix-nav-android:0.9.4")
    // 常规行图标（SukiSU 也是 Compose + Miuix 图标混用）
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    // hero 状态图标要与 SukiSU 一致（Icons.Rounded.CheckCircleOutline）：圆环+勾，
    // 不是 miuix 的裸勾。版本与上面 core 对齐，R8 会裁掉没引用到的图标
    implementation("androidx.compose.material:material-icons-extended:1.7.8")

    // 主题设置页（kit 的 ColorPaletteScreenMiuix）：PaletteStyle / ColorSpec / rememberDynamicColorScheme
    implementation("com.materialkolor:material-kolor:4.1.1")
    // 主题页的 collectAsStateWithLifecycle / dropUnlessResumed / viewModel()
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")

    // Shizuku：ADB 模式靠它把命令提到 shell 权限去跑（不需要 root）
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
