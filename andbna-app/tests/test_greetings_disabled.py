# -*- coding: utf-8 -*-
"""host 端验证：时段可关（内置人格 + 自定义人格）、缺的时段不补回、定时消息下线。

    cd andbna-app && python3 tests/test_greetings_disabled.py
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

OK = []
FAIL = []


def check(name, cond, detail=""):
    (OK if cond else FAIL).append(name)
    print(("  ok   " if cond else "  FAIL ") + name + (("  <- " + str(detail)) if (detail and not cond) else ""))


def logs():
    return "\n".join(br._state["log"])


def clear_logs():
    del br._state["log"][:]


def write(path, obj):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False)
    return path


def read(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def mkbot(base, appid, persona_name, persona="你是测试人格。"):
    d = os.path.join(base, "localbot", appid)
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, "persona.txt"), "w", encoding="utf-8") as f:
        f.write(persona)
    with open(os.path.join(d, "persona_name.txt"), "w", encoding="utf-8") as f:
        f.write(persona_name)
    return d


class FakeApi(object):
    def __init__(self):
        self.c2c = []
        self.groups = []

    async def post_c2c_message(self, **kw):
        self.c2c.append(kw)

    async def post_group_message(self, **kw):
        self.groups.append(kw)


class FakeClient(object):
    def __init__(self):
        self.api = FakeApi()


# 页面写出来的内置人格文件：只有 name/at/window_min/disabled（lines 恒为空）
PRESET_FILE = {"preset": True, "enabled": True, "targets": {"owner": True, "groups": False},
               "slots": [
                   {"name": "早上", "at": "08:00", "window_min": 60, "lines": [], "disabled": False},
                   {"name": "午间", "at": "12:00", "window_min": 60, "lines": [], "disabled": True},
                   {"name": "深夜", "at": "22:00", "window_min": 60, "lines": [], "disabled": False},
               ]}


def plan(bot, date_s, slot_name, appid):
    slot = [x for x in br._greetings_conf(bot)["slots"] if x["name"] == slot_name][0]
    return br._slot_plan(bot, date_s, slot, appid)[0]


def test_preset_disabled():
    print("[1] 内置人格（桑多涅）关掉「午间」")
    base = tempfile.mkdtemp(prefix="andbna-off-")
    bot = mkbot(base, "botP", "桑多涅-完整")
    write(os.path.join(bot, "owner.json"), {"member_openid": "OWNER1234", "user_openid": "OWNER1234"})
    write(os.path.join(bot, "greetings.json"), PRESET_FILE)

    conf = br._greetings_conf(bot)
    check("三个时段都在（关掉 ≠ 删掉）", [s["name"] for s in conf["slots"]] == ["早上", "午间", "深夜"], conf["slots"])
    check("午间 disabled=true 被读出来", [s["disabled"] for s in conf["slots"]] == [False, True, False], conf["slots"])
    check("内容仍取自代码（文件 lines 为空也不影响）",
          conf["slots"][1]["lines"] == br._PRESET_GREETINGS["桑多涅"][1]["lines"], conf["slots"][1])

    client = FakeClient()
    date_s = "2026-10-30"
    noon = plan(bot, date_s, "午间", "botP")
    clear_logs()
    got = asyncio.get_event_loop().run_until_complete(br._maybe_greet(client, "botP", bot, now=noon + 1))
    check("午间窗口内一条都不发", got == "" and not client.api.c2c and not client.api.groups, (got, client.api))
    check("日志：随机问候跳过：时段 午间 已关闭", "随机问候跳过：时段 午间 已关闭" in logs(), logs())

    morning = plan(bot, date_s, "早上", "botP")
    got2 = asyncio.get_event_loop().run_until_complete(br._maybe_greet(client, "botP", bot, now=morning + 1))
    check("没关的时段照常发", got2 == "早上" and len(client.api.c2c) == 1, (got2, client.api.c2c))

    # at / window_min 覆盖仍然有效
    f = read(os.path.join(bot, "greetings.json"))
    f["slots"][1]["at"] = "12:30"
    write(os.path.join(bot, "greetings.json"), f)
    conf2 = br._greetings_conf(bot)
    check("内置人格仍可改时刻（12:30）", conf2["slots"][1]["at"] == "12:30", conf2["slots"][1])
    shutil.rmtree(base, ignore_errors=True)


def test_preset_no_backfill():
    print("[2] 内置人格：缺的时段不自动补回 / 文件全空才补默认三条")
    base = tempfile.mkdtemp(prefix="andbna-off-")
    bot = mkbot(base, "botP2", "桑多涅-完整")
    write(os.path.join(bot, "greetings.json"),
          {"preset": True, "enabled": True, "slots": [{"name": "早上", "at": "09:10", "window_min": 20, "lines": []}]})
    conf = br._greetings_conf(bot)
    check("删掉的「午间/深夜」不再被补回", [s["name"] for s in conf["slots"]] == ["早上"], conf["slots"])
    check("留下的那条时刻按文件", (conf["slots"][0]["at"], conf["slots"][0]["window_min"]) == ("09:10", 20), conf["slots"][0])

    write(os.path.join(bot, "greetings.json"), {"preset": True, "enabled": True, "slots": []})
    conf2 = br._greetings_conf(bot)
    check("文件里没有任何 slots（首次迁移/新建）→ 用内置默认三条",
          [s["name"] for s in conf2["slots"]] == ["早上", "午间", "深夜"], conf2["slots"])
    check("默认三条都是开着的", all(not s["disabled"] for s in conf2["slots"]), conf2["slots"])
    shutil.rmtree(base, ignore_errors=True)


def test_custom_disabled():
    print("[3] 自定义人格同样能关时段")
    base = tempfile.mkdtemp(prefix="andbna-off-")
    bot = mkbot(base, "botC", "自定义：我的角色")
    write(os.path.join(bot, "owner.json"), {"member_openid": "OWNER1234", "user_openid": "OWNER1234"})
    write(os.path.join(bot, "greetings.json"), {
        "preset": False, "enabled": True, "targets": {"owner": True, "groups": False},
        "slots": [
            {"name": "早上", "at": "08:00", "window_min": 60, "lines": ["早A"], "disabled": True},
            {"name": "午间", "at": "12:00", "window_min": 60, "lines": ["午A", "午B"], "disabled": False},
        ]})
    conf = br._greetings_conf(bot)
    check("自定义：disabled 读得出来", [s["disabled"] for s in conf["slots"]] == [True, False], conf["slots"])
    check("自定义：关掉那条的 lines 还留着（能再打开）", conf["slots"][0]["lines"] == ["早A"], conf["slots"][0])

    client = FakeClient()
    date_s = "2026-10-31"
    m = plan(bot, date_s, "早上", "botC")
    clear_logs()
    got = asyncio.get_event_loop().run_until_complete(br._maybe_greet(client, "botC", bot, now=m + 1))
    check("自定义：关掉的早上不发", got == "" and not client.api.c2c, (got, client.api.c2c))
    check("自定义：日志 随机问候跳过：时段 早上 已关闭", "随机问候跳过：时段 早上 已关闭" in logs(), logs())

    f = read(os.path.join(bot, "greetings.json"))
    f["slots"][0]["disabled"] = False
    write(os.path.join(bot, "greetings.json"), f)
    got2 = asyncio.get_event_loop().run_until_complete(br._maybe_greet(client, "botC", bot, now=m + 2))
    check("自定义：打开后同一时刻照常发", got2 == "早上" and len(client.api.c2c) == 1, (got2, client.api.c2c))
    shutil.rmtree(base, ignore_errors=True)


def test_timers_offline():
    print("[4] 定时消息下线：tasks.json 不执行、不删除")
    base = tempfile.mkdtemp(prefix="andbna-off-")
    bot = mkbot(base, "botS", "自定义：我的角色")
    tasks = [{"id": "t1", "appid": "botS", "kind": "c2c", "target": "OWNER1234",
              "text": "定时消息不该发", "next": 1.0, "enabled": True}]
    write(os.path.join(base, "tasks.json"), tasks)
    br._base_dir = base
    client = FakeClient()
    real_sleep = asyncio.sleep

    async def spin():
        async def no_sleep(t, *a, **k):
            await real_sleep(0)
        asyncio.sleep = no_sleep
        try:
            task = asyncio.ensure_future(br._sched_loop(client, "botS", bot))
            for _ in range(6):
                await real_sleep(0)
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass
        finally:
            asyncio.sleep = real_sleep

    clear_logs()
    br._timers_offline_logged.clear()
    asyncio.get_event_loop().run_until_complete(spin())
    check("tasks.json 非空 → 记「定时消息已下线（改由定时问候承担），本次不执行」",
          "定时消息已下线（改由定时问候承担），本次不执行" in logs(), logs())
    check("近到期的任务一条都没发", not client.api.c2c and not client.api.groups, client.api.c2c)
    check("tasks.json 文件还在、内容原样（保留数据不删）",
          os.path.isfile(os.path.join(base, "tasks.json")) and read(os.path.join(base, "tasks.json")) == tasks)
    check("日志只记一次（不刷屏）", logs().count("定时消息已下线") == 1, logs().count("定时消息已下线"))
    br._base_dir = ""
    shutil.rmtree(base, ignore_errors=True)


def main():
    test_preset_disabled()
    test_preset_no_backfill()
    test_custom_disabled()
    test_timers_offline()
    print("\n%d ok / %d fail" % (len(OK), len(FAIL)))
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
