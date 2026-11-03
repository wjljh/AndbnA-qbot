package com.wjljh.andbna.local

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.wjljh.andbna.MainActivity
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * 更新包下载：前台服务 + 独立通知通道「更新下载」，进度写 `正在下载 42%（3.1/7.4 MB）`。
 *
 * 骨架与 SukiSU 的 DownloadService 一致：进程内线程下载 → 每 1% 更新一次通知 →
 * 成功发一条「点按安装」的完成通知，失败发「下载失败」。
 * 装包到 `cacheDir/update/`（私有目录不需要任何存储权限，FileProvider 也只暴露这一层）。
 *
 * 下载中点通知 = 回 App；下载完成点通知 = 把文件路径交给 MainActivity 走校验 + 安装。
 */
class UpdateDownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL).orEmpty()
        val name = intent?.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "andbna-update.apk" }
        val hint = intent?.getLongExtra(EXTRA_SIZE, 0L) ?: 0L
        if (url.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val first = notification(this, name, 0L, 0L, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID_PROGRESS, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID_PROGRESS, first)
        }
        Thread({ runDownload(url, name, hint) }, "update-download").start()
        return START_NOT_STICKY
    }

    private fun runDownload(url: String, name: String, hint: Long) {
        val dir = dir(this)
        // 旧包先清掉：cacheDir/update 只放「这一次的更新包」，多个残留只会占地方
        runCatching { dir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() } }
        val target = File(dir, name)
        val part = File(dir, name + ".part")
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "AndbnA-Updater/1.0 (Android)")
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            if (code != 200) throw java.io.IOException("HTTP " + code)
            val total = if (conn.contentLengthLong > 0) conn.contentLengthLong else hint
            if (part.exists()) part.delete()
            var done = 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = ((done * 100L) / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                notifyProgress(name, done, total, pct)
                            }
                        }
                    }
                    out.flush()
                }
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw java.io.IOException("写入失败：改不了文件名")
            if (total > 0 && target.length() != total) {
                throw java.io.IOException("大小不符：" + target.length() + " ≠ " + total)
            }
            Log.i(TAG, "更新：下载完成（" + target.absolutePath + "，" + target.length() + " 字节）")
            notifyCompleted(target)
        } catch (e: Throwable) {
            runCatching { part.delete() }
            val reason = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "更新：下载失败（" + reason + "）")
            notifyFailed(name, reason)
        } finally {
            runCatching { conn?.disconnect() }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        }
    }

    private fun notifyProgress(name: String, done: Long, total: Long, pct: Int) {
        runCatching {
            manager(this).notify(ID_PROGRESS, notification(this, name, done, total, pct))
        }
    }

    private fun notifyCompleted(apk: File) {
        val open = PendingIntent.getActivity(
            this, ID_DONE,
            Intent(this, MainActivity::class.java).apply {
                action = UpdateInstaller.ACTION_INSTALL
                putExtra(UpdateInstaller.EXTRA_PATH, apk.absolutePath)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("下载完成")
            .setContentText("点按校验并安装 " + apk.name)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { manager(this).notify(ID_DONE, n) }
        runCatching { manager(this).cancel(ID_PROGRESS) }
    }

    private fun notifyFailed(name: String, reason: String) {
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("下载失败")
            .setContentText(name + "：" + reason)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, ID_DONE, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()
        runCatching { manager(this).notify(ID_DONE, n) }
        runCatching { manager(this).cancel(ID_PROGRESS) }
    }

    companion object {
        private const val TAG = "更新"

        /** 独立通知通道「更新下载」（与 bot 运行通知分开） */
        const val CHANNEL = "andbna_update_download"
        const val CHANNEL_NAME = "更新下载"

        const val ACTION_DOWNLOAD = "com.wjljh.andbna.action.UPDATE_DOWNLOAD"
        const val EXTRA_URL = "url"
        const val EXTRA_NAME = "name"
        const val EXTRA_SIZE = "size"

        private const val ID_PROGRESS = 4801
        private const val ID_DONE = 4802

        /** 下载落地目录：cacheDir/update（FileProvider 只暴露这一层） */
        fun dir(ctx: Context): File = File(ctx.cacheDir, "update").apply { mkdirs() }

        fun start(ctx: Context, url: String, name: String, size: Long) {
            val i = Intent(ctx, UpdateDownloadService::class.java).apply {
                action = ACTION_DOWNLOAD
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_NAME, name)
                putExtra(EXTRA_SIZE, size)
            }
            runCatching { ctx.startForegroundService(i) }
                .onFailure { Log.w(TAG, "更新：下载服务启动失败（" + it.message + "）") }
        }

        private fun manager(ctx: Context) =
            ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val mgr = manager(ctx)
            if (mgr.getNotificationChannel(CHANNEL) == null) {
                val ch = NotificationChannel(CHANNEL, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                    description = "下载更新包时显示进度，下载完点一下就能装"
                    setShowBadge(false)
                }
                runCatching { mgr.createNotificationChannel(ch) }
                    .onFailure { Log.w(TAG, "更新：建渠道失败（" + it.message + "）") }
            }
        }

        private fun mb(bytes: Long): String =
            String.format(Locale.US, "%.1f", bytes / 1048576.0)

        /** 进度文案：`正在下载 42%（3.1/7.4 MB）`；总长未知时只写已下载 */
        fun progressText(done: Long, total: Long, pct: Int): String = if (total > 0) {
            "正在下载 " + pct + "%（" + mb(done) + "/" + mb(total) + " MB）"
        } else {
            "正在下载 " + mb(done) + " MB"
        }

        private fun notification(ctx: Context, name: String, done: Long, total: Long, pct: Int) =
            NotificationCompat.Builder(ctx, CHANNEL)
                .setContentTitle("更新包 " + name)
                .setContentText(progressText(done, total, pct))
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setProgress(100, pct, total <= 0L)
                .setOngoing(true)
                .setSilent(true)
                .setContentIntent(
                    PendingIntent.getActivity(
                        ctx, ID_PROGRESS, Intent(ctx, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                )
                .build()
    }
}
