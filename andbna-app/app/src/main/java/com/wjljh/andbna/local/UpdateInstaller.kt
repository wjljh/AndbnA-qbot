package com.wjljh.andbna.local

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.wjljh.andbna.BuildConfig
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * 更新包的校验与安装（三条路径）。
 *
 * 校验四条，与 SukiSU 的 verifyApk() 一致，任意一条不过就发「更新包校验失败」并停手：
 *  ① 包名 == BuildConfig.APPLICATION_ID（本包，debug/release 各自比对各自的 applicationId）；
 *  ② versionCode **严格大于**已安装版本；
 *  ③ ABI 匹配（包内 lib/<abi>/ 与本机 Build.SUPPORTED_ABIS 有交集）；
 *  ④ 签名与已安装版本一致（比对 PackageManager 给出的签名证书）。
 *
 * 安装分三种模式：
 *  · root：`cat apk | pm install -S <size>` —— 走 su 把字节流灌进 pm 的 stdin，
 *    不让 pm 自己去开 App 私有目录（SELinux 会拦）；
 *  · ADB(Shizuku)：同一条命令，字节流经 binder 传文件描述符到 shell 进程再灌进 pm；
 *  · 普通：PackageInstaller 建 session → 写入 → commit；没有「安装未知应用」权限先引导去开，
 *    仍然失败就用 FileProvider + ACTION_VIEW 交给系统安装器。
 */
object UpdateInstaller {

    private const val TAG = "更新"

    /** 完成通知点下来的动作：MainActivity 收到就校验 + 安装 */
    const val ACTION_INSTALL = "com.wjljh.andbna.action.UPDATE_INSTALL"
    const val EXTRA_PATH = "path"

    private const val CHANNEL = UpdateDownloadService.CHANNEL
    private const val ID_RESULT = 4803

    /** 校验结论：ok=false 时 [reason] 直接进通知，写人话 */
    data class Check(val ok: Boolean, val reason: String = "")

    fun verify(ctx: Context, apk: File): Check {
        if (!apk.isFile || apk.length() <= 0L) return Check(false, "安装包不存在或为空")
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val arch = runCatching { pm.getPackageArchiveInfo(apk.absolutePath, flags) }.getOrNull()
            ?: return Check(false, "读不出安装包信息（文件损坏或不是 APK）")
        // ABI/签名都要靠 sourceDir 指向这个文件才算得出来
        arch.applicationInfo?.let {
            it.sourceDir = apk.absolutePath
            it.publicSourceDir = apk.absolutePath
        }

        // ① 包名
        if (arch.packageName != BuildConfig.APPLICATION_ID) {
            return Check(false, "包名不符：" + arch.packageName + " ≠ " + BuildConfig.APPLICATION_ID)
        }

        // ② 版本号必须更高
        val newCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            arch.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            arch.versionCode.toLong()
        }
        val installed = runCatching {
            val self = pm.getPackageInfo(ctx.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) self.longVersionCode
            else {
                @Suppress("DEPRECATION")
                self.versionCode.toLong()
            }
        }.getOrDefault(0L)
        if (newCode <= installed) return Check(false, "版本不高于已安装：$newCode ≤ $installed")

        // ③ ABI
        val abis = apkAbis(apk)
        if (abis.isNotEmpty() && abis.none { Build.SUPPORTED_ABIS.contains(it) }) {
            return Check(false, "ABI 不匹配：包内 " + abis.joinToString("/") + "，本机 " +
                Build.SUPPORTED_ABIS.joinToString("/"))
        }

        // ④ 签名
        val mine = installedSignature(pm, ctx.packageName)
        val theirs = archiveSignature(arch)
        if (mine == null || theirs == null) return Check(false, "读不出签名（无法确认是自己签的包）")
        if (!mine.contentEquals(theirs)) return Check(false, "签名与已安装版本不一致")

        return Check(true)
    }

    /** 校验通过才装；不通过就发「更新包校验失败」并停手 */
    fun verifyAndInstall(ctx: Context, apk: File) {
        val c = verify(ctx, apk)
        if (!c.ok) {
            Log.w(TAG, "更新：校验失败（" + c.reason + "）")
            notify(ctx, "更新包校验失败", c.reason)
            return
        }
        Log.i(TAG, "更新：校验通过（" + apk.name + "，" + apk.length() + " 字节）")
        when (ModeStore.get(ctx) ?: RunMode.NORMAL) {
            RunMode.ROOT -> installRoot(ctx, apk)
            RunMode.ADB -> installAdb(ctx, apk)
            RunMode.NORMAL -> installNormal(ctx, apk)
        }
    }

    // ── root ────────────────────────────────────────────────────────────────

    /**
     * `pm install -S <size>` 读 stdin：把 APK 字节从 App 进程灌进 su 里那个 pm。
     * 不带 `-r`：四条校验已保证 versionCode 比已装的新，这是正常升级，不需要强制覆盖。
     */
    private fun installRoot(ctx: Context, apk: File) {
        val size = apk.length()
        Thread({
            var p: Process? = null
            val out = StringBuilder()
            try {
                // timeout 兜底：客户端死了也不留一个卡住的 pm
                p = ProcessBuilder("su", "-c", "exec timeout -s KILL 180 pm install -S " + size)
                    .redirectErrorStream(true)
                    .start()
                val proc = p
                val reader = Thread {
                    runCatching {
                        proc.inputStream.bufferedReader().forEachLine { out.append(it).append('\n') }
                    }
                }
                reader.start()
                runCatching {
                    FileInputStream(apk).use { input -> proc.outputStream.use { input.copyTo(it) } }
                }.onFailure { Log.w(TAG, "更新：写 su stdin 失败（" + it.message + "）") }
                val done = proc.waitFor(180, TimeUnit.SECONDS)
                reader.join(2_000)
                val text = out.toString().trim()
                if (done && text.contains("Success", ignoreCase = true)) {
                    Log.i(TAG, "更新：root 安装已提交（" + text.replace('\n', ' ') + "）")
                } else {
                    Log.w(TAG, "更新：root 安装失败（done=" + done + "，" + text.ifBlank { "无输出" } + "）")
                    notify(ctx, "更新失败", "root 安装失败：" + text.ifBlank { "pm 没有输出" }.take(120))
                }
            } catch (e: Throwable) {
                Log.w(TAG, "更新：root 安装异常（" + (e.message ?: e.javaClass.simpleName) + "）")
                notify(ctx, "更新失败", "root 安装异常：" + (e.message ?: ""))
            } finally {
                runCatching { p?.destroy() }
            }
        }, "update-install-root").start()
    }

    // ── ADB(Shizuku) ────────────────────────────────────────────────────────

    /** 同一条 pm install 命令，字节流经 binder 送进 shell 进程（shell 读不到 App 私有目录） */
    private fun installAdb(ctx: Context, apk: File) {
        Thread({
            try {
                if (!ShizukuShell.ensureForUse(ctx, waitMs = 4_000L)) {
                    Log.w(TAG, "更新：ADB 通道不可用（" + ShizukuShell.lastError + "）")
                    notify(ctx, "更新失败", "Shizuku 通道不可用：" + ShizukuShell.lastError.ifBlank { "没绑定上" })
                    return@Thread
                }
                val text = ShizukuShell.pmInstall(apk, apk.length())
                if (text.contains("Success", ignoreCase = true)) {
                    Log.i(TAG, "更新：ADB 安装已提交（" + text.replace('\n', ' ') + "）")
                } else {
                    Log.w(TAG, "更新：ADB 安装失败（" + text.ifBlank { "无输出" } + "）")
                    notify(ctx, "更新失败", "ADB 安装失败：" + text.ifBlank { "pm 没有输出" }.take(120))
                }
            } catch (e: Throwable) {
                Log.w(TAG, "更新：ADB 安装异常（" + (e.message ?: e.javaClass.simpleName) + "）")
                notify(ctx, "更新失败", "ADB 安装异常：" + (e.message ?: ""))
            }
        }, "update-install-adb").start()
    }

    // ── 普通模式 ────────────────────────────────────────────────────────────

    private fun installNormal(ctx: Context, apk: File) {
        Thread({
            val pm = ctx.packageManager
            val allowed = runCatching { pm.canRequestPackageInstalls() }.getOrDefault(true)
            if (!allowed) {
                Log.w(TAG, "更新：没有「安装未知应用」权限，引导去开")
                notify(ctx, "需要允许安装未知应用", "在设置里给本应用打开「安装未知应用」，再点完成通知装一次")
                runCatching {
                    ctx.startActivity(
                        Intent(
                            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + ctx.packageName),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            val committed = runCatching { sessionInstall(ctx, apk) }.getOrElse { e ->
                Log.w(TAG, "更新：PackageInstaller 失败（" + (e.message ?: e.javaClass.simpleName) + "）")
                false
            }
            // 仍失败（有的 ROM 不给 PackageInstaller 机会）→ 交给系统安装器
            if (!committed) openSystemInstaller(ctx, apk)
        }, "update-install-normal").start()
    }

    /** PackageInstaller：建 session → 写满 base.apk → commit（系统会弹自己的确认页） */
    private fun sessionInstall(ctx: Context, apk: File): Boolean {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            runCatching { setSize(apk.length()) }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            val out = session.openWrite("base.apk", 0L, apk.length())
            try {
                FileInputStream(apk).use { it.copyTo(out) }
                out.flush()
                session.fsync(out)
            } finally {
                runCatching { out.close() }
            }
            val report = Intent(ctx, UpdateInstallReceiver::class.java).setAction(ACTION_INSTALL)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(ctx, id, report, flags)
            session.commit(pending.intentSender)
        }
        Log.i(TAG, "更新：PackageInstaller 已提交（session=" + id + "）")
        return true
    }

    /** 最后一条路：FileProvider 给系统安装器一个可读 uri */
    private fun openSystemInstaller(ctx: Context, apk: File) {
        val uri = runCatching {
            FileProvider.getUriForFile(ctx, ctx.packageName + ".updatefp", apk)
        }.getOrElse { e ->
            Log.w(TAG, "更新：拿不到 FileProvider uri（" + e.message + "）")
            notify(ctx, "更新失败", "系统安装器打不开安装包：" + (e.message ?: ""))
            return
        }
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }.onFailure { e ->
            Log.w(TAG, "更新：拉起系统安装器失败（" + e.message + "）")
            notify(ctx, "更新失败", "拉起系统安装器失败：" + (e.message ?: ""))
        }
    }

    // ── 校验用到的小工具 ────────────────────────────────────────────────────

    /** 包里的 ABI 列表：读 zip 的 lib/<abi>/ 目录名（armeabi-v7a 这类带连字符的名字原样返回） */
    private fun apkAbis(apk: File): List<String> = runCatching {
        ZipFile(apk).use { zip ->
            zip.entries().asSequence()
                .map { it.name }
                .filter { it.startsWith("lib/") && it.count { c -> c == '/' } >= 2 }
                .map { it.split('/')[1] }
                .filter { it.isNotBlank() }
                .toSet()
                .toList()
        }
    }.getOrDefault(emptyList())

    private fun installedSignature(pm: PackageManager, pkg: String): ByteArray? = runCatching {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        firstSignature(pm.getPackageInfo(pkg, flags))
    }.getOrNull()

    private fun archiveSignature(info: PackageInfo): ByteArray? = runCatching { firstSignature(info) }.getOrNull()

    private fun firstSignature(info: PackageInfo): ByteArray? {
        val sigs: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.let { si ->
                if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        return sigs?.firstOrNull()?.toByteArray()
    }

    /** 只给日志用：签名的 sha256 前 8 字节（别把整份证书打到日志里） */
    fun fingerprint(sig: ByteArray?): String {
        if (sig == null) return "-"
        return runCatching {
            val d = MessageDigest.getInstance("SHA-256").digest(sig)
            d.take(8).joinToString("") { String.format("%02x", it) }
        }.getOrDefault("-")
    }

    // ── 通知 ────────────────────────────────────────────────────────────────

    fun notify(ctx: Context, title: String, text: String) {
        UpdateDownloadService.ensureChannel(ctx)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .build()
        runCatching {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID_RESULT, n)
        }
    }
}

/** PackageInstaller 的 commit 回执（结果只进日志；成功时进程通常已被系统杀掉） */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        if (status == PackageInstaller.STATUS_SUCCESS) {
            Log.i("更新", "更新：系统安装器已装好（status=$status）")
        } else {
            Log.w("更新", "更新：系统安装器未装成（status=$status，" + msg + "）")
            UpdateInstaller.notify(context, "更新失败", "系统安装器：$msg".ifBlank { "安装被取消" })
        }
    }
}
