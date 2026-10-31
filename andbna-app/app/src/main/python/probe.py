# -*- coding: utf-8 -*-
"""可行性探针：把这个模块的依赖链在 Android 上真导一遍。

存在的意义：botpy 依赖 aiohttp（C 扩展），能不能在 Android 上装出
预编译轮子，决定「纯 APK 跑 bot」这条路通不通。这个文件只做自检，
真正跑 bot 的入口以后放在这里（bot.py）。
"""


def probe():
    """返回每一层依赖的版本，装不上就抛出来"""
    out = {}
    import sys

    out["python"] = sys.version.split()[0]
    out["platform"] = sys.platform

    for name in ("aiohttp", "yaml", "apscheduler", "botpy"):
        try:
            mod = __import__(name)
            out[name] = getattr(mod, "__version__", "ok")
        except Exception as e:
            out[name] = "✗ %r" % (e,)
    return out


if __name__ == "__main__":
    for k, v in probe().items():
        print("%-12s %s" % (k, v))
