package com.wjljh.andbna.local

import android.content.Context
import java.util.concurrent.atomic.AtomicInteger

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

    /**
     * su 给 shell 起的进程名（SukiSU 这条线是「包名:shell」）。
     * [ShellJanitor] 启动时注入，收尸时按它扫尾；空串 = 还不知道，就不按名字扫。
     */
    @Volatile
    var shellName: String = ""

    /** 全局只留一条 root 命令在飞：连点 / 多线程同时调时不会堆出一串 shell 一起等 */
    private val gate = java.util.concurrent.locks.ReentrantLock()

    /** 跑一条 root 命令，带超时，避免卡住 UI */
    fun sh(cmd: String, timeoutMs: Long = 8000): R {
        if (!gate.tryLock(timeoutMs.coerceAtLeast(1000L), java.util.concurrent.TimeUnit.MILLISECONDS)) {
            return R(false, "超时（上一条 root 命令还没结束）")
        }
        try {
            return exec(cmd, timeoutMs)
        } finally {
            runCatching { gate.unlock() }
        }
    }

    /**
     * 真正执行的地方。外面多套一层：`echo PID:$$; exec timeout -s KILL <n> sh -c '<cmd>'`。
     *
     * 老实现只 destroy() su 客户端，远端那个 shell 没人通知 —— 它就活成 PPID=1 的孤儿
     * （一个 ~70MB，攒 11 个就是 770MB，能挂 5~6 小时）。这里三条腿一起上，任何一条都够：
     *  ① 远端 `timeout -s KILL`：客户端被回收、App 被杀、su 客户端超时……到点它自己死；
     *  ② `echo PID:$$` 让 App 拿到远端 shell 的 pid，超时时按 pid 精确收尸；
     *  ③ 收尸先整组 kill（连它 fork 出去的子孙一起），再逐个补刀，最后按名字扫尾。
     * 不依赖「su 退出时会顺手带走子进程」这种在 mksh / SukiSU 上并不成立的假设。
     */
    private fun exec(cmd: String, timeoutMs: Long): R {
        // 远端自杀期限比客户端宽 ~2s：正常路径仍由客户端判超时，这里只是「客户端没了也有人收尸」
        val guard = ((timeoutMs + 1999) / 1000).toInt().coerceAtLeast(1)
        val remote = "echo PID:" + D + D + "; exec timeout -s KILL " + guard + " sh -c " + sq(cmd)
        var proc: Process? = null
        try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", remote))
            proc = p
            val out = StringBuilder()
            val pid = AtomicInteger(0)
            val reader = Thread {
                try {
                    p.inputStream.bufferedReader().use { r ->
                        var seen = false
                        r.forEachLine { line ->
                            // 第一行是我们自己塞的 pid 回执，不算命令输出
                            if (!seen && line.startsWith("PID:")) {
                                seen = true
                                pid.set(line.removePrefix("PID:").trim().toIntOrNull() ?: 0)
                            } else {
                                out.append(line).append('\n')
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
            reader.start()
            val finished = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                reap(pid.get())
                return R(false, "超时")
            }
            reader.join(1500)
            return R(p.exitValue() == 0, out.toString().trim())
        } catch (e: Throwable) {
            return R(false, e.message ?: e.toString())
        } finally {
            try {
                proc?.destroy()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * 给远端收尸：整组 kill → 按 pid → 按名字扫。名字扫读 /proc 的 cmdline，不用 pidof ——
     * toybox 的 comm 只有 15 字符，名字会被截成 `com.wjljh.andbn`，pidof 认不出；
     * 而且 su 客户端如果在回执前就没了，pid 是 0，名字扫是唯一还认得出来的路。
     */
    private fun reap(pid: Int) {
        val name = shellName
        val script = buildString {
            append("#!/system/bin/sh\n")
            append("P=").append(pid).append("\n")
            append("if [ \"\$P\" -gt 0 ] 2>/dev/null; then\n")
            append("  kill -9 -- -\"\$P\" 2>/dev/null\n")
            append("  kill -9 \"\$P\" 2>/dev/null\n")
            append("  for c in \$(pgrep -P \"\$P\" 2>/dev/null); do\n")
            append("    kill -9 -- -\"\$c\" 2>/dev/null; kill -9 \"\$c\" 2>/dev/null\n")
            append("  done\n")
            append("fi\n")
            if (name.isNotBlank()) {
                append("for q in \$(pgrep -f -- '").append(name).append("' 2>/dev/null); do\n")
                append("  kill -9 \"\$q\" 2>/dev/null\n")
                append("done\n")
            }
            append("echo done\n")
        }
        val b64 = android.util.Base64.encodeToString(
            script.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
        )
        // 不走 exec()：收尸脚本自己就有超时兜底，再套一层只会让它在超时后另找一个 shell 来收自己
        runCatching { suOnce("echo $b64 | base64 -d | sh", 15000) }
    }

    /** 只给 [reap] 用的裸执行：su 起、读完、退出，不加 pid 回执也不加远端 timeout */
    private fun suOnce(script: String, timeoutMs: Long): R {
        var proc: Process? = null
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", script))
            proc = p
            val out = StringBuilder()
            val reader = Thread {
                try {
                    p.inputStream.bufferedReader().use { r -> r.forEachLine { out.append(it).append('\n') } }
                } catch (_: Exception) {
                }
            }
            reader.start()
            if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                return R(false, "超时")
            }
            reader.join(1000)
            R(p.exitValue() == 0, out.toString().trim())
        } catch (e: Throwable) {
            R(false, e.message ?: e.toString())
        } finally {
            runCatching { proc?.destroy() }
        }
    }

    /** 包一层单引号，把内部单引号转义掉（su 那层 shell 会解析这层） */
    private fun sq(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private const val D = "\$"

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
