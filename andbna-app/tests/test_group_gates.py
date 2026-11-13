# -*- coding: utf-8 -*-
"""host 端验证：群里 @ bot 不该 4 个 bot 全回复（四道闸）。

直接 import 真模块 app/src/main/python/bot_runner.py，stub 掉 client/api，
不装 app、不连 QQ，python3 就能跑：

    cd andbna-app && python3 tests/test_group_gates.py
"""
import asyncio
import os
import shutil
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "app", "src", "main", "python"))

import bot_runner as br  # noqa: E402

APPID = "botA_appid"
GROUP = "group_openid_1"
ME = {APPID}


class FakeAuthor(object):
    def __init__(self, oid, bot=None):
        self.member_openid = oid
        self.user_openid = oid
        self.id = oid
        if bot is not None:
            self.bot = bot


class FakeApi(object):
    """记录每一条成功发出的群消息（= 真实会推到其它 bot 的那条）。"""

    def __init__(self):
        self.sent = []

    async def post_group_message(self, **kw):
        self.sent.append(kw)


class FakeMsg(object):
    def __init__(self, mid, text, author, mentions=None, gid=GROUP, raw=None):
        self.id = mid
        self.content = text
        self.group_openid = gid
        self.author = author
        self.mentions = mentions or []
        self.raw = raw or {}
        self._api = API


API = None


def reset():
    global API
    br._seen_set.clear()
    del br._seen_ids[:]
    br._outbox.clear()
    br._rate.clear()
    br._intent.clear()          # 熔断计的是「回复意图」，跨用例不清会把后面的用例拖进静音
    br._mute.clear()
    br._state["log"][:] = []
    br._base_dir = ""          # 不读 llm.json → _reply 走连通测试文案，不联网
    API = FakeApi()


def logs():
    return list(br._state["log"])


def new_logs(since):
    return logs()[since:]


def run(msg):
    return asyncio.run(br._handle_group(APPID, BOTDIR, msg, "群消息(新事件)"))


def run_at(msg):
    return asyncio.run(br._handle_group(APPID, BOTDIR, msg, "群@"))


BOTDIR = ""
RESULTS = []


def check(name, cond, detail):
    RESULTS.append((name, bool(cond), detail))
    print(("  PASS  " if cond else "  FAIL  ") + name + " :: " + detail)


def at_mentions(oid=APPID, bot=True, is_you=True):
    # 真 payload 的 mentions[] 每项都带 is_you（平台按 bot 分配的权威 @ 判定）
    return [{"bot": bot, "id": oid, "is_you": is_you, "member_openid": oid,
             "member_role": "member", "scope": "single", "username": "谁"}]


def main():
    global BOTDIR
    BOTDIR = tempfile.mkdtemp(prefix="andbna_gate_test_")
    br._register_self_id(APPID, APPID)

    # ── a. 有人 @ 本 bot 的普通消息 → 回复 1 条 ────────────────────
    reset()
    run(FakeMsg("m_a1", "你好呀", FakeAuthor("user_h1", bot=False),
                at_mentions()))
    check("a 已@ → 回复 1 条", len(API.sent) == 1,
          "posts=%d log=%s" % (len(API.sent), new_logs(0)[-1:]))
    last_reply = API.sent[0]["content"] if API.sent else "<none>"

    # ── b. 群里没人 @ 的普通消息 → 跳过 ───────────────────────────
    reset()
    run(FakeMsg("m_b1", "大家好啊", FakeAuthor("user_h2", bot=False), []))
    check("b 未@ → 跳过", len(API.sent) == 0 and
          any("跳过：未 @ 本 bot" in x for x in logs()),
          "posts=%d logs=%s" % (len(API.sent), new_logs(0)))

    # 判不出来（mentions 里 id 读不出 / bot 标记缺失）→ 也跳过
    reset()
    run(FakeMsg("m_b2", "谁在", FakeAuthor("user_h3", bot=False),
                [{"name": "?"}]))
    check("b2 判不出@ → 跳过", len(API.sent) == 0 and
          any("跳过：判不出是否 @ 本 bot" in x for x in logs()),
          "posts=%d logs=%s" % (len(API.sent), new_logs(0)))

    # ── c. 作者是 bot（author.bot=true）→ 跳过 ────────────────────
    reset()
    run(FakeMsg("m_c1", "我是另一个 bot", FakeAuthor("botB_openid", bot=True),
                at_mentions()))
    known = os.path.join(BOTDIR, "known_bots.json")
    check("c author.bot → 跳过", len(API.sent) == 0 and
          any("跳过：作者是 bot" in x for x in logs()) and
          os.path.exists(known) and "botB_openid" in open(known, encoding="utf-8").read(),
          "posts=%d known_bots=%s logs=%s" % (
              len(API.sent),
              open(known, encoding="utf-8").read().strip() if os.path.exists(known) else "-",
              new_logs(0)))

    # ── d. 内容与刚发出的完全一致（回环）→ 跳过 ───────────────────
    reset()
    run(FakeMsg("m_d1", "早安", FakeAuthor("user_h4", bot=False), at_mentions()))
    echo = API.sent[0]["content"] if API.sent else ""
    reset()
    br._note_sent(APPID, GROUP, echo)          # 假装本 bot 刚发过这句
    run(FakeMsg("m_d2", echo, FakeAuthor("user_h5", bot=False), at_mentions()))
    check("d 回环 → 跳过", len(API.sent) == 0 and
          any("跳过：疑似复读本 bot 内容" in x for x in logs()),
          "echo=%r posts=%d logs=%s" % (echo[:20], len(API.sent), new_logs(0)))

    # ── e. 连发 10 条 @ 消息 → 只回 6 条，第 7 条起限流 ────────────
    reset()
    for i in range(10):
        run(FakeMsg("m_e%d" % i, "第%d条" % i, FakeAuthor("user_h6", bot=False),
                    at_mentions()))
    rate_logs = [x for x in logs() if "跳过：触发限流" in x]
    check("e 10 条@ → 只回 6 条", len(API.sent) == 6 and len(rate_logs) == 4,
          "posts=%d 限流日志=%d 首条=%s" % (len(API.sent), len(rate_logs),
                                            rate_logs[0] if rate_logs else "-"))

    # ── f. 4 个 bot 互相复读：A 的话被 B/C/D 收到 → 全跳 ───────────
    reset()
    # 回声判定要求归一化后 ≥ 6 字（短句撞车率太高，见 br._ECHO_MIN_LEN）
    a_text = "A 说的话很长很长"
    for i, bot in enumerate(["botB_openid", "botC_openid", "botD_openid"]):
        run(FakeMsg("m_f%d" % i, a_text, FakeAuthor(bot, bot=True), at_mentions()))
    # 就算 author.bot 缺失、openid 也没记过，只要内容与本 bot 发过的一致也不回
    reset()
    try:
        os.remove(os.path.join(BOTDIR, "known_bots.json"))
    except OSError:
        pass
    br._note_sent(APPID, GROUP, a_text)
    for i, bot in enumerate(["botB_openid", "botC_openid", "botD_openid"]):
        run(FakeMsg("m_g%d" % i, a_text, FakeAuthor(bot, bot=None), at_mentions()))
    check("f 4 bot 互相复读 → 0 新增", len(API.sent) == 0,
          "posts=%d logs=%s" % (len(API.sent), new_logs(0)))

    # ── 老路径（群@事件）也过同一套闸：作者是 bot 必须跳过 ────────
    reset()
    run_at(FakeMsg("m_h1", "老路径", FakeAuthor("botB_openid", bot=True)))
    check("g 群@ 老路径：作者是 bot → 跳过", len(API.sent) == 0 and
          any("跳过：作者是 bot" in x for x in logs()),
          "posts=%d logs=%s" % (len(API.sent), new_logs(0)))

    # ── 老路径正常 @ 仍回复（没被新闸掐死）────────────────────────
    reset()
    run_at(FakeMsg("m_h2", "老路径正常@", FakeAuthor("user_h9", bot=False)))
    check("g2 群@ 老路径：真人 → 回复 1 条", len(API.sent) == 1,
          "posts=%d" % len(API.sent))

    reset()
    shutil.rmtree(BOTDIR, ignore_errors=True)
    bad = [r for r in RESULTS if not r[1]]
    print("\n==== 结果：%d/%d 通过 ====" % (len(RESULTS) - len(bad), len(RESULTS)))
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
