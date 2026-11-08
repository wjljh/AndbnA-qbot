# -*- coding: utf-8 -*-
"""A/B/C 三处真机反馈的 host 端回归（不装 app、不连 QQ、不花 Key、不出网）：

  A) 内置人格判定改成「来源标记」：persona_meta.json 的 builtin 优先，其次看
     persona_name.txt 原文的「自定义：」前缀，都没有才回退按名字匹配
     → 用户导入的同名自定义人格（「自定义：鲸鱼娘」）不再被当成内置、问候不再锁死
  B) 群里「认主」：同一个 bot 下群里的 member_openid 与私聊的 user_openid 是**同一套 id**，
     作者 openid == owner.json 记的主人 → 主人称呼；groups/<gid>.json 的
     owner_member_openid 只是可选覆盖（手工纠正用）
  C) 群级互斥：同一条消息被两个 bot 同时点名时只有一个真的发出去。键与 gid 无关 ——
     真机上两个 bot 的 group_openid 按 bot 隔离，1.0.9 拿 gid 当键永不命中

    cd andbna-app && python3 tests/test_persona_master.py
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

OK = []
FAIL = []
GID = "group_openid_pmaster"
TMP = tempfile.mkdtemp(prefix="persona_master_test_")
BASE = os.path.join(TMP, "localbot")          # = filesDir/localbot


def check(name, cond, detail=""):
    (OK if cond else FAIL).append(name)
    print(("  ok   " if cond else "  FAIL ") + name
          + (("  <- " + str(detail)) if (detail and not cond) else ""))


def logs():
    return list(br._state["log"])


def logtext():
    return chr(10).join(logs())


def clear_logs():
    del br._state["log"][:]


def write_json(path, obj):
    d = os.path.dirname(path)
    if d and not os.path.isdir(d):
        os.makedirs(d)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False)
    return path


def mkbot(appid, persona_name=None, meta=None, greetings=None, owner=None,
          phrases=None, persona="你是测试人格。"):
    d = os.path.join(BASE, appid)
    os.makedirs(d, exist_ok=True)
    if persona is not None:
        with open(os.path.join(d, "persona.txt"), "w", encoding="utf-8") as f:
            f.write(persona)
    if persona_name is not None:
        with open(os.path.join(d, "persona_name.txt"), "w", encoding="utf-8") as f:
            f.write(persona_name)
    if meta is not None:
        write_json(os.path.join(d, "persona_meta.json"), meta)
    if greetings is not None:
        write_json(os.path.join(d, "greetings.json"), greetings)
    if owner is not None:
        write_json(os.path.join(d, "owner.json"), owner)
    if phrases is not None:
        write_json(os.path.join(d, "phrases.json"), phrases)
    return d


# ── 假件（与真机 botpy 的消息形状对齐：群里作者 openid 在 author.member_openid）──
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
    def __init__(self, mid, text, author, gid=GID):
        self.id = mid
        self.content = text
        self.group_openid = gid
        self.author = author
        self.mentions = []
        self.raw = {}
        self._api = API


MODEL = {"out": ""}
REAL_REPLY = br._reply
REAL_LLM = br._call_llm_sync


def fake_llm(conf, messages, max_tokens=400, temperature=0.9):
    return MODEL["out"]


def write_llm(**over):
    conf = {"api": "http://127.0.0.1:9/chat/completions", "key": "test-key",
            "model": "deepseek-chat", "web_search_enabled": False,
            "gate_enabled": True, "bot_max_turns": 6, "bot_silence_minutes": 10,
            "human_hourly_limit": 40, "reply_delay_min_ms": 0, "reply_delay_max_ms": 0}
    conf.update(over)
    write_json(os.path.join(BASE, "llm.json"), conf)


def write_bots(rows):
    write_json(os.path.join(TMP, "localbots.json"), rows)


def reset_runtime():
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
    del br._state["log"][:]
    API.sent[:] = []
    MODEL["out"] = ""
    br._reply = REAL_REPLY
    br._call_llm_sync = REAL_LLM


# ── A · 内置人格判定：来源标记优先，不看名字 ────────────────────────
def test_a():
    print("[A] 内置人格判定：persona_meta.json → 「自定义：」前缀 → 名字匹配")
    slot_own = [{"name": "问候", "at": "12:00", "window_min": 30, "lines": ["我自己的问候"]}]

    bot = mkbot("a1", "自定义：鲸鱼娘", {"builtin": False, "name": "鲸鱼娘"},
                greetings={"enabled": True, "slots": slot_own})
    conf = br._greetings_conf(bot)
    check("A1 同名自定义人格（meta.builtin=false）→ preset=False、slots 可自定义",
          conf["preset"] is False and [s["lines"] for s in conf["slots"]] == [["我自己的问候"]],
          conf)

    bot = mkbot("a2", "鲸鱼娘", {"builtin": True, "name": "鲸鱼娘"},
                greetings={"enabled": True, "slots": [
                    {"name": "早上", "at": "07:10", "window_min": 7, "lines": ["改不动的假内容"]}]})
    conf = br._greetings_conf(bot)
    check("A2 meta.builtin=true → preset=True、内容锁死（lines 仍取代码）",
          conf["preset"] is True and (conf["slots"][0]["at"], conf["slots"][0]["window_min"]) == ("07:10", 7)
          and conf["slots"][0]["lines"] == br._PRESET_GREETINGS["鲸鱼娘"][0]["lines"], conf)

    clear_logs()
    bot = mkbot("a3", "鲸鱼娘")
    conf = br._greetings_conf(bot)
    check("A3 无 meta、无「自定义：」前缀 → 回退按名字（与旧行为一致）",
          conf["preset"] is True
          and "问候判定：persona_meta.json 缺失，回退按名字匹配" in logtext(), logs())

    clear_logs()
    bot = mkbot("a4", "自定义：鲸鱼娘",
                greetings={"enabled": True, "slots": slot_own})
    conf = br._greetings_conf(bot)
    check("A4 无 meta + 原文「自定义：鲸鱼娘」→ preset=False（用户报 bug 的确切链路）",
          conf["preset"] is False and conf["slots"][0]["lines"] == ["我自己的问候"], conf)
    check("A4b 判定日志写明用的是「自定义：」前缀",
          "问候判定：persona_name.txt 带「自定义：」前缀" in logtext(), logs())
    check("A4c _persona_name 保持现状（「自定义：」前缀照样去掉）",
          br._persona_name(bot) == "鲸鱼娘", br._persona_name(bot))

    # 迁移也不该把同名自定义人格当内置（enabled 默认关、preset=False）
    clear_logs()
    bot = mkbot("a5", "自定义：鲸鱼娘")
    br._migrate_words(bot, "botA5")
    g = br._load_json(os.path.join(bot, "greetings.json"), {})
    check("A5 迁移：同名自定义人格写 preset=False、默认不开启",
          g.get("preset") is False and g.get("enabled") is False, g)


# ── B · 群里的主人判定：按 openid 比对 ─────────────────────────────
OWNER = "OWNER_OPENID_1234"


def test_b():
    print("[B] 群里认主：作者 openid == 主人 openid（groups 配置只是可选覆盖）")
    phrases = {"aliases": {"master": "阿爸", "others": "客人"}}
    owner_file = {"user_openid": OWNER, "member_openid": OWNER}
    bot = mkbot("b1", "桑多涅", owner=owner_file, phrases=phrases)

    clear_logs()
    msg = FakeMsg("m1", "在吗", FakeAuthor(OWNER))
    check("B1a 群里作者 openid == 主人 openid → 判成主人",
          br._is_master_msg(bot, GID, msg) is True)
    rules = br._words_rules(bot, "g" + GID, OWNER)
    check("B1b 主人 → master 称呼，且没有「禁止用主人」",
          "请用「阿爸」称呼对方" in rules and "禁止用「主人」" not in rules, rules)
    check("B1c 日志写明命中 owner openid + 群",
          "称呼：对象=主人（命中 owner openid 前 8 位 OWNER_OP｜群=group_op" in logtext(), logs())
    same, n, _o = br._master_word_fix(bot, "g" + GID, "主人早点睡", OWNER)
    check("B1d 主人 → 一个字都不替换", (same, n) == ("主人早点睡", 0), (same, n))

    clear_logs()
    guest = FakeMsg("m2", "在吗", FakeAuthor("GUEST_OPENID_99"))
    check("B2a 群里作者 openid != 主人 openid → 不是主人",
          br._is_master_msg(bot, GID, guest) is False)
    rules = br._words_rules(bot, "g" + GID, "GUEST_OPENID_99")
    check("B2b 客人 → others 称呼 + 禁止「主人」硬规则",
          "请称呼对方为「客人」" in rules and "禁止用「主人」称呼对方" in rules, rules)
    fixed, n, others = br._master_word_fix(bot, "g" + GID, "主人早点睡", "GUEST_OPENID_99")
    check("B2c 客人 → 正文里的「主人」被替换成「客人」",
          (fixed, n, others) == ("客人早点睡", 1, "客人"), (fixed, n, others))
    check("B2d 日志写明群 + 作者前 8 位",
          "称呼：对象=其他人（群=group_op｜作者前 8 位 GUEST_OP）" in logtext(), logs())
    bot_flag = FakeMsg("m3", "在吗", FakeAuthor(OWNER, bot=True))
    check("B2e 作者是 bot（哪怕 openid 撞了）→ 不算主人",
          br._is_master_msg(bot, GID, bot_flag) is False)

    # 可选覆盖：groups/<gid>.json 的 owner_member_openid
    bot = mkbot("b3", "桑多涅", owner=owner_file, phrases=phrases)
    write_json(os.path.join(bot, "groups", GID + ".json"),
               {"owner_member_openid": "OVERRIDE_777"})
    check("B3a 配了 owner_member_openid 且与作者一致 → 主人（覆盖生效）",
          br._is_master_msg(bot, GID, FakeMsg("m4", "在吗", FakeAuthor("OVERRIDE_777"))) is True)
    check("B3b 覆盖优先：这份群配置就是本群主人的唯一口径",
          br._is_master_msg(bot, GID, FakeMsg("m5", "在吗", FakeAuthor(OWNER))) is False)
    write_json(os.path.join(bot, "groups", GID[:8] + ".json"),
               {"owner_member_openid": "OVERRIDE_888"})
    os.remove(os.path.join(bot, "groups", GID + ".json"))
    check("B3c 群配置文件名兼容前 8 位写法",
          br._is_master_msg(bot, GID, FakeMsg("m6", "在吗", FakeAuthor("OVERRIDE_888"))) is True)

    rules = br._words_rules(bot, "p" + OWNER)
    check("B4 私聊主人 → 主人称呼（老路径不变）", "请用「阿爸」称呼对方" in rules, rules)

    # 端到端：guard 开 → 替换；guard 关 → 不替换但规则照旧注入
    write_llm()
    br._base_dir = BASE
    bot = mkbot("b5", "桑多涅", owner=owner_file, phrases=phrases)
    br._call_llm_sync = fake_llm
    MODEL["out"] = "主人晚安，早点睡。"

    reset_runtime()
    br._call_llm_sync = fake_llm
    MODEL["out"] = "主人晚安，早点睡。"
    asyncio.run(br._group_send("b5", bot, FakeMsg("mb1", "在吗", FakeAuthor("GUEST_B5")),
                              GID, "", "在吗"))
    got = [kw.get("content") for kw in API.sent]
    check("B5a guard 开（默认）→ 发出去的正文里「主人」已换成 others",
          got and got[-1] == "客人晚安，早点睡。", got)
    check("B5b 日志：称呼兜底：替换 1 处", "称呼兜底：替换 1 处" in logtext(), logs())

    write_llm(master_word_guard=False)
    reset_runtime()
    br._call_llm_sync = fake_llm
    MODEL["out"] = "主人晚安，早点睡。"
    asyncio.run(br._group_send("b5", bot, FakeMsg("mb2", "在吗", FakeAuthor("GUEST_B5")),
                              GID, "", "在吗"))
    got = [kw.get("content") for kw in API.sent]
    check("B5c guard=false → 不做文本替换（原样发出）",
          got and got[-1] == "主人晚安，早点睡。", got)
    check("B5d guard=false 但硬规则照样注入（禁止用「主人」）",
          "禁止用「主人」称呼对方" in logtext(), logs())
    check("B5e guard=false 时不写兜底日志", "称呼兜底" not in logtext(), logs())


# ── C · 群级互斥：同一条消息只有一个 bot 真的发出去 ────────────────
# 真机：同一个 QQ 群，两个 bot 看到的 group_openid **不一样**（按 bot 隔离）。
GID_A = "group_openid_A"      # 惜月看到的群 openid
GID_B = "group_openid_B"      # 大肥鱼看到的群 openid


def test_c():
    print("[C] 群级互斥：同一条消息被两个 bot 同时点名时只有一个发出")
    write_llm()
    write_bots([{"appid": "cA", "name": "桑多涅", "enabled": True},
                {"appid": "cB", "name": "哥伦比娅", "enabled": True}])
    br._base_dir = BASE
    botA = mkbot("cA", "桑多涅")
    botB = mkbot("cB", "哥伦比娅")

    async def slow(appid, botdir, key, who, incoming, images=None, extra_sys=""):
        # cA 先发；cB 还在生成（模拟同一条消息被两个 bot 各自点名、几乎同时完成）
        await asyncio.sleep(0.0 if appid == "cA" else 0.08)
        return "%s 说一句" % appid

    async def both(text):
        # 两个 bot 的 gid 必须不同（真机就是不同）。1.0.9 的 gid 版互斥只在
        # 「两个 bot 共享同一个 gid」时命中 —— 那个前提只存在于旧单测里，真机永不命中。
        await asyncio.gather(
            br._group_send("cA", botA, FakeMsg("mc1", text, FakeAuthor("h1"), gid=GID_A),
                           GID_A, "", text),
            br._group_send("cB", botB, FakeMsg("mc2", text, FakeAuthor("h1"), gid=GID_B),
                           GID_B, "", text),
        )

    reset_runtime()
    br._reply = slow
    asyncio.run(both("同一句"))
    got = [kw.get("content") for kw in API.sent]
    check("C1 同一条消息被两个 bot 同时点名（gid 不同）→ 只发出一条", len(got) == 1, got)
    check("C1b 另一个记「群级互斥…放弃」日志（带会话键）",
          any("群级互斥：本群 3 秒内已有" in x and "放弃" in x and "键=" in x
              for x in logs()), logs())

    async def fast(appid, botdir, key, who, incoming, images=None, extra_sys=""):
        return "%s 接一句" % appid

    reset_runtime()
    br._reply = fast
    asyncio.run(br._group_send("cA", botA, FakeMsg("mc3", "第一句", FakeAuthor("h3"), gid=GID_A),
                              GID_A, "", "第一句"))
    # 正常接龙：B 这一轮在 A 已经说完之后才开头（触发它的是 A 那句，不是同一条消息）→ 不该被拦
    asyncio.run(br._group_send("cB", botB,
                              FakeMsg("mc4", "桑多涅那句", FakeAuthor("oid_cA", bot=True), gid=GID_B),
                              GID_B, "桑多涅", "桑多涅那句"))
    check("C2 正常接龙（B 在 A 发言之后才开头）→ 照常发出，不被互斥误伤",
          len(API.sent) == 2, [kw.get("content") for kw in API.sent])


def main():
    os.makedirs(BASE, exist_ok=True)
    write_bots([{"appid": "b5", "name": "桑多涅", "enabled": True},
                {"appid": "cA", "name": "桑多涅", "enabled": True},
                {"appid": "cB", "name": "哥伦比娅", "enabled": True}])
    try:
        test_a()
        test_b()
        test_c()
    finally:
        shutil.rmtree(TMP, ignore_errors=True)
    print("\n%d ok / %d fail" % (len(OK), len(FAIL)))
    if FAIL:
        print("失败的检查：" + "、".join(FAIL))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
