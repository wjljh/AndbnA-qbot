package com.wjljh.andbna.local

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * 状态页「资源占用」卡的数据源。
 *
 * 选型理由（都是系统给的、不引第三方、不常驻线程）：
 *  · 内存：ActivityManager.MemoryInfo 的 totalMem/availMem —— 与系统「内存」页同一口径，
 *    一次调用就是快照，不需要任何权限。
 *  · CPU ：优先 /proc/stat 两次采样（整机口径，和 top 第一行一致）。Android 17 的 SELinux
 *    默认拦掉 App 读 /proc/stat → 先试直读，被拦再走一次特权通道（root 的 su / ADB 的
 *    Shizuku shell）读同一份 /proc/stat；两条都不行才退回 /proc/self/stat 的
 *    utime+stime delta / 墙钟，并在 UI 上标「本应用」。
 *  · 机器人占用：ActivityManager.getProcessMemoryInfo(本进程).totalPss ——
 *    就是 dumpsys meminfo 里那个 PSS，验收口径一致。
 *  · 代理占用：扫 /proc 找 :shell 与 autopull/watchdog.sh 的 VmRSS 合计。
 *    这两个进程一般由 root 拉起（root 域），App 直接读不到 → 直扫没命中再走特权通道；
 *    仍然读不到就返回 null，UI 显示「—」。
 *
 * 每次刷新最多只 fork 一个特权 shell：代理扫描和 /proc/stat 采样合并成一条命令发过去。
 * 失败一律返回 null（不返回 0），日志前缀「资源占用：」，异常不往外抛。
 */
data class ResourceSnapshot(
    /** 整机 CPU 百分比；读不到为 null */
    val cpuPct: Int? = null,
    /** true = 这个数是本进程 CPU%（整机读数拿不到时的退路） */
    val cpuIsApp: Boolean = false,
    val memPct: Int? = null,
    val memUsedMb: Long = 0L,
    val memTotalMb: Long = 0L,
    /** 本进程 PSS，MB；读不到为 null */
    val botPssMb: Int? = null,
    /** 代理/守护进程 RSS 合计，MB；读不到为 null（扫描成功但没这类进程 = 0） */
    val proxyRssMb: Int? = null,
)

object ResourceStats {

    private const val TAG = "AndbnA"
    private const val PREFIX = "资源占用："

    /** 两次采样的间隔：至少 1s（整机 CPU 用两次计数器差值算，间隔太短会抖）。 */
    private const val WINDOW_MS = 1000L

    /** Android 的 USER_HZ 固定 100。 */
    private const val CLK_TCK = 100L

    /**
     * 代理进程特征串（ERE）。写成 qbot[:]shell / watchdo[g] 是为了让特权 shell 那条命令
     * 自己的命令行不命中自己（命令行里出现的是带方括号的写法，不是这两个字面量）。
     */
    private const val PROXY_PATTERN = "qbot[:]shell|autopull/watchdo[g]\\.sh"

    fun sample(ctx: Context): ResourceSnapshot {
        val mem = runCatching { readMem(ctx) }.getOrNull()
        val pss = runCatching { readBotPss(ctx) }.getOrNull()

        var cpuPct = runCatching { readMachineCpuDirect() }.getOrNull()
        var cpuIsApp = false
        // 整机 CPU 是从哪条通道读到的：null=直读，su / Shizuku=特权通道（只用于日志）
        var cpuVia: String? = null
        var proxyMb = runCatching { scanOwn() }.getOrNull()

        // 直读 /proc/stat 被拦、或者自己扫不到代理进程 → 走一次特权通道，两种数据一次取回
        if (cpuPct == null || proxyMb == null) {
            val priv = runCatching { privileged(cpuPct == null) }.getOrNull()
            if (cpuPct == null) {
                cpuPct = priv?.cpuPct
                if (cpuPct != null) cpuVia = if (priv?.viaSu == true) "su" else "Shizuku"
            }
            if (proxyMb == null) proxyMb = priv?.proxyMb
        }

        if (cpuPct == null) {
            cpuPct = runCatching { readAppCpu() }.getOrNull()
            cpuIsApp = cpuPct != null
            if (cpuIsApp) {
                Log.w(TAG, PREFIX + "整机 CPU 读不到（/proc/stat 被系统拦），改显示本应用 CPU%")
            } else {
                Log.w(TAG, PREFIX + "CPU 读不到（/proc/stat 与 /proc/self/stat 都失败）")
            }
        }

        val snap = ResourceSnapshot(
            cpuPct = cpuPct,
            cpuIsApp = cpuIsApp,
            memPct = mem?.first,
            memUsedMb = mem?.second ?: 0L,
            memTotalMb = mem?.third ?: 0L,
            botPssMb = pss,
            proxyRssMb = proxyMb,
        )

        val sb = StringBuilder(PREFIX)
        when {
            snap.cpuPct == null -> sb.append("CPU —")
            snap.cpuIsApp -> sb.append("本应用 CPU ").append(snap.cpuPct)
                .append("%（/proc/self/stat）")
            else -> sb.append("整机 CPU ").append(snap.cpuPct).append("%（/proc/stat")
                .append(if (cpuVia == null) "" else " · " + cpuVia).append("）")
        }
        sb.append(" 内存 ").append(pctText(snap.memPct))
        if (snap.memTotalMb > 0L) {
            sb.append("（已用 ").append(snap.memUsedMb).append("/")
                .append(snap.memTotalMb).append(" MB）")
        }
        sb.append(" 机器人 ").append(mbText(snap.botPssMb))
        sb.append(" 代理 ").append(mbText(snap.proxyRssMb))
        Log.i(TAG, sb.toString())

        return snap
    }

    private fun pctText(v: Int?): String = (if (v == null) "—" else v.toString()) + "%"

    private fun mbText(v: Int?): String = if (v == null) "—" else v.toString() + " MB"

    // ── 内存 ────────────────────────────────────────────────────────────

    /** 返回 (百分比, 已用 MB, 总量 MB) */
    private fun readMem(ctx: Context): Triple<Int, Long, Long>? {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        if (mi.totalMem <= 0L) return null
        val used = (mi.totalMem - mi.availMem).coerceAtLeast(0L)
        val pct = (used * 100L / mi.totalMem).toInt().coerceIn(0, 100)
        return Triple(pct, used / 1024L / 1024L, mi.totalMem / 1024L / 1024L)
    }

    // ── CPU ─────────────────────────────────────────────────────────────

    /** /proc/stat 第一行的 (total, idle+iowait)；读不到返回 null。 */
    private fun statCounters(line: String?): Pair<Long, Long>? {
        if (line == null) return null
        val nums = line.trim().split(WHITESPACE).mapNotNull { it.toLongOrNull() }
        if (nums.size < 4) return null
        val total = nums.sum()
        val idle = nums[3] + (nums.getOrNull(4) ?: 0L)
        return total to idle
    }

    /** 整机 CPU：直读 /proc/stat 两次；被 SELinux 拦掉就返回 null（不睡那 320ms）。 */
    private fun readMachineCpuDirect(): Int? {
        val first = statCounters(readLines("/proc/stat")?.firstOrNull { it.startsWith("cpu ") })
            ?: return null
        Thread.sleep(WINDOW_MS)
        val second = statCounters(readLines("/proc/stat")?.firstOrNull { it.startsWith("cpu ") })
            ?: return null
        return cpuBetween(first, second)
    }

    private fun cpuBetween(a: Pair<Long, Long>, b: Pair<Long, Long>): Int? {
        val dTotal = b.first - a.first
        if (dTotal <= 0L) return null
        val dIdle = (b.second - a.second).coerceIn(0L, dTotal)
        return ((dTotal - dIdle) * 100.0 / dTotal).toInt().coerceIn(0, 100)
    }

    /** 退路：本进程 utime+stime / 墙钟 / 核数（换算成占整机的百分比，和整机口径可比）。 */
    private fun readAppCpu(): Int? {
        val t0 = SystemClock.elapsedRealtime()
        val a = selfTicks() ?: return null
        Thread.sleep(WINDOW_MS)
        val b = selfTicks() ?: return null
        val dt = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(1L)
        if (b < a) return null
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val pct = (b - a) * 1000.0 / (dt * CLK_TCK * cores) * 100.0
        return pct.toInt().coerceIn(0, 100)
    }

    private val WHITESPACE = Regex("\\s+")

    /** /proc/self/stat 的 utime+stime（第 14/15 字段）；进程名可能带空格，先跳过右括号。 */
    private fun selfTicks(): Long? {
        val line = readLines("/proc/self/stat")?.firstOrNull() ?: return null
        val close = line.lastIndexOf(')')
        if (close < 0) return null
        val rest = line.substring(close + 1).trim().split(WHITESPACE)
        // rest[0]=state(字段3) → utime 是字段 14，stime 是字段 15
        val utime = rest.getOrNull(11)?.toLongOrNull() ?: return null
        val stime = rest.getOrNull(12)?.toLongOrNull() ?: return null
        return utime + stime
    }

    // ── 机器人占用（本进程 PSS）────────────────────────────────────────

    /**
     * 本进程 PSS。首选 /proc/self/smaps_rollup 的 Pss —— 与 dumpsys meminfo 的
     * TOTAL PSS 同口径（Android 17 实测 ActivityManager.getProcessMemoryInfo 给的数只有它的一半，
     * 拿它当 PSS 会低报，所以只当退路）。
     */
    private fun readBotPss(ctx: Context): Int? {
        val pss = runCatching { File("/proc/self/smaps_rollup").readLines() }.getOrNull()
            ?.firstOrNull { it.startsWith("Pss:") }
            ?.filter { it.isDigit() }?.toLongOrNull()
        if (pss != null && pss > 0L) return (pss / 1024L).toInt()

        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val infos: Array<Debug.MemoryInfo>? = am.getProcessMemoryInfo(intArrayOf(Process.myPid()))
        val kb = infos?.firstOrNull()?.totalPss ?: return null
        if (kb <= 0) return null
        return kb / 1024
    }

    // ── 代理占用（:shell + 守护脚本 的 RSS 合计）───────────────────────

    /** 特权通道一次取回的两样东西（各自可能为 null）。 */
    private class Priv(val cpuPct: Int?, val proxyMb: Int?, val viaSu: Boolean)

    /**
     * 一次特权 shell（root 的 su / ADB 的 Shizuku）同时做两件事：
     * 扫代理进程的 VmRSS、需要时再采样 /proc/stat（sleep 放在 shell 里，省一次 fork）。
     */
    private fun privileged(needCpu: Boolean): Priv? {
        val script = privScript(needCpu)
        val useSu = RootHost.available()
        val out: String? = when {
            useSu -> RootHost.sh(script, timeoutMs = 8000).let { if (it.ok) it.out else null }
            ShizukuShell.bound -> ShizukuShell.exec(script)
            else -> null
        }
        if (out == null) {
            Log.w(TAG, PREFIX + "特权通道不可用（没有 root / Shizuku），代理占用显示「—」")
            return null
        }

        val stats = out.lineSequence().filter { it.startsWith("CPUSTAT ") }.toList()
        val cpuPct = if (stats.size >= 2) {
            cpuBetween(
                statCounters(stats[0].removePrefix("CPUSTAT ")) ?: (0L to 0L),
                statCounters(stats[1].removePrefix("CPUSTAT ")) ?: (0L to 0L),
            )
        } else {
            null
        }

        val found = Regex("N=(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull()
        val kb = Regex("S=(\\d+)").find(out)?.groupValues?.get(1)?.toLongOrNull()
        val proxyMb = if (found != null && kb != null) (kb / 1024L).toInt() else null
        if (proxyMb == null) {
            Log.w(TAG, PREFIX + "代理占用读不到（特权扫描输出异常：" + out.take(80) + "）")
        }
        return Priv(cpuPct, proxyMb, useSu)
    }

    /**
     * 特权 shell 的脚本：先扫代理进程（grep -l 找 cmdline 命中的 /proc/<pid>/cmdline，
     * 再把每个 pid 的 VmRSS 加起来），需要时再打两行 CPUSTAT。
     */
    private fun privScript(needCpu: Boolean): String {
        val dollar = "$"
        val sq = "'"
        return buildString {
            append("S=0; N=0\n")
            append("for f in ").append(dollar).append("(grep -l -a -E ")
            append(sq).append(PROXY_PATTERN).append(sq).append(" /proc/[0-9]*/cmdline 2>/dev/null); do\n")
            append("  p=").append(dollar).append("{f#/proc/}; p=").append(dollar).append("{p%/cmdline}\n")
            append("  r=").append(dollar).append("(awk '/VmRSS/{print ")
            append(dollar).append("2}' /proc/").append(dollar).append("p/status 2>/dev/null)\n")
            append("  if [ -n \"").append(dollar).append("r\" ]; then S=")
            append(dollar).append("((S+r)); N=").append(dollar).append("((N+1)); fi\n")
            append("done\n")
            append("echo \"PROXY N=").append(dollar).append("N S=").append(dollar).append("S\"\n")
            if (needCpu) {
                append("head -1 /proc/stat | sed 's/^/CPUSTAT /'\n")
                // 两次采样至少隔 1 秒，差值才算得准
                append("sleep 1\n")
                append("head -1 /proc/stat | sed 's/^/CPUSTAT /'\n")
            }
        }
    }

    /** 同 uid（App 自己拉起的 :shell）能直接读，省掉一次特权 shell。 */
    private fun scanOwn(): Int? {
        val pids = File("/proc").listFiles()
            ?.filter { it.isDirectory && it.name.all { c -> c.isDigit() } }
            ?.mapNotNull { it.name.toIntOrNull() }
            ?: return null
        var sum = 0L
        for (pid in pids) {
            if (pid == Process.myPid()) continue
            val cmd = runCatching { File("/proc/" + pid + "/cmdline").readText() }.getOrNull()
                ?: continue
            if (!isProxy(cmd)) continue
            val rss = runCatching { File("/proc/" + pid + "/status").readText() }.getOrNull()
                ?.lineSequence()?.firstOrNull { it.startsWith("VmRSS:") }
                ?.filter { it.isDigit() }?.toLongOrNull() ?: continue
            sum += rss
        }
        return if (sum > 0L) (sum / 1024L).toInt() else null
    }

    private fun isProxy(cmdline: String): Boolean {
        val c = cmdline.replace('\u0000', ' ')
        return c.contains("qbot:shell") || c.contains("autopull/watchdog.sh")
    }

    private fun readLines(path: String): List<String>? =
        runCatching { File(path).readLines() }.getOrNull()
}
