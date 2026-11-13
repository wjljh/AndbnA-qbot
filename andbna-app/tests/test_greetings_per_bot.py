# -*- coding: utf-8 -*-
"""host 端验证：称呼 / 随机问候改成每个 bot 一份，且问候按时段随机主动私聊发。

直接 import 真模块 app/src/main/python/bot_runner.py，stub 掉 client/api，
不装 app、不连 QQ，python3 就能跑：

    cd andbna-app && python3 tests/test_greetings_per_bot.py
"""
import asyncio
import json
import os
import shutil
import sys
import re
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


def mkbot(base, appid, persona_name=None, persona="你是测试人格。"):
    d = os.path.join(base, "localbot", appid)
    os.makedirs(d, exist_ok=True)
    write(os.path.join(d, "persona.txt"), persona) if False else None
    with open(os.path.join(d, "persona.txt"), "w", encoding="utf-8") as f:
        f.write(persona)
    if persona_name:
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


# ── 1 · 迁移：旧全局 phrases.json → 每个 bot 一份，tics 忽略 ────────────
def test_migrate():
    print("[1] 迁移")
    base = tempfile.mkdtemp(prefix="andbna-words-")
    write(os.path.join(base, "phrases.json"), {
        "tics": ["咕噜", "哼唧"],
        "aliases": {"主人": "主人", "群友": "小可爱"},
    })
    write(os.path.join(base, "greetings.json"), ["早上好呀，今天也要开心", "在的，找我吗"])

    preset_bot = mkbot(base, "botPreset", "哥伦比娅-完整")
    custom_bot = mkbot(base, "botCustom", "自定义：我的角色")

    clear_logs()
    br._migrate_words(preset_bot, "botPreset")
    br._migrate_words(custom_bot, "botCustom")

    ph = read(os.path.join(preset_bot, "phrases.json"))
    check("phrases.json 只有 aliases 两键", set(ph.keys()) == {"aliases"} and set(ph["aliases"].keys()) == {"master", "others"}, ph)
    check("旧 aliases 归并到 master/others", ph["aliases"] == {"master": "主人", "others": "小可爱"}, ph)
    check("tics 被忽略（没落进新文件）", "tics" not in json.dumps(ph, ensure_ascii=False))
    check("内置人格 greetings.json preset=true、slots 空（内容从代码来）",
          read(os.path.join(preset_bot, "greetings.json"))["preset"] is True
          and read(os.path.join(preset_bot, "greetings.json"))["slots"] == [])
    g_p, g_c = read(os.path.join(preset_bot, "greetings.json")), read(os.path.join(custom_bot, "greetings.json"))
    check("迁移后：内置人格默认开、自定义人格先关着",
          g_p.get("enabled") is True and g_c.get("enabled") is False, (g_p.get("enabled"), g_c.get("enabled")))
    check("迁移后默认发主人+群：targets 两个都开",
          g_p.get("targets") == {"owner": True, "groups": True}
          and g_c.get("targets") == {"owner": True, "groups": True}, (g_p.get("targets"), g_c.get("targets")))
    cc = read(os.path.join(custom_bot, "greetings.json"))
    check("自定义人格 preset=false，旧问候语并成一个时段",
          cc["preset"] is False and len(cc["slots"]) == 1 and cc["slots"][0]["lines"] == ["早上好呀，今天也要开心", "在的，找我吗"], cc)

    before = json.dumps([read(os.path.join(preset_bot, "phrases.json")), read(os.path.join(preset_bot, "greetings.json"))], ensure_ascii=False)
    br._migrate_words(preset_bot, "botPreset")
    after = json.dumps([read(os.path.join(preset_bot, "phrases.json")), read(os.path.join(preset_bot, "greetings.json"))], ensure_ascii=False)
    check("迁移幂等（跑第二次不改文件）", before == after)

    conf = br._greetings_conf(preset_bot)
    check("内置人格三个固定时段来自代码",
          conf["preset"] is True and [s["name"] for s in conf["slots"]] == ["早上", "午间", "深夜"], conf)
    check("内置人格默认窗口是整点一小时（08:00-09:00 / 12:00-13:00 / 22:00-23:00）",
          [br._window_label(x) for x in conf["slots"]] == ["08:00-09:00", "12:00-13:00", "22:00-23:00"],
          [br._window_label(x) for x in conf["slots"]])
    check("内置人格内容锁死（早上那组就是代码里那 2 条）",
          conf["slots"][0]["lines"] == br._PRESET_GREETINGS["哥伦比娅"][0]["lines"], conf["slots"][0])

    # 内置人格：同名时段只能改时间，lines 仍取代码；名字对不上的时段被忽略
    g = read(os.path.join(preset_bot, "greetings.json"))
    g["slots"] = [
        {"name": "早上", "at": "07:10", "window_min": 7, "lines": ["被忽略的假内容"]},
        {"name": "瞎写", "at": "09:00", "window_min": 30, "lines": ["也不该出现"]},
    ]
    write(os.path.join(preset_bot, "greetings.json"), g)
    conf2 = br._greetings_conf(preset_bot)
    names = [s["name"] for s in conf2["slots"]]
    check("内置人格：文件里缺的时段不再自动补回（只留写了的那条），瞎写的被丢掉",
          names == ["早上"], names)
    check("内置人格：at/window_min 可按文件改", (conf2["slots"][0]["at"], conf2["slots"][0]["window_min"]) == ("07:10", 7), conf2["slots"][0])
    check("内置人格：lines 仍是代码里那几条（改不动）",
          conf2["slots"][0]["lines"] == br._PRESET_GREETINGS["哥伦比娅"][0]["lines"], conf2["slots"][0]["lines"])
    shutil.rmtree(base, ignore_errors=True)


# ── 2 · 注入：非主人**永远**有硬规则；主人按 master 配置给 ────────────
def test_inject():
    print("[2] 注入")
    base = tempfile.mkdtemp(prefix="andbna-words-")
    # 本段断言「称呼 / 注入称呼」技术行 → 按 detail 跑
    br._base_dir = base
    write(os.path.join(base, "llm.json"), {"log_level": "detail"})
    bot = mkbot(base, "botA")
    clear_logs()
    rules = br._words_rules(bot, "gGROUP99")
    check("aliases 空 → 非主人照样注入硬规则（禁止「主人」+ 用「你」）",
          "禁止用「主人」称呼对方" in rules and "「你」" in rules and "注入称呼" in logs(), rules)
    sysmsg = br._build_messages(bot, "g1", "小明", "在吗", first=True)[0]["content"]
    check("aliases 空 → prompt 里没有 master 那句", "称呼主人为" not in sysmsg)
    check("首句不再注入问候", "可以用这句开场" not in sysmsg and "问候" not in sysmsg)

    write(os.path.join(bot, "phrases.json"), {"aliases": {"master": "阿爸", "others": "小可爱"}})
    write(os.path.join(bot, "owner.json"), {"member_openid": "OWNER1234", "user_openid": "OWNER1234"})
    clear_logs()
    rules = br._words_rules(bot, "pOWNER1234")
    check("主人私聊 → 只给 master 那句（祈使句）",
          "请用「阿爸」称呼对方" in rules and "禁止用「主人」" not in rules, rules)
    check("日志出现注入的称呼规则（含配置值）", "注入称呼" in logs() and "阿爸" in logs(), logs())
    check("日志写出对象判定（主人 + openid 前 8 位）",
          "称呼：对象=主人（openid 前 8 位 OWNER123）" in logs(), logs())
    sysmsg = br._build_messages(bot, "pOWNER1234", "", "在吗")[0]["content"]
    check("主人私聊的 prompt 带 master 称呼（人格之后、祈使句）",
          "请用「阿爸」称呼对方" in sysmsg and "小可爱" not in sysmsg, sysmsg[-80:])
    check("prompt 里不再有口癖注入", "口头习惯" not in sysmsg)

    clear_logs()
    rules2 = br._words_rules(bot, "gGROUP99")
    check("群聊 → 只给 others 那句 + 禁止「主人」",
          "请称呼对方为「小可爱」" in rules2 and "禁止用「主人」称呼对方" in rules2
          and "阿爸" not in rules2, rules2)
    # 群里的判定改成按 openid（1.1.x：「不认主」修复），日志带上判据；这里没传 member_oid
    check("日志写明对象=其他人", "称呼：对象=其他人（群=GROUP99｜作者前 8 位 未知）" in logs(), logs())
    sysmsg2 = br._build_messages(bot, "gGROUP99", "小明", "在吗")[0]["content"]
    check("群聊的 prompt 只有 others，没有 master",
          "小可爱" in sysmsg2 and "阿爸" not in sysmsg2 and "称呼主人为" not in sysmsg2,
          sysmsg2[-80:])

    write(os.path.join(bot, "phrases.json"), {"aliases": {"master": "", "others": "小可爱"}})
    clear_logs()
    check("master 为空 → 主人私聊不加规则（不硬塞默认词）", br._words_rules(bot, "pOWNER1234") == "")
    clear_logs()
    check("others 有值 → 群聊照常加", "请称呼对方为「小可爱」" in br._words_rules(bot, "gGROUP99"))

    # 没有 owner.json 但有 known_chats 的 c2c → 仍能认定主人
    os.remove(os.path.join(bot, "owner.json"))
    shutil.rmtree(base, ignore_errors=True)


# ── 3 · 随机问候：时段窗口内错开、目标可选、当天每目标一次 ──────────
SLOTS = [
    {"name": "早上", "at": "08:00", "window_min": 60, "lines": ["早A", "早B"]},
    {"name": "午间", "at": "12:00", "window_min": 30, "lines": ["午A", "午B"]},
    {"name": "深夜", "at": "22:00", "window_min": 45, "lines": ["深A", "深B"]},
]


def plan_of(bot, date_s, slot_name, appid="botG"):
    slot = [x for x in SLOTS if x["name"] == slot_name][0]
    return br._slot_plan(bot, date_s, slot, appid)[0]


def test_greet():
    print("[3] 随机问候（owner 目标）")
    base = tempfile.mkdtemp(prefix="andbna-words-")
    bot = mkbot(base, "botG")
    write(os.path.join(bot, "owner.json"), {"member_openid": "OWNER1234", "user_openid": "OWNER1234"})
    write(os.path.join(bot, "greetings.json"),
          {"preset": False, "enabled": True, "targets": {"owner": True, "groups": False}, "slots": SLOTS})
    client = FakeClient()
    date_s = "2026-10-30"

    plan = plan_of(bot, date_s, "早上")
    lt = time.localtime(plan)
    check("触发时刻落在 [at, at+window) 里（08:00~09:00）", lt.tm_hour == 8, time.strftime("%H:%M:%S", lt))
    check("同一 bot 同一天算两次是同一刻（重启不变）", plan_of(bot, date_s, "早上") == plan)
    check("不同 bot 同一时段错开", plan_of(bot, date_s, "早上", "botOther") != plan,
          (time.strftime("%H:%M:%S", lt),))

    clear_logs()
    got0 = asyncio.get_event_loop().run_until_complete(br._maybe_greet(client, "botG", bot, now=plan - 120))
    check("到点前不发", got0 == "" and not client.api.c2c, got0)
    check("日志写出触发时刻与窗口",
          ("随机问候：触发时刻 %s（时段 早上｜窗口 08:00-09:00）" % time.strftime("%H:%M", time.localtime(plan))) in logs(),
          logs())

    clear_logs()
    got = asyncio.get_event_loop().run_until_complete(br._maybe_greet(client, "botG", bot, now=plan + 1))
    check("到点发一条到主人私聊", got == "早上" and len(client.api.c2c) == 1, (got, client.api.c2c))
    check("不发群（targets.groups=false）", len(client.api.groups) == 0)
    check("日志：发主人私聊 成功", "随机问候：发主人私聊 成功（openid 前 8 位 OWNER123｜时段 早上｜窗口 08:00-09:00）" in logs(), logs())
    check("内容来自本时段 lines", client.api.c2c[0]["content"] in SLOTS[0]["lines"])
    check("greeted.json 记「时段|目标」", read(os.path.join(bot, "greeted.json")) ==
          {"date": date_s, "sent": ["早上|owner"]}, read(os.path.join(bot, "greeted.json")))

    clear_logs()
    got2 = asyncio.get_event_loop().run_until_complete(br._maybe_greet(client, "botG", bot, now=plan + 2))
    check("同一天第二次不发", got2 == "" and len(client.api.c2c) == 1)
    check("跳过日志：主人私聊今天已经发过", "随机问候跳过：主人私聊 今天已经发过（时段 早上）" in logs(), logs())

    plan_next = plan_of(bot, "2026-10-31", "早上")
    got3 = asyncio.get_event_loop().run_until_complete(br._maybe_greet(client, "botG", bot, now=plan_next + 1))
    check("跨天可再发", got3 == "早上" and len(client.api.c2c) == 2, (got3, len(client.api.c2c)))

    plan_noon = plan_of(bot, date_s, "午间")
    sent_before = len(client.api.c2c)
    got4 = asyncio.get_event_loop().run_until_complete(br._maybe_greet(client, "botG", bot, now=plan_noon + 1))
    check("午间只从午间那组抽", got4 == "午间" and client.api.c2c[-1]["content"] in SLOTS[1]["lines"], got4)

    # 没有 owner
    os.remove(os.path.join(bot, "owner.json"))
    plan_night = plan_of(bot, "2026-10-29", "深夜")
    sent_before = len(client.api.c2c)
    clear_logs()
    got6 = asyncio.get_event_loop().run_until_complete(br._maybe_greet(client, "botG", bot, now=plan_night + 1))
    check("没有 owner → 不发", got6 == "" and len(client.api.c2c) == sent_before)
    check("跳过日志：没有主人 openid", "随机问候跳过：没有主人 openid" in logs(), logs())

    # enabled=false
    write(os.path.join(bot, "owner.json"), {"member_openid": "OWNER1234", "user_openid": "OWNER1234"})
    write(os.path.join(bot, "greetings.json"),
          {"preset": False, "enabled": False, "targets": {"owner": True, "groups": False}, "slots": SLOTS})
    plan_off = plan_of(bot, "2026-11-02", "早上")
    sent_before = len(client.api.c2c)
    clear_logs()
    got_off = asyncio.get_event_loop().run_until_complete(br._maybe_greet(client, "botG", bot, now=plan_off + 1))
    check("enabled=false → 一条都不发", got_off == "" and len(client.api.c2c) == sent_before, got_off)
    check("跳过日志：未开启", "随机问候跳过：未开启" in logs(), logs())

    # 文件里没写 enabled / targets 时按默认（开、主人+群）
    write(os.path.join(bot, "greetings.json"), {"preset": False, "slots": SLOTS})
    conf_def = br._greetings_conf(bot)
    check("缺 enabled/targets 的文件按默认开", conf_def["enabled"] is True
          and conf_def["targets"] == {"owner": True, "groups": True}, conf_def)

    # 错开证明：真机那 4 个 appid，同一时段同一��触发时刻互不相同
    slot_night = br._PRESET_GREETINGS["沃雅妮莎"][2]
    appids = ["1905607232", "1905616312", "1905687965", "1905688928"]
    times = [br._slot_plan(bot, "2026-10-30", slot_night, a)[0] for a in appids]
    shown = [time.strftime("%H:%M:%S", time.localtime(t)) for t in times]
    check("四个 bot 同一时段触发时刻互不相同（错开）", len(set(times)) == 4, shown)
    check("四台都落在 22:00-23:00 内",
          all(time.localtime(t).tm_hour == 22 for t in times), shown)

    # slots 空（enabled 开）
    write(os.path.join(bot, "greetings.json"),
          {"preset": False, "enabled": True, "targets": {"owner": True, "groups": False}, "slots": []})
    clear_logs()
    got7 = asyncio.get_event_loop().run_until_complete(br._maybe_greet(client, "botG", bot, now=plan_off + 1))
    check("slots 空 → 不发", got7 == "" and len(client.api.c2c) == sent_before)
    # 非预设人格 + 槽位没填 lines：日志要说清「没有可用文案」，不能只说「未配置时段」
    check("跳过日志：没有可用文案", "问候：没有可用文案（非预设人格且槽位未填 lines）→ 本轮跳过" in logs(),
          logs())
    shutil.rmtree(base, ignore_errors=True)


def test_targets():
    print("[4] 发送目标 owner / groups")
    base = tempfile.mkdtemp(prefix="andbna-words-")
    bot = mkbot(base, "botT")
    write(os.path.join(bot, "owner.json"), {"member_openid": "OWNER1234", "user_openid": "OWNER1234"})
    write(os.path.join(bot, "known_chats.json"), {"chats": [
        {"openid": "GROUPAAAA1111", "kind": "group"},
        {"openid": "GROUPBBBB2222", "kind": "group"},
        {"openid": "OWNER1234", "kind": "c2c"},
    ]})
    write(os.path.join(bot, "greetings.json"),
          {"preset": False, "enabled": True, "targets": {"owner": False, "groups": True}, "slots": SLOTS})
    client = FakeClient()
    date_s = "2026-10-30"
    plan = plan_of(bot, date_s, "早上", "botT")

    clear_logs()
    got = asyncio.get_event_loop().run_until_complete(br._maybe_greet(client, "botT", bot, now=plan + 1))
    check("groups=true → 逐群各发一条", got == "早上" and len(client.api.groups) == 2, (got, len(client.api.groups)))
    check("groups=true 且 owner=false → 不私聊", len(client.api.c2c) == 0)
    check("日志：发群（openid 前 8 位）",
          "随机问候：发群 GROUPAAA… 成功（时段 早上｜窗口 08:00-09:00）" in logs(), logs())

    got2 = asyncio.get_event_loop().run_until_complete(br._maybe_greet(client, "botT", bot, now=plan + 2))
    check("同一天第二次不再发群", got2 == "" and len(client.api.groups) == 2, (got2, len(client.api.groups)))

    # 两个目标都开
    write(os.path.join(bot, "greetings.json"),
          {"preset": False, "enabled": True, "targets": {"owner": True, "groups": True}, "slots": SLOTS})
    plan2 = plan_of(bot, "2026-11-03", "早上", "botT")
    c2c_before, grp_before = len(client.api.c2c), len(client.api.groups)
    got3 = asyncio.get_event_loop().run_until_complete(br._maybe_greet(client, "botT", bot, now=plan2 + 1))
    check("owner+groups 都开 → 私聊 1 条 + 群 2 条",
          got3 == "早上" and len(client.api.c2c) == c2c_before + 1 and len(client.api.groups) == grp_before + 2,
          (len(client.api.c2c), len(client.api.groups)))

    # 开了 groups 却还没记录到群
    os.remove(os.path.join(bot, "known_chats.json"))
    plan3 = plan_of(bot, "2026-11-04", "早上", "botT")
    clear_logs()
    got4 = asyncio.get_event_loop().run_until_complete(br._maybe_greet(client, "botT", bot, now=plan3 + 1))
    check("没有已知群 → 跳过并记日志",
          "随机问候跳过：没有已知群" in logs() and len(client.api.groups) == grp_before + 2, logs())
    shutil.rmtree(base, ignore_errors=True)


# ── 5 · bot 能看时间：prompt 带当前时间 + 本地指令直接回 ────────────
class FakeAuthor2(object):
    def __init__(self, oid="OWNER1234"):
        self.user_openid = oid
        self.member_openid = oid


class FakeMsg(object):
    def __init__(self, api, oid="OWNER1234", mid="MSGID"):
        self._api = api
        self.author = FakeAuthor2(oid)
        self.id = mid


def test_time():
    print("[5] 时间")
    base = tempfile.mkdtemp(prefix="andbna-words-")
    bot = mkbot(base, "botTime")
    sysmsg = br._build_messages(bot, "g1", "", "在吗")[0]["content"]
    check("prompt 里带一行当前时间", ("现在是 %s。" % br._now_text()) in sysmsg, sysmsg[-120:])
    check("时间格式 YYYY-MM-DD 星期X HH:MM",
          re.match(r"^\d{4}-\d{2}-\d{2} 星期[一二三四五六日] \d{2}:\d{2}$", br._now_text()) is not None, br._now_text())
    for t in ("#时间", "现在几点", "几点了？", "#time"):
        check("识别本地指令：%s" % t, br._is_time_cmd(t))
    for t in ("你好", "现在几点睡觉", ""):
        check("不是本地指令：%r" % t, not br._is_time_cmd(t))

    client = FakeClient()
    msg = FakeMsg(client.api)
    clear_logs()
    handled = asyncio.get_event_loop().run_until_complete(
        br._local_time_reply("botTime", msg, "现在几点？", key="pOWNER1234"))
    check("私聊 #时间 → 立即回本地时间（不调模型）",
          handled and len(client.api.c2c) == 1 and client.api.c2c[0]["content"] == br._now_text(), client.api.c2c)
    check("日志：本地指令 #时间", "本地指令 #时间：直接回" in logs(), logs())

    grp_before = len(client.api.groups)
    handled2 = asyncio.get_event_loop().run_until_complete(
        br._local_time_reply("botTime", msg, "#时间", group_openid="GROUPAAAA1111"))
    check("群里 #时间 → 回群同样不走模型",
          handled2 and len(client.api.groups) == grp_before + 1
          and client.api.groups[-1]["group_openid"] == "GROUPAAAA1111", client.api.groups[-1:])

    handled3 = asyncio.get_event_loop().run_until_complete(
        br._local_time_reply("botTime", msg, "今天好累", key="pOWNER1234"))
    check("普通聊天不拦（照常进模型流程）", handled3 is False and len(client.api.c2c) == 1)
    shutil.rmtree(base, ignore_errors=True)


def main():
    test_migrate()
    test_inject()
    test_greet()
    test_targets()
    test_time()
    print("\n%d ok / %d fail" % (len(OK), len(FAIL)))
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
