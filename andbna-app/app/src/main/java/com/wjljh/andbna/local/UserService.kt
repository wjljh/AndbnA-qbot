package com.wjljh.andbna.local

import java.io.BufferedReader
import java.io.InputStreamReader

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
}
