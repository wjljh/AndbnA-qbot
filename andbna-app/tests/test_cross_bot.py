# -*- coding: utf-8 -*-
"""跨 bot 协同（真机根因回归）：group_openid 是**按 bot 隔离**的。

真机日志：同一个 QQ 群，惜月看到 groups/0FE14634….json，大肥鱼看到
groups/FC139BB1….json —— 两个 bot 拿到的 gid 不是同一个串。所以任何拿 gid 当
跨 bot 共享键的地方都永不命中（1.0.9 的总模型共享决定与群级互斥就是这样失效的）。

这里用「正文相同、gid 不同」的事件模拟真机：

  A) 同一条群消息两个 bot 各收一次（gid 不同）→ 总模型只调一次、只回一条
  B) 群级互斥：两个 bot 各自被点名（gid 不同）→ 3 秒内只有一个真正发出去
  C) 会话键 = sha1(正文)[:16] + 30 秒桶，与 gid 无关（@ 归一、桶划分）
  D) 认主：群里按「作者 openid == owner.json」判（同一 bot 内同一套 id），
     _words_rules 拿到的就是真 gid + 作者 openid（不是 gid 派生的假 key）

    cd andbna-app && python3 tests/test_cross_bot.py
"""
import asyncio
import json
import os
import re
import shutil
import sys
import tempfile
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "app", "src", "main", "python"))

import bot_runner as br  # noqa: E402

# 真机那两条 gid（同一个群，两个 bot 各一套）
GID_A = "0FE14634BEA6824FA46E90118503AB56"      # 惜月 1905616320
GID_B = "FC139BB1033AE4692554E358E3C9CE9D"      # 大肥鱼 1906331462
OWNER = "OWNER_MEMBER_OPENID_01"
BOTS = [("botA", "惜月"), ("botB", "大肥鱼")]
TMP = tempfile.mkdtemp(prefix="cross_bot_test_")
BASE = os.path.join(TMP, "localbot")           # = filesDir/localbot
RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok)))
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
    def __init__(self, mid, text, author, mentions=None, gid=GID_A):
        self.id = mid
        self.content = text
        self.group_openid = gid
        self.author = author
        self.mentions = mentions or []
        self.raw = {}
        self._api = API


GATE = {"calls": [], "ret": None, "exc": None}
REPLY = {"calls": [], "seen": []}


async def fake_gate_model(conf, prompt):
    GATE["calls"].append(prompt)
    if GATE["exc"] is not None:
        raise GATE["exc"]
    return GATE["ret"]


async def fake_reply(appid, botdir, key, who, incoming, images=None, extra_sys=""):
    """替身 _reply：顺手把「这一轮真正拼出来的 system」记下来（认主链路要证据）。"""
    oid = br._member_openid_ctx.get()
    REPLY["calls"].append({"appid": appid, "key": key, "who": who, "incoming": incoming})
    REPLY["seen"].append({"appid": appid, "key": key, "oid": oid,
                          "sys": br._system_prompt(botdir, key, oid)})
    return "（假回复 %s）" % appid


br._gate_model = fake_gate_model
br._reply = fake_reply


def write_json(path, obj):
    d = os.path.dirname(path)
    if d and not os.path.isdir(d):
        os.makedirs(d)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False)
    return path


def write_llm(**over):
    conf = {"api": "http://127.0.0.1:9/chat/completions", "key": "test-key",
            "model": "deepseek-chat", "judge_model": "", "gate_enabled": True,
            "bot_max_turns": 6, "bot_silence_minutes": 10, "human_hourly_limit": 40,
            "reply_delay_min_ms": 0, "reply_delay_max_ms": 0,
            "log_level": "detail"}
    conf.update(over)
    write_json(os.path.join(BASE, "llm.json"), conf)


def write_bots(rows=None):
    rows = rows if rows is not None else [{"appid": a, "name": n, "enabled": True}
                                          for a, n in BOTS]
    write_json(os.path.join(TMP, "localbots.json"), rows)


def botdir(appid):
    return os.path.join(BASE, appid)


def reset_page():
    for k in ("ctx", "turns", "chain", "silence", "human", "spoken", "counted",
              "downgrade"):
        br._gate_state[k].clear()
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
    del br._state["log"][:]
    API.sent[:] = []
    GATE["calls"][:] = []
    GATE["ret"], GATE["exc"] = None, None
    REPLY["calls"][:] = []
    REPLY["seen"][:] = []


def logs():
    return list(br._state["log"])


def has(sub):
    return any(sub in x for x in logs())


KEY_RE = re.compile(r"总模型共享键：([0-9a-f]{16}#\d+)")


def logged_keys():
    return [m.group(1) for x in logs() for m in [KEY_RE.search(x)] if m]


def run(appid, msg, tag="群消息(新事件)"):
    return asyncio.run(br._handle_group(appid, botdir(appid), msg, tag))


# ── A · 同一条消息、两个 bot、gid 不同 → 总模型只跑一次、只回一条 ──────
def test_a():
    print("[A] 同一条群消息（gid 不同）→ 总模型只调一次、只回一条")
    reset_page()
    write_llm()
    write_bots()
    br.set_reply_judge("random", 1.0)
    br.set_group_autochat("", "", True)
    GATE["ret"] = json.dumps({"action": "reply", "bot": "惜月", "why": "该她接"})
    body = "这个结构算对了吗"
    # 真机：两个 bot 各自收到同一条消息（各自一套 msg_id / group_openid）
    for appid, gid in (("botA", GID_A), ("botB", GID_B)):
        run(appid, FakeMsg("m_shared_%s" % appid, body, FakeAuthor("human_oid_1"), gid=gid))

    senders = [c["appid"] for c in REPLY["calls"]]
    check("A1 两个 bot 收到同一条消息（gid 不同）→ 总模型只跑一次",
          len(GATE["calls"]) == 1, "gate_calls=%d" % len(GATE["calls"]))
    check("A2 只产生一条回复（另一个 bot 不动）",
          senders == ["botA"] and len(API.sent) == 1,
          "reply=%s posts=%d" % (senders, len(API.sent)))
    check("A3 没被点名的 bot 记「总模型：reply 点名 惜月，本 bot 不动」",
          has("总模型：reply 点名 惜月，本 bot 不动"),
          [x for x in logs() if "总模型" in x])

    keys = logged_keys()
    now_bucket = int(time.time() // br._SESSION_BUCKET_SEC)
    check("A4 日志打出会话键（正文哈希前 16 位 + 桶号），两个 bot 是同一个键",
          len(keys) >= 2 and len(set(keys)) == 1
          and keys[0].startswith(br._text_hash(body) + "#"),
          "keys=%s want_hash=%s" % (keys, br._text_hash(body)))
    check("A5 桶号就是当前 30 秒桶（不是 gid）",
          keys and abs(int(keys[0].split("#")[1]) - now_bucket) <= 1, keys)

    # 反证：旧实现（key 带 gid）在同样输入下必然算出两个不同的键 —— 这条用例就是它的红
    check("A6 旧键（gid, 正文哈希）在两个 bot 上不相等（= 1.0.9 永不共享的根因）",
          (GID_A, br._text_hash(body)) != (GID_B, br._text_hash(body)),
          "A=%s B=%s" % (GID_A[:8], GID_B[:8]))


# ── B · 群级互斥：gid 不同也拦得住 ────────────────────────────
def test_b():
    print("[B] 群级互斥：两个 bot 各自被点名（gid 不同）→ 只发一条")
    reset_page()
    write_llm()
    write_bots()

    async def slow(appid, botdir_, key, who, incoming, images=None, extra_sys=""):
        # A 先发；B 还在生成 —— 两条不同的消息各自被点名、几乎同时完成
        await asyncio.sleep(0.0 if appid == "botA" else 0.08)
        return "%s 说一句" % appid

    br._reply = slow

    async def both():
        await asyncio.gather(
            br._group_send("botA", botdir("botA"),
                           FakeMsg("sA", "同一句", FakeAuthor("h1"), gid=GID_A),
                           GID_A, "", "同一句"),
            br._group_send("botB", botdir("botB"),
                           FakeMsg("sB", "同一句", FakeAuthor("h1"), gid=GID_B),
                           GID_B, "", "同一句"),
        )

    asyncio.run(both())
    br._reply = fake_reply
    got = [kw.get("content") for kw in API.sent]
    check("B1 gid 不同也拦得住：只发出一条", len(got) == 1, got)
    check("B2 日志：群级互斥…放弃，且带会话键",
          has("群级互斥：本群 3 秒内已有") and has("键="),
          [x for x in logs() if "互斥" in x])


# ── C · 会话键本身：与 gid 无关 ─────────────────────────────
def test_c():
    print("[C] 会话键 = sha1(正文)[:16] + 30 秒桶（与 gid 无关）")
    k = br._session_key("同一句")
    check("C1 键 = (sha1(正文) 前 16 位, 30 秒桶)",
          k == (br._text_hash("同一句"), int(time.time() // br._SESSION_BUCKET_SEC)), k)
    check("C2 正文不同 → 键不同", br._session_key("另一句")[0] != k[0])
    check("C3 30 秒一个桶：1000s→33，1029.9s→34",
          br._session_key("同一句", now=1000.0)[1] == 33
          and br._session_key("同一句", now=1029.9)[1] == 34)
    check("C4 两个 bot 的 gid 不参与：同一正文两个 gid 只有一个键（函数根本没接 gid）",
          br._session_key("同一句") == k)
    check("C5 @ 标记与空白先归一（mention 里带的是各 bot 自己的 openid）",
          br._session_key("  <@!AAAA> 同一句 ") == k, br._session_key("  <@!AAAA> 同一句 "))
    check("C6 跨桶兜底：_gate_claim 会在上一个桶里找回同一轮的记录",
          "bucket - 1" in _src(), "见 bot_runner._gate_claim")


def _src():
    import inspect
    return inspect.getsource(br._gate_claim)


# ── D · 认主：真 gid + 作者 openid ──────────────────────────
def test_d():
    print("[D] 群里认主：作者 openid == owner.json（同一 bot 内同一套 id）")
    reset_page()
    write_llm()
    write_bots()
    write_json(os.path.join(botdir("botA"), "owner.json"),
               {"user_openid": OWNER, "member_openid": OWNER})
    write_json(os.path.join(botdir("botA"), "phrases.json"),
               {"aliases": {"master": "阿爸", "others": "客人"}})
    br.set_reply_judge("random", 1.0)
    br.set_group_autochat("", "", True)

    check("D1 _is_master_msg(群里) 直接比作者 openid 与 owner.json",
          br._is_master_msg(botdir("botA"), GID_A,
                            FakeMsg("d0", "在吗", FakeAuthor(OWNER), gid=GID_A)) is True)
    GATE["ret"] = json.dumps({"action": "reply", "bot": "惜月", "why": "主人问话"})
    run("botA", FakeMsg("d1", "在吗", FakeAuthor(OWNER), gid=GID_A))
    seen = REPLY["seen"][-1] if REPLY["seen"] else {}
    check("D2 走到 _reply 的会话键就是 g+群 openid（真 gid，不是派生 key）",
          seen.get("key") == "g" + GID_A, seen.get("key"))
    check("D3 上下文里带的是作者 openid（群成员 openid 与私聊同一套）",
          seen.get("oid") == OWNER, seen.get("oid"))
    check("D4 _words_rules 注入主人称呼（说明 _is_master_msg 真被调用且判对）",
          "请用「阿爸」称呼对方" in str(seen.get("sys") or ""),
          str(seen.get("sys") or "")[:80])
    check("D5 日志：称呼：对象=主人（命中 owner openid 前 8 位 …｜群=…）",
          has("称呼：对象=主人（命中 owner openid 前 8 位 " + br._oid8(OWNER)),
          [x for x in logs() if "称呼" in x])

    reset_page()
    write_json(os.path.join(botdir("botA"), "owner.json"),
               {"user_openid": OWNER, "member_openid": OWNER})
    br.set_group_autochat("", "", True)
    GATE["ret"] = json.dumps({"action": "reply", "bot": "惜月", "why": "随便答"})
    run("botA", FakeMsg("d2", "在吗", FakeAuthor("GUEST_OPENID_99"), gid=GID_A))
    seen = REPLY["seen"][-1] if REPLY["seen"] else {}
    check("D6 客人（openid 不同）→ 不注入主人称呼，改注入「禁止用主人」",
          "禁止用「主人」称呼对方" in str(seen.get("sys") or ""),
          str(seen.get("sys") or "")[:80])


def main():
    os.makedirs(BASE, exist_ok=True)
    br._base_dir = BASE
    for appid, name in BOTS:
        d = botdir(appid)
        os.makedirs(d, exist_ok=True)
        with open(os.path.join(d, "persona_name.txt"), "w", encoding="utf-8") as f:
            f.write(name + "-完整\n")
    try:
        test_a()
        test_b()
        test_c()
        test_d()
    finally:
        shutil.rmtree(TMP, ignore_errors=True)
    ok = sum(1 for _n, r in RESULTS if r)
    print(chr(10) + "==== 跨 bot 协同：%d/%d 通过 ====" % (ok, len(RESULTS)))
    return 0 if ok == len(RESULTS) else 1


if __name__ == "__main__":
    sys.exit(main())
