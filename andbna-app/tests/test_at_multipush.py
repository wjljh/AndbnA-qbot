# -*- coding: utf-8 -*-
"""@ 门禁：群里只 @ 了一只 bot → 只有那只执行命令（2.0.1 真机 11-20 18:57:42 复盘）。

真机取证（5 只 bot 各自的 gateway_raw.jsonl，3143 条下行事件）：
  · 非 @ 的那 4 只收到 t=GROUP_MESSAGE_CREATE，mentions 恒 1 个元素、is_you=false；
  · 被 @ 的那只收到 GROUP_AT_MESSAGE_CREATE（另有一条普通群消息副本带 is_you=true）；
  · mention.id 是 32 位十六进制群 openid（按 bot 分配）→ 拿 appid 去比永远不命中；
  · 每只 bot 的 own_ids.json 恰好 1 个 id，等于它自己收到的 is_you=true 那条的 id。

覆盖：
  1) 只 @ 一只 → 只有那只动：另外 4 只 0 条消息、0 次 DELETE
  2) 平台多推（5 只的 payload 全带 is_you=true）→ 仍只有 1 只执行
  3) 判不出（没有 is_you、id 也不在候选里）→ 谁都不执行（fail-closed）
  4) 平台推了 @ 事件但 payload 说 is_you=false → 不执行（trusted_at 不再是免检章）
  5) 判据日志行内容正确（tag / mentions is_you / own_ids / called）
  6) own_ids.json 里混进真人 openid → 体检剔掉，且不再据此判「叫我」

    cd andbna-app && flock /tmp/andbna.py.lock python3 tests/test_at_multipush.py
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

BASE = tempfile.mkdtemp(prefix="atmul_test_")
GROUP = "0FE14634BEA6824FA46E90118503AB56"
MASTER = "76BA27AFCD5BE44BA58A7012209C7156"      # 真人（主人）在这个群里的 openid
APPIDS = ["app0", "app1", "app2", "app3", "app4"]
NAMES = ["小明零", "小明一", "小明二", "小明三", "小明四"]
# 每只 bot 自己的群 openid（32 位十六进制，真机就是这个形状）
OIDS = ["%08X" % (0xA1B2C3D0 + i) + "0" * 24 for i in range(len(APPIDS))]
REF_ID = "MSG_REF_OBJ_1"
RESULTS = []


def check(name, cond, detail=""):
    RESULTS.append((name, bool(cond)))
    print(("  PASS  " if cond else "  FAIL  ") + name
          + (("  <- " + str(detail)) if (detail and not cond) else ""))


BOT_LOGS = {}       # appid -> 这只 bot 处理这条消息期间新增的日志行


def logs():
    return list(br._state["log"])


def has(sub, appid=None):
    """appid 为空 = 全局日志；给了 appid = 只看那只 bot 这一段（[bot] 显示的是人格名）。"""
    rows = BOT_LOGS.get(appid, []) if appid else logs()
    return any(sub in x for x in rows)


def oid8(i):
    return OIDS[i][:8]


def author8():
    return MASTER[:8]


# ── 假件 ────────────────────────────────────────────────

class FakeHttp(object):
    """botpy BotHttp.request 的替身：只记 (method, url)，够判定撤没撤。"""

    def __init__(self):
        self.calls = []

    async def request(self, route, **kw):
        url = getattr(route, "url", None) or route.path
        self.calls.append((route.method, url))
        return {}


class FakeApi(object):
    def __init__(self):
        self.http = FakeHttp()
        self.sent = []
        self._http = self.http

    async def post_group_message(self, **kw):
        self.sent.append(kw)
        return {"id": "SENT_%d" % len(self.sent)}

    def deletes(self):
        return [c for c in self.http.calls if c[0] == "DELETE"]


class FakeAuthor(object):
    def __init__(self, oid, bot=None):
        self.member_openid = oid
        self.user_openid = oid
        self.id = oid
        self.bot = bot


class _RefObj(object):
    """botpy 的 Reference：message_id / id 是属性，不是字典键。"""

    def __init__(self, mid):
        self.message_id = mid
        self.id = ""


class FakeMsg(object):
    def __init__(self, mid, text, author, gid, mentions=None, role="owner", api=None):
        self.id = mid
        self.content = text
        self.group_openid = gid
        self.author = author
        self.mentions = mentions or []
        self.member_role = role
        self.raw = {}
        self._api = api
        self.message_reference = _RefObj(REF_ID)


# ── 夹具 ────────────────────────────────────────────────

def botdir(appid):
    d = os.path.join(BASE, appid)
    os.makedirs(d, exist_ok=True)
    return d


def write_json(path, obj):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False)


def reset(own_ids=None):
    """每个场景开始时：清掉进程级缓存（真实部署里 5 只 bot 是 5 个进程，这里模拟）。"""
    br._base_dir = BASE
    write_json(os.path.join(BASE, "llm.json"), {"log_level": "simple"})
    br._log_level_cache.clear()
    del br._state["log"][:]
    br._seen_set.clear()
    del br._seen_ids[:]
    br._own_ids_healed.clear()
    br._dedup.clear()
    br._outbox.clear()
    br._group_cache.clear()
    br._gm_conf_cache.clear()
    br._gm_names.clear()
    br._gm_claims.clear()
    br._gm_sent.clear()
    br._gm_mem.clear()
    br._gm_msg_idx.clear()
    br._gm_msg_idx_ts.clear()
    br._gm_msg_idx_miss.clear()
    rows = [{"appid": a, "name": NAMES[i], "enabled": True} for i, a in enumerate(APPIDS)]
    write_json(os.path.join(BASE, "localbots.json"), rows)
    for i, appid in enumerate(APPIDS):
        d = botdir(appid)
        write_json(os.path.join(d, "owner.json"), {"user_openid": MASTER})
        with open(os.path.join(d, "persona_name.txt"), "w", encoding="utf-8") as f:
            f.write(NAMES[i])
        ids = [OIDS[i]] if own_ids is None else own_ids[i]
        write_json(os.path.join(d, "own_ids.json"), {"ids": list(ids)})


def run_all(mentions_for, text, mid_prefix="m", tag="群消息(新事件)"):
    """5 只 bot 各收一份同一条消息 → 返回 {appid: api}，并记下每只那一段日志。"""
    apis = {}
    BOT_LOGS.clear()
    for i, appid in enumerate(APPIDS):
        api = FakeApi()
        apis[appid] = api
        m = FakeMsg("%s%d" % (mid_prefix, i), text, FakeAuthor(MASTER), GROUP,
                    mentions=mentions_for(i), api=api)
        before = len(br._state["log"])
        asyncio.run(br._handle_group(appid, botdir(appid), m, tag))
        BOT_LOGS[appid] = list(br._state["log"][before:])
    return apis


def actions(apis):
    """每只 bot「动了手」没有：发消息数 + DELETE 次数。"""
    return {a: (len(apis[a].sent), len(apis[a].deletes())) for a in APPIDS}


# ── 1 · 只 @ 了一只：只有那只动 ───────────────────────────

def case_single_at():
    print("[1] 只 @ 一只 → 另外 4 只 0 条消息、0 次 DELETE")
    reset()
    text = "<@!%s> #撤回" % OIDS[0]

    def mentions(i):
        return [{"id": OIDS[0], "member_openid": OIDS[0], "is_you": i == 0}]

    apis = run_all(mentions, text, "s")
    acts = actions(apis)
    check("1a 被 @ 的那只执行了（1 次 DELETE + 1 条「已撤回」）",
          acts[APPIDS[0]] == (1, 1)
          and [k.get("content") for k in apis[APPIDS[0]].sent] == ["已撤回"], acts)
    others = {a: acts[a] for a in APPIDS[1:]}
    check("1b 另外 4 只：0 条消息、0 次 DELETE",
          all(v == (0, 0) for v in others.values()), others)
    check("1c 消息行的 @ 标记：只有那只打「@ 我」，其余「@别人」",
          has("%s @ 我：@我 #撤回" % author8(), APPIDS[0])
          and all(has("%s @ 别人：@别人 #撤回" % author8(), a) for a in APPIDS[1:]),
          [x for x in logs() if "#撤回" in x][:6])
    check("1d 判据日志：那只 called=True，其余 called=False",
          has("→ called=True", APPIDS[0])
          and all(has("→ called=False", a) for a in APPIDS[1:]),
          [x for x in logs() if "判据" in x][:6])


# ── 2 · 平台多推（5 只的 payload 全带 is_you=true）→ 只 1 只执行 ──

def case_multi_push():
    print("[2] 平台多推 is_you=true → 仍只有 1 只执行（own_ids 精确比对兜底）")
    reset()
    text = "<@!%s> #撤回" % OIDS[0]

    def mentions(i):
        return [{"id": OIDS[0], "member_openid": OIDS[0], "is_you": True}]

    apis = run_all(mentions, text, "p")
    acts = actions(apis)
    check("2a 只有 own_ids 命中的那只执行", acts[APPIDS[0]] == (1, 1), acts)
    check("2b 另外 4 只 0 条消息、0 次 DELETE（is_you 不再被无条件相信）",
          all(acts[a] == (0, 0) for a in APPIDS[1:]), acts)
    check("2c 不执行的 4 只日志写明「is_you=true 的 id 不是本 bot 的 openid」",
          all(has("is_you=true 的 id", a) for a in APPIDS[1:]),
          [x for x in BOT_LOGS.get(APPIDS[1], []) if "判据" in x][:2])


# ── 3 · 判不出 → 谁都不执行（fail-closed）────────────────────

def case_unknown():
    print("[3] 判不出 → 不执行")
    reset()
    # 正文和 mentions 都是同一个「谁也不认识」的 id，而且没有 is_you 标记 → 真判不出
    unknown = "DEADBEEF" * 4
    text = "<@!%s> #撤回" % unknown

    def mentions(i):
        return [{"id": unknown, "member_openid": unknown}]

    apis = run_all(mentions, text, "u")
    acts = actions(apis)
    check("3a 5 只全都不执行（0 条消息 / 0 次 DELETE）",
          all(v == (0, 0) for v in acts.values()), acts)
    check("3b _at_tag 打出「@判不出」（不再假装「@我」）",
          all(has("@ 判不出：", a) for a in APPIDS),
          [x for x in logs() if "@ 判不出" in x][:3])
    check("3c 判据行写的是 called=False + 判不出的原因",
          all(has("→ called=False", a) for a in APPIDS)
          and has("不在候选集合", APPIDS[0]),
          [x for x in BOT_LOGS.get(APPIDS[0], []) if "判据" in x][:2])


# ── 4 · 平台推 @ 事件但 payload 说不是叫我 → 不执行 ─────────────

def case_trusted_at_contradiction():
    print("[4] trusted_at（tag=群@）不再是免检章")
    reset()

    def mentions(i):
        return [{"id": OIDS[0], "member_openid": OIDS[0], "is_you": i == 0}]

    text = "<@!%s> #撤回" % OIDS[0]
    apis = run_all(mentions, text, "t", tag="群@")
    acts = actions(apis)
    check("4a @ 事件 + is_you=false → 那 4 只不执行（payload 与事件类型打架时不跟）",
          all(acts[a] == (0, 0) for a in APPIDS[1:]), acts)
    check("4b 日志点明「payload 说不是叫我 → 不执行」",
          all(has("payload 说不是叫我", a) for a in APPIDS[1:]),
          [x for x in BOT_LOGS.get(APPIDS[1], []) if "判据" in x][:2])
    check("4c 那一只照常执行", acts[APPIDS[0]] == (1, 1), acts)


# ── 5 · 判据日志行内容 ────────────────────────────────────

def case_probe_line():
    print("[5] 判据日志行")
    reset()

    def mentions(i):
        return [{"id": OIDS[0], "member_openid": OIDS[0], "is_you": i == 0}]

    apis = run_all(mentions, "<@!%s> #撤回" % OIDS[0], "q")
    want_mine = ("群管理：#撤回 判据 tag=群消息(新事件) mentions=[%s:true] "
                 "own_ids=[%s] → called=True" % (oid8(0), oid8(0)))
    line_mine = [x for x in BOT_LOGS.get(APPIDS[0], []) if "判据" in x]
    check("5a 判据行：tag + mentions 的 is_you + own_ids + called=True",
          bool(line_mine) and want_mine in line_mine[0], line_mine[:1])
    want_other = ("mentions=[%s:false] own_ids=[%s] → called=False" % (oid8(0), oid8(1)))
    line_other = [x for x in BOT_LOGS.get(APPIDS[1], []) if "判据" in x]
    check("5b 别的 bot 那行：is_you=false、own_ids 是它自己的、called=False",
          bool(line_other) and want_other in line_other[0], line_other[:1])
    check("5c 判据行只在命令消息上出现（普通闲聊不刷屏）",
          not [x for x in logs() if "判据" in x and "#撤回" not in x],
          [x for x in logs() if "判据" in x][:3])


# ── 6 · own_ids.json 混进真人 openid → 体检剔掉 ─────────────────

def case_own_ids_heal():
    print("[6] own_ids.json 被真人 openid 污染 → 剔掉，且不再据此判「叫我」")
    polluted = [[MASTER, OIDS[i]] for i in range(len(APPIDS))]     # 主人 openid 混进来
    reset(own_ids=polluted)
    text = "<@!%s> #撤回" % MASTER         # 正文 @ 的是主人（真人）

    def mentions(i):
        return [{"id": MASTER, "member_openid": MASTER, "is_you": False}]

    apis = run_all(mentions, text, "h")
    acts = actions(apis)
    check("6a 没人被当成「叫了我」→ 5 只全不执行（以前会 5 只全抢答）",
          all(v == (0, 0) for v in acts.values()), acts)
    check("6b 体检日志写明剔掉了真人 openid",
          all(has("own_ids.json 清理", a) for a in APPIDS),
          [x for x in BOT_LOGS.get(APPIDS[0], []) if "own_ids" in x][:2])
    kept = json.load(open(os.path.join(botdir(APPIDS[0]), "own_ids.json"), encoding="utf-8"))
    check("6c 剔完只剩本 bot 自己的 openid（keep 自己那条）",
          sorted(kept.get("ids") or []) == [OIDS[0]], kept)


def main():
    try:
        reset()
        case_single_at()
        reset()
        case_multi_push()
        reset()
        case_unknown()
        reset()
        case_trusted_at_contradiction()
        reset()
        case_probe_line()
        reset()
        case_own_ids_heal()
    finally:
        shutil.rmtree(BASE, ignore_errors=True)
    bad = [r for r in RESULTS if not r[1]]
    print("\n==== @ 门禁（多 bot 只 @ 一只）：%d/%d 通过 ====" % (len(RESULTS) - len(bad), len(RESULTS)))
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())