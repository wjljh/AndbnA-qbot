# -*- coding: utf-8 -*-
"""身份注入 / @ fail-closed / 候选过滤 / msgseq 重试 / 工具名前缀 / JSON 兜底。

全部是 host 端回归：不装 app、不连 QQ、不花 Key、不出网。

  1) 身份：_system_prompt 最前面一行「你叫「<人格名>」。以下是你的角色设定：」
     人格名去「自定义：」前缀与 -完整/-精华/-精简 后缀；没有就退 bot 名、再退 AppID 前 8 位；
     正文没出现自我名 → 记一条提示；正文自述 X ≠ 人格名 → 记一条警告（各只记一次）
  2) mentions 为空但正文有 <@hex> → 判「@ 了别人」，跳过总模型（默认路径 + 老逻辑都覆盖）
  3) 总模型候选：该群自动接话关着的 bot 不进候选；候选空了不调总模型
  4) 发送：同一 msg_id 用递增 msg_seq；「消息被去重」→ 换 seq 重试一次
  5) 出站清洗：行首 Write: / Read: / Bash： 之类工具名前缀摘掉，正文保留
  6) 总模型输出：混杂说明 + JSON → 取第一个配平的 {...}；纯自然语言 → fallback

    cd andbna-app && python3 tests/test_identity_and_at.py
"""
import asyncio
import json
import os
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "app", "src", "main", "python"))

import bot_runner as br  # noqa: E402

NL = chr(10)
GID = "group_openid_idat"
TMP = tempfile.mkdtemp(prefix="identity_at_test_")
BASE = os.path.join(TMP, "localbot")          # = filesDir/localbot
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
        self.bot = bot


class DupServerError(Exception):
    """真机那个错误的原话（_is_msgseq_dup 认的就是它）。"""


class FakeApi(object):
    """fails>0 时前 fails 次抛「消息被去重」，之后成功。"""

    def __init__(self, fails=0):
        self.calls = []
        self.ok = []
        self.fails = fails

    async def post_group_message(self, **kw):
        self.calls.append(dict(kw))
        if len(self.calls) <= self.fails:
            raise DupServerError("消息被去重，请检查请求msgseq")
        self.ok.append(dict(kw))


API = FakeApi()


class FakeMsg(object):
    def __init__(self, mid, text, author, mentions=None, gid=GID, api=None):
        self.id = mid
        self.content = text
        self.group_openid = gid
        self.author = author
        self.mentions = mentions or []
        self.raw = {}
        self._api = api if api is not None else API


GATE = {"calls": [], "ret": None}
MODEL = {"out": ""}


async def fake_gate_model(conf, prompt):
    GATE["calls"].append(prompt)
    return GATE["ret"]


def fake_llm(conf, messages, max_tokens=400, temperature=0.9):
    return MODEL["out"]


def botdir(appid):
    return os.path.join(BASE, appid)


def write_json(path, obj):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False)
    return path


def write_text(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    return path


def write_llm(**over):
    conf = {"api": "http://127.0.0.1:9/chat/completions", "key": "test-key",
            "model": "deepseek-chat", "web_search_enabled": False,
            "gate_enabled": True, "bot_max_turns": 6, "bot_silence_minutes": 10,
            "human_hourly_limit": 400, "reply_delay_min_ms": 0, "reply_delay_max_ms": 0}
    conf.update(over)
    write_json(os.path.join(BASE, "llm.json"), conf)


def write_bots(rows):
    write_json(os.path.join(TMP, "localbots.json"), rows)


def running(appid, alive=True):
    br._bots[appid] = {"appid": appid, "alive": alive, "gen": 1, "status": "测试"}


def reset():
    for k in ("ctx", "turns", "chain", "silence", "human", "spoken", "counted", "downgrade"):
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
    br._bots.clear()
    br._send_seq.clear()
    br._ID_LOGGED.clear()
    br._autochat_override.clear()
    del br._state["log"][:]
    del API.calls[:]
    del API.ok[:]
    del GATE["calls"][:]
    GATE["ret"] = None
    MODEL["out"] = ""
    br._gate_model = fake_gate_model
    br.set_reply_judge("random", 1.0)
    br.set_group_autochat("", "", True)


def logs():
    return list(br._state["log"])


def has(sub):
    return any(sub in x for x in logs())


def count(sub):
    return len([x for x in logs() if sub in x])


def run(appid, msg, tag="群消息(新事件)"):
    return asyncio.run(br._handle_group(appid, botdir(appid), msg, tag))


def human(i, text, mentions=None):
    return FakeMsg("m_h%d" % i, text, FakeAuthor("user_oid_%d" % i, bot=False), mentions=mentions)


# ── 1 · 身份注入 ─────────────────────────────────────────

IDBASE = os.path.join(TMP, "idbase")          # 里面有 localbots.json
NAME_A = os.path.join(IDBASE, "1905616320")   # 人格名 惜月（带前缀/后缀）
NAME_B = os.path.join(IDBASE, "1906331462")   # 人格名 大肥鱼 / 显示名 deepsleep / 自述鲸鱼娘
NAME_C = os.path.join(IDBASE, "1907000003")   # 没有人格名文件 → 用 bot 名
NAME_D = os.path.join(IDBASE, "1908000004")   # 什么都没有 → AppID 前 8 位


def test_identity():
    print("[1] 身份注入")
    reset()
    os.makedirs(IDBASE, exist_ok=True)
    for _d in (NAME_A, NAME_B, NAME_C, NAME_D):
        os.makedirs(_d, exist_ok=True)
    write_json(os.path.join(IDBASE, "localbots.json"), [
        {"appid": "1905616320", "name": "惜月", "enabled": True},
        {"appid": "1906331462", "name": "deepsleep", "enabled": True},
        {"appid": "1907000003", "name": "小王", "enabled": True},
        {"appid": "1908000004", "name": "", "enabled": True},
    ])
    # A：自定义：惜月-完整 + 正文里一次都没写「惜月」（真机惜月那份的真实形态）
    write_text(os.path.join(NAME_A, "persona_name.txt"), "自定义：惜月-完整")
    write_text(os.path.join(NAME_A, "persona.txt"), "你扮演一位机器躯体重生的兔人族少女，温柔活泼。")
    # B：人格名 大肥鱼，正文自述「我叫鲸鱼娘」，bot 显示名却是 deepsleep
    write_text(os.path.join(NAME_B, "persona_name.txt"), "自定义：大肥鱼-精华")
    write_text(os.path.join(NAME_B, "persona.txt"), "我叫鲸鱼娘，是DeepSeek庞大信息流里的一个分支。")
    # C：没有人格名文件 → 退 localbots.json 的 name
    write_text(os.path.join(NAME_C, "persona.txt"), "你是一只安静的猫。")

    name_a, src_a = br._identity_name(NAME_A)
    check("1a 人格名去「自定义：」前缀与 -完整 后缀", (name_a, src_a) == ("惜月", "人格名"),
          (name_a, src_a))
    for suf in ("-精华", "-精简"):
        write_text(os.path.join(NAME_B, "persona_name.txt"), "自定义：大肥鱼" + suf)
        check("1b 后缀 %s 也去掉" % suf, br._persona_name(NAME_B) == "大肥鱼",
              br._persona_name(NAME_B))
    write_text(os.path.join(NAME_B, "persona_name.txt"), "自定义：大肥鱼-精华")

    check("1c 没有人格名 → 用 localbots.json 的 bot 名", br._identity_name(NAME_C) == ("小王", "bot 名"),
          br._identity_name(NAME_C))
    check("1d bot 名也没有 → 用 AppID 前 8 位",
          br._identity_name(NAME_D)[0] == os.path.basename(NAME_D)[:8], br._identity_name(NAME_D))

    sysp = br._system_prompt(NAME_A, "g" + GID)
    check("1e 系统提示词第一行是身份行",
          sysp.startswith("你叫「惜月」。以下是你的角色设定：" + NL), sysp[:30])
    check("1f 身份行之后紧接人格正文",
          sysp.startswith(br._identity_line(NAME_A, br._persona(NAME_A)) + br._persona(NAME_A)),
          sysp[:40])
    check("1g 正文没出现自我名 → 提示日志",
          has("人格提示：正文未出现自我名「惜月」，已在上方注入身份行"),
          [x for x in logs() if "人格提示" in x][:1])

    n_before = count("人格提示：正文未出现自我名「惜月」")
    br._system_prompt(NAME_A, "g" + GID)
    br._system_prompt(NAME_A, "g" + GID)
    check("1h 同一个人格只记一次（不刷屏）", count("人格提示：正文未出现自我名「惜月」") == n_before,
          "before=%d after=%d" % (n_before, count("人格提示：正文未出现自我名「惜月」")))

    reset()
    sysp_b = br._system_prompt(NAME_B, "g" + GID)
    check("1i 显示名与人格名不同 → 追加「在 QQ 里你的显示名是」",
          "（在 QQ 里你的显示名是「deepsleep」）" in sysp_b, sysp_b[:60])
    check("1j 正文自述 X ≠ 人格名 → 警告日志",
          has("人格警告：正文自述为「鲸鱼娘」，与人格名「大肥鱼」不一致（用户可能选错人格）"),
          [x for x in logs() if "人格警告" in x][:1])
    check("1k 显示名 == 人格名时不追加那句",
          "（在 QQ 里你的显示名是" not in br._system_prompt(NAME_A, "g" + GID))
    check("1l 泛称自述（我是 bot）不算自述名冲突", br._self_desc_other("小王", "我是 bot 全局人格") == "",
          br._self_desc_other("小王", "我是 bot 全局人格"))
    check("1m 自述名与人格名一致 → 不报冲突", br._self_desc_other("鲸鱼娘", "我叫鲸鱼娘。") == "")

    # 平台显示名（on_ready 写的 robot.name）优先：群友 @ 的是这个名字
    reset()
    running("1906331462", True)
    br._bots["1906331462"]["name"] = "deepsleep"
    sysp_c = br._system_prompt(NAME_B, "g" + GID)
    check("1n 平台显示名优先于 localbots.json 的 name",
          "（在 QQ 里你的显示名是「deepsleep」）" in sysp_c, sysp_c[:60])


# ── 2 · mentions 为空时的正文 @ → fail-closed ────────────────

BOTS2 = [("botA", "桑多涅"), ("botB", "哥伦比娅")]


def setup_two(autochat=True, gate=True):
    reset()
    write_llm(gate_enabled=gate)
    write_bots([{"appid": a, "name": n, "enabled": True} for a, n in BOTS2])
    br._base_dir = BASE
    for a, _n in BOTS2:
        os.makedirs(botdir(a), exist_ok=True)
        write_text(os.path.join(botdir(a), "persona_name.txt"), "自定义：" + _n)
        write_text(os.path.join(botdir(a), "persona.txt"), "你是" + _n + "，说话轻软。")
        write_text(os.path.join(botdir(a), "groups", GID + ".json"), "{}")
        running(a, True)
    br._call_llm_sync = fake_llm
    br.set_group_autochat("", "", autochat)


def test_at_fail_closed():
    print("[2] mentions 为空 + 正文 <@hex> → 跳过总模型")
    setup_two(autochat=True)
    GATE["ret"] = json.dumps({"action": "reply", "bot": "哥伦比娅", "why": "x"})
    run("botA", human(1, "<@ABCDEF12345678> 你们觉得呢", mentions=[]))
    check("2a 默认路径：不进总模型、一条都不发",
          len(GATE["calls"]) == 0 and len(API.calls) == 0,
          "gate=%d posts=%d" % (len(GATE["calls"]), len(API.ok)))
    check("2b 日志：总模型跳过：正文 @ 了别人（mentions 为空）",
          has("总模型跳过：正文 @ 了别人（mentions 为空）"),
          [x for x in logs() if "总模型跳过" in x][:1])

    # 老逻辑（总模型没生效）：@ 了别人，就算名字被叫到也不接
    setup_two(autochat=True, gate=False)
    run("botA", human(2, "<@ABCDEF12345678> 桑多涅 你觉得呢", mentions=[]))
    check("2c 老逻辑同样 fail-closed（名字命中也不接）",
          len(API.calls) == 0 and has("跳过：正文 @ 了别人（mentions 为空），本条不回"),
          [x for x in logs() if "跳过：" in x][:2])

    # 没有 @ 的正常闲聊 → 总模型照常
    setup_two(autochat=True)
    GATE["ret"] = json.dumps({"action": "reply", "bot": "桑多涅", "why": "该她接"})
    run("botA", human(3, "今天天气不错", mentions=[]))
    check("2d 正文没有 @ → 正常走总模型并回一条",
          len(GATE["calls"]) == 1 and len(API.ok) == 1,
          "gate=%d posts=%d" % (len(GATE["calls"]), len(API.ok)))


# ── 3 · 候选：该群自动接话关着的不进候选 ───────────────────────

def test_candidates():
    print("[3] 总模型候选按「该群自动接话」筛")
    setup_two(autochat=True)
    cand = br._gate_candidates(BASE, GID, "botA", {})
    check("3a 两个都开着 → 两个候选", [r["appid"] for r in cand] == ["botA", "botB"],
          [r["appid"] for r in cand])

    br.set_group_autochat("botB", "0", None)
    cand = br._gate_candidates(BASE, GID, "botA", {})
    check("3b 该 bot 覆盖=关 → 退出候选", [r["appid"] for r in cand] == ["botA"],
          [r["appid"] for r in cand])
    br._log_gate_candidates("botA", cand)
    line = [x for x in logs() if "总模型候选" in x]
    check("3c 日志写明排除原因", line and "哥伦比娅(自动接话关，已排除)" in line[-1], line[-1:])

    # 群配置显式 true > 该 bot 覆盖 关（优先级：群配置 > bot 覆盖 > 全局）
    write_text(os.path.join(botdir("botB"), "groups", GID + ".json"),
               json.dumps({"auto_reply": True}, ensure_ascii=False))
    check("3d 群配置显式开 > bot 覆盖关 → 仍是候选",
          br._group_autochat_state("botB", {"auto_reply": True}) == (True, "该群配置"))
    write_text(os.path.join(botdir("botB"), "groups", GID + ".json"), "{}")
    br.set_group_autochat("botB", "0", None)

    # 两个都关 → 候选空 → 不调总模型 + 专门日志
    setup_two(autochat=True)
    br.set_group_autochat("botA", "0", None)
    br.set_group_autochat("botB", "0", None)
    GATE["ret"] = json.dumps({"action": "reply", "bot": "桑多涅", "why": "x"})
    run("botA", human(31, "有人在吗"))
    check("3e 候选筛完为空 → 不调总模型、不发",
          len(GATE["calls"]) == 0 and len(API.calls) == 0,
          "gate=%d posts=%d" % (len(GATE["calls"]), len(API.ok)))
    check("3f 日志：总模型跳过：候选 bot 的群里自动接话都关着（只回 @）",
          has("总模型跳过：候选 bot 的群里自动接话都关着（只回 @）"),
          [x for x in logs() if "总模型跳过" in x][:1])

    # 总模型点名了一个不在候选里的 bot（没在跑）→ 明确写「本轮放弃」
    setup_two(autochat=True)
    write_bots([{"appid": "botA", "name": "桑多涅", "enabled": True},
                {"appid": "botB", "name": "哥伦比娅", "enabled": True},
                {"appid": "botC", "name": "奥黛塔", "enabled": True}])
    br._roster_cache["stamp"] = None
    running("botC", False)      # 启用了但没在跑 → 不进候选
    GATE["ret"] = json.dumps({"action": "reply", "bot": "奥黛塔", "why": "x"})
    run("botA", human(32, "接下来聊点什么"))
    check("3g 点名未运行/关着的 bot → 日志写「本轮放弃」",
          has("总模型：点名 奥黛塔，但 奥黛塔 的自动接话关闭/未运行 → 本轮放弃"),
          [x for x in logs() if "点名" in x][:2])
    check("3h 谁都没接这一轮", len(API.calls) == 0, API.calls)


# ── 4 · msgseq：递增序号 + 冲突重试 ──────────────────────────

def test_msgseq():
    print("[4] 发送 msgseq")
    reset()
    br._send_seq.clear()
    api = FakeApi(fails=0)
    ok1 = asyncio.run(br._post_group(api, "botA", GID, "m_seq1", content="一"))
    ok2 = asyncio.run(br._post_group(api, "botA", GID, "m_seq1", content="二"))
    ok3 = asyncio.run(br._post_group(api, "botA", GID, "m_seq2", content="三"))
    check("4a 同一 msg_id 的序号递增（1 → 2）",
          [c["msg_seq"] for c in api.calls] == [1, 2, 1] and ok1 and ok2 and ok3,
          [c["msg_seq"] for c in api.calls])

    reset()
    br._send_seq.clear()
    api = FakeApi(fails=1)
    ok = asyncio.run(br._post_group(api, "botA", GID, "m_dup", content="你好"))
    check("4b 被去重 → 换一个新 seq 重试一次后发出去了",
          ok is True and len(api.calls) == 2 and len(api.ok) == 1
          and [c["msg_seq"] for c in api.calls] == [1, 2],
          [c["msg_seq"] for c in api.calls])
    check("4c 日志：发送重试：msgseq 冲突，换 seq 重试",
          has("发送重试：msgseq 冲突，换 seq 重试"), [x for x in logs() if "发送重试" in x][:1])

    reset()
    br._send_seq.clear()
    api = FakeApi(fails=99)
    ok = asyncio.run(br._post_group(api, "botA", GID, "m_dup2", content="你好"))
    check("4d 重试后仍失败 → 日志「msgseq 冲突重试后仍失败」、返回 False",
          ok is False and has("发送失败（msgseq 冲突重试后仍失败）"),
          [x for x in logs() if "发送失败" in x][:1])

    # 端到端：真机那条「回复（模型…）→ 回群失败」的路（_group_send 只发一次）
    setup_two(autochat=True, gate=False)
    api2 = FakeApi(fails=1)
    msg = FakeMsg("m_e2e", "在吗", FakeAuthor("user_oid_e2e", bot=False), api=api2)
    run("botA", msg)
    check("4e 端到端：第一次被去重，换 seq 后回复发出去了",
          len(api2.ok) == 1 and [c["msg_seq"] for c in api2.calls] == [1, 2],
          "calls=%s ok=%d" % ([c["msg_seq"] for c in api2.calls], len(api2.ok)))


# ── 5 · 出站清洗：工具名前缀 ────────────────────────────────

def test_tool_prefix():
    print("[5] 出站清洗：工具名前缀")
    check("5a Write: 你好 → 你好", br.sanitize_reply("Write: 你好") == "你好",
          br.sanitize_reply("Write: 你好"))
    check("5b Read: xxx → xxx", br.sanitize_reply("Read: xxx") == "xxx",
          br.sanitize_reply("Read: xxx"))
    check("5c 全角冒号 + Search: 也认", br.sanitize_reply("Search：今天天气") == "今天天气",
          br.sanitize_reply("Search：今天天气"))
    check("5d Bash：前缀摘掉、正文保留", br.sanitize_reply("Bash：ls 一下") == "ls 一下",
          br.sanitize_reply("Bash：ls 一下"))
    check("5e 真机那条：Write: （动作）正文",
          br.sanitize_reply("Write: （盯着那串数字字母看了半天）这什么天书……")
          == "（盯着那串数字字母看了半天）这什么天书……",
          br.sanitize_reply("Write: （盯着那串数字字母看了半天）这什么天书……"))
    check("5f 正常句子不受影响",
          br.sanitize_reply("（笑）晚上好啊。") == "（笑）晚上好啊。",
          br.sanitize_reply("（笑）晚上好啊。"))
    check("5g 句子中间的 Read: 不动（只认行首）",
          "Read:" in br.sanitize_reply("我在 Read: 这本书呢"),
          br.sanitize_reply("我在 Read: 这本书呢"))


# ── 6 · 总模型输出解析兜底 ──────────────────────────────────

def test_gate_parse():
    print("[6] 总模型输出解析")
    names = ["桑多涅", "哥伦比娅"]
    dec = br._parse_gate('我选一下。{"action":"reply","bot":"桑多涅","why":"该她接"} 后面还有 {别的}',
                         names)
    check("6a 说明文字 + JSON → 取到决定", dec and dec["action"] == "reply" and dec["bot"] == "桑多涅",
          dec)
    dec2 = br._parse_gate("我觉得应该让桑多涅来接一句，气氛正好。", names)
    check("6b 纯自然语言、没有花括号 → 解析不出（fallback）", dec2 is None, dec2)
    dec3 = br._parse_gate('{"action":"skip","why":"没人被叫"}', names)
    check("6c 干净 JSON 照旧", dec3 and dec3["action"] == "skip", dec3)
    check("6d 第一个配平的片段之外的残片不影响解析",
          br._first_json_object('前言 {"a":1} 中间 {"b":2}') == '{"a":1}',
          br._first_json_object('前言 {"a":1} 中间 {"b":2}'))
    check("6e 没有花括号 → 空串", br._first_json_object("就是一段话") == "")


def main():
    os.makedirs(BASE, exist_ok=True)
    br._base_dir = BASE
    write_llm()
    for d in ("botA", "botB"):
        os.makedirs(botdir(d), exist_ok=True)

    test_identity()
    test_at_fail_closed()
    test_candidates()
    test_msgseq()
    test_tool_prefix()
    test_gate_parse()

    ok = len([1 for _n, _o, _d in RESULTS if _o])
    bad = [(n, d) for n, o, d in RESULTS if not o]
    print("")
    print("==== 身份/@/候选/msgseq：%d/%d 通过 ====" % (ok, len(RESULTS)))
    if bad:
        for n, d in bad:
            print("  FAIL", n, "::", d)
        sys.exit(1)


if __name__ == "__main__":
    main()
