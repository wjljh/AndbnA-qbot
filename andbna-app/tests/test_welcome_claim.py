# -*- coding: utf-8 -*-
"""进群欢迎的跨 bot 认领 + 我方主动消息的共享指纹（2.0.0 真机两处刷屏）。

真机现场（56a2558e / 2.0.0，用户实测并抓了日志）：
  A) 群里拉进一个人，5 只 bot 全发了欢迎语 —— GROUP_MEMBER_ADD 对**每只 bot** 各下一次，
     群 openid / 成员 openid 都是每 bot 一套，只有事件体里的 timestamp 是跨 bot 同一个值；
  B) 欢迎 / 警告 / 禁言通知被别的 bot 当成「[其他bot] 说了话」，于是又互相接话刷屏 ——
     这些是我方主动消息，收到就该直接丢弃，不能靠总模型 skip（那是白烧 token）。

本文件只证明桌面可验的四件事：跨 bot 原子认领、TTL、共享指纹拦截、退化路径。
首次发言欢迎这条兜底已整块删除（用户实测：每个新人第一句话都被 @ 一次，很烦）：C 组只验它不再发。
假 api 记录每一次发消息，tempfile 当 filesDir，不装 app、不连 QQ：

    cd andbna-app && flock /tmp/andbna.py.lock python3 tests/test_welcome_claim.py
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

BASE = tempfile.mkdtemp(prefix="wc_test_")          # = filesDir
BOT_A, BOT_B = "botA_appid", "botB_appid"
NAME_A, NAME_B = "惜月", "大肥鱼"
GID_A = "0FE14634BEA6824FA46E90118503AB56"          # 同一个物理群，两个 bot 各一套 gid
GID_B = "FC139BB1033AE4692554E358E3C9CE9D"
GROUP_NAME = "测试群一"
WELCOME = "（抬眼）来了？随便坐，别拘束。"
REAL_JITTER = br._GM_CLAIM_JITTER                    # 真机常量：0~1.2 秒
RESULTS = []
API_A = API_B = None


# ── 假对象 ──────────────────────────────────────

class FakeHttp(object):
    def __init__(self):
        self.fail = {}

    async def request(self, route, **kw):
        url = getattr(route, "url", None) or route.path
        for key, exc in self.fail.items():
            if key in url or key in route.path:
                raise exc
        if route.path.endswith("/info"):
            return {"group_name": GROUP_NAME, "group_member_num": 12}
        return {}


class FakeApi(object):
    def __init__(self):
        self.http = FakeHttp()
        self._http = self.http
        self.sent = []
        self.fail_send = None          # 设成异常 → 发送接口一律失败

    async def post_group_message(self, **kw):
        if self.fail_send is not None:
            raise self.fail_send
        self.sent.append(kw)
        return {"id": "SENT_%d" % len(self.sent)}


class FakeAuthor(object):
    def __init__(self, oid, nick="", bot=None):
        self.member_openid = oid
        self.user_openid = oid
        self.id = oid
        self.bot = bot
        if nick:
            self.username = nick


class FakeMsg(object):
    def __init__(self, mid, text, author, gid, api=None):
        self.id = mid
        self.content = text
        self.group_openid = gid
        self.author = author
        self.mentions = []
        self.member_role = ""
        self.raw = {}
        self._api = api


# ── 夹具 ────────────────────────────────────────

def botdir(appid):
    d = os.path.join(BASE, appid)
    os.makedirs(d, exist_ok=True)
    return d


def botdirs():
    return botdir(BOT_A), botdir(BOT_B)


def write_roster():
    rows = [{"appid": BOT_A, "name": NAME_A, "enabled": True},
            {"appid": BOT_B, "name": NAME_B, "enabled": True}]
    with open(os.path.join(BASE, "localbots.json"), "w", encoding="utf-8") as f:
        json.dump(rows, f, ensure_ascii=False)


def write_names(d, gid, name=GROUP_NAME):
    with open(os.path.join(d, "group_names.json"), "w", encoding="utf-8") as f:
        json.dump({gid: {"name": name, "members": 12, "ts": int(time.time() * 1000)}},
                  f, ensure_ascii=False)


def write_admin(d, gid, text=WELCOME):
    dd = os.path.join(d, "group_admin")
    os.makedirs(dd, exist_ok=True)
    conf = {"enabled": False, "mode": "keyword",
            "welcome": {"enabled": True, "at": True, "text": text}}
    with open(os.path.join(dd, gid + ".json"), "w", encoding="utf-8") as f:
        json.dump(conf, f, ensure_ascii=False)
    br._gm_conf_cache.clear()


def reset():
    global API_A, API_B
    API_A, API_B = FakeApi(), FakeApi()
    br._base_dir = BASE
    write_roster()
    with open(os.path.join(BASE, "llm.json"), "w", encoding="utf-8") as f:
        json.dump({"log_level": "detail"}, f)
    br._log_level_cache.clear()
    br._state["log"][:] = []
    br._seen_set.clear()
    del br._seen_ids[:]
    br._outbox.clear()
    br._gate_state["ctx"].clear()
    br._gm_conf_cache.clear()
    br._gm_names.clear()
    br._gm_claims.clear()
    br._gm_sent.clear()
    br._gm_mem.clear()
    br._gm_welcome_mem.clear()
    br._GM_CLAIM_JITTER = 0.0        # 单测不等真机的 0~1.2 秒；常量本身在 A1 单独验
    for d in botdirs():
        shutil.rmtree(os.path.join(d, "group_admin"), ignore_errors=True)
        for name in ("group_names.json", "memory.json"):
            try:
                os.remove(os.path.join(d, name))
            except OSError:
                pass
    shutil.rmtree(os.path.join(BASE, "group_admin_shared"), ignore_errors=True)


def logs():
    return list(br._state["log"])


def has(text):
    return any(text in x for x in logs())


def check(name, cond, detail=""):
    RESULTS.append((name, bool(cond)))
    print("  %s  %s%s" % ("PASS" if cond else "FAIL", name,
                          (" :: %s" % (detail,)) if (detail and not cond) else ""))


def member_event(api, gid, oid, ts):
    return br._GmMemberEvent(api, {"id": "EV_%s" % oid, "d": {
        "timestamp": ts, "group_openid": gid, "member_openid": oid,
        "union_group_openid": "U_" + oid}})


def sent_text(kw):
    md = kw.get("markdown")
    return str(md.get("content") if isinstance(md, dict) else (kw.get("content") or ""))


# ── A 进群欢迎：同一次进群只有一只 bot 发 ──────────────

def case_jitter():
    seen = []
    real = br.random.uniform
    br.random.uniform = lambda a, b: (seen.append((a, b)), 0.0)[1]
    try:
        br._GM_CLAIM_JITTER = REAL_JITTER
        asyncio.run(br._gm_claim_wait())
    finally:
        br.random.uniform = real
        br._GM_CLAIM_JITTER = 0.0
    check("A1 认领前随机等 random.uniform(0, 1.2)", REAL_JITTER == 1.2 and seen == [(0, 1.2)],
          "jitter=%s calls=%s" % (REAL_JITTER, seen))


def case_join_two_bots():
    reset()
    dA, dB = botdirs()
    for d, gid in ((dA, GID_A), (dB, GID_B)):
        write_admin(d, gid)
        write_names(d, gid)
    ts = 1700000000
    ok_a = asyncio.run(br.group_admin_member_add(BOT_A, dA, member_event(API_A, GID_A, "M_A", ts)))
    ok_b = asyncio.run(br.group_admin_member_add(BOT_B, dB, member_event(API_B, GID_B, "M_B", ts)))
    check("A2 同一个 timestamp 的进群事件 → 只有一只 bot 真的发（另一只一个字节都不发）",
          ok_a is True and ok_b is False and len(API_A.sent) == 1 and not API_B.sent,
          "A=%s(%d) B=%s(%d)" % (ok_a, len(API_A.sent), ok_b, len(API_B.sent)))
    check("A3 没抢到的 bot 记「进群欢迎由 <别的 appid> 发，本 bot 不动」",
          has("群管理：进群欢迎由 %s 发，本 bot 不动" % BOT_A),
          [x for x in logs() if "本 bot 不动" in x][:1])
    check("A4 共享键 = join:<群名>:<timestamp>（两只 bot 算出同一个文件名）",
          os.path.isfile(br._gm_claim_file(BASE, "join:%s:%s" % (GROUP_NAME, ts))), "")
    rec = json.load(open(br._gm_claim_file(BASE, "join:%s:%s" % (GROUP_NAME, ts)), encoding="utf-8"))
    check("A5 认领文件里写着抢到的 appid（真机复盘看得到是谁发的）", rec.get("appid") == BOT_A, rec)
    check("A6 群名里的 / 与空白不会拼进路径（用户群名是自由文本）",
          "/" not in os.path.basename(br._gm_claim_file(BASE, "join:a/b c:1")), "")


# ── B 认领文件：已存在 / 超过 10 分钟 ────────────

def case_claim_exists():
    reset()
    dA, _dB = botdirs()
    write_admin(dA, GID_A)
    write_names(dA, GID_A)
    ts = 1700000100
    mine, _owner = br._gm_claim_take(BASE, "join:%s:%s" % (GROUP_NAME, ts), BOT_B, NAME_B)
    ok = asyncio.run(br.group_admin_member_add(BOT_A, dA, member_event(API_A, GID_A, "M_X", ts)))
    check("B1 认领文件已存在（别的 bot 先到）→ 本 bot 不发",
          mine is True and ok is False and not API_A.sent, "mine=%s ok=%s" % (mine, ok))
    check("B2 日志里是抢到的那只 bot 的 appid",
          has("群管理：进群欢迎由 %s 发" % BOT_B), [x for x in logs() if "本 bot 不动" in x][:1])


def case_claim_expired():
    reset()
    dA, _dB = botdirs()
    write_admin(dA, GID_A)
    write_names(dA, GID_A)
    ts = 1700000200
    key = "join:%s:%s" % (GROUP_NAME, ts)
    path = br._gm_claim_file(BASE, key)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump({"appid": BOT_B, "name": NAME_B, "ts": int(time.time()) - 601, "ttl": 600}, f)
    ok = asyncio.run(br.group_admin_member_add(BOT_A, dA, member_event(API_A, GID_A, "M_Y", ts)))
    rec = json.load(open(path, encoding="utf-8"))
    check("B3 认领文件 >10 分钟 → 视为无效，本 bot 重新认领并发出",
          ok is True and len(API_A.sent) == 1 and rec.get("appid") == BOT_A,
          "ok=%s sent=%d rec=%s" % (ok, len(API_A.sent), rec))
    # TTL 清理：过期的 .claim 不能长期堆积
    stale = br._gm_claim_file(BASE, "join:%s:%s" % (GROUP_NAME, ts + 999))
    with open(stale, "w", encoding="utf-8") as f:
        json.dump({"appid": BOT_B, "name": NAME_B, "ts": int(time.time()) - 3600, "ttl": 600}, f)
    gone = br._gm_claim_purge(BASE)
    check("B4 TTL 清理：>10 分钟的认领文件被删掉（不会越攒越多）",
          gone >= 1 and not os.path.isfile(stale), "gone=%d" % gone)


def case_claim_release():
    """抢到了但没发出去 → 释放认领，别让这次进群没人欢迎（事件重放时另一只 bot 补）。"""
    reset()
    dA, dB = botdirs()
    for d, gid in ((dA, GID_A), (dB, GID_B)):
        write_admin(d, gid)
        write_names(d, gid)
    ts = 1700000600
    API_A.fail_send = RuntimeError("网络炸了")
    ok_a = asyncio.run(br.group_admin_member_add(BOT_A, dA, member_event(API_A, GID_A, "M_R1", ts)))
    path = br._gm_claim_file(BASE, "join:%s:%s" % (GROUP_NAME, ts))
    check("B5 认领后发送失败 → 认领被释放（文件删掉）+ 日志写明",
          ok_a is False and not os.path.isfile(path) and has("释放认领"), "ok=%s path=%s" % (ok_a, path))
    ok_b = asyncio.run(br.group_admin_member_add(BOT_B, dB, member_event(API_B, GID_B, "M_R2", ts)))
    check("B6 认领释放后，另一只 bot 收到同一个 timestamp 能补发（不会没人欢迎）",
          ok_b is True and len(API_B.sent) == 1, "ok=%s sent=%d" % (ok_b, len(API_B.sent)))


# ── C 首次发言不再欢迎（兜底路径已删）────────────

def case_no_first_welcome():
    """用户实测：五只 bot 对同一个新人的第一句话各 @ 一次。现在 GROUP_MEMBER_ADD 真事件到了，
    这条兜底整块删掉：新人发言 → 不发消息、不写指纹、不落 welcomed.json、不认领。
    """
    reset()
    dA, dB = botdirs()
    for d, gid in ((dA, GID_A), (dB, GID_B)):
        write_admin(d, gid, text="欢迎新人")
        write_names(d, gid)
    m1 = FakeMsg("f1", "大家好", FakeAuthor("OID_F1", "新人甲"), GID_A, API_A)
    m2 = FakeMsg("f2", "大家好", FakeAuthor("OID_F2", "新人甲"), GID_B, API_B)
    got_a = asyncio.run(br._group_admin_tick(BOT_A, dA, m1, GID_A, m1.content, "f1"))
    got_b = asyncio.run(br._group_admin_tick(BOT_B, dB, m2, GID_B, m2.content, "f2"))
    check("C1 新人第一次发言 → 一只 bot 都不欢迎（一个字节都不发）",
          got_a is False and got_b is False and not API_A.sent and not API_B.sent,
          "A=%s(%d) B=%s(%d)" % (got_a, len(API_A.sent), got_b, len(API_B.sent)))
    check("C2 日志里没有「触发=首次发言」这条分支了",
          not has("触发=首次发言") and not has("首次发言欢迎"),
          [x for x in logs() if "首次发言" in x][:1])
    check("C3 不写 <botdir>/group_admin/<gid>.welcomed.json",
          not [n for n in os.listdir(os.path.join(dA, "group_admin"))
               if n.endswith(".welcomed.json")], os.listdir(os.path.join(dA, "group_admin")))
    check("C4 不认领 welcome1st 键、也不写共享指纹 _sys_msgs.json",
          not os.path.isdir(br._gm_claim_dir(BASE))
          and not os.path.isfile(br._gm_sys_path(BASE)), "")
    check("C5 兜底函数与其认领真的删了（不是关掉）",
          not hasattr(br, "_gm_welcome_first") and not hasattr(br, "_gm_claim_first")
          and not hasattr(br, "_gm_welcome_claim") and not hasattr(br, "_gm_welcome_path"), "")


# ── D 我方主动消息的共享指纹：别的 bot 收到就丢弃 ─────────────

def case_sys_fingerprint():
    reset()
    dA, dB = botdirs()
    write_admin(dA, GID_A)
    write_names(dA, GID_A)
    asyncio.run(br.group_admin_member_add(BOT_A, dA, member_event(API_A, GID_A, "M_SYS", 1700000300)))
    sent = sent_text(API_A.sent[-1]) if API_A.sent else ""
    check("D1 欢迎发出去了（这是后面比对的那条正文）", bool(sent) and WELCOME in sent, sent)
    check("D2 发出后写进共享指纹表 _sys_msgs.json",
          os.path.isfile(br._gm_sys_path(BASE))
          and br._gm_sys_hit(BASE, WELCOME) is True, "")
    check("D3 平台把 @ 渲染成 <@!openid>：归一化后仍然命中（两侧哈希一致）",
          br._gm_sys_hit(BASE, "<@!MEMBER_OID> " + WELCOME) is True, "")

    real_reply = br._reply

    async def no_reply(*a, **kw):
        return ""
    br._reply = no_reply
    try:
        # 别的 bot（BOT_B，自己的 gid）收到 BOT_A 刚发的欢迎 → 直接丢弃
        msg = FakeMsg("d1", "<@!M_SYS> " + WELCOME, FakeAuthor("OID_B_VIEW", "其他bot", bot=True),
                      GID_B, API_B)
        asyncio.run(br._handle_group(BOT_B, dB, msg, "群消息(新事件)"))
        check("D4 命中指纹 → 不回复、不发任何消息",
              not API_B.sent and has("跳过：我方主动消息（欢迎/警告），不参与互见"),
              [x for x in logs() if "主动消息" in x][:1])
        check("D5 不进群上下文（_ctx_note 之前就丢了，总模型看不到、不烧 token）",
              not (br._gate_state["ctx"].get((BOT_B, GID_B)) or []),
              br._gate_state["ctx"].get((BOT_B, GID_B)))
        check("D6 不进任何记忆（memory.json 里 0 条）",
              br._memory_rows(br._memory(dB)) == 0, br._memory(dB))
        # 对照：真人说同样一句话 → 指纹不拦（不能把真人当成我方主动消息）
        reset()
        write_admin(dA, GID_A)
        write_names(dA, GID_A)
        br._reply = no_reply
        asyncio.run(br.group_admin_member_add(BOT_A, dA,
                                             member_event(API_A, GID_A, "M_SYS2", 1700000301)))
        human = FakeMsg("d2", "欢迎加入本群", FakeAuthor("HUMAN_1", "苏"), GID_B, API_B)
        asyncio.run(br._handle_group(BOT_B, dB, human, "群消息(新事件)"))
        check("D7 真人发一样的正文 → 不当成我方主动消息（只按 bot 作者查指纹）",
              not has("跳过：我方主动消息"), [x for x in logs() if "主动消息" in x][:1])
    finally:
        br._reply = real_reply


# ── E 指纹 TTL：121 秒后不再命中 ────────────────

def case_fingerprint_ttl():
    reset()
    now = 1700000400.0
    br._gm_sys_note(BASE, WELCOME, now=now)
    check("E1 刚发出去 → 命中", br._gm_sys_hit(BASE, WELCOME, now=now + 1) is True, "")
    check("E2 119 秒还在 120 秒窗口内", br._gm_sys_hit(BASE, WELCOME, now=now + 119) is True, "")
    check("E3 121 秒后 → 不再命中（按正常 bot 消息处理）",
          br._gm_sys_hit(BASE, WELCOME, now=now + 121) is False, "")
    br._gm_sys_note(BASE, "又一条主动消息", now=now + 121)      # 写入时顺手清理过期项
    d = json.load(open(br._gm_sys_path(BASE), encoding="utf-8"))
    check("E4 写入时顺手清掉过期项（文件不留垃圾，只剩刚写的那条）",
          list(d) == [br._hash_text("又一条主动消息")], d)
    check("E5 别人的正常消息从来不命中", br._gm_sys_hit(BASE, "这是别人说的话") is False, "")


# ── F 群名拿不到 → 退化为本 bot 内去重 ─────────────────

def case_group_name_missing():
    reset()
    dA, dB = botdirs()
    for d, gid in ((dA, GID_A), (dB, GID_B)):
        write_admin(d, gid)                     # 故意不写 group_names.json
    API_A.http.fail["/info"] = RuntimeError("info 接口 500")
    API_B.http.fail["/info"] = RuntimeError("info 接口 500")
    ts = 1700000500
    ok_a = asyncio.run(br.group_admin_member_add(BOT_A, dA, member_event(API_A, GID_A, "M_G1", ts)))
    ok_b = asyncio.run(br.group_admin_member_add(BOT_B, dB, member_event(API_B, GID_B, "M_G2", ts)))
    check("F1 群名不可用 → 各发各的（退化成 bot 内去重，不会互相等）",
          ok_a is True and ok_b is True and len(API_A.sent) == 1 and len(API_B.sent) == 1,
          "A=%s(%d) B=%s(%d)" % (ok_a, len(API_A.sent), ok_b, len(API_B.sent)))
    check("F2 日志写明「群名不可用，本次不跨 bot 去重」",
          has("群名不可用，本次不跨 bot 去重"), [x for x in logs() if "群名不可用" in x][:1])
    check("F3 键退回 join:<本 bot 该群 id 前 8 位>:<timestamp>",
          os.path.isfile(br._gm_claim_file(BASE, "join:%s:%s" % (GID_A[:8], ts))), "")
    ok_a2 = asyncio.run(br.group_admin_member_add(BOT_A, dA, member_event(API_A, GID_A, "M_G1", ts)))
    check("F4 本 bot 内的 5 分钟去重还在（第二道闸，重复下发不重发）",
          ok_a2 is False and len(API_A.sent) == 1, "ok=%s sent=%d" % (ok_a2, len(API_A.sent)))


def main():
    write_roster()
    reset()
    case_jitter()
    case_join_two_bots()
    case_claim_exists()
    case_claim_expired()
    case_claim_release()
    case_no_first_welcome()
    case_sys_fingerprint()
    case_fingerprint_ttl()
    case_group_name_missing()
    shutil.rmtree(BASE, ignore_errors=True)
    bad = [r for r in RESULTS if not r[1]]
    print("\n==== 进群欢迎认领 / 主动消息指纹：%d/%d 通过 ====" % (len(RESULTS) - len(bad), len(RESULTS)))
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
