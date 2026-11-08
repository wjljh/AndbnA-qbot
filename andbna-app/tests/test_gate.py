# -*- coding: utf-8 -*-
"""host 端验证：多 bot 群聊总模型（gatekeeper）/ 6 句上限 / 成对静默 / 人格摘要。

直接 import 真模块 app/src/main/python/bot_runner.py，stub 掉模型调用与 client/api，
不装 app、不连 QQ，python3 就能跑：

    cd andbna-app && python3 tests/test_gate.py

覆盖（对应任务书验收 1）：
  单 bot 不走总模型 / 多 bot 走总模型 / 总模型失败退回自判 / 6 句上限触发 end_topic /
  成对静默（A→B 静默但 A→C 可回）/ 真人消息不受静默 / 超 40 条降 conservative /
  人格摘要 hash 未变不重生成 / 真人 id 绝不进 bot_ids.json / 未知 bot id 标 [其他bot]
"""
import asyncio
import json
import os
import shutil
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "app", "src", "main", "python"))

import bot_runner as br  # noqa: E402

GID = "group_openid_gate"
TMP = tempfile.mkdtemp(prefix="gate_test_")
BASE = os.path.join(TMP, "localbot")          # = filesDir/localbot
BOTS = [
    ("botA", "桑多涅"),
    ("botB", "哥伦比娅"),
    ("botC", "奥黛塔"),
]
RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), str(detail)))
    print(("PASS " if ok else "FAIL ") + name + (("   " + str(detail)) if detail else ""))


# ── 假件 ────────────────────────────────────────────────

class FakeAuthor(object):
    def __init__(self, oid, bot=None):
        self.member_openid = oid
        self.user_openid = oid
        self.id = oid
        if bot is not None:
            self.bot = bot


class FakeApi(object):
    def __init__(self):
        self.sent = []

    async def post_group_message(self, **kw):
        self.sent.append(kw)


API = FakeApi()


class FakeMsg(object):
    def __init__(self, mid, text, author, mentions=None, gid=GID):
        self.id = mid
        self.content = text
        self.group_openid = gid
        self.author = author
        self.mentions = mentions or []
        self.raw = {}
        self._api = API


GATE = {"calls": [], "ret": None, "exc": None}
REPLY = {"calls": []}


async def fake_gate_model(conf, prompt):
    GATE["calls"].append(prompt)
    if GATE["exc"] is not None:
        raise GATE["exc"]
    return GATE["ret"]


async def fake_reply(appid, botdir, key, who, incoming, images=None, extra_sys=""):
    REPLY["calls"].append({"appid": appid, "who": who, "incoming": incoming, "extra": extra_sys})
    return "（假回复）"


br._gate_model = fake_gate_model
br._reply = fake_reply


def write_llm(**over):
    conf = {
        "api": "http://127.0.0.1:9/chat/completions",
        "key": "test-key",
        "model": "deepseek-chat",
        "judge_model": "",
        "gate_enabled": True,
        "bot_max_turns": 6,
        "bot_silence_minutes": 10,
        "human_hourly_limit": 40,
        "reply_delay_min_ms": 0,
        "reply_delay_max_ms": 0,
        "log_level": "detail",
    }
    conf.update(over)
    with open(os.path.join(BASE, "llm.json"), "w", encoding="utf-8") as f:
        json.dump(conf, f, ensure_ascii=False)


def write_bots(rows=None):
    rows = rows if rows is not None else [{"appid": a, "name": n, "enabled": True}
                                          for a, n in BOTS]
    with open(os.path.join(TMP, "localbots.json"), "w", encoding="utf-8") as f:
        json.dump(rows, f, ensure_ascii=False)


def botdir(appid):
    return os.path.join(BASE, appid)


def reset_page():
    """每个用例前把「只放内存」的那一堆状态清干净。"""
    br._gate_state["ctx"].clear()
    br._gate_state["turns"].clear()
    br._gate_state["chain"].clear()
    br._gate_state["silence"].clear()
    br._gate_state["human"].clear()
    br._gate_state["spoken"].clear()
    br._gate_state["counted"].clear()
    br._gate_state["downgrade"].clear()
    br._gate_state["who_log"] = 6
    br._gate_rec.clear()
    br._roster_cache["stamp"] = None
    br._dedup.clear()
    br._seen_set.clear()
    del br._seen_ids[:]
    br._outbox.clear()
    br._rate.clear()
    br._intent.clear()
    br._mute.clear()
    br._state["log"][:]
    API.sent[:] = []
    GATE["calls"][:] = []
    GATE["ret"], GATE["exc"] = None, None
    REPLY["calls"][:] = []


def logs():
    return list(br._state["log"])


def has(sub):
    return any(sub in x for x in logs())


def run(appid, msg, tag="群消息(新事件)"):
    return asyncio.run(br._handle_group(appid, botdir(appid), msg, tag))


def human(i, text):
    return FakeMsg("m_h%d" % i, text, FakeAuthor("user_oid_%d" % i, bot=False))


def from_bot(i, text, oid="oid_sd"):
    return FakeMsg("m_b%d" % i, text, FakeAuthor(oid, bot=True))


def main():
    os.makedirs(BASE, exist_ok=True)
    br._base_dir = BASE
    write_llm()
    write_bots()
    for appid, name in BOTS:
        d = botdir(appid)
        os.makedirs(d, exist_ok=True)
        with open(os.path.join(d, "persona_name.txt"), "w", encoding="utf-8") as f:
            f.write(name + "-完整\n")

    # ── a. 单 bot：不走总模型 ──────────────────────────────
    reset_page()
    write_bots([{"appid": "botA", "name": "桑多涅", "enabled": True}])
    br.set_reply_judge("random", 1.0)
    br.set_group_autochat("", "", True)   # 群里自动接话开（多 bot 群里总模型才有活干）
    GATE["ret"] = json.dumps({"action": "reply", "bot": "桑多涅", "why": "x"})
    run("botA", human(1, "今天谁在"))
    check("a 单 bot（启用数=1）不走总模型",
          len(GATE["calls"]) == 0 and not has("总模型") and len(API.sent) == 1,
          "gate=%d posts=%d" % (len(GATE["calls"]), len(API.sent)))

    # ── b. 多 bot：走总模型，一条消息只一个人回 ────────────
    reset_page()
    write_bots()
    GATE["ret"] = json.dumps({"action": "reply", "bot": "桑多涅", "why": "该她接"})
    msg = human(2, "这个结构算对了吗")
    for appid, _n in BOTS:                 # 三个 bot 各收到一次同一条消息
        run(appid, msg)
    senders = [c["appid"] for c in REPLY["calls"]]
    prompt = GATE["calls"][0] if GATE["calls"] else ""
    check("b 多 bot：总模型只跑一次（跨 bot 共享决定）", len(GATE["calls"]) == 1,
          "gate_calls=%d" % len(GATE["calls"]))
    check("b2 多 bot：只有被点名的 bot 回", senders == ["botA"] and len(API.sent) == 1,
          "reply=%s posts=%d" % (senders, len(API.sent)))
    check("b3 提示词含发言人清单 / 摘要 / 真人标签",
          "桑多涅：" in prompt and "[作者 " in prompt and "群内最近消息" in prompt,
          prompt[:60].replace(chr(10), " "))

    # ── c. 总模型失败：退回自判（互斥仍生效）──────────────
    reset_page()
    GATE["exc"] = RuntimeError("boom")
    msg = human(3, "这句随便聊聊")
    for appid, _n in BOTS:
        run(appid, msg)
    check("c 总模型失败 → 退回自判，且日志写清",
          has("总模型失败，退回自判") and len(API.sent) == 1,
          "posts=%d reply=%s" % (len(API.sent), [r["appid"] for r in REPLY["calls"]]))

    # ── d. 6 句上限 → end_topic + 成对静默 ────────────────
    reset_page()
    # bot_ids.json 只收 bot（这里模拟各 bot 已经学到的那份）
    br._save_json(br._bot_ids_paths(botdir("botB"))[0], {"oid_sd": "botA"})
    br._save_json(br._bot_ids_paths(botdir("botA"))[0], {"oid_gld": "botB"})
    GATE["ret"] = json.dumps({"action": "reply", "bot": "哥伦比娅", "why": "让月亮接"})
    for i in range(6):                     # 桑多涅连说 6 句（都被哥伦比娅收到）
        run("botB", from_bot(i, "桑多涅第 %d 句" % i))
    check("d1 连续 bot 链句数累积到 6", br._turn_of(GID, "桑多涅") == 6,
          "turns=%s chain=%s" % (br._turn_of(GID, "桑多涅"), br._chain_of(GID)))
    # 第 7 条：哥伦比娅说话，总模型想点桑多涅 → 代码按「已达上限」改成让她收尾
    GATE["ret"] = json.dumps({"action": "reply", "bot": "桑多涅", "why": "继续聊"})
    run("botA", from_bot(99, "哥伦比娅问一句", oid="oid_gld"))
    cover = REPLY["calls"][-1] if REPLY["calls"] else {}
    check("d2 6 句上限触发 end_topic", has("总模型：end_topic(桑多涅"),
          [x for x in logs() if "总模型" in x][-2:])
    check("d3 收尾注入了「连着说了 N 句」的系统提示",
          "连着说了 6 句" in str(cover.get("extra") or ""),
          str(cover.get("extra") or "")[:40])
    check("d4 收尾后成对静默生效（桑多涅→哥伦比娅）",
          has("静默生效：桑多涅→哥伦比娅") and br._silenced(GID, "桑多涅", "哥伦比娅"),
          [x for x in logs() if "静默" in x])

    # ── e. 成对静默：A→B 静默，但 A→C 照常回 ──────────────
    reset_page()
    br._save_json(br._bot_ids_paths(botdir("botA"))[0],
                  {"oid_gld": "botB", "oid_odt": "botC"})
    br._silence_set(GID, "桑多涅", "哥伦比娅", 10)
    GATE["ret"] = json.dumps({"action": "reply", "bot": "桑多涅", "why": "她想接"})
    run("botA", from_bot(201, "哥伦比娅说话", oid="oid_gld"))
    check("e1 A→B 静默：A 不回 B 的话",
          len(API.sent) == 0 and has("成对静默"),
          "posts=%d" % len(API.sent))
    reset_page()
    br._save_json(br._bot_ids_paths(botdir("botA"))[0],
                  {"oid_gld": "botB", "oid_odt": "botC"})
    br._silence_set(GID, "桑多涅", "哥伦比娅", 10)
    GATE["ret"] = json.dumps({"action": "reply", "bot": "桑多涅", "why": "她想接"})
    run("botA", from_bot(202, "奥黛塔说话", oid="oid_odt"))
    check("e2 A→C 没静默：A 照常能回", len(API.sent) == 1,
          "posts=%d logs=%s" % (len(API.sent), [x for x in logs() if "静默" in x][:2]))

    # ── f. 真人消息不受静默影响 ───────────────────────────
    reset_page()
    br._silence_set(GID, "桑多涅", "哥伦比娅", 10)
    GATE["ret"] = json.dumps({"action": "reply", "bot": "桑多涅", "why": "真人问的"})
    run("botA", human(7, "有人吗"))
    check("f 真人消息不受成对静默影响", len(API.sent) == 1, "posts=%d" % len(API.sent))

    # ── g. 真人一小时超软上限 → 判定降 conservative（不硬拒）──
    reset_page()
    GATE["ret"] = json.dumps({"action": "skip", "bot": "", "why": "不接"})
    for i in range(41):
        run("botA", human(300 + i, "第 %d 句" % i))
    lv, n = br._gate_level(GID, br._gate_conf(BASE), "")
    check("g 真人超 40 条 → conservative（不硬拒）",
          lv == "conservative" and has("降为 conservative"),
          "level=%s n=%d" % (lv, n))

    # ── h. 人格摘要：hash 没变不重生成 ────────────────────
    calls = {"n": 0}

    def fake_summary(conf, persona_text, appid=""):
        calls["n"] += 1
        return "这是一段测试摘要"

    old_call = br._summary_call
    br._summary_call = fake_summary
    d = botdir("botD")
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, "persona.txt"), "w", encoding="utf-8") as f:
        f.write("这是一个自定义人格的正文第一版")
    with open(os.path.join(d, "persona_name.txt"), "w", encoding="utf-8") as f:
        f.write("自定义：测试角色\n")
    s1 = br.persona_summary_sync(d, "botD")
    s2 = br.persona_summary_sync(d, "botD")
    check("h1 摘要生成一次后写盘", s1 == "这是一段测试摘要" and calls["n"] == 1,
          "calls=%d s=%s" % (calls["n"], s1))
    check("h2 人格 hash 未变 → 不重新生成", calls["n"] == 1 and s2 == s1,
          "calls=%d" % calls["n"])
    with open(os.path.join(d, "persona.txt"), "w", encoding="utf-8") as f:
        f.write("这是一个自定义人格的正文第二版（改过了）")
    br.persona_summary_sync(d, "botD")
    check("h3 人格改过 → 才会重新生成", calls["n"] == 2, "calls=%d" % calls["n"])
    # 内置人格用写死的摘要，不调模型
    before = calls["n"]
    builtin = br.persona_summary(botdir("botA"))
    check("h4 内置人格摘要写死不调模型",
          calls["n"] == before and len(builtin) >= 300,
          "len=%d calls=%d" % (len(builtin), calls["n"]))
    br._summary_call = old_call

    # ── i. bot_ids.json：只记 bot，真人 id 绝不写进去 ──────
    reset_page()
    GATE["ret"] = json.dumps({"action": "skip", "bot": "", "why": "不接"})
    run("botA", human(500, "真人的一句话"))
    table = br._bot_ids(botdir("botA"))
    check("i1 真人 openid 绝不进 bot_ids.json",
          "user_oid_500" not in table and not any(
              v == "user_oid_500" for v in table.values()),
          "table=%s" % table)

    # ── j. author.bot=true 但表里没有 → 标 [其他bot] ───────
    reset_page()
    GATE["ret"] = json.dumps({"action": "skip", "bot": "", "why": "不接"})
    run("botA", from_bot(600, "陌生 bot 说的", oid="oid_unknown_bot"))
    rows = br._ctx_rows("botA", GID)
    label = rows[-1]["who"] if rows else ""
    check("j 未知 bot id → [其他bot] 标签", label == "[其他bot]", "label=%r" % label)

    # ── k. bot_ids.json 命中 → [名字] 标签（表里只会有 bot）──
    reset_page()
    br._save_json(br._bot_ids_paths(botdir("botA"))[0], {"oid_sd": "botA"})
    GATE["ret"] = json.dumps({"action": "skip", "bot": "", "why": "不接"})
    run("botA", from_bot(700, "这是桑多涅说的", oid="oid_sd"))
    rows = br._ctx_rows("botA", GID)
    label = rows[-1]["who"] if rows else ""
    check("k bot_ids 命中 → [桑多涅] 标签", label == "[桑多涅]", "label=%r" % label)

    # ── l. 去重键：完整打印 + 同内容 3 秒短窗 ──────────────
    reset_page()
    GATE["ret"] = json.dumps({"action": "skip", "bot": "", "why": "不接"})
    run("botA", FakeMsg("ROBOT1.0_u0Iabcdefgh", "重复投递测试", FakeAuthor("user_dup", bot=False)))
    run("botA", FakeMsg("ROBOT1.0_u0Iabcdefgh", "重复投递测试", FakeAuthor("user_dup", bot=False)))
    run("botA", FakeMsg("ROBOT1.0_u0Iabcdefgh", "内容完全不同的另一条", FakeAuthor("user_dup", bot=False)))
    key_logs = [x for x in logs() if "去重键=" in x]
    check("l1 日志打印完整去重键（不截断）",
          any("ROBOT1.0_u0Iabcdefgh" in x for x in key_logs), key_logs[:1])
    check("l2 同 id 同内容重复被去重、同 id 不同内容不被吃掉",
          sum(1 for x in logs() if "已处理过，跳过" in x) == 1 and len(GATE["calls"]) == 2,
          "dup=%d gate=%d" % (sum(1 for x in logs() if "已处理过，跳过" in x), len(GATE["calls"])))

    # ── m. 延迟三态：0/0 跟随全局、1/1 不延迟、其它用真实值 ──
    reset_page()
    gconf = br._gate_conf(BASE)
    d00 = br._delay_of("botA", gconf, {"dmin": 0, "dmax": 0})
    d11 = br._delay_of("botA", gconf, {"dmin": 1, "dmax": 1})
    dxx = br._delay_of("botA", gconf, {"dmin": 200, "dmax": 400})
    check("m 延迟三态：0/0 跟随全局、1/1 不延迟、其它用真实值",
          d00 == (0, 0) and d11 == (1, 1) and dxx == (200, 400),
          "00=%s 11=%s xx=%s" % (d00, d11, dxx))

    # ── n. 「群里自动接话」关闭时，总模型点了也不回（不冲掉「只回 @」）──
    reset_page()
    br.set_group_autochat("", "", False)
    GATE["ret"] = json.dumps({"action": "reply", "bot": "桑多涅", "why": "总模型想让她回"})
    run("botA", human(900, "随便说说"))
    check("n 「群里自动接话」关闭 → 总模型点名也不回",
          len(API.sent) == 0 and has("「群里自动接话」关闭"),
          "posts=%d" % len(API.sent))
    br.set_group_autochat("", "", True)

    bad = [r for r in RESULTS if not r[1]]
    print("\n==== 结果：%d/%d 通过 ====" % (len(RESULTS) - len(bad), len(RESULTS)))
    shutil.rmtree(TMP, ignore_errors=True)
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
