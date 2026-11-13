# -*- coding: utf-8 -*-
"""两处真机事故的 host 端回归（不装 app、不连 QQ、不花 Key、不出网）：

  A) 问候：persona_meta.json 的 name 带档位后缀（「鲸鱼娘-完整」），而 _PRESET_GREETINGS
     的键不带 → 旧实现永远查不到预设，掉到文件槽位；内置人格的槽位 lines 是空的，
     于是一条问候都发不出去。现在查表前先去后缀，查不到再按 persona_name.txt 查一次。

  B) own_author.json 被写进人类 openid → 主人所有消息都被当成「本 bot 自己」跳过。
     污染源是回声分支里的 _note_own_author（能走到那里的作者都不是 bot）。现在：
     回声分支不学 id、_bot_author 不看 own_author、_is_echo 要求归一化后 ≥ 6 字、
     启动时按已知人类 id 自愈清除脏数据。

    cd andbna-app && python3 tests/test_own_author_heal.py
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
APPID = "botHeal_appid"
GROUP = "group_openid_heal"
OWNER = "OWNER_OPENID_0001"          # 主人私聊的 openid（真人）
HUMAN_A = "HUMAN_OPENID_AAAA"
HUMAN_B = "HUMAN_OPENID_BBBB"
OTHER_BOT = "OTHER_BOT_OPENID_CCCC"
SELF_ID = "SELF_OPENID_DDDD"
TMP = tempfile.mkdtemp(prefix="own_author_heal_test_")
BASE = os.path.join(TMP, "localbot")


def check(name, cond, detail=""):
    (OK if cond else FAIL).append(name)
    print(("  ok   " if cond else "  FAIL ") + name
          + (("  <- " + str(detail)) if (detail and not cond) else ""))


def logs():
    return list(br._state["log"])


def logtext():
    return "\n".join(logs())


def clear_logs():
    del br._state["log"][:]


def wjson(path, obj):
    d = os.path.dirname(path)
    if d and not os.path.isdir(d):
        os.makedirs(d)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False)
    return path


def rtext(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def mkbot(appid, name=None, meta=None, greetings=None, owner=None):
    d = os.path.join(BASE, appid)
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, "persona.txt"), "w", encoding="utf-8") as f:
        f.write("你是测试人格。")
    if name is not None:
        with open(os.path.join(d, "persona_name.txt"), "w", encoding="utf-8") as f:
            f.write(name)
    if meta is not None:
        wjson(os.path.join(d, "persona_meta.json"), meta)
    if greetings is not None:
        wjson(os.path.join(d, "greetings.json"), greetings)
    if owner is not None:
        wjson(os.path.join(d, "owner.json"), owner)
    return d


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
        self.c2c = []

    async def post_group_message(self, **kw):
        self.sent.append(kw)

    async def post_c2c_message(self, **kw):
        self.c2c.append(kw)


class FakeClient(object):
    def __init__(self):
        self.api = FakeApi()


API = None


class FakeMsg(object):
    def __init__(self, mid, text, author, mentions=None, gid=GROUP):
        self.id = mid
        self.content = text
        self.group_openid = gid
        self.author = author
        self.mentions = mentions or []
        self.raw = {}
        self._api = API


def at_mentions(oid=APPID):
    return [{"bot": True, "id": oid, "is_you": True, "member_openid": oid,
             "member_role": "member", "scope": "single", "username": "谁"}]


def reset_env():
    global API
    br._seen_set.clear()
    del br._seen_ids[:]
    br._outbox.clear()
    br._rate.clear()
    br._intent.clear()
    br._mute.clear()
    br._state["log"][:] = []
    br._base_dir = ""
    API = FakeApi()


def run(msg):
    return asyncio.run(br._handle_group(APPID, BOTDIR, msg, "群消息(新事件)"))


BOTDIR = ""


# ── A. 问候：meta 名字带档位后缀也要命中预设 ─────────────────────

def test_preset_suffix():
    print("[A] 问候：persona_meta.json 的 name 带档位后缀")
    # 鲸鱼娘-完整 → 命中「鲸鱼娘」的预设（内置文案，3 个时段）
    d = mkbot("g_wd", name="鲸鱼娘-完整", meta={"builtin": True, "name": "鲸鱼娘-完整"},
              greetings={"preset": True, "enabled": True,
                         "targets": {"owner": True, "groups": False},
                         "slots": [{"name": "早上", "at": "08:00", "window_min": 30, "lines": []}]})
    fixed = br._greet_preset(d)
    check("A1 鲸鱼娘-完整 → 命中预设", bool(fixed) and len(fixed) == 3,
          "fixed=%r" % (fixed,))
    check("A2 命中内置文案（首句非空、来自代码）",
          bool(fixed) and fixed[0]["lines"] == br._PRESET_GREETINGS["鲸鱼娘"][0]["lines"],
          fixed[0]["lines"] if fixed else "-")
    conf = br._greetings_conf(d)
    check("A3 greetings_conf 用预设：preset=True 且有文案",
          conf["preset"] is True and bool(conf["slots"])
          and all(s["lines"] for s in conf["slots"]) and len(conf["slots"]) == 1,
          conf["slots"])
    check("A4 文件里那一条空 lines 时段不会把文案吞掉",
          conf["slots"][0]["name"] == "早上" and conf["slots"][0]["at"] == "08:00",
          conf["slots"][0])
    check("A4b 命中预设要留一行日志（真机复盘看得见）",
          "问候判定：命中内置预设「鲸鱼娘」" in "\n".join(br._state["log"]),
          br._state["log"][-3:])

    # 奥黛塔-完整 → 命中「奥黛塔」
    d = mkbot("g_odt", name="奥黛塔-完整", meta={"builtin": True, "name": "奥黛塔-完整"})
    fixed = br._greet_preset(d)
    check("A5 奥黛塔-完整 → 命中预设",
          bool(fixed) and fixed == br._PRESET_GREETINGS["奥黛塔"], fixed)

    # meta 名字查不到（改名）→ 再按 persona_name.txt 查一次
    d = mkbot("g_alt", name="鲸鱼娘-精华", meta={"builtin": True, "name": "旧名字"})
    clear_logs()
    fixed = br._greet_preset(d)
    check("A6 meta 名查不到 → 回退 persona_name.txt 命中预设",
          bool(fixed) and fixed == br._PRESET_GREETINGS["鲸鱼娘"], "%r / %s" % (fixed, logtext()))
    check("A7 回退成功时不记「按文件槽位」", "按文件槽位" not in logtext(), logtext())

    # 都查不到 → 保持旧日志措辞
    d = mkbot("g_none", name="", meta={"builtin": True, "name": "幽灵-完整"})
    clear_logs()
    check("A8 都查不到 → None", br._greet_preset(d) is None)
    check("A9 保留旧日志措辞",
          "问候判定：persona_meta.json 标了内置，但代码里没有「幽灵-完整」的预设问候 → 按文件槽位"
          in logtext(), logtext())

    # 自定义人格：meta builtin=false → 一律按文件，名字撞上内置也不锁
    d = mkbot("g_custom", name="自定义：鲸鱼娘",
              meta={"builtin": False, "name": "鲸鱼娘"},
              greetings={"preset": False, "enabled": True,
                         "targets": {"owner": True, "groups": False},
                         "slots": [{"name": "问候", "at": "12:00", "window_min": 30,
                                    "lines": ["自定义的一句"]}]})
    check("A10 自定义人格仍按文件（不命中预设）", br._greet_preset(d) is None)
    conf = br._greetings_conf(d)
    check("A11 自定义人格的文案来自文件",
          conf["preset"] is False and conf["slots"][0]["lines"] == ["自定义的一句"],
          conf["slots"])
    shutil.rmtree(os.path.join(BASE, "g_wd"), ignore_errors=True)
    shutil.rmtree(os.path.join(BASE, "g_odt"), ignore_errors=True)
    shutil.rmtree(os.path.join(BASE, "g_alt"), ignore_errors=True)
    shutil.rmtree(os.path.join(BASE, "g_none"), ignore_errors=True)
    shutil.rmtree(os.path.join(BASE, "g_custom"), ignore_errors=True)


# ── B. 槽位 lines 空且非预设：日志说清「没有可用文案」 ────────────

def test_empty_lines_log():
    print("[B] 文件槽位 lines 空 + 没命中预设")
    client = FakeClient()
    d = mkbot("g_empty", name="自定义：某人",
              meta={"builtin": False, "name": "某人"},
              greetings={"preset": False, "enabled": True,
                         "targets": {"owner": True, "groups": False},
                         "slots": [{"name": "问候", "at": "12:00", "window_min": 60, "lines": []}]})
    clear_logs()
    got = asyncio.run(br._maybe_greet(client, "g_empty", d, now=12 * 3600 + 60))
    check("B1 没有文案 → 不发", got == "" and not client.api.c2c and not client.api.sent, got)
    check("B2 日志：没有可用文案（非预设人格且槽位未填 lines）",
          "问候：没有可用文案（非预设人格且槽位未填 lines）→ 本轮跳过" in logtext(), logtext())
    shutil.rmtree(d, ignore_errors=True)


# ── C. 回声：短句不算，命中也不学 id ───────────────────────────

def test_echo_rules():
    print("[C] _is_echo 收紧")
    reset_env()
    br._note_sent(APPID, GROUP, "定位到了")
    check("C1 4 字短句：不算回声", br._is_echo(APPID, GROUP, "定位到了") is False)
    br._note_sent(APPID, GROUP, "12345")
    check("C2 5 字：不算回声（边界外）", br._is_echo(APPID, GROUP, "12345") is False)
    br._note_sent(APPID, GROUP, "123456")
    check("C3 6 字：算回声（边界内）", br._is_echo(APPID, GROUP, "123456") is True)
    br._note_sent(APPID, GROUP, "今天的风有点大呢")
    check("C4 长句命中仍是回声", br._is_echo(APPID, GROUP, "今天的风有点大呢") is True)
    check("C5 归一化后算长度（带空白与 @ 也不算长）",
          br._is_echo(APPID, GROUP, "1 2 3 4 5") is False)
    check("C6 空文本不算回声", br._is_echo(APPID, GROUP, "") is False)
    check("C7 常量就是 6", br._ECHO_MIN_LEN == 6)


def test_echo_does_not_learn():
    """回声命中：人类作者不写 own_author.json；bot 作者写 bot_ids.json（真·自己的回声）。"""
    print("[D] 回声分支不再学 author id")
    reset_env()
    check("D0 _note_own_author 已删除", not hasattr(br, "_note_own_author"))
    # 人类作者 + 内容与本 bot 刚发的一致 → 只跳过，不落 own_author.json
    br._note_sent(APPID, GROUP, "这句话是我刚才说的")
    run(FakeMsg("m_echo1", "这句话是我刚才说的", FakeAuthor(HUMAN_A, bot=False), at_mentions()))
    own_path = os.path.join(BOTDIR, "own_author.json")
    check("D1 人类作者的回声 → 跳过", any("跳过：疑似复读本 bot 内容" in x for x in logs()), logs())
    check("D2 人类作者的回声不写 own_author.json", not os.path.exists(own_path),
          rtext(own_path) if os.path.exists(own_path) else "-")
    check("D3 该人类 id 没被记成自己的", HUMAN_A not in br._own_author(BOTDIR))

    # bot 作者 + 内容命中 → 学进 bot_ids.json（不是 own_author.json）
    reset_env()
    br._note_sent(APPID, GROUP, "这句话是我刚才说的")
    run(FakeMsg("m_echo2", "这句话是我刚才说的", FakeAuthor(SELF_ID, bot=True), at_mentions()))
    table = br._bot_ids(BOTDIR)
    check("D4 bot 作者的回声 → 学进 bot_ids.json", table.get(SELF_ID) == APPID, table)
    check("D5 学到的 id 不进 own_author.json", not os.path.exists(own_path))


# ── E. 闸 2：own_author.json 不再决定跳过 ─────────────────────

def test_bot_author_ignores_own_author():
    print("[E] _bot_author 不再看 own_author.json")
    d = mkbot("g_author", name="鲸鱼娘", meta={"builtin": True, "name": "鲸鱼娘"})
    wjson(os.path.join(d, "own_author.json"), {"ids": [HUMAN_A]})
    clear_logs()
    why = br._bot_author(d, FakeMsg("m_a1", "在吗", FakeAuthor(HUMAN_A, bot=False)))
    check("E1 own_author 里躺着人类 id 也不跳过", why == "", why)
    check("E2 不再出现「本 bot 自己的 author id」这类日志",
          "本 bot 自己的 author id" not in logtext(), logtext())
    why = br._bot_author(d, FakeMsg("m_a2", "我是 bot", FakeAuthor(OTHER_BOT, bot=True)))
    check("E3 author.bot=true 仍然跳过", "author.bot=true" in why, why)
    wjson(os.path.join(d, "known_bots.json"), {"bots": [OTHER_BOT]})
    why = br._bot_author(d, FakeMsg("m_a3", "我是 bot", FakeAuthor(OTHER_BOT, bot=None)))
    check("E4 known_bots.json 仍然跳过", "known_bots.json" in why, why)
    shutil.rmtree(d, ignore_errors=True)


# ── F. 自愈 ───────────────────────────────────────────────────

def test_heal():
    print("[F] own_author.json 启动自愈")
    d = mkbot("g_heal", name="鲸鱼娘", meta={"builtin": True, "name": "鲸鱼娘"},
              owner={"user_openid": OWNER})
    wjson(os.path.join(d, "known_bots.json"), {"bots": [OTHER_BOT]})
    path = os.path.join(d, "own_author.json")
    # 主人 openid + 人类 openid + 别的 bot 的 id + 真正自己的 id
    # 真正「自己的 id」只认 bot_ids.json 里那份（author.bot=true + 回声才写）
    br._note_own_bot_id(d, [SELF_ID], "g_heal")
    wjson(path, {"ids": [OWNER, HUMAN_A, OTHER_BOT, SELF_ID]})
    br._note_human_author([HUMAN_A])
    clear_logs()
    dropped = br._heal_own_author(d, "g_heal")
    after = json.loads(rtext(path))
    check("F1 剔除了 3 个（主人 / 见过的真人 / 别的 bot）",
          dropped == {OWNER, HUMAN_A, OTHER_BOT}, dropped)
    check("F2 只留下 bot_ids.json 认得的自己的 id", after.get("ids") == [SELF_ID], after)
    check("F3 日志：自己 author id 自愈：剔除 3 个（疑似人类 openid）",
          "自己 author id 自愈：剔除 3 个（疑似人类 openid）" in logtext(), logtext())

    # 全是脏数据 → 文件删掉
    wjson(path, {"ids": [OWNER, HUMAN_B]})
    br._note_human_author([HUMAN_B])
    clear_logs()
    br._heal_own_author(d, "g_heal")
    check("F4 剔空 → 删除文件", not os.path.exists(path))
    check("F5 剔空也记一条自愈日志",
          "自己 author id 自愈：剔除 2 个（疑似人类 openid）" in logtext(), logtext())

    # 干净文件不动、不记日志
    wjson(path, {"ids": [SELF_ID]})
    clear_logs()
    br._heal_own_author(d, "g_heal")
    check("F6 干净文件原样保留",
          json.loads(rtext(path)).get("ids") == [SELF_ID], rtext(path))
    check("F7 没有可剔的不记自愈日志", "自愈：剔除" not in logtext(), logtext())

    # 真人身份出现在消息里 → 进对照表（_sender_of 的真实路径）
    reset_env()
    who = br._sender_of(d, FakeMsg("m_s1", "我是主人", FakeAuthor(HUMAN_B, bot=False)))
    check("F8 真人作者被记进对照表", HUMAN_B in br._HUMAN_AUTHORS, br._HUMAN_AUTHORS)
    check("F9 真人作者仍判成真人", who[3] is False, who)
    wjson(path, {"ids": [HUMAN_B]})
    clear_logs()
    br._heal_own_author(d, "g_heal")
    check("F10 见过的真人 id 会被剔除（不靠配置）", not os.path.exists(path),
          rtext(path) if os.path.exists(path) else "-")

    # 没有任何「人类证据」的 id：只要不在 bot_ids.json 里就剔除（真机残留就是这一类）
    wjson(path, {"ids": ["2A9AC8566DDC14C94C6370D3B049D335"]})
    clear_logs()
    br._heal_own_author(d, "g_heal")
    check("F11 无证据的 id 也不留（不在 bot_ids.json 就不算自己）",
          not os.path.exists(path) and "自己 author id 自愈：剔除 1 个" in logtext(), logtext())
    shutil.rmtree(d, ignore_errors=True)


def main():
    global BOTDIR
    BOTDIR = os.path.join(BASE, APPID)
    os.makedirs(BOTDIR, exist_ok=True)
    br._register_self_id(APPID, APPID)
    br._base_dir = ""
    test_preset_suffix()
    test_empty_lines_log()
    test_echo_rules()
    test_echo_does_not_learn()
    test_bot_author_ignores_own_author()
    test_heal()
    shutil.rmtree(TMP, ignore_errors=True)
    print("\n%d ok / %d fail" % (len(OK), len(FAIL)))
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
