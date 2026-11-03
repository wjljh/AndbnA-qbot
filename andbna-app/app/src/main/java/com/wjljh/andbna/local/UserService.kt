package com.wjljh.andbna.local

import android.os.ParcelFileDescriptor
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Shizuku 的 UserService：这个类由 Shizuku 在 **shell 进程（uid 2000）** 里用反射 new 出来，
 * 所以它里面的代码天然具备 shell 权限 —— 这是 ADB 模式能干活的关键。
 *
 * 必须是 [IUserService.Stub]（一个 Binder）本体，**不能**是 android.app.Service：
 * Shizuku 的 ServiceStarter 直接 (IBinder) Class.forName(...).newInstance()，
 * 继承 Service 会在它那边抛 ClassCastException，binder 永远绑不上（bound=false）。
 * 也因此不需要在 AndroidManifest 里声明 <service> —— 进程由 Shizuku 自己起。
 *
 * 注意：它不能引用任何 UI / Activity 相关的东西，进程里只有它自己。
 */
class UserService : IUserService.Stub() {

    override fun exec(cmd: String): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val sb = StringBuilder()
            val out = Thread {
                try {
                    BufferedReader(InputStreamReader(p.inputStream)).use { r ->
                        r.forEachLine { sb.append(it).append('\n') }
                    }
                } catch (_: Throwable) {
                }
            }
            val err = Thread {
                try {
                    BufferedReader(InputStreamReader(p.errorStream)).use { r ->
                        r.forEachLine { sb.append(it).append('\n') }
                    }
                } catch (_: Throwable) {
                }
            }
            out.start()
            err.start()
            val done = p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)
            if (!done) {
                p.destroy()
                return "超时"
            }
            out.join(800)
            err.join(800)
            sb.toString().trim()
        } catch (e: Throwable) {
            "执行失败：" + e.message
        }
    }

    /**
     * 自更新的安装通道：`pm install -S <size>`（不带 -r：校验已保证是新版本，走正常升级），APK 字节直接从 binder 传进来的
     * 文件描述符读到 pm 的 stdin —— 和 root 那条 `cat apk | pm install` 同一条命令，
     * 只是这里不能让 pm 自己去开 App 私有目录（shell 没那个权限，SELinux 也会拦）。
     */
    override fun pmInstall(fd: ParcelFileDescriptor?, size: Long): String {
        if (fd == null) return "没拿到安装包的文件描述符"
        var p: Process? = null
        try {
            p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "pm install -S " + size))
            val sb = StringBuilder()
            val out = Thread {
                try {
                    BufferedReader(InputStreamReader(p.inputStream)).use { r ->
                        r.forEachLine { sb.append(it).append('\n') }
                    }
                } catch (_: Throwable) {
                }
            }
            val err = Thread {
                try {
                    BufferedReader(InputStreamReader(p.errorStream)).use { r ->
                        r.forEachLine { sb.append(it).append('\n') }
                    }
                } catch (_: Throwable) {
                }
            }
            out.start()
            err.start()
            var writeError = ""
            try {
                FileInputStream(fd.fileDescriptor).use { input ->
                    p.outputStream.use { sink -> input.copyTo(sink) }
                }
            } catch (e: Throwable) {
                writeError = "写入 pm 失败：" + e.message
            }
            // 安装本身可能慢（要解压 + 刷 dex），给 3 分钟
            val done = p.waitFor(180, TimeUnit.SECONDS)
            if (!done) {
                p.destroy()
                return "超时"
            }
            out.join(1_500)
            err.join(1_500)
            val text = sb.toString().trim()
            return if (writeError.isBlank()) text else (writeError + "\n" + text).trim()
        } catch (e: Throwable) {
            return "执行失败：" + e.message
        } finally {
            runCatching { fd.close() }
            p?.let { runCatching { it.destroy() } }
        }
    }
}
