package com.wjljh.andbna.local

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 残留 `:shell` 清理器（安全网）。
 *
 * 那些 `:shell` 是 Shizuku 的 UserService 进程（app_process64 + framework + 本 App 的 APK，
 * 每个几十上百 MB）。只要客户端进程先死，Shizuku 13.7 不会立刻回收它，它就挂成 PPID=1 的孤儿。
 * 源头堵在别处（root 模式不 bind、按需绑定、切出 ADB 时 unbind、BotService 销毁时 unbind），
 * 这里负责把「真没人管」的收掉：App 起一次 + 之后每 2 分钟一次，阈值 300 秒。
 *
 * 认人三条同时成立才动手，宁漏不错：
 *  ① ARGS 里出现 `<包名>:shell`（看门狗脚本的 argv 是 `sh /…/autopull/watchdog.sh`，不含它）；
 *  ② PPID=1（父进程已经没了 = 真孤儿；还有父进程说明是正在用的那一条）；
 *  ③ 存活 > 300 秒，且没有「300 秒内起来的子孙」（有 = 正在跑命令）。
 *
 * 候选年龄用 `ps` 的 ELAPSED：实测 root 下 `/proc/<pid>/stat` 偶尔读不到别的 uid 的进程，
 * 而 ps 一直都在 —— 这正是之前「明明有孤儿却报命中 0」的原因。
 */
object ShellJanitor {

    private const val TAG = "AndbnA"
    private const val PREFIX = "清理残留 shell："
    private const val STALE_SEC = 300
    private const val PERIOD_MS = 2 * 60 * 1000L
    private const val LOGDIR = "autopull"
    private const val LOGFILE = "autopull.log"

    @Volatile
    private var started = false

    /** App 进程起来调一次（幂等）：解析出 shell 的名字，然后起一条低频清理线程 */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (RootHost.shellName.isBlank()) RootHost.shellName = app.packageName + ":shell"
        synchronized(this) {
            if (started) return
            started = true
            Thread({ loop(app) }, "shell-janitor").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun loop(ctx: Context) {
        while (true) {
            try {
                sweep(ctx)
            } catch (e: Throwable) {
                Log.w(TAG, PREFIX + "扫描异常：" + e)
            }
            runCatching { Thread.sleep(PERIOD_MS) }
        }
    }

    private fun sweep(ctx: Context) {
        if (RootHost.known() == false) return
        // 还没确认过 root 时只有一种情况可以先动手：当前就是 root 模式（探测马上也会跑一次）。
        // 不在 root 模式但**确实有 root** 的机器照样扫 —— 现场那些孤儿多半是 Shizuku 的 shell，
        // ADB 模式下一样会攒。
        if (RootHost.known() == null && ModeStore.get(ctx) != RunMode.ROOT) return
        val target = RootHost.shellName.ifBlank { ctx.packageName + ":shell" }
        val b64 = android.util.Base64.encodeToString(
            script(target).toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
        )
        val t0 = System.currentTimeMillis()
        val r = RootHost.sh("echo $b64 | base64 -d | sh", timeoutMs = 20000)
        val cost = System.currentTimeMillis() - t0
        val hits = r.out.lines().map { it.trim() }.filter { it.startsWith(PREFIX) }
        hits.forEach { line ->
            Log.i(TAG, line)
            fileLog(ctx, line)
        }
        Log.i(
            TAG,
            PREFIX + "扫描完成，命中 " + hits.size + "（耗时 " + cost + "ms）" +
                if (r.ok) "" else "（脚本没跑成功：" + r.out.take(80) + "）",
        )
    }

    /** 清理器本体：一次 su，跑一遍 ps + 逐个认人 + kill -9，命中就报一行 */
    private fun script(target: String): String {
        val D = "\$"
        return """
            #!/system/bin/sh
            # AndbnA 残留 shell 清理器（App 生成、root 执行；只认「包名:shell」的孤儿）
            T="$target"
            MIN=$STALE_SEC
            # ELAPSED 转秒：支持 D-HH:MM:SS / HH:MM:SS / MM:SS
            sec_of() {
              echo "${D}1" | awk -F"[-:]" '{
                if (NF == 4) print ${D}1*86400 + ${D}2*3600 + ${D}3*60 + ${D}4
                else if (NF == 3) print ${D}1*3600 + ${D}2*60 + ${D}3
                # 不到 1 小时的进程 ps 打的是 MM:SS，别当成「秒」——否则 300s 阈值永远到不了
                else if (NF == 2) print ${D}1*60 + ${D}2
                else print ${D}1
              }'
            }
            ps -A -o PID,PPID,ELAPSED,ARGS 2>/dev/null | while read -r pid ppid et rest; do
              [ "${D}ppid" = "1" ] || continue
              case "${D}rest" in
                *"${D}T"*) ;;
                *) continue ;;
              esac
              age=${D}(sec_of "${D}et")
              [ -n "${D}age" ] || continue
              [ "${D}age" -ge "${D}MIN" ] 2>/dev/null || continue
              # 还在跑命令就不动它：只要有一个 MIN 秒内起来的子孙，说明这棵树还活着
              busy=0
              for c in ${D}(pgrep -P "${D}pid" 2>/dev/null); do
                cet=${D}(ps -A -o PID,ELAPSED 2>/dev/null | awk -v p="${D}c" '${D}1==p{print ${D}2}')
                cs=${D}(sec_of "${D}cet")
                [ -n "${D}cs" ] && [ "${D}cs" -lt "${D}MIN" ] && busy=1
              done
              [ "${D}busy" = "1" ] && continue
              if kill -9 "${D}pid" 2>/dev/null; then
                echo "${PREFIX}pid=${D}pid 存活 ${D}((age/60)) 分钟"
              else
                echo "${PREFIX}pid=${D}pid kill 失败（存活 ${D}((age/60)) 分钟）"
              fi
            done
            exit 0
        """.trimIndent()
    }

    /** 落到和看门狗同一份日志里：adb pull 得到，UI 的日志页也是同一个目录 */
    private fun fileLog(ctx: Context, line: String) {
        val d = ctx.getExternalFilesDir(null)?.let { File(it, LOGDIR) } ?: return
        if (!d.exists() && !d.mkdirs()) return
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        runCatching { File(d, LOGFILE).appendText(stamp + " " + line + "\n") }
    }
}