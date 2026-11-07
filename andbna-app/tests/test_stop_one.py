# -*- coding: utf-8 -*-
"""host 端验证：stop_one / stop_all 是真停（不是只把状态写成「已停止」）。

背景（根因）：botpy.client.Client.close 是**协程**，旧代码直接 c.close() 只造了个没人
await 的协程对象；而 client.run() 内部是 loop.run_until_complete(runner())，不停那个
loop 就永远不会返回 —— 于是界面显示「已停止」，bot 还在回消息。

这里用假 client + 假 run()（真跑一个 loop.run_until_complete(sleep)）把整条链路走一遍：
  loop 与 client 一起登记 / close() 真被 await / loop 真被停 / run() 真退出 /
  generation 提升后旧实例的事件被 _stale() 丢掉且只记一次日志 / snapshot() alive 全 false

    cd andbna-app && python3 tests/test_stop_one.py
"""
import asyncio
import json
import os
import shutil
import sys
import tempfile
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "app", "src", "main", "python"))

import bot_runner as br  # noqa: E402

TMP = tempfile.mkdtemp(prefix="stop_test_")
BASE = os.path.join(TMP, "localbot")
RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), str(detail)))
    print(("PASS " if ok else "FAIL ") + name + (("   " + str(detail)) if detail else ""))


# ── 假 botpy client ─────────────────────────────────────

class FakeClient(object):
    """只复刻真 client 的两件事：close() 是协程；run() 阻塞在自己的 loop 上。"""

    def __init__(self, appid, secret, botdir, gen):
        self.appid = appid
        self.gen = gen
        self.loop = None
        self.closed = False
        self.ran = False
        self.returned = False
        self.error = None

    def run(self, appid="", secret=""):
        self.loop = asyncio.get_event_loop()
        self.ran = True
        try:
            self.loop.run_until_complete(asyncio.sleep(600))
        except BaseException as e:      # loop.stop() → RuntimeError
            self.error = e
            raise
        finally:
            self.returned = True

    async def close(self):
        for _ in range(3):
            await asyncio.sleep(0)      # 真 close 要 await http.close()，这里占位
        self.closed = True


CLIENTS = {}


def fake_make_client(appid, secret, botdir, gen=None):
    c = FakeClient(appid, secret, botdir, gen)
    CLIENTS[appid] = c
    return c


br._make_client = fake_make_client


def wait_for(fn, timeout=5.0, step=0.02):
    end = time.time() + timeout
    while time.time() < end:
        if fn():
            return True
        time.sleep(step)
    return fn()


def snap():
    return {b["appid"]: b for b in json.loads(br.snapshot())}


def has(line):
    return any(line in x for x in list(br._state["log"]))


def start(appid):
    r = br.start_one(appid, "secret-" + appid, BASE)
    ok = wait_for(lambda: _client_ready(appid))
    return r, ok


def _client_ready(appid):
    info = br._bots.get(appid) or {}
    c = CLIENTS.get(appid)
    return bool(c is not None and info.get("client") is c and c.loop is not None
                and c.loop.is_running())


def main():
    print("== 1) 起了 2 个 bot ==")
    r1, ok1 = start("test_bot1")
    r2, ok2 = start("test_bot2")
    check("两个 bot 都起来并各自连上", r1 == "OK" and r2 == "OK" and ok1 and ok2,
          "%s / %s" % (r1, r2))

    s = snap()
    check("两个 bot 的 loop 都已登记（stop_one 靠它停）",
          br._bots["test_bot1"].get("loop") is CLIENTS["test_bot1"].loop
          and br._bots["test_bot2"].get("loop") is CLIENTS["test_bot2"].loop,
          "bot1 loop 已登记=%r" % (br._bots["test_bot1"].get("loop") is not None))
    check("snapshot 里两个都 alive", s["test_bot1"]["alive"] and s["test_bot2"]["alive"],
          json.dumps(s, ensure_ascii=False))

    print("\n== 2) 只停 test_bot1 ==")
    old_gen = br._bots["test_bot1"]["gen"]
    c1, c2 = CLIENTS["test_bot1"], CLIENTS["test_bot2"]
    check("stop_one 返回 OK", br.stop_one("test_bot1") == "OK")

    check("close() 真被 await 了（不是造了个协程对象）", wait_for(lambda: c1.closed, 3.0),
          "closed=%r" % c1.closed)
    check("被停那个的 loop 真的停了", wait_for(lambda: not c1.loop.is_running(), 3.0),
          "loop.is_running()=%r" % c1.loop.is_running())
    check("client.run() 真的退出了（线程不再阻塞）", wait_for(lambda: c1.returned, 3.0),
          "returned=%r err=%r" % (c1.returned, c1.error))

    check("另一个 bot 完全没被牵连（仍在跑）",
          c2.loop.is_running() and not c2.closed and br._bots["test_bot2"]["alive"],
          "closed=%r running=%r" % (c2.closed, c2.loop.is_running()))

    check("generation 已提升（旧实例回调失效）", br._bots["test_bot1"]["gen"] != old_gen,
          "%r -> %r" % (old_gen, br._bots["test_bot1"]["gen"]))

    s = snap()
    check("snapshot：被停的 alive=false / 状态「已停止」",
          s["test_bot1"]["alive"] is False and s["test_bot1"]["status"] == "已停止",
          json.dumps(s["test_bot1"], ensure_ascii=False))
    check("snapshot：另一个仍 alive=true", s["test_bot2"]["alive"] is True,
          json.dumps(s["test_bot2"], ensure_ascii=False))

    print("\n== 3) 僵尸事件兜底 ==")
    check("旧 gen 的事件被判定为过期", br._stale("test_bot1", old_gen) is True)
    check("日志记了一次「忽略：来自已停止实例的事件」", has("忽略：来自已停止实例的事件"))
    n1 = len(br._state["log"])
    for _ in range(5):
        br._stale("test_bot1", old_gen)
    check("同一个 gen 只记一次，不刷屏", len(br._state["log"]) == n1,
          "多记了 %d 行" % (len(br._state["log"]) - n1))
    check("当前 gen 的事件不过滤（正常放行）",
          br._stale("test_bot2", br._bots["test_bot2"]["gen"]) is False)
    check("旧 gen 不影响在跑的 bot 的状态",
          br._stale("test_bot2", old_gen) is True and br._bots["test_bot2"]["alive"] is True)

    print("\n== 4) 停全部 ==")
    check("stop_all 返回 OK", br.stop_all() == "OK")
    check("两个 client 都真被关", wait_for(lambda: c2.closed, 3.0), "closed=%r" % c2.closed)
    check("两个 loop 都停了", wait_for(lambda: not c2.loop.is_running(), 3.0),
          "bot2 running=%r" % c2.loop.is_running())
    s = snap()
    check("snapshot 里 alive 全为 false",
          all(not b["alive"] for b in s.values()) and len(s) == 2,
          json.dumps(s, ensure_ascii=False))
    check("日志有「收到停止指令」与「已停止（loop 已停…）」",
          has("收到停止指令") and has("已停止（loop 已停，旧实例事件将被忽略）"))

    print("\n== 5) 边界 ==")
    check("重复 stop_all 不报错", br.stop_all() == "OK")
    check("停没在跑的 appid 返回「没在跑」", br.stop_one("test_不存在") == "没在跑")

    bad = [r for r in RESULTS if not r[1]]
    print("\n==== 结果：%d/%d 通过 ====" % (len(RESULTS) - len(bad), len(RESULTS)))
    for r in bad:
        print("  FAIL " + r[0] + "  " + r[2])
    shutil.rmtree(TMP, ignore_errors=True)
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
