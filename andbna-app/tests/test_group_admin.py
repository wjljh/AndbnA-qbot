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
import datetime
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
        self.bot_state = None     # 「获取机器人群内状态」的返回体（None = 不返回内容）

    async def request(self, route, **kw):
        # 记 route.url（已把 {group_openid} 这些占位符填好）= 真机上真正打出去的那条路径
        url = getattr(route, "url", None) or route.path
        self.calls.append((route.method, url, dict(getattr(route, "parameters", {}) or {}),
                           kw.get("json")))
        for key, exc in self.fail.items():
            if key in url or key in route.path:
                raise exc
        if self.bot_state is not None and route.path.endswith("/bot_state"):
            return self.bot_state
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
    """botpy 的 403（名字与真的一致：_gm_denied 只看类名）。

    code 可选：真机上市台把业务 code 丢在返回体里，botpy 只抬出 message；
    单测两种形状（有 code 字段 / 只有正文）都要能取。
    """

    def __init__(self, msg, code=None):
        self.msgs = msg
        if code is not None:
            self.code = code


class _BodyHttp(object):
    """http 层不抛、直接把平台返回体当成功值返回的实现（有的版本就这样）。"""

    def __init__(self, body):
        self.body = body

    async def request(self, route, **kw):
        return self.body


class _BodyApi(object):
    def __init__(self, body):
        self._http = _BodyHttp(body)


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
    br._gm_msg_ts.clear()
    br._gm_msg_idx_miss.clear()
    br._gm_welcome_mem.clear()
    del MODEL_CALLS[:]
    del FACTS[:]
    for d in (botdir(BOT_A), botdir(BOT_B)):
        shutil.rmtree(os.path.join(d, "group_admin"), ignore_errors=True)
        for name in ("persona_meta.json", "persona_name.txt", "msg_idx.json", "msg_ts.json"):
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


def expire_ts(body):
    """请求体里的 mute_expire_at（RFC3339 字符串）→ epoch 秒；缺失/不是字符串 → None。

    平台的字段类型是 string（不是老的整数时间戳），所以单测必须按字符串解析，
    顺手也就验证了「能解析成 RFC3339」这件事。
    """
    members = (body or {}).get("members") or [{}]
    s = members[0].get("mute_expire_at")
    if not isinstance(s, str):
        return None
    try:
        return datetime.datetime.fromisoformat(s).timestamp()
    except ValueError:
        return None


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


# ── D0 禁言请求体：RFC3339 / 时区 / 30 天钳制 / 解禁 ──────────

GM_RFC3339 = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}[+-]\d{2}:\d{2}$")
NOW_FIX = 1786000000            # 固定时刻（epoch 秒），跨时区的断言全靠它


def case_mute_body():
    """官方文档：mute_expire_at 是 RFC3339 字符串（不是整数时间戳），上限 30 天。"""
    real_tz = os.environ.get("TZ")
    try:
        # ① 东八区（无夏令时）：格式 + 具体字符串
        os.environ["TZ"] = "Asia/Shanghai"
        time.tzset()
        b = br._gm_restrict_body(TARGET, 10, NOW_FIX)
        s = b["members"][0].get("mute_expire_at")
        check("D0-1 mute_expire_at 是 RFC3339 字符串（偏移带冒号、秒级）",
              isinstance(s, str) and bool(GM_RFC3339.match(s)), "%r" % (s,))
        check("D0-2 东八区 + 固定 now → 具体字符串（2026-08-06T15:16:40+08:00）",
              s == "2026-08-06T15:16:40+08:00"
              and b["members"][0]["op"] == "add"
              and b["members"][0]["member_openid"] == TARGET, "%s" % (b,))

        # ② 夏令时：同一时刻夏令时/冬令时各一个具体字符串（-04:00 / -05:00）
        os.environ["TZ"] = "America/New_York"
        time.tzset()
        jul = br._gm_restrict_body(TARGET, 10, NOW_FIX)["members"][0]["mute_expire_at"]
        jan_utc = datetime.datetime(2026, 1, 15, 12, 0, 0,
                                    tzinfo=datetime.timezone.utc).timestamp()
        jan = br._gm_restrict_body(TARGET, 10, jan_utc)["members"][0]["mute_expire_at"]
        check("D0-3 夏令时 -04:00 / 冬令时 -05:00（按到期时刻的实际偏移）",
              jul == "2026-08-06T03:16:40-04:00" and jan == "2026-01-15T07:10:00-05:00",
              "jul=%s jan=%s" % (jul, jan))

        # ③ 超过 30 天 → 钳到 30 天（并留一行日志）
        os.environ["TZ"] = "Asia/Shanghai"
        time.tzset()
        over = br._gm_restrict_body(TARGET, 40 * 24 * 60, NOW_FIX)
        span = (expire_ts(over) or 0) - NOW_FIX
        check("D0-4 minutes>30 天 → 钳到 30 天（日志：禁言时长钳到 30 天）",
              span == 30 * 24 * 60 * 60 and has("禁言时长钳到 30 天"),
              "span=%s logs=%s" % (span, [x for x in logs() if "钳到" in x][:1]))
        fit = br._gm_restrict_body(TARGET, 30 * 24 * 60, NOW_FIX)
        check("D0-5 正好 30 天不钳（边界）",
              (expire_ts(fit) or 0) - NOW_FIX == 30 * 24 * 60 * 60,
              "span=%s" % ((expire_ts(fit) or 0) - NOW_FIX,))

        # ④ 解禁：op=del 且不带 mute_expire_at
        d0 = br._gm_restrict_body(TARGET, 0, NOW_FIX)
        dneg = br._gm_restrict_body(TARGET, -5, NOW_FIX)
        check("D0-6 minutes<=0 → op=del 且不含 mute_expire_at",
              all(x["members"][0]["op"] == "del"
                  and "mute_expire_at" not in x["members"][0] for x in (d0, dneg)),
              "%s %s" % (d0, dneg))

        # ⑤ 失败日志：code / msg / http 都留着，「非管理员」归一化成 reason=not_admin
        reset()
        API.http.fail = {"restrict_chat_setting": ForbiddenError("非管理员不能操作")}
        ok = asyncio.run(br._gm_mute(API, BOT_A, GID_A, TARGET, 10, NOW_FIX))
        line = [x for x in logs() if "禁言失败" in x][:1]
        check("D0-7 失败日志保留 code/msg/http + reason=not_admin",
              ok is False and bool(line) and "reason=not_admin" in line[0]
              and "msg=非管理员不能操作" in line[0] and "http=403" in line[0], line)

        # ⑥ GET 返回体按文档字段名解析（global_rule.mode + members[]）
        doc = {"global_rule": {"mode": "always", "schedule_rules": []},
               "members": [{"member_openid": TARGET,
                            "mute_expire_at": "2026-08-05T11:23:05+08:00"}]}
        mode, members = br._gm_restrict_summary(doc)
        check("D0-8 GET 返回体按文档字段名解析（global_rule.mode + members[]）",
              mode == "always" and len(members) == 1
              and members[0]["member_openid"] == TARGET, "%s %s" % (mode, members))
    finally:
        if real_tz is None:
            os.environ.pop("TZ", None)
        else:
            os.environ["TZ"] = real_tz
        time.tzset()


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
        exp = expire_ts(mutes[0][1] if mutes else None)
        check("D3 禁言走 POST restrict_chat_setting（op=add，10 分钟，RFC3339 到期时刻）",
              len(mutes) == 1 and mutes[0][1]["members"][0]["op"] == "add"
              and exp is not None and abs(exp - (time.time() + 600)) < 90,
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
        exp = expire_ts(mutes[-1][1] if mutes else None)
        mins = ((exp - time.time()) // 60) if exp is not None else -1
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
        exp = expire_ts(body)
        mins = ((exp - time.time()) // 60) if exp is not None else -1
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

        # 4) #撤回自己 → 撤本 bot 上一条（用发出去时记下的 msg_id）：唯一的「撤自己」入口
        reset()
        write_owner(d)
        write_admin(d, GID_A)
        br._reply = stub_reply()
        br._gm_note_sent(BOT_A, GID_A, "MINE_MSG_77")
        msg = FakeMsg("c4", "#撤回自己", FakeAuthor(MASTER, "主人"), GID_A,
                      mentions=[{"id": SELF_OID, "member_openid": SELF_OID, "is_you": True}])
        asyncio.run(br.group_admin_cmd(BOT_A, d, msg, GID_A, msg.content, msg.id))
        recs = [p for (m, p, _a, _b) in API.http.calls if m == "DELETE" and "/messages/" in p]
        check("I5 #撤回自己 → 撤本 bot 上一条（outbox 记的 msg_id）",
              len(recs) == 1 and "MINE_MSG_77" in recs[0] and has("本 bot 上一条"),
              "recalls=%s" % recs)
        check("I5b #撤回自己 / #撤回 自己 / #撤回上一条 都解析成命令，#禁言 自己 不算命令",
              br._gm_cmd_of("#撤回自己") == ("撤回", "自己")
              and br._gm_cmd_of("#撤回 自己") == ("撤回", "自己")
              and br._gm_cmd_of("#撤回上一条") == ("撤回", "上一条")
              and br._gm_cmd_of("<@!ME> #撤回 上一条") == ("撤回", "上一条")
              and br._gm_cmd_of("#禁言 自己") == ("", ""),
              "%s / %s / %s" % (br._gm_cmd_of("#撤回自己"), br._gm_cmd_of("#撤回上一条"),
                                br._gm_cmd_of("#禁言 自己")))

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

        # N5 ref_idx 查不到 → 不退化撤自己的（2.0.1 真机事故回归）：不发 DELETE，回失败原因
        reset()
        write_owner(d)
        write_admin(d, GID_A)
        br._gm_note_sent(BOT_A, GID_A, "MINE_LAST_88")
        m = FakeMsg("n5", "#撤回", FakeAuthor(MASTER, "主人"), GID_A,
                    mentions=[{"id": SELF_OID, "member_openid": SELF_OID, "is_you": True}])
        m.raw = scene_raw(ref_idx="REFIDX_MISSING")
        asyncio.run(br.group_admin_cmd(BOT_A, d, m, GID_A, m.content, m.id))
        recs = [p for (meth, p, _a, _b) in API.http.calls if meth == "DELETE" and "/messages/" in p]
        said = str(API.sent[0].get("content") or "") if API.sent else ""
        check("N5 ref_idx 不在本地表 → 不撤本 bot 上一条（不退化），回「太早了」+ #撤回自己",
              not recs and len(API.sent) == 1 and "已撤回" not in said
              and said == br._GM_RECALL_EARLY
              and "太早了" in said and "#撤回自己" in said and "解析不到" not in said
              and has("引用解析失败：ref_idx 不在本地表") and has("→ 不退化")
              and has("有引用但没有可撤的 id"),
              "recalls=%s said=%r" % (recs, said))

        # 5) 完全没有引用 → 一个 DELETE 都不发，回引导语（以前这里会撤掉本 bot 的上一条）
        reset()
        write_owner(d)
        write_admin(d, GID_A)
        br._gm_note_sent(BOT_A, GID_A, "MINE_NOREF_11")
        m = FakeMsg("n5b", "#撤回", FakeAuthor(MASTER, "主人"), GID_A,
                    mentions=[{"id": SELF_OID, "member_openid": SELF_OID, "is_you": True}])
        asyncio.run(br.group_admin_cmd(BOT_A, d, m, GID_A, m.content, m.id))
        check("N5b 无引用 + 没有「自己」→ 不调 DELETE，回「要撤哪条？回复它再说 #撤回」",
              not [c for c in API.http.calls if c[0] == "DELETE"]
              and [k.get("content") for k in API.sent] == ["要撤哪条？回复它再说 #撤回"]
              and has("没有引用 → 不撤本 bot 的消息"),
              "sent=%s" % ([k.get("content") for k in API.sent],))

        # 6) 有引用但平台拒绝撤回 → 不发「已撤回」、不撤自己的，回复带平台 code
        reset()
        write_owner(d)
        write_admin(d, GID_A)
        br._gm_note_sent(BOT_A, GID_A, "MINE_LAST_99")
        API.http.fail = {"/messages/": ForbiddenError("no permission to delete message", 306004)}
        m = FakeMsg("n5c", "#撤回", FakeAuthor(MASTER, "主人"), GID_A,
                    mentions=[{"id": SELF_OID, "member_openid": SELF_OID, "is_you": True}])
        m.message_reference = _RefObj("OTHER_MSG_1")
        asyncio.run(br.group_admin_cmd(BOT_A, d, m, GID_A, m.content, m.id))
        recs = [p for (meth, p, _a, _b) in API.http.calls if meth == "DELETE" and "/messages/" in p]
        said = str(API.sent[0].get("content") or "") if API.sent else ""
        check("N6 有引用但撤回失败 → 只试那条、不撤自己的、不发「已撤回」、回复带 code 306004",
              len(recs) == 1 and "OTHER_MSG_1" in recs[0]
              and not [p for p in recs if "MINE_LAST_99" in p]
              and len(API.sent) == 1 and "已撤回" not in said
              and said == "撤回失败：平台拒绝（code 306004）"
              and has("群管理：撤回失败 code=306004"),
              "recalls=%s said=%r" % (recs, said))

        # 7) 平台返回体 / 异常 → code、message、HTTP 状态各自取对（日志里必须有 code）
        info = br._gm_err_info({"code": 306004, "message": "no permission to delete message"})
        check("N7 返回体：code=306004、msg 原样",
              info["code"] == "306004" and info["msg"] == "no permission to delete message"
              and info["http"] == "", "info=%s" % (info,))
        info = br._gm_err_info(ForbiddenError("no permission to delete message", 306004))
        check("N7b 异常：类名反查 HTTP 403 + 业务 code 从字段取",
              info["code"] == "306004" and info["http"] == "403", "info=%s" % (info,))
        info = br._gm_err_info(ForbiddenError("retract message error, code=306005 retry"))
        check("N7c 异常正文里只有 code=306005 → 也抠得出来（botpy 丢了 body 的 code）",
              info["code"] == "306005" and info["http"] == "403", "info=%s" % (info,))
        info = br._gm_err_info(ForbiddenError("权限不足"))
        check("N7d 真拿不到 code → 留空（不编一个），回复退回通用话",
              info["code"] == "" and info["http"] == "403"
              and br._gm_recall_fail_text(info) == "撤回失败（可能没有群管理权限）",
              "info=%s text=%r" % (info, br._gm_recall_fail_text(info)))
        check("N7e msg 截断到 120 字；成功返回体的 code=0 不算失败",
              len(br._gm_err_info({"message": "x" * 500})["msg"]) == 120
              and br._gm_body_code({"code": 0}) == ""
              and br._gm_body_code({"code": 306004}) == "306004",
              "trunc/body-code")
        reset()
        write_owner(d)
        write_admin(d, GID_A)
        br._gm_note_sent(BOT_A, GID_A, "MINE_LAST_77")
        api0 = _BodyApi({"code": 306004, "message": "no permission to delete message"})
        ok, info = asyncio.run(br._gm_recall(api0, BOT_A, GID_A, "OTHER_MSG_2"))
        check("N7f http 层不抛、直接把 {code,message} 当成功返回 → 仍判失败并记 code",
              ok is False and info["code"] == "306004"
              and has("群管理：撤回失败 code=306004 msg=no permission to delete message")
              and br._gm_recall_fail_text(info) == "撤回失败：平台拒绝（code 306004）",
              "ok=%s info=%s" % (ok, info))

        # N6 私聊 #撤回：门禁不适用、私聊入口不经过群管理命令（行为与 2.0.0 一致）
        m = FakeMsg("n6", "#撤回", FakeAuthor(MASTER, "主人"), "")
        allowed, why = br._gm_called_me(BOT_A, d, m, "", "#撤回")
        src = open(os.path.join(ROOT, "app", "src", "main", "python", "bot_runner.py"),
                   encoding="utf-8").read()
        tail = src[src.index("async def on_c2c_message_create"):]
        cut = tail.find("\n        async def ", 20)
        body = tail[:cut] if cut > 0 else tail
        check("N9 私聊：#撤回 不被群门禁拦（私聊入口本来就不走 group_admin_cmd）",
              allowed is True and "group_admin_cmd" not in body
              and "_group_admin_tick" not in body and br._gm_cmd_of("#撤回") == ("撤回", ""),
              "allowed=%s why=%s c2c_hit=%s" % (allowed, why, "group_admin_cmd" in body))
    finally:
        br._reply = real_reply


# ── P 撤回文案分家 + 让本地表真有命中率（真机 11-20 19:27:59）─────────
#
# 真机那一条：群消息 payload 带 ref_msg_idx=REFIDX_HTsaCK…，msg_idx.json 里 68 条没有它
# （那条是在表开始记录之前 / 换包重启之前收到的），于是回了一句「解析不到」——用户既不知道
# 为什么，也没有出路。现在：三种失败三种话，且 bot 自己发出去的消息也进表（#撤回自己 才是
# 平台真正允许的那条路）。

def case_recall_texts():
    real_reply = br._reply
    try:
        d = botdir(BOT_A)
        br._reply = stub_reply()
        said = {}

        def cmd(mid, raw, ref_obj=None):
            m = FakeMsg(mid, "#撤回", FakeAuthor(MASTER, "主人"), GID_A,
                        mentions=[{"id": SELF_OID, "member_openid": SELF_OID, "is_you": True}])
            m.raw = raw
            if ref_obj:
                m.message_reference = _RefObj(ref_obj)
            asyncio.run(br.group_admin_cmd(BOT_A, d, m, GID_A, m.content, m.id))
            return str(API.sent[-1].get("content") or "") if API.sent else ""

        # ① 命中：上了表的那条 → 已撤回
        reset()
        write_owner(d)
        write_admin(d, GID_A)
        br._msg_idx_note(BOT_A, d, _idx_msg("t1", "REFIDX_HIT1"), "t1")
        said["hit"] = cmd("p1", scene_raw(ref_idx="REFIDX_HIT1"))
        recs = [p for (m, p, _a, _b) in API.http.calls if m == "DELETE" and "/messages/" in p]
        check("P1 ref_idx 命中本地表 → 撤那条、回「已撤回」",
              said["hit"] == "已撤回" and len(recs) == 1 and "t1" in recs[0],
              "said=%r recalls=%s" % (said["hit"], recs))

        # ② 未命中：表里没有那条 → 「太早了」+ 一条能用的出路（不是「解析不到」）
        reset()
        write_owner(d)
        write_admin(d, GID_A)
        said["early"] = cmd("p2", scene_raw(ref_idx="REFIDX_HTsaCK+NxS1ha"))
        check("P2 ref_idx 未命中 → 「太早了，我现在撤不了（只记得最近收到的一批）」+ #撤回自己",
              said["early"] == br._GM_RECALL_EARLY
              and "太早了" in said["early"] and "只记得最近收到的一批" in said["early"]
              and "#撤回自己" in said["early"] and "解析不到" not in said["early"]
              and not [c for c in API.http.calls if c[0] == "DELETE"],
              "said=%r" % (said["early"],))
        check("P2b 文案不暗示「能撤别人的」：写明平台只允许撤本 bot 自己发的",
              "平台只允许我撤自己发的消息" in said["early"]
              and "别人的" not in said["early"], "said=%r" % (said["early"],))

        # ③ 没有引用 → 维持原来的引导
        reset()
        write_owner(d)
        write_admin(d, GID_A)
        said["ask"] = cmd("p3", scene_raw())
        check("P3 完全没有引用 → 维持引导语（与 ② 不是同一句）",
              said["ask"] == br._GM_RECALL_ASK
              and not [c for c in API.http.calls if c[0] == "DELETE"],
              "said=%r" % (said["ask"],))

        # ④ 有 ref_id 但平台拒绝 → 带平台 code（不是 ② 也不是 ③）
        reset()
        write_owner(d)
        write_admin(d, GID_A)
        API.http.fail = {"/messages/": ForbiddenError("no permission to delete message", 306008)}
        said["deny"] = cmd("p4", scene_raw(), ref_obj="OTHER_MSG_P4")
        check("P4 平台拒绝 → 回平台 code（306008），三句文案两两不同",
              said["deny"] == "撤回失败：平台拒绝（code 306008）"
              and len(set([said["hit"], said["early"], said["ask"], said["deny"]])) == 4,
              "said=%s" % (said,))
    finally:
        br._reply = real_reply


# ── R 撤回失败按「target 是不是本 bot 发的」分家（真机 11-20 19:44:15）──────
#
# 真机那一条：被回复的是「人（或别的 bot）发的」消息 → code=无 / msg=无操作权限 / http=500；
# 同一只 bot 18:47:34 撤「本 bot 上一条」却成功 —— 平台只允许撤自己发的消息。
# 所以失败文案先分「是不是我发的」：不是我发的就说清楚，是我发的才叫「平台拒绝」，
# 并且把平台的「无操作权限」归一化成 reason=not_mine 方便统计。

def case_recall_own_split():
    real_reply = br._reply
    try:
        d = botdir(BOT_A)
        br._reply = stub_reply()

        def cmd(mid, raw=None, ref_obj=None, text="#撤回"):
            m = FakeMsg(mid, text, FakeAuthor(MASTER, "主人"), GID_A,
                        mentions=[{"id": SELF_OID, "member_openid": SELF_OID, "is_you": True}])
            if raw is not None:
                m.raw = raw
            if ref_obj:
                m.message_reference = _RefObj(ref_obj)
            asyncio.run(br.group_admin_cmd(BOT_A, d, m, GID_A, m.content, m.id))
            said = str(API.sent[-1].get("content") or "") if API.sent else ""
            deletes = [p for (meth, p, _a, _b) in API.http.calls if meth == "DELETE"]
            return said, deletes

        # R1 target 在已发集合（#撤回自己 撤的就是 sent:<gid> 那条）+ 平台拒绝 → 「平台拒绝」
        reset()
        write_owner(d)
        write_admin(d, GID_A)
        br._gm_note_sent(BOT_A, GID_A, "MINE_R1", d)
        API.http.fail = {"/messages/": ForbiddenError("no permission to delete message", 306012)}
        said, deletes = cmd("r1", text="#撤回自己")
        check("R1 target 在本 bot 已发集合 + 仍失败 → 「撤回失败：平台拒绝（code …）」",
              said == "撤回失败：平台拒绝（code 306012）"
              and len(deletes) == 1 and "MINE_R1" in deletes[0]
              and has("群管理：撤回失败 code=306012") and has("reason=denied")
              and said != br._GM_RECALL_NOT_MINE,
              "said=%r deletes=%s" % (said, deletes))

        # R1b 我发的、平台没给 code → 用平台原话，不退成「可能没有群管理权限」
        reset()
        write_owner(d)
        write_admin(d, GID_A)
        br._gm_note_sent(BOT_A, GID_A, "MINE_R1B", d)
        API.http.fail = {"/messages/": ForbiddenError("撤回接口限流")}
        said, _deletes = cmd("r1b", text="#撤回自己")
        check("R1b 我发的 + 平台只给 msg → 「撤回失败：平台拒绝（<平台原话>）」",
              said == "撤回失败：平台拒绝（撤回接口限流）" and has("reason=denied")
              and br._GM_NOT_MINE_MSG not in said,
              "said=%r" % (said,))

        # R2 不在已发集合 + 平台「无操作权限」→ 「这条不是我发的」，日志 reason=not_mine
        reset()
        write_owner(d)
        write_admin(d, GID_A)
        br._msg_idx_note(BOT_A, d, _idx_msg("human_r2", "REFIDX_HUMAN_R2"), "human_r2")
        API.http.fail = {"/messages/": ForbiddenError("无操作权限")}
        said, deletes = cmd("r2", scene_raw(ref_idx="REFIDX_HUMAN_R2"))
        check("R2 本地表能对上 id 但不在已发集合 + msg=无操作权限 → 「这条不是我发的…」",
              said == br._GM_RECALL_NOT_MINE and "不是我发的" in said
              and "平台只让我撤自己发的消息" in said
              and len(deletes) == 1 and "human_r2" in deletes[0]
              and has("reason=not_mine") and said != br._GM_RECALL_EARLY,
              "said=%r deletes=%s" % (said, deletes))

        # R2b 只有「无操作权限」这条平台原话（没有本地表线索）→ 也是「不是我发的」
        reset()
        write_owner(d)
        write_admin(d, GID_A)
        API.http.fail = {"/messages/": ForbiddenError("无操作权限")}
        said, _deletes = cmd("r2b", ref_obj="OTHER_MSG_R2B")
        check("R2b 平台原话「无操作权限」单独成立 → 也判「不是我发的」（不看本地表）",
              said == br._GM_RECALL_NOT_MINE and has("reason=not_mine"),
              "said=%r" % (said,))

        # R2c 三种文案互不混淆：太早 / 不是我发的 / 没有引用 两两不同
        check("R2c 「不是我发的」与「太早了」「要撤哪条」三句两两不同",
              len(set([br._GM_RECALL_NOT_MINE, br._GM_RECALL_EARLY, br._GM_RECALL_ASK])) == 3
              and "太早了" not in br._GM_RECALL_NOT_MINE
              and "只记得最近收到的一批" not in br._GM_RECALL_NOT_MINE,
              br._GM_RECALL_NOT_MINE)

        # R3 归一化函数：mine / 平台原话 / 本地表解析三条判据各自成立
        check("R3 reason 归一化：我发的=denied、无操作权限=not_mine、本地表解析=not_mine",
              br._gm_recall_reason({"msg": "无操作权限"}, False) == "not_mine"
              and br._gm_recall_reason({"msg": "x"}, True) == "denied"
              and br._gm_recall_reason({"msg": "x"}, False, True) == "not_mine"
              and br._gm_recall_reason({"msg": "no permission to delete message"}, False)
              == "denied",
              "%s/%s/%s/%s" % (br._gm_recall_reason({"msg": "无操作权限"}, False),
                               br._gm_recall_reason({"msg": "x"}, True),
                               br._gm_recall_reason({"msg": "x"}, False, True),
                               br._gm_recall_reason({"msg": "no permission"}, False)))

        # R4 平台拒绝但既没 code 也没有「无操作权限」字样 + 不是本地表解析出来的
        #    → 不硬说成「不是我发的」，也不编 code：把平台原话带出来；
        #    老调用方（mine/reason 都没有）仍然退回通用话，行为不变。
        reset()
        write_owner(d)
        write_admin(d, GID_A)
        API.http.fail = {"/messages/": ForbiddenError("权限不足")}
        said, _deletes = cmd("r4", ref_obj="OTHER_MSG_R4")
        check("R4 没 code 也没线索 → 带平台原话、不说成「不是我发的」；老调用仍是通用话",
              said == "撤回失败：平台拒绝（权限不足）"
              and said != br._GM_RECALL_NOT_MINE
              and has("reason=denied")
              and br._gm_recall_fail_text({"code": ""}) == "撤回失败（可能没有群管理权限）",
              "said=%r legacy=%r" % (said, br._gm_recall_fail_text({"code": ""})))

        # R5 已发集合不止「最后一条」：更早发的那条也认得出来（mine:<message id> 落盘）
        reset()
        write_owner(d)
        write_admin(d, GID_A)
        br._gm_note_sent(BOT_A, GID_A, "MINE_OLD", d)
        br._gm_note_sent(BOT_A, GID_A, "MINE_NEW", d)
        check("R5 更早发出去的 id 仍算「我发的」（sent:<gid> 只记最后一条，另有 mine:<id>）",
              br._gm_own_sent(BOT_A, d, GID_A, "MINE_OLD") is True
              and br._gm_own_sent(BOT_A, d, GID_A, "MINE_NEW") is True
              and br._gm_own_sent(BOT_A, d, GID_A, "SOMEONE_ELSE") is False
              and br._gm_last_sent(BOT_A, GID_A, d) == "MINE_NEW", "own-sent set")
    finally:
        br._reply = real_reply


def case_idx_hit_rate():
    """让本地表真有命中率：bot 自己发的消息也记 id（#撤回自己 是平台唯一允许的那条路）。"""
    real_reply = br._reply
    try:
        d = botdir(BOT_A)
        br._reply = stub_reply()

        # Q1 bot 自己发出去的一条 → 表里有 sent:<gid>（进程内存 + 落盘都有）
        reset()
        write_owner(d)
        write_admin(d, GID_A)
        asyncio.run(br._gm_say(API, BOT_A, GID_A, "", "本 bot 的通知"))
        idx = br._GM_MSG_IDX_SENT + GID_A
        check("Q1 bot 自己发的消息也记 message_id（msg_idx.json 的 sent:<gid>）",
              br._gm_last_sent(BOT_A, GID_A, d) == "SENT_1"
              and br._msg_idx_find(d, idx) == "SENT_1"
              and os.path.exists(os.path.join(d, "msg_idx.json")),
              "last=%r find=%r" % (br._gm_last_sent(BOT_A, GID_A, d), br._msg_idx_find(d, idx)))

        # Q2 模拟重启（进程内存清空、盘上的表还在）→ #撤回自己 仍然撤得掉
        br._gm_sent.clear()
        br._gm_msg_idx.clear()
        br._gm_msg_idx_ts.clear()
        m = FakeMsg("q2", "#撤回自己", FakeAuthor(MASTER, "主人"), GID_A,
                    mentions=[{"id": SELF_OID, "member_openid": SELF_OID, "is_you": True}])
        asyncio.run(br.group_admin_cmd(BOT_A, d, m, GID_A, m.content, m.id))
        recs = [p for (meth, p, _a, _b) in API.http.calls if meth == "DELETE" and "/messages/" in p]
        check("Q2 重启后 #撤回自己 靠落盘那份仍能撤（不再「本 bot 刚没发过消息」）",
              len(recs) == 1 and "SENT_1" in recs[0]
              and str(API.sent[-1].get("content") or "") == "已撤回", "recalls=%s" % recs)

        # Q3 私聊收到的消息也进同一张表（以前只有 _handle_group 记）
        reset()
        write_owner(d)
        br._msg_idx_note(BOT_A, d, _idx_msg("c9", "REFIDX_C2C1"), "c9")
        check("Q3 私聊 / 群聊共用一个记录点（那条有 msg_idx 就进表）",
              br._msg_idx_find(d, "REFIDX_C2C1") == "c9", br._msg_idx_find(d, "REFIDX_C2C1"))
        src = open(os.path.join(ROOT, "app", "src", "main", "python", "bot_runner.py"),
                   encoding="utf-8").read()
        tail = src[src.index("async def on_c2c_message_create"):]
        cut = tail.find("\n        async def ", 20)
        body = tail[:cut] if cut > 0 else tail
        check("Q3b 私聊处理入口里真的调了 _msg_idx_note",
              "_msg_idx_note(" in body, "in_c2c=%s" % ("_msg_idx_note(" in body))

        # Q4 回填：真机 messages.db 的 msg 表就是这一套列（没有消息 id）→ 跳过 0 条 + 一条 detail
        br._arc_connect(d)
        n0 = br._msg_idx_backfill(d, BOT_A)
        check("Q4 存档里没有消息 id → 回填 0 条，并记一条 detail 说明原因",
              n0 == 0 and (has("msg_idx 回填跳过") or has("msg_idx 回填")),
              "n=%d log=%s" % (n0, [x for x in logs() if "回填" in x][:1]))

        # Q5 存档里真有（msg_idx, message_id）两列 → 回填进表，引用重新能命中
        d2 = botdir(BOT_B)
        os.makedirs(d2, exist_ok=True)
        db = os.path.join(d2, "messages.db")
        try:
            os.remove(db)
        except OSError:
            pass
        import sqlite3
        conn = sqlite3.connect(db)
        conn.execute("CREATE TABLE msg(id INTEGER PRIMARY KEY, chat_type TEXT, chat_id TEXT,"
                     " author_openid TEXT, author_name TEXT, ts INTEGER, text TEXT,"
                     " reply_text TEXT, msg_idx TEXT, message_id TEXT)")
        conn.execute("INSERT INTO msg(chat_type, chat_id, ts, text, msg_idx, message_id)"
                     " VALUES('group', ?, ?, 'x', 'REFIDX_BF1', 'bf_mid_1')",
                     (GID_B, int(time.time())))
        conn.commit()
        conn.close()
        br._gm_msg_idx.clear()
        br._gm_msg_idx_ts.clear()
        br._msg_idx_fill_miss.clear()
        n1 = br._msg_idx_backfill(d2, BOT_B)
        check("Q5 存档里同时有序号 + id → 回填进表（REFIDX_BF1 → bf_mid_1）",
              n1 == 1 and br._msg_idx_find(d2, "REFIDX_BF1") == "bf_mid_1",
              "n=%d find=%r" % (n1, br._msg_idx_find(d2, "REFIDX_BF1")))
    finally:
        br._reply = real_reply


# ── O 多 @ 时没权限的那几只不该回嘴（5 只 bot 全 @ → 4 条噪音）──────

class _NestErr(ForbiddenError):
    """botpy 各种「code 藏在身上」的异常形状：整份返回体挂在 data 上（类名仍是 403）。"""

    def __init__(self, msg, body):
        ForbiddenError.__init__(self, msg)
        self.data = dict(body)


def case_multi_at_denied():
    real_reply = br._reply
    try:
        d = botdir(BOT_A)
        br._reply = stub_reply()

        # O1 群里 @ 了多只 bot、本 bot 非主人非管理员 → 0 条消息，只留 1 行 detail
        reset()
        write_owner(d)
        write_admin(d, GID_A)
        with open(os.path.join(d, "known_bots.json"), "w", encoding="utf-8") as f:
            json.dump({"bots": ["OTHER_BOT_OID", "THIRD_BOT_OID"]}, f)
        m = FakeMsg("o1", "<@!OTHER_BOT_OID> #撤回", FakeAuthor(HUMAN, "路人"), GID_A,
                    mentions=[{"id": "OTHER_BOT_OID", "member_openid": "OTHER_BOT_OID",
                               "is_you": False},
                              {"id": SELF_OID, "member_openid": SELF_OID, "is_you": True}],
                    role="member")
        called, why_called = br._gm_called_me(BOT_A, d, m, GID_A, m.content)
        handled = asyncio.run(br.group_admin_cmd(BOT_A, d, m, GID_A, m.content, m.id))
        detail = [x for x in logs() if "未授权，且本条还叫了别的 bot → 静默" in x]
        check("O1 群里 @ 多只 bot + 非主人非管理员 → 0 条消息、1 行 detail（以前回 1 条噪音）",
              called is True and handled is True and not API.sent and len(detail) == 1
              and not [x for x in logs() if "被拒" in x]
              and not [c for c in API.http.calls if c[0] == "DELETE"],
              "called=%s sent=%d detail=%d" % (called, len(API.sent), len(detail)))
        check("O2 多 @ 场景下 _gm_called_me 依旧判「叫了我」（预期行为，不动）",
              called is True and "is_you" in why_called, why_called)

        # O3 只有正文能给线索（known_bots.json 还没学到别的 bot）→ 照样静默
        reset()
        write_owner(d)
        write_admin(d, GID_A)
        try:
            os.remove(os.path.join(d, "known_bots.json"))
        except OSError:
            pass
        m = FakeMsg("o3", "<@!UNKNOWN_BOT_OID> #撤回", FakeAuthor(HUMAN, "路人"), GID_A,
                    mentions=[{"id": SELF_OID, "member_openid": SELF_OID, "is_you": True}],
                    role="member")
        asyncio.run(br.group_admin_cmd(BOT_A, d, m, GID_A, m.content, m.id))
        check("O3 mentions 只给本 bot、正文里还有别的 <@id>（真机「@别人」占位）→ 静默",
              not API.sent and has("未授权，且本条还叫了别的 bot → 静默"),
              "sent=%s" % ([k.get("content") for k in API.sent],))

        # O4 @ 的是真人（不是 bot）→ 不算「叫了别的 bot」，照旧回权限话
        reset()
        write_owner(d)
        write_admin(d, GID_A)
        m = FakeMsg("o4", "#禁言 10", FakeAuthor(HUMAN, "路人"), GID_A,
                    mentions=[{"member_openid": TARGET, "id": TARGET},
                              {"id": SELF_OID, "member_openid": SELF_OID, "is_you": True}],
                    role="member")
        multi, why = br._gm_other_at(BOT_A, d, m, m.content)
        asyncio.run(br.group_admin_cmd(BOT_A, d, m, GID_A, m.content, m.id))
        check("O4 只 @ 了真人（不是 bot）→ 不静默，仍回「没有群管理权限」",
              multi is False and [k.get("content") for k in API.sent] == [br._GM_NO_PERM],
              "multi=%s why=%r sent=%s" % (multi, why, [k.get("content") for k in API.sent]))

        # O5 群里只 @ 了本 bot、非主人非管理员 → 仍回「没有群管理权限」（别让用户以为坏了）
        reset()
        write_owner(d)
        write_admin(d, GID_A)
        m = FakeMsg("o5", "<@!%s> #撤回" % SELF_OID, FakeAuthor(HUMAN, "路人"), GID_A,
                    mentions=[{"id": SELF_OID, "member_openid": SELF_OID, "is_you": True}],
                    role="member")
        asyncio.run(br.group_admin_cmd(BOT_A, d, m, GID_A, m.content, m.id))
        check("O5 群里只 @ 本 bot + 非主人非管理员 → 仍回「没有群管理权限」",
              [k.get("content") for k in API.sent] == [br._GM_NO_PERM]
              and has("群管理：#撤回 被拒") and not has("未授权，且本条还叫了别的 bot"),
              "sent=%s" % ([k.get("content") for k in API.sent],))

        # O6 私聊（gid 为空）非主人 → 仍然明确回话（私聊只有一个对象，不能静默）
        reset()
        write_owner(d)
        write_admin(d, GID_A)
        m = FakeMsg("o6", "<@!OTHER_BOT_OID> #撤回", FakeAuthor(HUMAN, "路人"), "",
                    mentions=[], role="member")
        asyncio.run(br.group_admin_cmd(BOT_A, d, m, "", m.content, m.id))
        check("O6 私聊非主人 → 仍回「没有群管理权限」（群里的多 @ 判定不适用私聊）",
              [k.get("content") for k in API.sent] == [br._GM_NO_PERM]
              and has("群管理：#撤回 被拒"),
              "sent=%s" % ([k.get("content") for k in API.sent],))

        # O7 撤回失败拿 code：异常体藏在 data 里 / 埋在 args 里 / 在 160 字截断之后
        info = br._gm_err_info(_NestErr("no permission to delete message",
                                        {"code": 306004, "message": "no permission"}))
        check("O7 返回体挂在异常的 data 上 → 照样抠出 code=306004",
              info["code"] == "306004"
              and info["msg"] == "no permission to delete message", "info=%s" % (info,))
        info = br._gm_err_info(RuntimeError("%s" % ({"errcode": 11244},)))
        check("O7b 返回体埋在 args 里 + 键名是 errcode → 抠出 11244",
              info["code"] == "11244", "info=%s" % (info,))
        info = br._gm_err_info(ForbiddenError("x" * 200 + " retract error, code=306010"))
        check("O7c code 在 160 字之后（_gm_why 会截断）→ 从异常原文再扫一遍，得 306010",
              info["code"] == "306010", "info=%s" % (info,))
        info = br._gm_err_info(ForbiddenError("权限不足"))
        check("O7d 真拿不到 code → 留空，回复退回通用话（不编一个 code）",
              info["code"] == "" and br._gm_recall_fail_text(info) == "撤回失败（可能没有群管理权限）",
              "info=%s" % (info,))

        # O8 真链路：_handle_group 收到多 @ 的非主人命令 → 一条都不发（不是只有单测能走通）
        reset()
        write_owner(d)
        write_admin(d, GID_A)
        with open(os.path.join(d, "known_bots.json"), "w", encoding="utf-8") as f:
            json.dump({"bots": ["OTHER_BOT_OID"]}, f)
        m = FakeMsg("o8a", "<@!OTHER_BOT_OID> #撤回", FakeAuthor(HUMAN, "路人"), GID_A,
                    mentions=[{"id": "OTHER_BOT_OID", "member_openid": "OTHER_BOT_OID",
                               "is_you": False},
                              {"id": SELF_OID, "member_openid": SELF_OID, "is_you": True}],
                    role="member")
        asyncio.run(br._handle_group(BOT_A, d, m, "群消息(新事件)"))
        check("O9 真链路 _handle_group：多 @ + 非主人 → 0 条消息、0 个撤回请求、1 行 detail",
              not API.sent and not [c for c in API.http.calls if c[0] == "DELETE"]
              and len([x for x in logs() if "未授权，且本条还叫了别的 bot → 静默" in x]) == 1,
              "sent=%s calls=%s" % (len(API.sent), [c[0] for c in API.http.calls]))

        # O10 端到端：平台拒绝 + 异常里带 code → 群里回的那一句带 code
        reset()
        write_owner(d)
        write_admin(d, GID_A)
        API.http.fail = {"/messages/": _NestErr("no permission to delete message",
                                                {"code": 306004, "message": "no permission"})}
        m = FakeMsg("o8", "<@!%s> #撤回" % SELF_OID, FakeAuthor(MASTER, "主人"), GID_A,
                    mentions=[{"id": SELF_OID, "member_openid": SELF_OID, "is_you": True}])
        m.message_reference = _RefObj("OTHER_MSG_9")
        asyncio.run(br.group_admin_cmd(BOT_A, d, m, GID_A, m.content, m.id))
        said = str(API.sent[0].get("content") or "") if API.sent else ""
        check("O8 #撤回 失败（code 在异常 data 里）→ 回复带 code，日志里有 code=306004",
              said == "撤回失败：平台拒绝（code 306004）" and has("群管理：撤回失败 code=306004"),
              "said=%r" % (said,))
    finally:
        br._reply = real_reply
        try:                     # 这一段造的 known_bots.json 别留给后面的用例
            os.remove(os.path.join(d, "known_bots.json"))
        except OSError:
            pass


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


# ── PM 平台错误码 + 群内身份自检 + 2 分钟预判（真机 11-20 20:10）──────────
#
# 真机取证：DELETE /v2/groups/<gid>/messages/ROBOT1.0_… → http=400 code=40062003「无操作权限」
# （id 有效，卡在「机器人不是群管理员」）；同一个 id 换个群 → code=40061001「请求参数无效」。
# 官方「获取机器人群内状态」GET /v2/groups/{group_openid}/bot_state → member_role。

def case_perm_map():
    real_reply = br._reply
    try:
        d = botdir(BOT_A)
        br._reply = stub_reply()

        # ① 码表四种：撤回失败的四句人话（码表压过「这条是不是我发的」）
        pairs = [("40062003", "没有撤回权限：这只 bot 不是群管理员"),
                 ("40064004", "超过 2 分钟，撤不了"),
                 ("40061001", "消息 id 或群不匹配（多半是群/消息对不上）"),
                 ("50065001", "平台侧撤回失败，稍后重试")]
        got = [(c, br._gm_recall_fail_text({"code": c, "msg": "无操作权限"}, False))
               for c, _e in pairs]
        check("PM1 四个平台错误码 → 四句人话（40062003 不再被说成「不是我发的」）",
              [g[1] for g in got] == [e for _c, e in pairs], "%s" % (got,))
        check("PM1b 码表不认识的 code 仍走老文案（不硬套身份话）",
              br._gm_recall_fail_text({"code": "306004"}, True) == "撤回失败：平台拒绝（code 306004）",
              "%r" % (br._gm_recall_fail_text({"code": "306004"}, True),))

        # ② 2 分钟预判：落盘 → 清内存（模拟重启）→ 仍判得出超时，不打平台
        reset()
        write_owner(d)
        write_admin(d, GID_A)
        now = time.time()
        br._msg_ts_put(d, "OLD_MSG_1", now - 600)
        br._msg_ts_put(d, "THIRTY_SEC_1", now - 30)
        br._msg_ts_put(d, "B119", now - 119)
        br._msg_ts_put(d, "B121", now - 121)
        flushed = br._msg_ts_flush(d)
        br._gm_msg_ts.clear()          # 只剩磁盘那份：重启后预判还能用
        ok, info = asyncio.run(br._gm_recall(API, BOT_A, GID_A, "OLD_MSG_1", d))
        check("PM2 落盘过 600 秒 → 预判拦下：不调 DELETE、文案「超过 2 分钟，撤不了」",
              flushed and ok is False and (info or {}).get("reason") == "expired"
              and not [c for c in API.http.calls if c[0] == "DELETE"]
              and br._gm_recall_fail_text(info) == "超过 2 分钟，撤不了"
              and has("撤回跳过"),
              "flushed=%s info=%s calls=%s" % (flushed, info, [c[0] for c in API.http.calls]))
        check("PM2b 边界：119 秒放行、121 秒拦下（官方 2 分钟）",
              br._gm_recall_expired(d, "B119", now) is None
              and (br._gm_recall_expired(d, "B121", now) or 0) > 120,
              "%s/%s" % (br._gm_recall_expired(d, "B119", now),
                         br._gm_recall_expired(d, "B121", now)))
        ok, info = asyncio.run(br._gm_recall(API, BOT_A, GID_A, "NO_TS_1", d))
        check("PM2c 本地没有时刻记录 → 不拦（平台才是裁决方）",
              ok is True and len([c for c in API.http.calls if c[0] == "DELETE"]) == 1,
              "ok=%s info=%s" % (ok, info))
        ok, _info = asyncio.run(br._gm_recall(API, BOT_A, GID_A, "THIRTY_SEC_1", d))
        check("PM2d 30 秒 → 照常打平台（窗口内不误伤）",
              ok is True and len([c for c in API.http.calls if c[0] == "DELETE"]) == 2,
              "ok=%s" % (ok,))

        # ③ 群内身份自检：拿得到 → 日志 + 失败回复带一句
        reset()
        write_owner(d)
        write_admin(d, GID_A)
        API.http.fail = {"/messages/": ForbiddenError("无操作权限", 40062003)}
        API.http.bot_state = {"member_openid": SELF_OID,
                              "joined_at": "2026-08-10T10:00:00+08:00",
                              "allow_proactive_msg": False,
                              "recv_msg_setting": "all", "member_role": "member"}
        m = FakeMsg("pm3", "#撤回", FakeAuthor(MASTER, "主人"), GID_A,
                    mentions=[{"id": SELF_OID, "member_openid": SELF_OID, "is_you": True}])
        m.message_reference = _RefObj("MEMBER_MSG_1")
        asyncio.run(br.group_admin_cmd(BOT_A, d, m, GID_A, m.content, m.id))
        said = str(API.sent[-1].get("content") or "") if API.sent else ""
        check("PM3 撤回被拒 40062003 → 回复 = 码表那句 + 群内身份=普通成员（日志里也有）",
              said.startswith("没有撤回权限：这只 bot 不是群管理员")
              and "群内身份=普通成员" in said
              and has("群内身份=普通成员") and has("群管理：撤回失败 code=40062003"),
              "said=%r" % (said,))

        # ④ 自检拿不到（11253 白名单 / 没有 member_role）→ 不抛、回复退回原文
        reset()
        write_owner(d)
        write_admin(d, GID_A)
        API.http.fail = {"/messages/": ForbiddenError("无操作权限", 40062003),
                         "bot_state": ForbiddenError("应用无接口访问权限", 11253)}
        m = FakeMsg("pm4", "#撤回", FakeAuthor(MASTER, "主人"), GID_A,
                    mentions=[{"id": SELF_OID, "member_openid": SELF_OID, "is_you": True}])
        m.message_reference = _RefObj("MEMBER_MSG_2")
        asyncio.run(br.group_admin_cmd(BOT_A, d, m, GID_A, m.content, m.id))
        said = str(API.sent[-1].get("content") or "") if API.sent else ""
        check("PM4 身份自检拿不到（code=11253）→ 不抛、回复只有码表那句",
              said == "没有撤回权限：这只 bot 不是群管理员"
              and has("群内身份自检拿不到"), "said=%r" % (said,))

        # ⑤ 禁言同理：「无操作权限」→ 「没有禁言权限：这只 bot 不是群管理员」+ 身份
        reset()
        write_owner(d)
        write_admin(d, GID_A)
        API.http.fail = {"restrict_chat_setting": ForbiddenError("无操作权限")}
        API.http.bot_state = {"member_role": "member"}
        m = FakeMsg("pm5", "#禁言 10", FakeAuthor(MASTER, "主人"), GID_A,
                    mentions=[{"member_openid": TARGET, "id": TARGET},
                              {"id": SELF_OID, "member_openid": SELF_OID, "is_you": True}])
        asyncio.run(br.group_admin_cmd(BOT_A, d, m, GID_A, m.content, m.id))
        said = str(API.sent[-1].get("content") or "") if API.sent else ""
        check("PM5 #禁言 被拒（无操作权限）→ 「没有禁言权限：这只 bot 不是群管理员」+ 身份",
              said.startswith("没有禁言权限：这只 bot 不是群管理员")
              and "群内身份=普通成员" in said and has("reason=not_admin"), "said=%r" % (said,))
        check("PM6 _gm_mute_fail_text 三档文案 + _gm_mute 仍返回 bool（老签名没变）",
              br._gm_mute_fail_text({"reason": "not_admin"}) == "没有禁言权限：这只 bot 不是群管理员"
              and br._gm_mute_fail_text({"reason": "denied", "msg": "x"}) == "禁言失败：平台拒绝（x）"
              and br._gm_mute_fail_text({}, "解禁") == "解禁失败（可能没有群管理权限）"
              and isinstance(asyncio.run(br._gm_mute(API, BOT_A, GID_A, TARGET, 10)), bool),
              "mute-text")

        # ⑥ 管理员身份被拒 → 话术不同（不说「去授权」，说「可能是别的原因」）
        reset()
        write_owner(d)
        write_admin(d, GID_A)
        API.http.fail = {"/messages/": ForbiddenError("无操作权限", 40062003)}
        API.http.bot_state = {"member_role": "admin"}
        m = FakeMsg("pm7", "#撤回", FakeAuthor(MASTER, "主人"), GID_A,
                    mentions=[{"id": SELF_OID, "member_openid": SELF_OID, "is_you": True}])
        m.message_reference = _RefObj("MEMBER_MSG_3")
        asyncio.run(br.group_admin_cmd(BOT_A, d, m, GID_A, m.content, m.id))
        said = str(API.sent[-1].get("content") or "") if API.sent else ""
        check("PM7 群内身份=管理员还失败 → 不回「去设为管理员」，说「可能是别的原因」",
              "群内身份=管理员" in said and "设为群管理员" not in said, "said=%r" % (said,))
    finally:
        br._reply = real_reply


def main():
    write_roster()
    reset()
    case_norm()
    case_model()
    case_steps()
    reset()
    case_mute_body()
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
    case_recall_texts()
    reset()
    case_recall_own_split()
    reset()
    case_idx_hit_rate()
    reset()
    case_multi_at_denied()
    reset()
    case_perm_map()
    reset()
    case_wired()
    shutil.rmtree(BASE, ignore_errors=True)
    bad = [r for r in RESULTS if not r[1]]
    print("\n==== 群管理：%d/%d 通过 ====" % (len(RESULTS) - len(bad), len(RESULTS)))
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
