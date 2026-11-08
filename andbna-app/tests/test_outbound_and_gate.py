# -*- coding: utf-8 -*-
"""四项 Python 侧修复的 host 端回归（不装 app、不连 QQ、不花 Key、不许网）：

  1) 称呼：非主人永远注入硬规则（内置 / 自定义人格都追加在人格之后）+
     发送前把回复里的「主人」换成 others 配置值；master_word_guard=false 不换
  2) 出站清洗：模型把自己的指令 / 思考 / end 标记抄进正文时只留正文；洗空了就不发
  3) 总模型候选只看「运行中」的 bot，未运行的排除并记日志；只剩 1 个就不走总模型
  4) 正文有 <@hex> 但平台 mentions 为空 → fail-closed，跳过总模型本条

    cd andbna-app && python3 tests/test_outbound_and_gate.py
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

GID = "group_openid_out"
TMP = tempfile.mkdtemp(prefix="outgate_test_")
BASE = os.path.join(TMP, "localbot")          # = filesDir/localbot
BOTS = [("botA", "桑多涅"), ("botB", "哥伦比娅"), ("botC", "奥黛塔")]
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


GATE = {"calls": [], "ret": None}
MODEL = {"out": ""}


async def fake_gate_model(conf, prompt):
    GATE["calls"].append(prompt)
    return GATE["ret"]


async def fake_reply(appid, botdir, key, who, incoming, images=None, extra_sys=""):
    return "（假回复）"


def fake_llm(conf, messages, max_tokens=400, temperature=0.9):
    """顶掉真正的 HTTP 调用：回复内容由 MODEL["out"] 指定。"""
    return MODEL["out"]


REAL_REPLY = br._reply
REAL_LLM = br._call_llm_sync


def botdir(appid):
    return os.path.join(BASE, appid)


def write_json(path, obj):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False)
    return path


def write_llm(**over):
    conf = {"api": "http://127.0.0.1:9/chat/completions", "key": "test-key",
            "model": "deepseek-chat", "web_search_enabled": False,
            "gate_enabled": True, "bot_max_turns": 6, "bot_silence_minutes": 10,
            "human_hourly_limit": 40, "reply_delay_min_ms": 0, "reply_delay_max_ms": 0}
    conf["log_level"] = "detail"
    conf.update(over)
    write_json(os.path.join(BASE, "llm.json"), conf)


def write_bots(rows=None):
    rows = rows if rows is not None else [{"appid": a, "name": n, "enabled": True}
                                          for a, n in BOTS]
    write_json(os.path.join(TMP, "localbots.json"), rows)


def set_running(appid, alive=True):
    """模拟 start_all / start_one 维护的运行表。"""
    br._bots[appid] = {"appid": appid, "alive": alive, "gen": 1, "status": "测试"}


def reset():
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
    br._bots.clear()
    del br._state["log"][:]
    API.sent[:] = []
    GATE["calls"][:] = []
    GATE["ret"] = None
    MODEL["out"] = ""
    br._reply = REAL_REPLY
    br._call_llm_sync = REAL_LLM
    br._gate_model = fake_gate_model
    br.set_reply_judge("random", 1.0)
    br.set_group_autochat("", "", True)


def logs():
    return list(br._state["log"])


def has(sub):
    return any(sub in x for x in logs())


def run(appid, msg, tag="群消息(新事件)"):
    return asyncio.run(br._handle_group(appid, botdir(appid), msg, tag))


def human(i, text):
    return FakeMsg("m_h%d" % i, text, FakeAuthor("user_oid_%d" % i, bot=False))


# ── 1 · 称呼：硬规则注入 + 发送前兜底替换 ─────────────────────

def test_words():
    print("[1] 称呼硬规则 / 兜底替换")
    reset()
    bot = botdir("botA")
    write_json(os.path.join(bot, "phrases.json"), {"aliases": {"others": "客人"}})
    write_json(os.path.join(bot, "owner.json"), {"user_openid": "OWNER1234"})

    rules = br._words_rules(bot, "g" + GID)
    check("1a 非主人 → 祈使句硬规则 + others 配置值",
          "本轮对话对象不是主人" in rules and "禁止用「主人」称呼对方" in rules
          and "客人" in rules, rules)
    # 1.0.9：身份行排在最前（模型以前不知道自己叫什么），人格紧随其后、规则收尾
    sysp = br._system_prompt(bot, "g" + GID)
    check("1b 身份行 → 人格 → 规则（顺序固定）",
          sysp.startswith("你叫「") and br._persona(bot) in sysp
          and sysp.index(br._persona(bot)) < sysp.index("本轮对话对象不是主人"),
          sysp[:20])
    check("1c 没配 others → 仍注入禁止「主人」，改叫「你」",
          "禁止用「主人」称呼对方" in br._words_rules(botdir("botB"), "g" + GID)
          and "「你」" in br._words_rules(botdir("botB"), "g" + GID))

    fixed, n, others = br._master_word_fix(bot, "g" + GID, "主人嫌吵啦，快睡吧。")
    check("1d 非主人正文里的「主人」→ others 配置值", (fixed, n, others) == ("客人嫌吵啦，快睡吧。", 1, "客人"),
          (fixed, n, others))
    fixed2, n2, _o = br._master_word_fix(botdir("botB"), "g" + GID, "主人说了算。")
    check("1e 没配 others → 换成「你」", fixed2 == "你说了算。" and n2 == 1, fixed2)
    same, n3, _x = br._master_word_fix(bot, "pOWNER1234", "主人说了算。")
    check("1f 主人本人（_is_master_key 为真）→ 一个字都不换", same == "主人说了算。" and n3 == 0)

    # 发送前兜底：真的走 _reply（模型调用被 stub）
    write_llm()
    br._base_dir = BASE
    br._call_llm_sync = fake_llm
    MODEL["out"] = "主人嫌吵啦，快睡吧。"
    out = asyncio.run(REAL_REPLY("botA", bot, "g" + GID, "小明", "在吗"))
    check("1g 非主人 + 回复含「主人」→ 发出去的是替换后的正文", out == "客人嫌吵啦，快睡吧。", out)
    check("1h 日志：称呼兜底一行",
          has("称呼兜底：替换 1 处「主人」→「客人」（对方非主人）"),
          [x for x in logs() if "称呼兜底" in x])

    write_llm(master_word_guard=False)
    del br._state["log"][:]
    MODEL["out"] = "主人嫌吵啦，快睡吧。"
    out2 = asyncio.run(REAL_REPLY("botA", bot, "g" + GID, "小明", "在吗"))
    check("1i master_word_guard=false → 不替换", out2 == "主人嫌吵啦，快睡吧。", out2)
    check("1j master_word_guard=false → 不写兜底日志", not has("称呼兜底"))

    write_llm()
    MODEL["out"] = "主人嫌吵啦。"
    out3 = asyncio.run(REAL_REPLY("botA", bot, "pOWNER1234", "", "在吗"))
    check("1k 主人私聊 → 不替换", out3 == "主人嫌吵啦。", out3)
    check("1l master_word_guard 缺省视为开（llm.json 没这个键）",
          br._master_guard_on(BASE) is True)
    write_llm(master_word_guard=False)
    check("1m master_word_guard=false 读得出来", br._master_guard_on(BASE) is False)
    write_llm()


# ── 2 · 出站清洗 ─────────────────────────────────────────

def test_sanitize():
    print("[2] 出站清洗")
    check("2a 指令句 + end 之前的计划全部丢掉（样例 1）",
          br.sanitize_reply('不要过长，1-2句。回应"那不一定"——不确定的事，练到了自然知道。'
                            'end那就等练到了再说。现在争这个，不如早点睡——眼皮的账，明天自己会算。')
          == "那就等练到了再说。现在争这个，不如早点睡——眼皮的账，明天自己会算。",
          br.sanitize_reply('不要过长，1-2句。回应"那不一定"——不确定的事，练到了自然知道。'
                            'end那就等练到了再说。现在争这个，不如早点睡——眼皮的账，明天自己会算。'))
    check("2b 指令句 + end 之前的计划全部丢掉（样例 2）",
          br.sanitize_reply("别的 bot 说要算账。我回应，简短。end账我来算。"
                            "两点以后还没睡的，明天足尖先替你们记住。")
          == "账我来算。两点以后还没睡的，明天足尖先替你们记住。",
          br.sanitize_reply("别的 bot 说要算账。我回应，简短。end账我来算。"
                            "两点以后还没睡的，明天足尖先替你们记住。"))
    check("2c 「思考：」整句丢掉", br.sanitize_reply("思考：他在试我。\n嗯，随你吧。") == "嗯，随你吧。",
          br.sanitize_reply("思考：他在试我。\n嗯，随你吧。"))
    check("2d （思考：…）整段丢掉，后面的正文留着",
          br.sanitize_reply("（思考：他生气了）那就随他吧。") == "那就随他吧。")
    check("2e 叙述性括号动作保留", br.sanitize_reply("（笑）晚上好啊。") == "（笑）晚上好啊。")
    check("2f 正常台词原样返回", br.sanitize_reply("晚上好啊，今天练到几点？") == "晚上好啊，今天练到几点？")
    check("2g 洗完只剩标记 → 空串", br.sanitize_reply("不要过长，1-2句。end") == "",
          br.sanitize_reply("不要过长，1-2句。end"))
    check("2h 英文里的 end 不误伤", br.sanitize_reply("那首歌的 end 挺好听的。") == "那首歌的 end 挺好听的。",
          br.sanitize_reply("那首歌的 end 挺好听的。"))

    # _reply：洗空 → 返回空串 + 记日志；_group_send 看到空串就不发
    reset()
    write_llm()
    br._base_dir = BASE
    br._call_llm_sync = fake_llm
    MODEL["out"] = "不要过长，1-2句。end"
    out = asyncio.run(REAL_REPLY("botA", botdir("botA"), "g" + GID, "小明", "在吗"))
    check("2i 洗空 → _reply 返回空串", out == "", out)
    check("2j 日志：出站清洗：内容为空，丢弃", has("出站清洗：内容为空，丢弃"),
          [x for x in logs() if "出站清洗" in x])

    reset()
    write_llm()
    br._base_dir = BASE
    br._call_llm_sync = fake_llm
    MODEL["out"] = "不要过长，1-2句。end嗯，那早点睡吧。"
    run("botA", human(21, "今天好累"))
    check("2k 清洗后仍为空/有正文之外的路径 → 只有正文被发出去",
          len(API.sent) == 1 and API.sent[0]["content"].strip() == "嗯，那早点睡吧。",
          API.sent)

    reset()
    write_llm()
    br._base_dir = BASE
    br._call_llm_sync = fake_llm
    MODEL["out"] = "end"
    run("botA", human(22, "今天好累"))
    check("2l 洗空 → 群里一条都不发", len(API.sent) == 0, API.sent)
    check("2m 洗空 → 记「丢弃」日志", has("出站清洗：内容为空，丢弃"))


# ── 3 · 总模型候选只看运行中 ──────────────────────────────

def test_candidates():
    print("[3] 总模型候选")
    reset()
    write_llm()
    write_bots()
    br._base_dir = BASE
    for a, _n in BOTS:
        set_running(a, True)
    cand = br._gate_candidates(BASE)
    check("3a 三个都在跑 → 三个候选", [r["appid"] for r in cand] == ["botA", "botB", "botC"],
          [r["appid"] for r in cand])

    set_running("botC", False)
    cand = br._gate_candidates(BASE)
    check("3b 没在跑的被排除", [r["appid"] for r in cand] == ["botA", "botB"],
          [r["appid"] for r in cand])
    br._log_gate_candidates("botA", cand)
    line = [x for x in logs() if "总模型候选" in x]
    check("3c 日志：运行中 / 未运行已排除",
          line and "桑多涅(运行中)" in line[0] and "奥黛塔(未运行，已排除)" in line[0],
          line[:1])

    # 运行中只剩 1 个 → 不走总模型（老逻辑照常回）
    reset()
    write_llm()
    write_bots()
    br._base_dir = BASE
    set_running("botA", True)
    set_running("botB", False)
    set_running("botC", False)
    GATE["ret"] = json.dumps({"action": "reply", "bot": "哥伦比娅", "why": "x"})
    run("botA", human(31, "有人吗"))
    check("3d 运行中只剩 1 个 → 不调总模型", len(GATE["calls"]) == 0,
          "gate=%d" % len(GATE["calls"]))
    check("3e 运行中只剩 1 个 → 日志写明、按老逻辑回", has("总模型不走：运行中的 bot 只有 1 个")
          and len(API.sent) == 1, [x for x in logs() if "总模型不走" in x])

    # 两个都在跑 → 走总模型，且候选日志跟着打
    reset()
    write_llm()
    write_bots()
    br._base_dir = BASE
    set_running("botA", True)
    set_running("botB", True)
    set_running("botC", False)
    GATE["ret"] = json.dumps({"action": "reply", "bot": "桑多涅", "why": "该她接"})
    msg = human(32, "这个结构算对了吗")
    for a in ("botA", "botB", "botC"):
        run(a, msg)
    check("3f 两个在跑 → 走总模型（只跑一次）", len(GATE["calls"]) == 1,
          "gate=%d" % len(GATE["calls"]))
    check("3g 候选日志在调用前，且排除没在跑的那个",
          has("总模型候选：") and has("奥黛塔(未运行，已排除)"),
          [x for x in logs() if "总模型候选" in x][:1])


# ── 4 · 正文 @ 漏判（mentions 为空）→ fail-closed ─────────────

def test_at_leak():
    print("[4] 正文 @ 漏判")
    reset()
    write_llm()
    write_bots()
    br._base_dir = BASE
    msg = FakeMsg("m_at1", "<@ABCDEF12345678> 你觉得呢", FakeAuthor("user_oid_at", bot=False),
                  mentions=[])
    state, why = br._at_me("botA", botdir("botA"), msg)
    check("4a 正文有 <@hex> + mentions 空 → 判「不是叫我」（不再当没人被 @）",
          state == "no" and "mentions 为空" in why, (state, why))
    check("4b _at_others 认为 @ 了别人", br._at_others("botA", botdir("botA"), msg) is True)

    plain = FakeMsg("m_at2", "你觉得呢", FakeAuthor("user_oid_at2", bot=False), mentions=[])
    check("4c 没有 <@> 时口径不变（正文和 mentions 都没有 @）",
          br._at_me("botA", botdir("botA"), plain) == ("no", "正文和 mentions 都没有 @")
          and br._at_others("botA", botdir("botA"), plain) is False)

    for a, _n in BOTS:
        set_running(a, True)
    GATE["ret"] = json.dumps({"action": "reply", "bot": "哥伦比娅", "why": "x"})
    run("botA", msg)
    check("4d 走到总模型之前就被拦下（本条一条都不发）",
          len(GATE["calls"]) == 0 and len(API.sent) == 0,
          "gate=%d posts=%d" % (len(GATE["calls"]), len(API.sent)))
    check("4e 日志：总模型跳过：正文 @ 了别人（mentions 为空）",
          has("总模型跳过：正文 @ 了别人（mentions 为空）"),
          [x for x in logs() if "总模型跳过" in x][:1])


def main():
    os.makedirs(BASE, exist_ok=True)
    br._base_dir = BASE
    write_llm()
    write_bots()
    for appid, name in BOTS:
        d = botdir(appid)
        os.makedirs(d, exist_ok=True)
        with open(os.path.join(d, "persona.txt"), "w", encoding="utf-8") as f:
            f.write("你是" + name + "，说话自然简短。")
        with open(os.path.join(d, "persona_name.txt"), "w", encoding="utf-8") as f:
            f.write(name + "-完整\n")

    br._gate_model = fake_gate_model
    test_words()
    test_sanitize()
    test_candidates()
    test_at_leak()

    shutil.rmtree(TMP, ignore_errors=True)
    bad = [r for r in RESULTS if not r[1]]
    print("\n==== 结果：%d/%d 通过 ====" % (len(RESULTS) - len(bad), len(RESULTS)))
    for name, _ok, detail in bad:
        print("FAIL %s   %s" % (name, detail))
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
