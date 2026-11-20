# -*- coding: utf-8 -*-
"""群管理（2.1.0 · Python 侧）：违规检测 / 撤回 / 阶梯禁言 / 踢出 / 命令 / 进群欢迎。

真机上这里每一条都要靠「群管理权限 + 事件订阅」才成立，所以单测全部用假 HTTP 桩
（FakeHttp 记录每一次官方接口调用）+ 假 api 对象（记录每一次发消息），不装 app、不连 QQ：

  A) 关键词归一化（全角 / 空白 / 零宽 / 大小写）
  B) 判定模型 yes-no（解析 + 三种 mode 的问不问规则）
  C) 次数阶梯（steps → 禁言分钟）
  D) 踢出阈值（n >= kick_at → 踢；踢失败 → 退化为最长时长禁言）
  E) 共享计数跨 bot（群名 + 昵称，两个 bot 两套 gid）
  F) 无昵称退化（<appid>:<openid>，只能 bot 内独立计）
  G) 无权限降级（403 / Forbidden / 权限 字样 → 优雅失败，不抛）
  H) 命令权限（主人 / 群管理员 / 都不是）
  I) 新人第一次发言不再欢迎（兜底已删除）+ 5 分钟内存去重
  J) intents 的 1<<24 与 GROUP_MEMBER_ADD 的路由
  K) 未知群 / 没有 api 也不炸

    cd andbna-app && flock /tmp/andbna.py.lock python3 tests/test_group_admin.py
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

BASE = tempfile.mkdtemp(prefix="gm_test_")          # = filesDir
BOT_A, BOT_B = "botA_appid", "botB_appid"
NAME_A, NAME_B = "惜月", "大肥鱼"
GID_A = "0FE14634BEA6824FA46E90118503AB56"          # 同一群，两个 bot 各一套 gid
GID_B = "FC139BB1033AE4692554E358E3C9CE9D"
GROUP_NAME = "测试群一"
MASTER = "MASTER_OPENID_1"
HUMAN = "HUMAN_OPENID_9"
TARGET = "TARGET_OPENID_7"
SELF_OID = "SELF_BOT_OPENID"      # 本 bot 的 openid（mentions.is_you=true 的那个）
GID_MUTE = "0FE14634BEA6824FA46E90118503AB57"   # 只给「静音整群」那条用例用的群 id
RESULTS = []
FACTS = []          # 警告时交给模型的系统补充
MODEL_CALLS = []    # 判定模型被问了几次


# ── 假对象 ──────────────────────────────────────

class FakeHttp(object):
    """botpy BotHttp.request 的替身：记录 (method, path, params, body)，可指定某个路径失败。"""

    def __init__(self):
        self.calls = []
        self.fail = {}            # path 子串 -> 要抛的异常

    async def request(self, route, **kw):
        # 记 route.url（已把 {group_openid} 这些占位符填好）= 真机上真正打出去的那条路径
        url = getattr(route, "url", None) or route.path
        self.calls.append((route.method, url, dict(getattr(route, "parameters", {}) or {}),
                           kw.get("json")))
        for key, exc in self.fail.items():
            if key in url or key in route.path:
                raise exc
        if route.path.endswith("/info"):
            return {"group_name": GROUP_NAME, "group_member_num": 12}
        return {}

    def paths(self, method=""):
        return [(m, p) for (m, p, _a, _b) in self.calls if not method or m == method]


class FakeApi(object):
    def __init__(self):
        self.http = FakeHttp()
        self.sent = []
        self._http = self.http

    async def post_group_message(self, **kw):
        self.sent.append(kw)
        return {"id": "SENT_%d" % len(self.sent)}


class ForbiddenError(RuntimeError):
    """botpy 的 403（名字与真的一致：_gm_denied 只看类名）。"""

    def __init__(self, msg):
        self.msgs = msg


class FakeAuthor(object):
    def __init__(self, oid, nick="", bot=None):
        self.member_openid = oid
        self.user_openid = oid
        self.id = oid
        self.bot = bot
        if nick:
            self.username = nick


class FakeMsg(object):
    def __init__(self, mid, text, author, gid, mentions=None, role="", api=None):
        self.id = mid
        self.content = text
        self.group_openid = gid
        self.author = author
        self.mentions = mentions or []
        self.member_role = role
        self.raw = {}
        self._api = API if api is None else api


class FakeConn(object):
    """ConnectionState 的最小替身：只做 dispatch 登记。"""

    def __init__(self, api=None):
        self.api = api
        self.events = []

    def _dispatch(self, name, ev):
        self.events.append((name, ev))


class FakeIntents(object):
    """botpy.Intents 的最小替身：public_messages 是 1<<25。"""

    def __init__(self, public_guild_messages=False, public_messages=False):
        self.value = (1 if public_guild_messages else 0) | (1 << 25 if public_messages else 0)


API = None


# ── 夹具 ────────────────────────────────────────

def reset():
    global API
    API = FakeApi()
    br._base_dir = BASE
    # log_level=detail：群管理的技术行（intents / 计数退化 / 事件去重）都在这一档
    with open(os.path.join(BASE, "llm.json"), "w", encoding="utf-8") as f:
        json.dump({"log_level": "detail"}, f)
    br._log_level_cache.clear()
    br._state["log"][:] = []
    br._seen_set.clear()
    del br._seen_ids[:]
    br._outbox.clear()
    br._gate_state["ctx"].clear()
    br._group_cache.clear()
    br._gm_conf_cache.clear()
    br._gm_names.clear()
    br._gm_claims.clear()
    br._gm_sent.clear()
    br._gm_mem.clear()
    br._gm_msg_idx.clear()
    br._gm_msg_idx_ts.clear()
    br._gm_msg_idx_miss.clear()
    br._gm_welcome_mem.clear()
    del MODEL_CALLS[:]
    del FACTS[:]
    for d in (botdir(BOT_A), botdir(BOT_B)):
        shutil.rmtree(os.path.join(d, "group_admin"), ignore_errors=True)
        for name in ("persona_meta.json", "persona_name.txt", "msg_idx.json"):
            try:
                os.remove(os.path.join(d, name))
            except OSError:
                pass
    shutil.rmtree(os.path.join(BASE, "group_admin_shared"), ignore_errors=True)


def botdir(appid):
    d = os.path.join(BASE, appid)
    os.makedirs(d, exist_ok=True)
    return d


def write_roster():
    rows = [{"appid": BOT_A, "name": NAME_A, "enabled": True},
            {"appid": BOT_B, "name": NAME_B, "enabled": True}]
    with open(os.path.join(BASE, "localbots.json"), "w", encoding="utf-8") as f:
        json.dump(rows, f, ensure_ascii=False)


def write_owner(d=None):
    d = d or botdir(BOT_A)
    with open(os.path.join(d, "owner.json"), "w", encoding="utf-8") as f:
        json.dump({"user_openid": MASTER}, f, ensure_ascii=False)


def write_names(d, gid, name=GROUP_NAME):
    with open(os.path.join(d, "group_names.json"), "w", encoding="utf-8") as f:
        json.dump({gid: {"name": name, "members": 12, "ts": int(time.time() * 1000)}},
                  f, ensure_ascii=False)


def write_admin(d, gid, **kw):
    conf = {"enabled": True, "mode": "keyword+model",
            "keywords": ["卖挂", "买挂", "开挂", "外挂", "挂机脚本"],
            "steps": [{"n": 1, "mute_min": 10}, {"n": 2, "mute_min": 60}],
            "kick_at": 3, "warn_enabled": True, "recall_enabled": True,
            "welcome": {"enabled": False, "at": True, "text": ""}}
    conf.update(kw)
    if conf.get("welcome") is None:      # 反过来验「群文件没写 welcome」那条路
        conf.pop("welcome", None)
    dd = os.path.join(d, "group_admin")
    os.makedirs(dd, exist_ok=True)
    with open(os.path.join(dd, gid + ".json"), "w", encoding="utf-8") as f:
        json.dump(conf, f, ensure_ascii=False, indent=1)
    br._gm_conf_cache.clear()
    return conf


def logs():
    return list(br._state["log"])


def has(text):
    return any(text in x for x in logs())


def stub_model(answer, hits=None):
    async def fake(appid, botdir_, gid, hit, text):
        MODEL_CALLS.append((hit, text))
        if hits is not None:
            hits.append(hit)
        return answer, answer
    return fake


def stub_reply(say="别再发挂了"):
    async def fake(appid, botdir_, key, who, incoming, images=None, extra_sys=""):
        FACTS.append(extra_sys)
        return say
    return fake


def check(name, cond, detail):
    RESULTS.append((name, bool(cond), detail))
    print(("  PASS  " if cond else "  FAIL  ") + name + " :: " + str(detail))


def tick(msg, gid, text, appid=BOT_A, d=None):
    return asyncio.run(br._group_admin_tick(appid, d or botdir(appid), msg, gid, text, msg.id))


def violation_file(gid=GID_A):
    return br._gm_vpath(BASE, GROUP_NAME)


# ── A 关键词归一化 ───────────────────────────────

def case_norm():
    check("A1 去空白：「开 挂」== 「开挂」", br._gm_norm("开 挂") == "开挂", br._gm_norm("开 挂"))
    check("A2 大小写：「ABC」→「abc」", br._gm_norm("ABC") == "abc", br._gm_norm("ABC"))
    check("A3 全角：「ＡＢＣ」→「abc」", br._gm_norm("ＡＢＣ") == "abc", br._gm_norm("ＡＢＣ"))
    check("A4 零宽：开\\u200b挂 命中", br._gm_norm("开\u200b挂") == "开挂", repr(br._gm_norm("开\u200b挂")))
    hit = br._gm_keyword_hit("谁 有 外　挂 卖？", ["外挂"])
    check("A5 全角空格 + 空格拆词仍命中", hit == "外挂", "hit=%r" % (hit,))
    check("A6 干净正文不命中", br._gm_keyword_hit("今天天气不错", ["外挂"]) == "", "no hit")
    check("A7 命中词按配置原样返回（日志要照抄）",
          br._gm_keyword_hit("求 开挂 教程", ["开挂"]) == "开挂", "hit=开挂")


# ── B 判定模型 yes-no ───────────────────────────

def case_model():
    check("B1 yes → yes", br._gm_parse_yes("yes") == "yes" and br._gm_parse_yes("Yes.") == "yes",
          "yes")
    check("B2 no → no", br._gm_parse_yes("no") == "no" and br._gm_parse_yes("No.") == "no", "no")
    check("B3 「不是」判 no", br._gm_parse_yes("不是") == "no", br._gm_parse_yes("不是"))
    check("B4 空 / 判不出 → 空（按未违规）",
          br._gm_parse_yes("") == "" and br._gm_parse_yes("嗯") == "", "empty")

    real = br._gm_model_ask
    d = botdir(BOT_A)
    conf = br.group_admin_conf
    try:
        write_admin(d, GID_A)
        br._gm_model_ask = stub_model("no")
        v = asyncio.run(br._gm_verdict(BOT_A, d, GID_A, conf(d, GID_A), "谁有开挂", "开挂"))
        check("B5 keyword+model：命中但模型 no → 不判违规",
              v["violation"] is False and v["model"] == "no" and len(MODEL_CALLS) == 1,
              "v=%s calls=%d" % (v, len(MODEL_CALLS)))

        br._gm_model_ask = stub_model("yes")
        v = asyncio.run(br._gm_verdict(BOT_A, d, GID_A, conf(d, GID_A), "谁有开挂", "开挂"))
        check("B6 keyword+model：命中 + 模型 yes → 违规",
              v["violation"] is True and v["hit"] == "开挂", "v=%s" % (v,))

        cfg = br.group_admin_conf(d, GID_A)
        cfg["mode"] = "keyword"
        del MODEL_CALLS[:]
        v = asyncio.run(br._gm_verdict(BOT_A, d, GID_A, cfg, "谁有开挂", "开挂"))
        check("B7 mode=keyword：命中直接判，不问模型",
              v["violation"] is True and len(MODEL_CALLS) == 0, "calls=%d" % len(MODEL_CALLS))

        cfg["mode"] = "model"
        del MODEL_CALLS[:]
        br._gm_model_ask = stub_model("yes")
        v = asyncio.run(br._gm_verdict(BOT_A, d, GID_A, cfg, "今天天气不错", ""))
        check("B8 mode=model：没命中词也问模型（成本自负）",
              len(MODEL_CALLS) == 1 and v["violation"] is True, "calls=%d" % len(MODEL_CALLS))
    finally:
        br._gm_model_ask = real


# ── C 次数阶梯 ─────────────────────────────────

def case_steps():
    steps = [{"n": 1, "mute_min": 10}, {"n": 2, "mute_min": 60}, {"n": 3, "mute_min": 240}]
    check("C1 n=1 → 10 分钟", br._gm_mute_minutes(1, steps) == 10, "%d" % br._gm_mute_minutes(1, steps))
    check("C2 n=2 → 60 分钟", br._gm_mute_minutes(2, steps) == 60, "%d" % br._gm_mute_minutes(2, steps))
    check("C3 n=3 → 240 分钟", br._gm_mute_minutes(3, steps) == 240, "%d" % br._gm_mute_minutes(3, steps))
    check("C4 n=9 越界 → 最后一档", br._gm_mute_minutes(9, steps) == 240, "%d" % br._gm_mute_minutes(9, steps))
    check("C5 n=0 → 第一档", br._gm_mute_minutes(0, steps) == 10, "%d" % br._gm_mute_minutes(0, steps))
    check("C6 坏 steps（空 / 负）→ 默认一档 10",
          br._gm_mute_minutes(5, []) == 10 and br._gm_mute_minutes(5, [{"n": 1, "mute_min": -3}]) == 10,
          "default")
    check("C7 最长档用于踢出退化的兜底", br._gm_longest_minutes(steps) == 240,
          "%d" % br._gm_longest_minutes(steps))


# ── D 违规全流程：撤回 + 阶梯禁言 + 警告 ──────────────────

def case_punish():
    real_model, real_reply = br._gm_model_ask, br._reply
    try:
        d = botdir(BOT_A)
        write_admin(d, GID_A)
        write_names(d, GID_A)
        br._gm_model_ask = stub_model("yes")
        br._reply = stub_reply()
        msg = FakeMsg("m1", "谁有开挂脚本", FakeAuthor(HUMAN, "苏"), GID_A)
        ok = tick(msg, GID_A, msg.content)
        rec = br._gm_users(violation_file()).get("苏") or {}
        recalls = [p for (m, p, _a, _b) in API.http.calls if m == "DELETE" and "/messages/" in p]
        mutes = [(a, b) for (m, p, a, b) in API.http.calls
                 if m == "POST" and p.endswith("restrict_chat_setting")]
        check("D1 判定 yes → 违规处置（n=1，禁言 10 分钟）",
              ok is True and rec.get("count") == 1 and has("群管理：违规 n=1（关键词=开挂，模型=yes）→ 禁言 10 分钟"),
              "count=%s logs=%s" % (rec.get("count"),
                                    [x for x in logs() if "群管理：违规" in x][:1]))
        check("D2 撤回走 DELETE /messages/{id}", len(recalls) == 1 and "m1" in recalls[0],
              "recalls=%s" % recalls)
        check("D3 禁言走 POST restrict_chat_setting（op=add，10 分钟）",
              len(mutes) == 1 and mutes[0][1]["members"][0]["op"] == "add"
              and abs(mutes[0][1]["members"][0]["mute_expire_at"] - (time.time() + 600)) < 90,
              "%s" % (mutes[0][1] if mutes else None,))
        check("D4 警告按人格生成并 @ 了对方（markdown + qqbot-at-user）",
              FACTS and "累计第 1 次" in FACTS[0]
              and any('<qqbot-at-user id="%s"/>' % HUMAN in str(k.get("markdown") or {})
                      for k in API.sent),
              "facts=%s sent=%d" % (FACTS[:1], len(API.sent)))

        # 第二次：阶梯到 60 分钟
        del MODEL_CALLS[:]
        msg2 = FakeMsg("m2", "求开挂", FakeAuthor(HUMAN, "苏"), GID_A)
        tick(msg2, GID_A, msg2.content)
        rec2 = br._gm_users(violation_file()).get("苏") or {}
        check("D5 第 2 次 → 60 分钟（阶梯生效）",
              rec2.get("count") == 2 and has("禁言 60 分钟"), "count=%s" % rec2.get("count"))
        check("D6 判定模型每次只问一次", len(MODEL_CALLS) == 1, "calls=%d" % len(MODEL_CALLS))
    finally:
        br._gm_model_ask, br._reply = real_model, real_reply


# ── E 踢出阈值 ─────────────────────────────────

def case_kick():
    real_model, real_reply = br._gm_model_ask, br._reply
    try:
        d = botdir(BOT_A)
        write_admin(d, GID_A, kick_at=3,
                    steps=[{"n": 1, "mute_min": 10}, {"n": 2, "mute_min": 60},
                           {"n": 3, "mute_min": 240}])
        write_names(d, GID_A)
        br._gm_model_ask = stub_model("yes")
        br._reply = stub_reply()
        # 先攒到 2 次
        br._gm_bump(violation_file(), GROUP_NAME, "苏", BOT_A)
        br._gm_bump(violation_file(), GROUP_NAME, "苏", BOT_A)
        API.http.fail = {"/members/": ForbiddenError("没有权限")}
        msg = FakeMsg("m3", "开挂多少钱", FakeAuthor(HUMAN, "苏"), GID_A)
        ok = tick(msg, GID_A, msg.content)
        kicks = [p for (m, p, _a, _b) in API.http.calls if m == "DELETE" and "/members/" in p]
        mutes = [(a, b) for (m, p, a, b) in API.http.calls
                 if m == "POST" and p.endswith("restrict_chat_setting")]
        mins = ((mutes[-1][1]["members"][0]["mute_expire_at"] - time.time()) // 60) if mutes else -1
        check("E1 n=3 ≥ kick_at → 尝试踢出（DELETE /members/{openid}）",
              ok is True and len(kicks) == 1 and TARGET not in kicks[0] and HUMAN in kicks[0],
              "kicks=%s" % kicks)
        check("E2 踢出失败（无权限）→ 退化为最长时长禁言 240 分钟",
              len(mutes) == 1 and 239 <= mins <= 241 and has("踢出失败 → 最长禁言 240 分钟"),
              "mins=%s logs=%s" % (mins, [x for x in logs() if "踢出" in x][:2]))
        check("E3 无权限时日志写明「没有群管理权限」",
              has("没有群管理权限 → 退化为最长时长禁言"), "log")

        # 踢出成功的那一条：不做禁言
        reset()
        write_admin(d, GID_A, kick_at=1)
        write_names(d, GID_A)
        br._gm_model_ask = stub_model("yes")
        br._reply = stub_reply()
        msg = FakeMsg("m4", "开挂教程", FakeAuthor(HUMAN, "苏"), GID_A)
        tick(msg, GID_A, msg.content)
        mutes = [(m, p) for (m, p, _a, _b) in API.http.calls
                 if m == "POST" and p.endswith("restrict_chat_setting")]
        check("E4 kick_at=1 且踢出成功 → 不再禁言",
              len(mutes) == 0 and has("违规 n=1（关键词=开挂，模型=yes）→ 踢出"),
              "mutes=%d logs=%s" % (len(mutes), [x for x in logs() if "违规 n=1" in x][:1]))
    finally:
        br._gm_model_ask, br._reply = real_model, real_reply


# ── F 共享计数跨 bot ───────────────────────────

def case_shared():
    real_model, real_reply = br._gm_model_ask, br._reply
    try:
        write_roster()
        for appid, gid in ((BOT_A, GID_A), (BOT_B, GID_B)):
            d = botdir(appid)
            write_admin(d, gid)
            write_names(d, gid)          # 两个 bot 看到不同 gid，但群名相同
        br._gm_model_ask = stub_model("yes")
        br._reply = stub_reply()
        m1 = FakeMsg("a1", "谁有开挂", FakeAuthor("OID_A", "苏"), GID_A)
        tick(m1, GID_A, m1.content, appid=BOT_A)
        m2 = FakeMsg("b1", "求一个开挂", FakeAuthor("OID_B", "苏"), GID_B)
        tick(m2, GID_B, m2.content, appid=BOT_B)
        path = os.path.join(BASE, "group_admin_shared", br._gm_slug(GROUP_NAME),
                            "violations.json")
        rec = br._gm_users(path).get("苏") or {}
        check("F1 共享文件按「群名 slug + 昵称」落地",
              os.path.isfile(path) and rec.get("count") == 2,
              "path=%s rec=%s" % (path, rec))
        check("F2 两个 bot 都记进了 bots（appid 各自一套）",
              sorted(rec.get("bots") or []) == sorted([BOT_A, BOT_B]), "bots=%s" % (rec.get("bots"),))
        check("F3 第 2 只 bot 按共享次数走阶梯（60 分钟）",
              has("禁言 60 分钟"), [x for x in logs() if "违规 n=2" in x][:1])

        # 同一条正文在同一 30 秒桶里：后到的 bot 让位
        reset()
        write_roster()
        for appid, gid in ((BOT_A, GID_A), (BOT_B, GID_B)):
            write_admin(botdir(appid), gid)
            write_names(botdir(appid), gid)
        br._gm_model_ask = stub_model("yes")
        br._reply = stub_reply()
        same = "大家一起开挂"
        ma = FakeMsg("x1", same, FakeAuthor("OID_A2", "苏"), GID_A)
        tick(ma, GID_A, same, appid=BOT_A)
        mb = FakeMsg("x2", same, FakeAuthor("OID_B2", "苏"), GID_B)
        tick(mb, GID_B, same, appid=BOT_B)
        check("F4 同一条违规只处置一次（后到的写「已由 惜月 处理，本 bot 不动」）",
              has("群管理：已由 %s 处理，本 bot 不动" % NAME_A),
              [x for x in logs() if "本 bot 不动" in x][:1])
        check("F5 让位的那只不再调判定模型（省一次钱）",
              not any(x for x in MODEL_CALLS if x[1] == same and False) and
              len([c for c in MODEL_CALLS if c[1] == same]) == 1,
              "calls=%s" % (MODEL_CALLS,))
    finally:
        br._gm_model_ask, br._reply = real_model, real_reply


# ── G 无昵称 → bot 内独立计 ──────────────────────

def case_no_nick():
    real_model, real_reply = br._gm_model_ask, br._reply
    try:
        d = botdir(BOT_A)
        write_admin(d, GID_A)
        write_names(d, GID_A)
        br._gm_model_ask = stub_model("yes")
        br._reply = stub_reply()
        msg = FakeMsg("n1", "开挂脚本", FakeAuthor("OID_NONICK"), GID_A)
        tick(msg, GID_A, msg.content)
        local = os.path.join(d, "group_admin", GID_A + ".violations.json")
        rec = br._gm_users(local).get("%s:OID_NONICK" % BOT_A) or {}
        check("G1 无昵称 → 键 <appid>:<openid>，写 bot 目录（不写共享目录）",
              rec.get("count") == 1 and not os.path.exists(violation_file()),
              "rec=%s shared=%s" % (rec, os.path.exists(violation_file())))
        check("G2 日志写明「计数：昵称不可用，按 bot 内独立计」",
              has("计数：昵称不可用，按 bot 内独立计"), [x for x in logs() if "计数" in x][:1])
    finally:
        br._gm_model_ask, br._reply = real_model, real_reply


# ── H 无权限降级 ───────────────────────────────

def case_denied():
    real_model, real_reply = br._gm_model_ask, br._reply
    try:
        d = botdir(BOT_A)
        write_admin(d, GID_A)
        write_names(d, GID_A)
        br._gm_model_ask = stub_model("yes")
        br._reply = stub_reply()
        API.http.fail = {"restrict_chat_setting": ForbiddenError("机器人没有群管理权限"),
                         "/messages/": ForbiddenError("权限不足")}
        msg = FakeMsg("h1", "卖挂啦", FakeAuthor(HUMAN, "苏"), GID_A)
        ok = tick(msg, GID_A, msg.content)       # 不许抛
        check("H1 无群管理权限：撤回 / 禁言都失败但不抛，计数照记",
              ok is True and has("群管理：违规 n=1") and has("撤回=失败") and has("禁言 10 分钟（失败）"),
              [x for x in logs() if "群管理：违规 n=1" in x][:1])
        check("H2 失败日志点明「没有群管理权限」",
              has("没有群管理权限 → 继续后面几步") and has("没有群管理权限 → 已优雅降级"),
              [x for x in logs() if "群管理权限" in x][:2])
    finally:
        br._gm_model_ask, br._reply = real_model, real_reply


# ── I 命令权限 ────────────────────────────────

def case_cmd():
    real_reply = br._reply
    try:
        d = botdir(BOT_A)
        write_owner(d)
        write_admin(d, GID_A)
        br._reply = stub_reply()
        # 1) 既不是主人也不是管理员
        msg = FakeMsg("c1", "#禁言 10", FakeAuthor(HUMAN, "路人"), GID_A,
                      mentions=[{"member_openid": TARGET, "id": TARGET, "username": "甲"},
                                {"id": SELF_OID, "member_openid": SELF_OID, "is_you": True}])
        handled = asyncio.run(br.group_admin_cmd(BOT_A, d, msg, GID_A, msg.content, msg.id))
        check("I1 无权限：#禁言 → 回「没有群管理权限」并记日志，不发禁言请求",
              handled is True
              and any(k.get("content") == br._GM_NO_PERM for k in API.sent)
              and not [c for c in API.http.calls if "restrict" in c[1]]
              and has("群管理：#禁言 被拒"), "sent=%s" % ([k.get("content") for k in API.sent],))

        # 2) 主人 + @ 目标
        reset()
        write_owner(d)
        write_admin(d, GID_A)
        br._reply = stub_reply()
        msg = FakeMsg("c2", "#禁言 15", FakeAuthor(MASTER, "主人"), GID_A,
                      mentions=[{"member_openid": TARGET, "id": TARGET, "username": "甲"},
                                {"id": SELF_OID, "member_openid": SELF_OID, "is_you": True}])
        asyncio.run(br.group_admin_cmd(BOT_A, d, msg, GID_A, msg.content, msg.id))
        posts = [(a, b) for (m, p, a, b) in API.http.calls
                 if m == "POST" and p.endswith("restrict_chat_setting")]
        body = posts[-1][1] if posts else {}
        mins = ((body["members"][0]["mute_expire_at"] - time.time()) // 60) if body else -1
        check("I2 主人：#禁言 15 → op=add 且 15 分钟",
              bool(posts) and body["members"][0]["op"] == "add" and 14 <= mins <= 15
              and body["members"][0]["member_openid"] == TARGET,
              "mins=%s body=%s" % (mins, body))
        check("I3 #禁言 之后还查了一次群禁言状态（GET 同一路径）",
              any(m == "GET" and p.endswith("restrict_chat_setting")
                  for (m, p, _a, _b) in API.http.calls), "get")

        # 3) 群管理员（member_role=admin）
        reset()
        write_owner(d)
        write_admin(d, GID_A)
        br._reply = stub_reply()
        msg = FakeMsg("c3", "#解禁", FakeAuthor("ADMIN_OID", "管理员"), GID_A,
                      mentions=[{"member_openid": TARGET, "id": TARGET, "username": "甲"},
                                {"id": SELF_OID, "member_openid": SELF_OID, "is_you": True}],
                      role="admin")
        asyncio.run(br.group_admin_cmd(BOT_A, d, msg, GID_A, msg.content, msg.id))
        posts = [(a, b) for (m, p, a, b) in API.http.calls
                 if m == "POST" and p.endswith("restrict_chat_setting")]
        check("I4 member_role=admin 也算管理员（#解禁 → op=del）",
              bool(posts) and posts[-1][1]["members"][0]["op"] == "del" and has("群管理：#解禁"),
              "body=%s" % (posts[-1][1] if posts else None,))

        # 4) #撤回 不带回复 → 撤本 bot 上一条（用发出去时记下的 msg_id）
        reset()
        write_owner(d)
        write_admin(d, GID_A)
        br._reply = stub_reply()
        br._gm_note_sent(BOT_A, GID_A, "MINE_MSG_77")
        msg = FakeMsg("c4", "#撤回", FakeAuthor(MASTER, "主人"), GID_A,
                      mentions=[{"id": SELF_OID, "member_openid": SELF_OID, "is_you": True}])
        asyncio.run(br.group_admin_cmd(BOT_A, d, msg, GID_A, msg.content, msg.id))
        recs = [p for (m, p, _a, _b) in API.http.calls if m == "DELETE" and "/messages/" in p]
        check("I5 #撤回 不带回复 → 撤本 bot 上一条（outbox 记的 msg_id）",
              len(recs) == 1 and "MINE_MSG_77" in recs[0] and has("本 bot 上一条"),
              "recalls=%s" % recs)

        # 5) 不是命令的正文不动
        check("I6 普通正文不当作命令", br._gm_cmd_of("今天 #撤回 一下") == ("" , "")
              and br._gm_cmd_of("#禁言") == ("禁言", ""), "parse")
        check("I7 「@某人 #禁言 10」先剥 <@…> 再匹配（真机正文长这样）",
              br._gm_cmd_of("<@!TARGET_OPENID_7> #禁言 10") == ("禁言", "10")
              and br._gm_cmd_of("<@!ME> #撤回") == ("撤回", ""),
              "%s / %s" % (br._gm_cmd_of("<@!TARGET_OPENID_7> #禁言 10"),
                           br._gm_cmd_of("<@!ME> #撤回")))
    finally:
        br._reply = real_reply


# ── J 欢迎（成员事件 + 首次发言兜底 + 一次性）────────────

def case_welcome():
    try:
        d = botdir(BOT_A)
        write_admin(d, GID_A, welcome={"enabled": True, "at": True, "text": "欢迎加入"})
        # 1) 首次发言不再欢迎（兜底路径已整块删除）：不发消息、不写指纹、不落盘
        m = FakeMsg("f1", "大家好", FakeAuthor("OID_FIRST", "第一次"), GID_A)
        got1 = tick(m, GID_A, m.content)
        m2 = FakeMsg("f2", "我再说一句", FakeAuthor("OID_FIRST", "第一次"), GID_A)
        got2 = tick(m2, GID_A, m2.content)
        claims_dir = os.path.join(BASE, "group_admin_shared", "_claims")
        check("J1 新人第一次发言不再触发任何欢迎（不发消息、不写指纹）",
              got1 is False and got2 is False and not API.sent
              and not os.path.isfile(br._gm_sys_path(BASE))
              and not has("触发=首次发言"),
              "got=%s/%s sent=%d" % (got1, got2, len(API.sent)))
        check("J2 首次发言的落盘与认领也不再写（*.welcomed.json / welcome1st 键都没有）",
              not [n for n in os.listdir(os.path.join(d, "group_admin"))
                   if n.endswith(".welcomed.json")]
              and not [n for n in (os.listdir(claims_dir) if os.path.isdir(claims_dir) else [])
                       if n.startswith("welcome1st")], "")
        check("J2b 首次发言欢迎的函数已经删掉（真删，不是关掉）",
              not hasattr(br, "_gm_welcome_first") and not hasattr(br, "_gm_claim_first")
              and not hasattr(br, "_gm_welcome_claim"), "")

        # 2) 5 分钟内存去重
        t0 = 1000000.0
        check("J3 5 分钟内第二次视为已欢迎",
              br._gm_welcome_recent(BOT_A, GID_A, "M9", t0) is False
              and br._gm_welcome_recent(BOT_A, GID_A, "M9", t0 + 10) is True, "dedupe")
        check("J4 超过 5 分钟可以再欢迎",
              br._gm_welcome_recent(BOT_A, GID_A, "M9", t0 + 301) is False, "expire")

        # 3) GROUP_MEMBER_ADD 事件 → 真欢迎（markdown + @）
        reset()
        write_admin(d, GID_A, welcome={"enabled": True, "at": True, "text": "欢迎加入"})
        ev = br._GmMemberEvent(API, {"id": "EV1", "d": {
            "timestamp": 1, "group_openid": GID_A, "member_openid": "M_NEW",
            "union_group_openid": "U1"}})
        ok = asyncio.run(br.group_admin_member_add(BOT_A, d, ev))
        check("J5 成员进群 → 发 welcome.text 且 @ 新成员（markdown msg_type=2）",
              ok is True and API.sent and API.sent[-1].get("msg_type") == 2
              and '<qqbot-at-user id="M_NEW"/>' in API.sent[-1]["markdown"]["content"]
              and "欢迎加入" in API.sent[-1]["markdown"]["content"],
              "sent=%s" % ([{k: v for k, v in s.items() if k != "markdown"} for s in API.sent],))
        check("J6 日志：进群欢迎 → 已发（群=xxxx｜成员前 8 位）",
              has("群管理：进群欢迎 → 已发（群=%s｜成员=M_NEW" % GID_A[:8]),
              [x for x in logs() if "进群欢迎" in x][:1])
        n0 = len(API.sent)
        asyncio.run(br.group_admin_member_add(BOT_A, d, ev))
        check("J7 事件重复下发 → 5 分钟内不再发（防刷屏）",
              len(API.sent) == n0 and has("5 分钟内已欢迎过"),
              "posts=%d" % (len(API.sent) - n0))

        # 4) 欢迎关着 → 不发
        reset()
        write_admin(d, GID_A, welcome={"enabled": False})
        asyncio.run(br.group_admin_member_add(BOT_A, d, ev))
        check("J8 welcome.enabled=false → 一个字节都不发", len(API.sent) == 0,
              "posts=%d" % len(API.sent))

        # 5) 群文件没写 welcome → 也不会凭空欢迎（新人发言与事件都不发）
        reset()
        write_admin(d, GID_A, welcome=None)
        m = FakeMsg("w1", "大家好", FakeAuthor("OID_NOCONF", "新人"), GID_A)
        tick(m, GID_A, m.content)
        ev2 = br._GmMemberEvent(API, {"d": {"group_openid": GID_A, "member_openid": "M_NOCONF"}})
        asyncio.run(br.group_admin_member_add(BOT_A, d, ev2))
        check("J9 群文件没写 welcome → 新人发言与进群事件都一个字节不发",
              not API.sent, "posts=%d" % len(API.sent))
    finally:
        pass


# ── K intents / 事件路由 / 未知群不炸 ──────────────

def case_intents():
    intents = br._make_intents(FakeIntents, BOT_A)
    check("K1 intents 含 GROUP_MEMBER_EVENT（1<<24）与 public_messages（1<<25）",
          bool(intents.value & (1 << 24)) and bool(intents.value & (1 << 25))
          and br._GM_MEMBER_INTENT == (1 << 24),
          "intents=0x%x" % intents.value)
    check("K2 detail 日志打了 intents=0x…",
          has("intents=0x%x" % intents.value), [x for x in logs() if "intents=" in x][:1])

    conn = FakeConn(API)
    ev = br._gm_parse_member_add(conn, {"id": "EV2", "d": {
        "group_openid": GID_A, "member_openid": "M_ROUTE", "timestamp": 2}})
    check("K3 GROUP_MEMBER_ADD 解析后 dispatch 到 group_member_add",
          conn.events and conn.events[0][0] == "group_member_add"
          and conn.events[0][1] is ev, "events=%s" % [e[0] for e in conn.events])
    check("K4 事件体字段对得上（group_openid 就是本 bot 视角的群 id）",
          ev.group_openid == GID_A and ev.member_openid == "M_ROUTE" and ev.event_id == "EV2",
          "%r" % (ev,))

    # 未知群 / 没 api / 空事件：一律不炸
    bad = br._GmMemberEvent(None, {})
    ok1 = asyncio.run(br.group_admin_member_add(BOT_A, botdir(BOT_A), bad))
    ok2 = asyncio.run(br._gm_call(None, "DELETE", "/x"))
    conf = br.group_admin_conf(botdir(BOT_A), "../../etc/passwd")
    check("K5 空事件 / 没 api / 路径穿越的 gid 都不炸，且默认不做任何事",
          ok1 is False and ok2[0] is False and conf["enabled"] is False,
          "ok1=%s ok2=%s enabled=%s" % (ok1, ok2[0], conf["enabled"]))
    check("K6 没有群管理规则文件 → 群管理整体关闭（老版本行为不变）",
          br.group_admin_conf(botdir(BOT_B), "nothing_here")["enabled"] is False, "default off")


# ── L 与 Kotlin 侧的两处契约（_default.json 兜底 / _stats.json）────────

def write_default_welcome(d, enabled=True, text="欢迎加入本群"):
    dd = os.path.join(d, "group_admin")
    os.makedirs(dd, exist_ok=True)
    with open(os.path.join(dd, "_default.json"), "w", encoding="utf-8") as f:
        json.dump({"welcome": {"enabled": enabled, "at": True, "text": text}},
                  f, ensure_ascii=False)
    br._gm_conf_cache.clear()


def case_contract():
    real_model, real_reply = br._gm_model_ask, br._reply
    try:
        d = botdir(BOT_A)
        # 1) 群规则文件没写 welcome → 用 _default.json 的 bot 级默认
        write_admin(d, GID_A, welcome=None)
        write_default_welcome(d, True, "欢迎来到本群")
        conf = br.group_admin_conf(d, GID_A)
        check("L1 群文件没写 welcome → 用 _default.json 的 bot 级默认",
              conf["welcome"]["enabled"] is True and conf["welcome"]["text"] == "欢迎来到本群",
              "%s" % (conf["welcome"],))
        write_admin(d, GID_A, welcome={"enabled": True})     # 只写了开关
        conf = br.group_admin_conf(d, GID_A)
        check("L1b 群文件的 welcome 只写了开关 → 文案沿用 bot 级默认（字段级合并）",
              conf["welcome"]["enabled"] is True and conf["welcome"]["text"] == "欢迎来到本群",
              "%s" % (conf["welcome"],))
        ev = br._GmMemberEvent(API, {"d": {"group_openid": GID_A, "member_openid": "M_DEF"}})
        ok = asyncio.run(br.group_admin_member_add(BOT_A, d, ev))
        check("L2 新群还没有规则文件也能欢迎（bot 级默认生效）",
              ok is True and API.sent
              and "欢迎来到本群" in str(API.sent[-1].get("markdown") or {}),
              "posts=%d" % len(API.sent))

        # 2) _stats.json：界面「统计」只读它
        reset()
        write_admin(d, GID_A)
        write_names(d, GID_A)
        br._gm_model_ask = stub_model("yes")
        br._reply = stub_reply()
        m1 = FakeMsg("s1", "谁有开挂", FakeAuthor("OID_S1", "苏"), GID_A)
        tick(m1, GID_A, m1.content)
        m2 = FakeMsg("s2", "买挂找我", FakeAuthor("OID_S2", "李"), GID_A)
        tick(m2, GID_A, m2.content)
        path = os.path.join(d, "group_admin", "_stats.json")
        row = (json.load(open(path, encoding="utf-8")) if os.path.isfile(path) else {}).get(GID_A) or {}
        check("L3 每次处置都记 _stats.json（users=人数，hits=本 bot 处置次数）",
              row.get("users") == 2 and row.get("hits") == 2, "row=%s" % (row,))
    finally:
        br._gm_model_ask, br._reply = real_model, real_reply


# ── M 内置人格的欢迎语锁死（自定义人格才允许用户写）────────────

BUILTIN_SANDONGE = "（头也没抬，笔尖还在算）来了就自己找地方。先说好，别碰我的法洁欧。"
GENERIC_FALLBACK = "（抬眼）来了？随便坐，别拘束。"


def write_persona(d, builtin, name):
    with open(os.path.join(d, "persona_meta.json"), "w", encoding="utf-8") as f:
        json.dump({"builtin": builtin, "name": name}, f, ensure_ascii=False)


def sent_texts():
    out = []
    for k in API.sent:
        md = k.get("markdown")
        if isinstance(md, dict):
            out.append(str(md.get("content") or ""))
        else:
            out.append(str(k.get("content") or ""))
    return out


def case_welcome_locked():
    d = botdir(BOT_A)
    # 1) 内置人格：用代码里的文案，忽略文件里的 text
    write_persona(d, True, "桑多涅-完整")          # 真机落盘的名字带档位后缀
    write_admin(d, GID_A, welcome={"enabled": True, "at": True, "text": "用户写死的欢迎语"})
    ev = br._GmMemberEvent(API, {"d": {"group_openid": GID_A, "member_openid": "M_LOCK"}})
    ok = asyncio.run(br.group_admin_member_add(BOT_A, d, ev))
    text = sent_texts()[-1] if API.sent else ""
    check("M1 内置人格（桑多涅-完整）→ 用内置文案、忽略文件 text，且仍 @ 人",
          ok is True and BUILTIN_SANDONGE in text and "用户写死的欢迎语" not in text
          and '<qqbot-at-user id="M_LOCK"/>' in text,
          "text=%s" % text)
    check("M2 detail 日志写明「进群欢迎用内置文案（人格=桑多涅）」",
          has("群管理：进群欢迎用内置文案（人格=桑多涅）"),
          [x for x in logs() if "内置文案" in x][:1])

    # 2) 自定义人格：用文件里的 text
    reset()
    write_persona(d, False, "鲸鱼娘")          # 名字撞内置也不算（真机事故）
    write_admin(d, GID_A, welcome={"enabled": True, "at": True, "text": "我自己写的欢迎"})
    ev = br._GmMemberEvent(API, {"d": {"group_openid": GID_A, "member_openid": "M_CUSTOM"}})
    asyncio.run(br.group_admin_member_add(BOT_A, d, ev))
    text = sent_texts()[-1] if API.sent else ""
    check("M3 自定义人格（meta builtin=false）→ 用文件里的 text（名字撞内置也不锁）",
          "我自己写的欢迎" in text and "新来的鱼片" not in text, "text=%s" % text)

    # 3) 自定义人格 + 文件 text 为空 → 通用兜底
    reset()
    write_persona(d, False, "自定义：小林")
    write_admin(d, GID_A, welcome={"enabled": True, "at": False, "text": ""})
    ev = br._GmMemberEvent(API, {"d": {"group_openid": GID_A, "member_openid": "M_EMPTY"}})
    asyncio.run(br.group_admin_member_add(BOT_A, d, ev))
    text = sent_texts()[-1] if API.sent else ""
    check("M4 自定义人格 + text 为空 → 通用兜底（at=false 时不 @）",
          GENERIC_FALLBACK in text and "qqbot-at-user" not in text, "text=%s" % text)

    # 4) 文案来源规则本身（首次发言兜底已删，直接验 _gm_welcome_text）
    reset()
    write_persona(d, True, "猫娘")
    write_admin(d, GID_A, welcome={"enabled": True, "at": True, "text": "随便改的"})
    text = br._gm_welcome_text(br.group_admin_conf(d, GID_A), d, BOT_A)
    check("M5 内置人格（猫娘）→ 欢迎语锁死内置文案、忽略文件 text",
          "（耳朵抖了抖，凑近嗅了一下）嗯？新来的。坐吧喵，我不咬人的。" in text and "随便改的" not in text,
          "text=%s" % text)
    try:
        os.remove(os.path.join(d, "persona_meta.json"))
    except OSError:
        pass


class _RefObj(object):
    """botpy 的 Reference：message_id / id 是属性，不是字典键。"""

    def __init__(self, mid):
        self.message_id = mid
        self.id = ""


def _idx_msg(mid, idx):
    """一条「收到过的消息」：本地表要记的是它自己的 msg_idx。"""
    m = FakeMsg(mid, "垃圾广告", FakeAuthor(HUMAN, "苏"), GID_A)
    m.raw = scene_raw(msg_idx=idx)
    return m


# ── N 命令的 @ 门禁 + 引用解析（5 只 bot 各回一句报错的回归）──────────

def scene_raw(msg_idx="", ref_idx=""):
    """真机 payload 形状：引用在 message_scene.ext 的 ref_msg_idx=…，不是 message_reference。"""
    ext = []
    if ref_idx:
        ext.append("ref_msg_idx=%s" % ref_idx)
    if msg_idx:
        ext.append("msg_idx=%s" % msg_idx)
    return {"d": {"message_scene": {"ext": ext}, "message": {}}}


def write_group_mute(d, gid):
    """groups/<gid>.json enabled=false → _handle_group 记完 msg_idx 就返回，不花任何调用。"""
    dd = os.path.join(d, "groups")
    os.makedirs(dd, exist_ok=True)
    with open(os.path.join(dd, gid + ".json"), "w", encoding="utf-8") as f:
        json.dump({"enabled": False}, f)


def case_gate_and_ref():
    real_reply = br._reply
    try:
        d = botdir(BOT_A)
        br._reply = stub_reply()

        # N1 没 @ 本 bot 的群消息带 #撤回 → 不处理、不发任何话
        reset()
        write_owner(d)
        write_admin(d, GID_A)
        m = FakeMsg("n1", "#撤回", FakeAuthor(MASTER, "主人"), GID_A,
                    mentions=[{"id": "OTHER_BOT_OID", "member_openid": "OTHER_BOT_OID",
                               "is_you": False}])
        got = tick(m, GID_A, m.content)
        check("N1 没 @ 本 bot：#撤回 静默跳过（不发消息 / 不撤回，只留 detail 一行）",
              got is True and not API.sent
              and not [c for c in API.http.calls if c[0] == "DELETE"]
              and has("群管理：#撤回 不是叫本 bot，跳过"),
              "sent=%s del=%s" % (len(API.sent),
                                  len([c for c in API.http.calls if c[0] == "DELETE"])))

        # N2 收消息时把本条 msg_idx → message id 记进本地表（真链路上挂的点）
        reset()
        write_owner(d)
        write_admin(d, GID_A)
        # 单独一个 gid：静音文件不能留给后面的用例（groups/<gid>.json 是整群静音）
        write_group_mute(d, GID_MUTE)
        target = FakeMsg("t1", "垃圾广告", FakeAuthor(HUMAN, "苏"), GID_MUTE)
        target.raw = scene_raw(msg_idx="REFIDX_T1")
        asyncio.run(br._handle_group(BOT_A, d, target, "群消息(新事件)"))
        os.remove(os.path.join(d, "groups", GID_MUTE + ".json"))
        check("N2 收到消息 → message_scene.ext 的 msg_idx 进本地表（msg_idx.json）",
              br._msg_idx_find(d, "REFIDX_T1") == "t1"
              and os.path.exists(os.path.join(d, "msg_idx.json")) and not API.sent,
              "find=%r file=%s" % (br._msg_idx_find(d, "REFIDX_T1"),
                                   os.path.exists(os.path.join(d, "msg_idx.json"))))

        # N3 @ 了本 bot + ref_msg_idx 命中本地表 → 撤回那条 message_id，且只回 1 条
        reset()
        write_owner(d)
        write_admin(d, GID_A)
        br._msg_idx_note(BOT_A, d, _idx_msg("t9", "REFIDX_T9"), "t9")
        m = FakeMsg("n3", "#撤回", FakeAuthor(MASTER, "主人"), GID_A,
                    mentions=[{"id": SELF_OID, "member_openid": SELF_OID, "is_you": True}])
        m.raw = scene_raw(msg_idx="REFIDX_CMD", ref_idx="REFIDX_T9")
        asyncio.run(br.group_admin_cmd(BOT_A, d, m, GID_A, m.content, m.id))
        recs = [p for (meth, p, _a, _b) in API.http.calls if meth == "DELETE" and "/messages/" in p]
        check("N3 @ 了本 bot + ref_msg_idx 命中本地表 → 撤回对应 message_id，只回 1 条",
              len(recs) == 1 and "t9" in recs[0] and len(API.sent) == 1
              and API.sent[0].get("content") == "已撤回",
              "recalls=%s sent=%s" % (recs, [k.get("content") for k in API.sent]))

        # N4 message_reference 是 botpy 的对象（不是 dict）也能取到 id
        reset()
        write_owner(d)
        write_admin(d, GID_A)
        m = FakeMsg("n4", "#撤回", FakeAuthor(MASTER, "主人"), GID_A,
                    mentions=[{"id": SELF_OID, "member_openid": SELF_OID, "is_you": True}])
        m.message_reference = _RefObj("REF_OBJ_ID")
        asyncio.run(br.group_admin_cmd(BOT_A, d, m, GID_A, m.content, m.id))
        recs = [p for (meth, p, _a, _b) in API.http.calls if meth == "DELETE" and "/messages/" in p]
        check("N4 message_reference 对象（Reference.message_id）也能取到被引用的 id",
              br._gm_ref_id(m) == "REF_OBJ_ID" and len(recs) == 1 and "REF_OBJ_ID" in recs[0]
              and len(API.sent) == 1,
              "ref=%r recalls=%s" % (br._gm_ref_id(m), recs))

        # N5 ref_idx 查不到 → 退化为「本 bot 上一条」，日志写明失败原因
        reset()
        write_owner(d)
        write_admin(d, GID_A)
        br._gm_note_sent(BOT_A, GID_A, "MINE_LAST_88")
        m = FakeMsg("n5", "#撤回", FakeAuthor(MASTER, "主人"), GID_A,
                    mentions=[{"id": SELF_OID, "member_openid": SELF_OID, "is_you": True}])
        m.raw = scene_raw(ref_idx="REFIDX_MISSING")
        asyncio.run(br.group_admin_cmd(BOT_A, d, m, GID_A, m.content, m.id))
        recs = [p for (meth, p, _a, _b) in API.http.calls if meth == "DELETE" and "/messages/" in p]
        check("N5 ref_idx 不在本地表 → 撤「本 bot 上一条」并记「引用解析失败」",
              len(recs) == 1 and "MINE_LAST_88" in recs[0]
              and has("引用解析失败：ref_idx 不在本地表") and has("本 bot 上一条"),
              "recalls=%s" % (recs,))

        # N6 私聊 #撤回：门禁不适用、私聊入口不经过群管理命令（行为与 2.0.0 一致）
        m = FakeMsg("n6", "#撤回", FakeAuthor(MASTER, "主人"), "")
        allowed, why = br._gm_called_me(BOT_A, d, m, "", "#撤回")
        src = open(os.path.join(ROOT, "app", "src", "main", "python", "bot_runner.py"),
                   encoding="utf-8").read()
        tail = src[src.index("async def on_c2c_message_create"):]
        cut = tail.find("\n        async def ", 20)
        body = tail[:cut] if cut > 0 else tail
        check("N6 私聊：#撤回 不被群门禁拦（私聊入口本来就不走 group_admin_cmd）",
              allowed is True and "group_admin_cmd" not in body
              and "_group_admin_tick" not in body and br._gm_cmd_of("#撤回") == ("撤回", ""),
              "allowed=%s why=%s c2c_hit=%s" % (allowed, why, "group_admin_cmd" in body))
    finally:
        br._reply = real_reply


# ── Z 端到端：_handle_group 里接上了（不是只有单测能走通）────────

def case_wired():
    real_model, real_reply = br._gm_model_ask, br._reply
    try:
        d = botdir(BOT_A)
        write_admin(d, GID_A)
        write_names(d, GID_A)
        br._gm_model_ask = stub_model("yes")
        br._reply = stub_reply()
        msg = FakeMsg("z1", "谁有开挂", FakeAuthor(HUMAN, "苏"), GID_A)
        asyncio.run(br._handle_group(BOT_A, d, msg, "群消息(新事件)"))
        recs = [p for (m, p, _a, _b) in API.http.calls if m == "DELETE" and "/messages/" in p]
        check("Z1 群消息进 _handle_group → 违规被撤回 + 阶梯禁言（钩子接在实际链路上）",
              len(recs) == 1 and "z1" in recs[0] and has("群管理：违规 n=1"), "recalls=%s" % recs)
        check("Z2 违规这条不再走正常回复（处置完就 return：只发出那一条警告）",
              len(API.sent) == 1 and has("警告=已发")
              and "<qqbot-at-user" in str(API.sent[0].get("markdown") or {}),
              "sent=%d kinds=%s" % (len(API.sent), [bool(k.get("markdown")) for k in API.sent]))
    finally:
        br._gm_model_ask, br._reply = real_model, real_reply


def main():
    write_roster()
    reset()
    case_norm()
    case_model()
    case_steps()
    reset()
    case_punish()
    reset()
    case_kick()
    reset()
    case_shared()
    reset()
    case_no_nick()
    reset()
    case_denied()
    reset()
    case_cmd()
    reset()
    case_welcome()
    reset()
    case_intents()
    reset()
    case_contract()
    reset()
    case_welcome_locked()
    reset()
    case_gate_and_ref()
    reset()
    case_wired()
    shutil.rmtree(BASE, ignore_errors=True)
    bad = [r for r in RESULTS if not r[1]]
    print("\n==== 群管理：%d/%d 通过 ====" % (len(RESULTS) - len(bad), len(RESULTS)))
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
