package com.wjljh.andbna.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wjljh.andbna.BuildConfig
import com.wjljh.andbna.R
import com.wjljh.andbna.local.BotHost
import com.wjljh.andbna.local.UpdateDownloadService
import com.wjljh.andbna.local.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Settings

/**
 * 「关于」二级页（设置页最后那一行进来）。
 *
 * 版式：细线返回箭头 + 独占一行的「关于」大标题（与主题设置页同一个顶栏）
 * → 应用图标 + 名称 + 版本名/版本号 + 构建类型 → 一段介绍 → 技术信息 → 开源致谢 → 许可证说明。
 *
 * 技术信息一律现读，读不到的写「未知」，不留写死的常量（版本号不从代码里抄）。
 */
@Composable
fun AboutScreen(metrics: Metrics, onBack: () -> Unit) {
    val ctx = LocalContext.current
    // 包信息 / 设备信息是 IO，不在组合期读
    var info by remember { mutableStateOf<AboutInfo?>(null) }
    LaunchedEffect(Unit) {
        info = withContext(Dispatchers.IO) { readAbout(ctx) }
    }

    // 自动检查更新：开关落盘（默认关）。打开后启动时最短 30 分钟才查一次，
    // 发现新版本在状态页 hero 下面出一张提示卡；「立即检查」在下一行（无视 30 分钟节流）。
    var autoCheck by remember { mutableStateOf(Updater.autoEnabled(ctx)) }

    // 「立即检查」：无视 30 分钟节流（Updater.checkNow 仍写 last_check_at），结论写在这一行的小字里
    var checkNote by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    BackHandler { onBack() }

    Scaffold(
        containerColor = PanelColors.surface,
        topBar = { AboutHeader(onBack) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            PageColumn(metrics) {
                val about = info ?: AboutInfo.EMPTY

                // ① 图标 + 名称 + 版本 + 构建类型
                SectionCard(metrics) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        LauncherIcon(76.dp)
                        Text(
                            text = "AndbnA",
                            fontSize = 22.sp,
                            fontWeight = FontWeight(600),
                            color = PanelColors.onBackground,
                        )
                        Text(
                            text = "版本 " + about.versionName + "（" + about.versionCode + "）",
                            fontSize = 14.sp,
                            color = PanelColors.onSurfaceVariantSummary,
                        )
                        Text(
                            text = "构建类型 " + about.buildType,
                            fontSize = 13.sp,
                            color = PanelColors.onSurfaceVariantSummary,
                        )
                    }
                }

                // ② 自动检查更新：图标整体卡片的**下方**，独立一行（只一个开关，不放任何说明文字）
                SectionCard(metrics) {
                    SuperRow(
                        icon = MiuixIcons.Settings,
                        title = "自动检查更新",
                        trailing = {
                            Switch(checked = autoCheck, onCheckedChange = {
                                autoCheck = it
                                Updater.setAutoEnabled(ctx, it)
                            })
                        },
                    )
                }

                // ②-2 立即检查：一行小字动作，忽略 30 分钟节流；查到的结论就地显示，
                // 并立刻接管状态页那张提示卡（UpdateRuntime），不用回去重进
                SectionCard(metrics) {
                    SuperRow(
                        icon = Icons.Filled.Refresh,
                        title = "立即检查",
                        subtitle = checkNote.ifBlank { "忽略 30 分钟节流，马上查一次更新" },
                        onClick = {
                            if (!checking) {
                                checking = true
                                checkNote = "检查中…"
                                scope.launch {
                                    val c = Updater.checkNow(ctx, BuildConfig.VERSION_NAME)
                                    checkNote = when (val r = c.result) {
                                        is Updater.Result.Newer -> {
                                            UpdateRuntime.found(r.release)
                                            c.note
                                        }
                                        Updater.Result.Latest -> c.note
                                        Updater.Result.Failed -> "检查失败（" + c.note + "）"
                                    }
                                    checking = false
                                }
                            }
                        },
                    )
                }

                // ③ 这是什么 App
                SectionCard(metrics) {
                    Text(
                        text = "本机 QQ 机器人托管面板",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                        color = PanelColors.onBackground,
                    )
                    Text(
                        text = "QQ 机器人的 Python 运行时打包在 App 里，由本机前台服务托管，不用另装模块。" +
                            "支持多个 bot 同时在线、按 bot 选人格、长期记忆，以及定时消息。",
                        fontSize = 14.sp,
                        color = PanelColors.onSurfaceVariantSummary,
                    )
                }

                // ③ 技术信息
                SectionCard(metrics, title = "技术信息", gap = 0.dp) {
                    InfoRow("Android 版本", about.android)
                    InfoRow("设备型号", about.device)
                    InfoRow("构建类型", about.buildType)
                    InfoRow("构建时间", about.buildTime)
                    InfoRow("Python", about.python)
                    InfoRow("Chaquopy", about.chaquopy)
                }

                // ④ 开源致谢（许可证说明在下一段）
                SectionCard(metrics, title = "开源致谢", gap = 0.dp) {
                    CreditRow("KernelSU-Style-UI-Kit", "胶囊底栏与液体玻璃 · GPL-3.0")
                    CreditRow("Miuix（miuix-kmp）", "组件与主题 · Apache-2.0")
                    CreditRow("DGP-Studio / Genshin.Skill", "内置人格文本来源 · MIT")
                    CreditRow("linsport / dsh-whale-persona", "鲸鱼娘人格 · MIT")
                    CreditRow("JetBrains / compose-multiplatform", "引导页结构与动效参考（Apache-2.0）")
                }

                // ⑤ 许可证说明
                SectionCard(metrics, title = "许可证说明") {
                    Text(
                        text = "上表组件的著作权归各自作者，许可证以各自仓库声明为准。" +
                            "本 App 只按结构与取值的规格复刻界面，未打包第三方源码。",
                        fontSize = 13.sp,
                        color = PanelColors.onSurfaceVariantSummary,
                    )
                }

                // ⑥ 成本口径（hero 上只写「余额差值估算」，整句挂在页面最底部，10sp 小字）
                Text(
                    text = COST_NOTE,
                    fontSize = 10.sp,
                    lineHeight = 15.sp,
                    color = PanelColors.onSurfaceVariantSummary.copy(alpha = 0.8f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }
        }
    }

}

/** 成本金额的口径说明：hero 只写「约 ¥x.xx（余额差值估算）」，完整一句挂在这里。 */
internal const val COST_NOTE = "金额按上游余额差值估算，含同一 Key 的其它端消耗"

/** 顶栏：细线返回箭头一行，大标题「关于」独占下一行（与主题设置页同一套）。 */
@Composable
private fun AboutHeader(onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 4.dp, end = 16.dp, bottom = 6.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = MiuixIcons.Back,
                contentDescription = "返回",
                tint = PanelColors.onBackground,
                modifier = Modifier.size(24.dp),
            )
        }
        Text(
            text = "关于",
            fontSize = 28.sp,
            fontWeight = FontWeight(600),
            color = PanelColors.onBackground,
            modifier = Modifier.padding(start = 12.dp, top = 2.dp),
        )
    }
}

/** 应用图标：直接取 mipmap/ic_launcher（自适应图标 XML 也画得出来），画不出来就退回字母块。 */
@Composable
private fun LauncherIcon(size: Dp) {
    val ctx = LocalContext.current
    val bitmap = remember {
        runCatching {
            val d = ctx.getDrawable(R.mipmap.ic_launcher) ?: return@runCatching null
            val px = 180
            val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, px, px)
            d.draw(Canvas(bmp))
            bmp.asImageBitmap()
        }.getOrNull()
    }
    val shape = RoundedCornerShape(20.dp)
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "应用图标",
            modifier = Modifier.size(size).clip(shape),
        )
    } else {
        Box(
            modifier = Modifier.size(size).clip(shape).background(PanelColors.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text("A", fontSize = 34.sp, fontWeight = FontWeight(600), color = PanelColors.onPrimary)
        }
    }
}

/** 技术信息的一行：左侧标签，右侧取值（取值可能是「未知」，不许编）。 */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, fontSize = 15.sp, color = PanelColors.onSurface)
        Spacer(Modifier.size(12.dp))
        Text(
            text = value,
            fontSize = 14.sp,
            color = PanelColors.onSurfaceVariantSummary,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 致谢的一行：项目名 + 一句它贡献了什么（含许可证）。 */
@Composable
private fun CreditRow(name: String, what: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(text = name, fontSize = 15.sp, color = PanelColors.onSurface)
            Text(
                text = what,
                fontSize = 13.sp,
                color = PanelColors.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 关于页要显示的现读信息。 */
private data class AboutInfo(
    val versionName: String,
    val versionCode: Long,
    val buildType: String,
    val buildTime: String,
    val android: String,
    val device: String,
    val python: String,
    val chaquopy: String,
) {
    companion object {
        val EMPTY = AboutInfo("未知", 0L, "未知", "未知", "未知", "未知", "未知", "未知")
    }
}

private fun readAbout(ctx: Context): AboutInfo {
    val pm = ctx.packageManager
    val pi = runCatching {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(ctx.packageName, 0)
    }.getOrNull()
    val versionName = pi?.versionName ?: "未知"
    val versionCode = when {
        pi == null -> 0L
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> pi.longVersionCode
        else -> {
            @Suppress("DEPRECATION")
            pi.versionCode.toLong()
        }
    }
    val flags = ctx.applicationInfo?.flags ?: 0
    val buildType = if (flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) "debug" else "release"
    val android = Build.VERSION.RELEASE + "（API " + Build.VERSION.SDK_INT + "）"
    val device = (Build.MANUFACTURER.orEmpty() + " " + Build.MODEL.orEmpty()).trim()
    return AboutInfo(
        versionName = versionName,
        versionCode = versionCode,
        buildType = buildType,
        buildTime = buildTime(pi),
        android = android,
        device = device.ifBlank { "未知" },
        python = pythonVersion(),
        chaquopy = chaquopyVersion(),
    )
}

/** 构建时间：取包安装/更新时刻（PackageInfo.lastUpdateTime），读不到就「未知」。 */
private fun buildTime(pi: android.content.pm.PackageInfo?): String {
    val t = pi?.lastUpdateTime ?: 0L
    if (t <= 0L) return "未知"
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
    return fmt.format(java.util.Date(t))
}

/**
 * Python 版本：走现有 Python 桥问解释器自己（bot_runner.python_version → platform.python_version()）。
 *
 * 解释器没起来就不显示任何数字：写「未知（Python 未初始化）」——绝不显示 0.0，也不为了一行
 * 版本号去把 Python 拉起来。
 */
private fun pythonVersion(): String {
    val v = runCatching { BotHost.pythonVersion() }.getOrDefault("")
    return v.ifBlank { "未知（Python 未初始化）" }
}

/**
 * Chaquopy 版本：构建时按根构建脚本里声明的插件版本注入 BuildConfig.CHAQUOPY_VERSION
 * （运行时依赖 com.chaquo.python.runtime:chaquopy_java 与插件同版本）；取不到就是「未知」。
 */
private fun chaquopyVersion(): String = runCatching { BuildConfig.CHAQUOPY_VERSION }.getOrDefault("")
    .ifBlank { "未知" }
