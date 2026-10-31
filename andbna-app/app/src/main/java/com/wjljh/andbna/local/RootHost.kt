package com.wjljh.andbna.local

import android.content.Context

/**
 * root 托管：把「开机自动起」和「电池豁免」真正落到系统里。
 *
 * 为什么只有 root 能做到开机自启：普通模式和 ADB 模式都得等人打开 App；
 * root 可以在 /data/adb/service.d/ 里放一个脚本，开机由内核级守护直接执行。
 * 而且脚本以 uid 0 运行，能启动 non-exported 的前台服务（普通 shell 被拦）。
 */
object RootHost {

    data class R(val ok: Boolean, val out: String)

    private const val BOOT_SCRIPT = "/data/adb/service.d/andbna_qbot.sh"

    /** 启动时探一次的结果；null 表示还没探过 */
    @Volatile
    private var rootOk: Boolean? = null

    /** 真去要一次 root。成功会弹授权框，用户拒绝就返回 false */
    fun checkRoot(): Boolean {
        val r = sh("id -u", timeoutMs = 5000)
        val ok = r.ok && r.out.trim() == "0"
        rootOk = ok
        return ok
    }

    /** 已经是 root 就返回上次结果，没探过就现在探 */
    fun hasRoot(): Boolean = rootOk ?: checkRoot()

    /** 缓存里的结论：true=已确认有 root，false=确认没有，null=还没探过（不弹授权框） */
    fun known(): Boolean? = rootOk

    /** App 启动时调一次：把授权框提前弹掉，别等用户点模式时才弹 */
    fun primeRoot() {
        if (rootOk == null) checkRoot()
    }

    /** 跑一条 root 命令，带超时，避免卡住 UI */
    fun sh(cmd: String, timeoutMs: Long = 8000): R {
        var proc: Process? = null
        return try {
            proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val out = StringBuilder()
            val reader = Thread {
                try {
                    proc.inputStream.bufferedReader().use { r ->
                        r.forEachLine { out.append(it).append('\n') }
                    }
                } catch (_: Exception) {
                }
            }
            reader.start()
            val finished = proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroy()
                return R(false, "超时")
            }
            reader.join(1500)
            R(proc.exitValue() == 0, out.toString().trim())
        } catch (e: Throwable) {
            R(false, e.message ?: e.toString())
        } finally {
            try {
                proc?.destroy()
            } catch (_: Throwable) {
            }
        }
    }

    fun available(): Boolean {
        val r = sh("id")
        return r.ok && r.out.contains("uid=0")
    }

    fun versionText(): String = sh("id -u").out.ifBlank { "-" }

    /** 开机启动脚本：等系统起来后把前台服务拉起（root 才能启 non-exported 服务） */
    private fun bootScript(pkg: String): String = """
        #!/system/bin/sh
        # AndbnA 本机 bot 开机自启（由 AndbnA App 生成）
        # 只有 root 能启动 non-exported 的前台服务；普通 shell 会被拦。
        sleep 25
        am start-foreground-service -n $pkg/com.wjljh.andbna.local.BotService \
            -a com.wjljh.andbna.START_LOCAL --user 0 >/dev/null 2>&1
    """.trimIndent() + "\n"

    fun bootInstalled(): Boolean = sh("test -f $BOOT_SCRIPT && echo yes").out.contains("yes")

    fun installBoot(pkg: String): R {
        val script = bootScript(pkg)
        val b64 = android.util.Base64.encodeToString(
            script.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
        )
        // 用 base64 传内容，避免换行和引号在 su 里被吃掉
        val r = sh(
            "mkdir -p /data/adb/service.d && " +
                "echo '$b64' | base64 -d > $BOOT_SCRIPT && " +
                "chmod 755 $BOOT_SCRIPT && ls -l $BOOT_SCRIPT"
        )
        return r
    }

    fun removeBoot(): R = sh("rm -f $BOOT_SCRIPT && echo done")

    /** 电池优化白名单：不进白名单，后台随时可能被清 */
    fun whitelist(pkg: String): R = sh("dumpsys deviceidle whitelist +$pkg")

    fun unwhitelist(pkg: String): R = sh("dumpsys deviceidle whitelist -$pkg")

    fun isWhitelisted(pkg: String): Boolean =
        sh("dumpsys deviceidle whitelist | grep -c '$pkg'").out.trim().let { it == "1" }

    /** root 下可以直接把 App 拉起来（Activity 是导出的，root 更是通行） */
    fun relaunch(pkg: String): R =
        sh("am start -n $pkg/com.wjljh.andbna.MainActivity --user 0")

    /** 一次性把托管条件配齐：开机脚本 + 白名单 */
    fun setup(context: Context): R {
        val pkg = context.packageName
        val steps = StringBuilder()
        var allOk = true
        val boot = installBoot(pkg)
        allOk = allOk && boot.ok
        steps.append("开机脚本: ").append(if (boot.ok) "已写入" else "失败 " + boot.out).append('\n')
        val wl = whitelist(pkg)
        allOk = allOk && wl.ok
        steps.append("电池白名单: ").append(if (wl.ok) "已加入" else "失败 " + wl.out)
        return R(allOk, steps.toString())
    }

    fun teardown(): R {
        val a = removeBoot()
        return R(a.ok, "已移除开机脚本")
    }
}
