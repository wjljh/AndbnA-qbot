package com.wjljh.andbna.ui.util

import android.content.pm.ApplicationInfo
import android.os.Build

/**
 * 预测性返回开关。照搬 kit TemplateApplication.Companion.setEnableOnBackInvokedCallback：
 * 主题页每切一次就改写 ApplicationInfo，随后 Activity.recreate() 生效。
 */
fun setEnableOnBackInvokedCallback(appInfo: ApplicationInfo, enable: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    runCatching {
        val method = ApplicationInfo::class.java.getDeclaredMethod(
            "setEnableOnBackInvokedCallback",
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        method.invoke(appInfo, enable)
    }
}
