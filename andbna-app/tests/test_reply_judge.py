# -*- coding: utf-8 -*-
"""1.0.6 判定重写 + @ 语义：host 端验证（不装 app、不连 QQ）。

直接 import 真模块 app/src/main/python/bot_runner.py，模型调用用 stub 顶掉：

    cd andbna-app && python3 tests/test_reply_judge.py
"""
import asyncio
import json
import os
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "app", "src", "main", "python"))

import bot_runner as br  # noqa: E402

APPID = "botA_appid"
GROUP = "grp_1"
BOTDIR = ""
BASE = ""
RESULTS = []


def check(name, cond, detail):
    RESULTS.append((name, bool(cond), detail))
    print(("  PASS  " if cond else "  FAIL  ") + name + " :: " + detail)


class FakeAuthor(object):
    def __init__(self, oid):
        self.member_openid = oid
        self.user_openid = oid
        self.id = oid
        self.bot = False


class FakeApi(object):
    def __init__(self):
        self.sent = []

    async def post_group_message(self, **kw):
        self.sent.append(kw)


API = None


class FakeMsg(object):
    def __init__(self, mid, text, author, mentions=None, gid=GROUP):
        self.id = mid
        self.content = text
        self.group_openid = gid
        self.author = FakeAuthor(author)
        self.mentions = mentions or []
        self.raw = {}
        self._api = API


def reset():
    global API
    br._seen_set.clear()
    del br._seen_ids[:]
    br._outbox.clear()
    br._rate.clear()
    br._intent.clear()
    br._mute.clear()
    br._state["log"][:] = []
    br._autochat["on"] = False
    br._autochat_override.clear()
    API = FakeApi()


def logs():
    return list(br._state["log"])


def stub_llm(fn):
    """替掉真正的模型调用（_call_llm_sync 跑在 asyncio.to_thread 里）。"""
    br._call_llm_sync = fn


def run(msg):
    return asyncio.run(br._handle_group(APPID, BOTDIR, msg, "群消息(新事件)"))


_REAL_LLM_CONF = br._llm_conf


def patch_conf(level="normal"):
    """假装 llm.json 已经配好（Key / 地址 / 模型都在），整条链路都交给 stub。"""

    def fake(base_dir=""):
        return {
            "key": "k", "api": "http://127.0.0.1:1/v1/chat/completions",
            "model": "judge-stub", "vision_model": "", "judge_model": "",
            "summary_model": "", "search_model": "", "reasoning_effort": "",
            "context_window": 131072, "web_search_enabled": False,
            "search_persona_style": False, "search_auto_decide": False,
            "search_provider": "off", "search_base": "", "search_auth": "none",
            "search_fields": "", "balance_api": "",
            "judge_level": level, "name_hit_as_call": True,
        }

    br._llm_conf = fake


def restore_conf():
    br._llm_conf = _REAL_LLM_CONF


def judge_conf(level="normal"):
    return {"model": "judge-stub", "judge_level": level}


def main():
    global BOTDIR, BASE
    BOTDIR = tempfile.mkdtemp(prefix="andbna_judge_bot_")
    BASE = tempfile.mkdtemp(prefix="andbna_judge_base_")
    br._register_self_id(APPID, APPID)
    # 人格名：名字命中的主要来源
    with open(os.path.join(BOTDIR, "persona_name.txt"), "w", encoding="utf-8") as f:
        f.write("沃雅妮莎-完整")
    with open(os.path.join(BOTDIR, "phrases.json"), "w", encoding="utf-8") as f:
        f.write(json.dumps({"aliases": {"master": "主人", "others": "伙伴、朋友"}},
                           ensure_ascii=False))

    # ── a. 解析：1/0、接/不接、yes/no、true/false、是/否、带标点、大小写 ──
    cases = [
        ("1", True), ("0", False), (" 1 ", True), ("1.", True), ("“0”", False),
        ("接", True), ("不接", False), ("YES", True), ("no", False),
        ("True", True), ("false", False), ("是", True), ("否", False),
        ("建议回 1", True), ("这条不该接", False),
    ]
    bad = [(r, br._parse_judge(r)) for r, want in cases if br._parse_judge(r) is not want]
    check("a 解析 1/0 各写法", not bad, "错例=%s" % (bad[:3],))

    # 解析不出 → None（调用方按「接」处理）；"Need decide." 这种不能被当成「不接」
    none_cases = ["", "Need decide.", "我不太确定", "……"]
    got = [(r, br._parse_judge(r)) for r in none_cases]
    check("a2 解析不出 → None", all(v is None for _r, v in got), "%s" % (got,))

    # ── b. 判定：不可解析的输出按「接」处理，日志带原文前 80 字 ──
    reset()
    stub_llm(lambda conf, msgs, mt=400, tp=0.9: "Need decide. 还编首歌？")
    hit = asyncio.run(br._should_reply(judge_conf("normal"), "还编首歌"))
    line = [x for x in logs() if "解析不出 1/0" in x]
    check("b 不可解析 → 接 + 日志带原文", hit is True and line and
          "Need decide." in line[0], "hit=%s log=%s" % (hit, line[:1]))

    # 0 就是 0（解析出来就不该兜成接）
    reset()
    stub_llm(lambda conf, msgs, mt=400, tp=0.9: "0")
    hit0 = asyncio.run(br._should_reply(judge_conf("normal"), "签到"))
    check("b2 明确 0 → 不接", hit0 is False, "logs=%s" % (logs()[-1:],))

    # ── c. 三档：提示词倾向不同 + 判定异常时的兜底不同 ──
    p_con = br._judge_prompt("在吗", "conservative")
    p_nor = br._judge_prompt("在吗", "normal")
    p_eag = br._judge_prompt("在吗", "eager")
    check("c 三档提示词不同", len({p_con, p_nor, p_eag}) == 3 and
          "拿不准时选择 1" in p_nor and "拿不准" not in p_con and
          "几乎都回 1" in p_eag, "normal含拿不准=%s" % ("拿不准时选择 1" in p_nor))

    def boom(conf, msgs, mt=400, tp=0.9):
        raise RuntimeError("stub 判定失败")

    checks = {}
    for lv in ("conservative", "normal", "eager"):
        reset()
        stub_llm(boom)
        checks[lv] = asyncio.run(br._should_reply(judge_conf(lv), "在吗"))
    check("c2 判定异常：normal/eager 接、conservative 不接",
          checks["conservative"] is False and checks["normal"] is True
          and checks["eager"] is True,
          "%s" % (checks,))
    # 日志要写清走了哪一档
    reset()
    stub_llm(boom)
    asyncio.run(br._should_reply(judge_conf("normal"), "在吗"))
    check("c3 异常日志写档位", any("normal 档按「接」处理" in x for x in logs()),
          "%s" % (logs()[-1:],))

    # ── d. 名字命中 → 跳过判定直回（不 @ 也回，且不用开「群里自动接话」）──
    reset()
    br._base_dir = ""
    called = []
    stub_llm(lambda conf, msgs, mt=400, tp=0.9: called.append(1) or "0")
    run(FakeMsg("d1", "沃雅妮莎在干啥呢", "user_a1"))
    check("d 名字命中 → 直回", len(API.sent) == 1 and not called and
          any("名字命中（沃雅妮莎）→ 直回" in x for x in logs()),
          "posts=%d 判定调用=%d logs=%s" % (len(API.sent), len(called), logs()[-2:]))

    # 称呼（phrases.json 的 aliases）同样算被叫
    reset()
    run(FakeMsg("d2", "伙伴们晚上吃啥", "user_a2"))
    check("d2 称呼命中 → 直回", len(API.sent) == 1 and
          any("名字命中（伙伴）" in x for x in logs()),
          "posts=%d logs=%s" % (len(API.sent), logs()[-1:]))

    # ── e. 不含名字的普通闲聊：走判定；normal 档 stub 回 1 → 回 ──
    reset()
    br._autochat["on"] = True        # 生产里这一档是「群里自动接话」开着的形态
    patch_conf("normal")             # 判定槽位真的发得出去（stub 收）
    br._base_dir = BASE             # 有基点才会去取 conf（内容由 patch_conf 顶掉）
    stub_llm(lambda conf, msgs, mt=400, tp=0.9: "1")
    run(FakeMsg("e1", "这游戏有人玩过吗", "user_a3"))
    check("e 普通闲聊 → 判定接", len(API.sent) == 1 and
          any("判定（模型 judge-stub｜normal 档）：1 → 接" in x for x in logs()),
          "posts=%d logs=%s" % (len(API.sent), logs()[-3:]))
    restore_conf()
    br._base_dir = ""

    # 判定回 0 → 跳过（判定确实在拦）
    reset()
    br._autochat["on"] = True
    stub_llm(lambda conf, msgs, mt=400, tp=0.9: "0")
    run(FakeMsg("e2", "哈哈哈哈", "user_a4"))
    check("e2 普通闲聊 → 判定不接则跳过", len(API.sent) == 0 and
          any("跳过：回复判定不接这句" in x for x in logs()),
          "logs=%s" % (logs()[-2:],))
    restore_conf()

    # ── f. name_hit_as_call 关掉：名字出现在正文里也不再直回 ──
    reset()
    br._base_dir = BASE
    with open(os.path.join(BASE, "llm.json"), "w", encoding="utf-8") as f:
        f.write(json.dumps({"api": "http://127.0.0.1:1/v1", "key": "k",
                            "model": "m", "judge_level": "normal",
                            "name_hit_as_call": False}, ensure_ascii=False))
    br._autochat["on"] = True
    stub_llm(lambda conf, msgs, mt=400, tp=0.9: "0")
    run(FakeMsg("f1", "沃雅妮莎在干啥呢", "user_a5"))
    check("f name_hit_as_call=false → 不直回（走判定，判定不接）", len(API.sent) == 0 and
          not any("名字命中" in x for x in logs()) and
          any("跳过：回复判定不接这句" in x for x in logs()),
          "posts=%d logs=%s" % (len(API.sent), logs()[-2:]))
    br._base_dir = ""

    # ── g. @ 语义：<@id> → @你 / @作者本人 / @用户xxxxxxxx + 元信息行 ──
    author = "AUTHOR1234567890"
    other = "OTHERUSER9876543"
    text = "<@%s> 你说他是谁 <@%s> <@%s>" % (APPID, author, other)
    msg = FakeMsg("g1", text, author, mentions=[
        {"id": APPID, "is_you": True, "member_openid": APPID},
        {"id": author, "is_you": False, "member_openid": author},
        {"id": other, "is_you": False, "member_openid": other},
    ])
    body, meta, line = br._at_semantics(APPID, BOTDIR, msg)
    check("g @ 语义替换", body == "@你 你说他是谁 @作者本人 @用户OTHERUSE",
          "body=%r" % (body,))
    check("g2 元信息行", meta.startswith("[作者 AUTHOR12｜本条 @ 了：你、作者本人、用户OTHERUSE"),
          "meta=%r" % (meta,))
    check("g3 日志行", line.endswith("（3 个）") and "提及=你/作者/用户OTHERUSE" in line,
          "line=%r" % (line,))
    check("g4 提示词里没有完整 openid", author not in body and author not in meta and
          other not in body, "meta=%r body=%r" % (meta, body))

    # 没有 @ 的消息：元信息写「无人」
    body2, meta2, line2 = br._at_semantics(
        APPID, BOTDIR, FakeMsg("g5", "今天好累啊", author))
    check("g5 无 @ → 无人", body2 == "今天好累啊" and "无人" in meta2 and
          "（0 个）" in line2, "meta=%r" % (meta2,))

    # ── h. 模型 @ 别人：<at> 标签校验（只认本条消息的 mentions）──
    mentions = [{"id": author}, {"id": other}]
    plain, ids = br._resolve_at_tags("<at>OTHERUSE</at> 说他吧", mentions)
    check("h 前缀命中 → 转平台标签", ids == [other] and "⟦AT0⟧" in plain,
          "ids=%s plain=%r" % (ids, plain))
    md = br._at_markdown(plain, ids)
    check("h2 平台语法", md == '<qqbot-at-user id="%s"/> 说他吧' % other, "md=%r" % (md,))

    plain2, ids2 = br._resolve_at_tags("<at>DEADBEEF</at> 瞎编的 id", mentions)
    check("h3 瞎编的 id 一律剥掉", ids2 == [] and "<at>" not in plain2 and
          "DEADBEEF" not in plain2, "ids=%s plain=%r" % (ids2, plain2))

    # 全量 openid 也行
    plain3, ids3 = br._resolve_at_tags("<at>%s</at> 你好" % author, mentions)
    check("h4 全量 openid 也行", ids3 == [author], "ids=%s" % (ids3,))

    # 带 @ 的回复走 markdown（msg_type=2），不带 @ 的老路不变（msg_type=0）
    reset()
    br._autochat["on"] = True
    patch_conf("eager")
    br._base_dir = BASE
    stub_llm(lambda conf, msgs, mt=400, tp=0.9: "<at>%s</at> 你说他呀" % other[:8])
    m_at = FakeMsg("h5", "他谁啊", author, mentions=[
        {"id": APPID, "is_you": True}, {"id": other}, {"id": author}])
    run(m_at)
    kinds = [k["msg_type"] for k in API.sent]
    mds = [k.get("markdown") for k in API.sent]
    restore_conf()
    check("h5 带 @ 的回复 → markdown", kinds == [2] and mds and
          mds[0]["content"] == '<qqbot-at-user id="%s"/> 你说他呀' % other,
          "kinds=%s md=%s" % (kinds, mds))

    # ── i. llm.json 读得到两个新开关，认不出的档位落回 normal ──
    with open(os.path.join(BASE, "llm.json"), "w", encoding="utf-8") as f:
        f.write(json.dumps({"api": "http://127.0.0.1:1/v1", "key": "k",
                            "model": "m", "judge_level": "EAGER",
                            "name_hit_as_call": "false"}, ensure_ascii=False))
    c = br._llm_conf(BASE)
    check("i llm.json 两个新键", c and c["judge_level"] == "eager" and
          c["name_hit_as_call"] is False, "%s" % ({k: c.get(k) for k in
                                                   ("judge_level", "name_hit_as_call")},))
    with open(os.path.join(BASE, "llm.json"), "w", encoding="utf-8") as f:
        f.write(json.dumps({"api": "http://127.0.0.1:1/v1", "key": "k",
                            "model": "m", "judge_level": "乱填"}, ensure_ascii=False))
    c2 = br._llm_conf(BASE)
    check("i2 认不出的档位 → normal，开关默认开",
          c2["judge_level"] == "normal" and c2["name_hit_as_call"] is True, "%s" % (c2["judge_level"],))

    ok = sum(1 for _n, c_, _d in RESULTS if c_)
    bad_names = [n for n, c_, _d in RESULTS if not c_]
    print("==== 判定/ @ 语义：%d/%d 通过 ====" % (ok, len(RESULTS)))
    if bad_names:
        print("失败：%s" % "，".join(bad_names))
    return 0 if ok == len(RESULTS) else 1


if __name__ == "__main__":
    sys.exit(main())
