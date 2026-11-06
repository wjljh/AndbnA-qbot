# -*- coding: utf-8 -*-
"""群级配置（<botdir>/groups/<group_openid>.json）：生效点 + 未配置群的回归。

直接 import 真模块，stub 掉 client/api 与模型调用，不装 app、不连 QQ：

    cd andbna-app && python3 tests/test_group_config.py
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

APPID = "botA_appid"
GA = "group_openid_A"
GB = "group_openid_B"
OWNER = "user_owner_1"

BOTDIR = ""
API = None
RESULTS = []
SEARCHES = []          # stub 掉的 web_search 调用记录


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


class FakeMsg(object):
    def __init__(self, mid, text, author, mentions=None, gid=GA):
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


def reset(autochat=False, base_dir=""):
    """每个用例从干净的闸门状态开始（与 test_group_gates.py 同一套重置）。"""
    global API
    br._seen_set.clear()
    del br._seen_ids[:]
    br._outbox.clear()
    br._rate.clear()
    br._intent.clear()
    br._mute.clear()
    br._state["log"][:] = []
    br._group_cache.clear()
    br._daily_cache.clear()
    br._autochat_override.clear()
    try:                              # 当日计数：上一个用例用掉的额度不带进下一个
        os.remove(os.path.join(BOTDIR, "group_quota.json"))
    except OSError:
        pass
    del SEARCHES[:]
    br._base_dir = base_dir
    br.set_group_autochat(autochat)
    API = FakeApi()


def logs():
    return list(br._state["log"])


def write_group(gid, **kw):
    """写一份群配置；没给的字段按「跟随全局」的默认值落盘（和界面写出来的形状一致）。"""
    conf = {"auto_reply": None, "daily_limit": None,
            "web_search": None, "enabled": True}
    conf.update(kw)
    d = os.path.join(BOTDIR, "groups")
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, gid + ".json"), "w", encoding="utf-8") as f:
        json.dump(conf, f, ensure_ascii=False, indent=1)
    br._group_cache.clear()
    return conf


def write_llm(daily_limit):
    """写全局每日上限（llm.json 的 daily_limit，0 = 无上限）：群配置 null 时读这一档。"""
    with open(os.path.join(BOTDIR, "llm.json"), "w", encoding="utf-8") as f:
        json.dump({"daily_limit": daily_limit}, f, ensure_ascii=False)
    return daily_limit


def drop_group(gid):
    """删掉覆盖 = 恢复默认（界面「恢复默认（删除覆盖）」那一行做的是同一件事）。"""
    for name in (gid + ".json", gid + ".persona.txt"):
        path = os.path.join(BOTDIR, "groups", name)
        if os.path.exists(path):
            os.remove(path)
    br._group_cache.clear()


def run(msg, tag="群消息(新事件)"):
    return asyncio.run(br._handle_group(APPID, BOTDIR, msg, tag))


def at(gid, mid, text):
    return FakeMsg(mid, text, FakeAuthor("user_h", bot=False), at_mentions(), gid=gid)


def plain(gid, mid, text):
    return FakeMsg(mid, text, FakeAuthor("user_h", bot=False), [], gid=gid)


def check(name, cond, detail):
    RESULTS.append((name, bool(cond), detail))
    print(("  PASS  " if cond else "  FAIL  ") + name + " :: " + detail)


def main():
    global BOTDIR
    BOTDIR = tempfile.mkdtemp(prefix="andbna_group_conf_")
    br._register_self_id(APPID, APPID)
    br.set_reply_judge("random", 1.0)      # 自动接话开着时「判定」必定接，用例可重复
    # 模型配置：只进内存（key 不落盘），api 指向一个不会真的连上的地址 —— 联网与回复全部 stub
    br.set_llm("sk-test", "http://127.0.0.1:9/v1", "test-model")
    br.web_search = lambda *a, **kw: (SEARCHES.append(a[0] if a else ""), [])[1]
    br._call_llm_sync = lambda conf, msgs, *a, **kw: "好呀。"
    # owner.json：主人发 #搜 才算指令，缺了它 _local_web_cmd 会当「非主人」直接降级
    with open(os.path.join(BOTDIR, "owner.json"), "w", encoding="utf-8") as f:
        json.dump({"member_openid": OWNER, "user_openid": OWNER}, f)

    # ── 回归基线：一个群都没配置时，行为与升级前完全一致 ────────────
    reset(autochat=False)
    run(plain(GA, "r1", "大家好"))
    check("回归1 未配置群 + 全局关 → 非@ 不回", len(API.sent) == 0 and
          any("跳过：未 @ 本 bot" in x for x in logs()),
          "posts=%d logs=%s" % (len(API.sent), logs()[-1:]))

    reset(autochat=True)
    run(plain(GA, "r2", "大家好"))
    check("回归2 未配置群 + 全局开 → 非@ 照旧接一句", len(API.sent) == 1,
          "posts=%d" % len(API.sent))

    reset(autochat=False)
    run(at(GA, "r3", "你好呀"))
    check("回归3 未配置群 @ 照旧回 1 条", len(API.sent) == 1, "posts=%d" % len(API.sent))

    # ── 1) auto_reply=false：该群非 @ 消息不回（A 关、B 开，实测不同）────
    reset(autochat=True)
    write_group(GA, auto_reply=False)
    write_group(GB, auto_reply=True)
    run(plain(GA, "a1", "A 群闲聊"))
    a_posts = len(API.sent)
    run(plain(GB, "b1", "B 群闲聊"))
    check("1 群配置 auto_reply：A(false) 不回 / B(true) 回", a_posts == 0 and len(API.sent) == 1,
          "A=%d B=%d logs=%s" % (a_posts, len(API.sent) - a_posts, logs()[-1:]))
    check("1b 日志写清判定来源是该群配置",
          any("判定来源：该群配置" in x for x in logs()),
          str([x for x in logs() if "判定来源" in x][:1]))

    # 删掉覆盖 → 回到全局（此时全局是开 → 又回）
    drop_group(GA)
    reset(autochat=True)
    run(plain(GA, "a2", "A 群闲聊再来"))
    check("2 删掉覆盖 → 回到全局（开 → 回）", len(API.sent) == 1, "posts=%d" % len(API.sent))
    reset(autochat=False)
    run(plain(GA, "a3", "A 群闲聊第三次"))
    check("2b 删掉覆盖 → 回到全局（关 → 不回）", len(API.sent) == 0, "posts=%d" % len(API.sent))

    # 全局关 + 群显式 true → 该群自动接话
    reset(autochat=False)
    write_group(GA, auto_reply=True)
    run(plain(GA, "a4", "A 群自动接话开着"))
    check("3 群 auto_reply=true 覆盖全局关 → 非@ 也回", len(API.sent) == 1,
          "posts=%d" % len(API.sent))

    # ── 4) enabled=false：整群完全静默（连 @ 都不回）──────────────
    reset(autochat=True)
    write_group(GA, enabled=False)
    run(at(GA, "e1", "@ 了也不回"))
    a_posts = len(API.sent)
    run(at(GB, "e2", "B 群正常"))
    check("4 整群静音：A(@) 0 条 / B 未配置仍回", a_posts == 0 and len(API.sent) == 1,
          "A=%d B=%d logs=%s" % (a_posts, len(API.sent) - a_posts, logs()[-1:]))
    check("4b 日志写清该群已静音", any("该群已静音" in x for x in logs()),
          str([x for x in logs() if "静音" in x][:1]))

    # ── 5) daily_limit=2：第 3 条起静默（计数按群独立）───────────────
    reset(autochat=True)
    write_group(GA, daily_limit=2)
    for i in range(4):
        run(at(GA, "d%d" % i, "第%d条" % (i + 1)))
    check("5 daily_limit=2 → 只回 2 条，第 3/4 条静默", len(API.sent) == 2,
          "posts=%d logs=%s" % (len(API.sent), [x for x in logs() if "上限" in x][:1]))
    check("5b 日志写清上限与条数",
          any("该群今日已回 2 条，达到上限 2 条" in x for x in logs()),
          str([x for x in logs() if "上限" in x][:1]))
    run(at(GB, "d9", "B 群不受 A 的额度影响"))
    check("5c 额度按群独立（B 未配置照常回）", len(API.sent) == 3, "posts=%d" % len(API.sent))

    # ── 5d) 计数跨天归零：昨天用满 2 条，今天照样能回 ─────────────────
    reset(autochat=True)
    write_group(GA, daily_limit=2)
    with open(os.path.join(BOTDIR, "group_quota.json"), "w", encoding="utf-8") as f:
        json.dump({GA: {"date": "2026-01-01", "n": 2}}, f)
    run(at(GA, "q1", "新的一天第一条"))
    check("5d 额度计数跨天归零（昨天用满不影响今天）", len(API.sent) == 1,
          "posts=%d" % len(API.sent))

    # ── 6) web_search=false：#搜（主人）也被拒、一个请求都不发 ─────────
    reset(autochat=False, base_dir=BOTDIR)
    write_group(GA, web_search=False)
    msg = FakeMsg("w1", "#搜 今天天气", FakeAuthor(OWNER, bot=False), at_mentions(), gid=GA)
    run(msg)
    check("6 群 web_search=false → #搜 被拒（没发起搜索）", len(SEARCHES) == 0 and
          any("该群已关闭联网" in x for x in logs()),
          "searches=%d posts=%d logs=%s" % (len(SEARCHES), len(API.sent),
                                            [x for x in logs() if "联网" in x][:2]))
    check("6b 该群被拒时给主人一句可读的提示", len(API.sent) == 1 and
          "关掉了联网" in (API.sent[0]["content"] if API.sent else ""),
          "reply=%r" % (API.sent[0]["content"] if API.sent else ""))

    # B 群没这个覆盖 → 照旧真的去搜（证明不是把联网整体掐死）
    reset(autochat=False, base_dir=BOTDIR)
    msg = FakeMsg("w2", "#搜 今天天气", FakeAuthor(OWNER, bot=False), at_mentions(), gid=GB)
    run(msg)
    check("6c 未配置群 #搜 照旧联网（回归）", len(SEARCHES) >= 1,
          "searches=%d logs=%s" % (len(SEARCHES), [x for x in logs() if "联网" in x][:2]))

    # 自判 / 触发词搜索：群关了联网就一次都不搜，日志说清楚
    reset(autochat=False, base_dir=BOTDIR)
    write_group(GA, web_search=False)
    run(at(GA, "w3", "最近有什么新闻"))
    check("6d 群关了联网 → 触发词也不搜（日志说明）", len(SEARCHES) == 0 and
          any("该群已关闭联网" in x for x in logs()),
          "searches=%d logs=%s" % (len(SEARCHES), [x for x in logs() if "联网" in x][:2]))

    reset(autochat=False, base_dir=BOTDIR)
    run(at(GB, "w4", "最近有什么新闻"))
    check("6e 未配置群的触发词搜索照旧（回归）", len(SEARCHES) >= 1,
          "searches=%d" % len(SEARCHES))

    # ── 7) 群人格已下线：老文件里的 persona / <gid>.persona.txt 一律不再生效 ──
    reset(autochat=False)
    pdir = os.path.join(os.path.dirname(BOTDIR), os.path.basename(BOTDIR) + "_files")
    with open(os.path.join(BOTDIR, "persona.txt"), "w", encoding="utf-8") as f:
        f.write("我是 bot 全局人格")
    write_group(GA, persona="猫娘")
    with open(os.path.join(BOTDIR, "groups", GA + ".persona.txt"), "w",
              encoding="utf-8") as f:
        f.write("我是该群专门用的人格")
    sys_prompt = br._system_prompt(BOTDIR, "g" + GA)
    check("7 群人格已下线：恒用 bot 人格（老 groups/<gid>.persona.txt 被忽略）",
          sys_prompt.startswith("我是 bot 全局人格"), sys_prompt[:24])
    check("7b 不再记「群人格找不到文件」日志（那段逻辑已删）",
          not any("群人格" in x for x in logs()),
          str([x for x in logs() if "群人格" in x][:1]))

    # ── 8) 读盘缓存：mtime 没变就不重读，改文件后立即生效 ───────────────
    reset(autochat=False)
    write_group(GA, daily_limit=5)
    br._group_conf(BOTDIR, GA)                       # 先读一次进缓存
    calls = {"n": 0}
    real_load = br._load_json

    def counting_load(path, default):
        calls["n"] += 1
        return real_load(path, default)

    br._load_json = counting_load
    try:
        br._group_conf(BOTDIR, GA)
        cached_calls = calls["n"]
        write_group(GA, daily_limit=7)                # mtime 变 → 缓存失效
        conf = br._group_conf(BOTDIR, GA)
        after_calls = calls["n"]
    finally:
        br._load_json = real_load
    check("8 缓存：同一 mtime 不重读盘 / 改了文件立刻重读",
          cached_calls == 0 and after_calls == 1 and conf["daily_limit"] == 7,
          "cached=%d after=%d daily_limit=%s" % (cached_calls, after_calls,
                                                 conf.get("daily_limit")))

    # ── 9) 坏配置不炸：非法值一律按「跟随全局」处理 ─────────────────────
    reset(autochat=False)
    bad = os.path.join(BOTDIR, "groups", GA + ".json")
    with open(bad, "w", encoding="utf-8") as f:
        f.write("{ 这不是 json")
    br._group_cache.clear()
    conf = br._group_conf(BOTDIR, GA)
    check("9 坏 JSON → 全部跟随全局（不抛异常，daily_limit 为 null = 跟随全局）",
          conf["auto_reply"] is None and conf["enabled"] is True and
          conf["daily_limit"] is None, str(conf))
    write_group(GA, auto_reply="yes", daily_limit=-3)
    conf = br._group_conf(BOTDIR, GA)
    check("9b 脏值归一：auto_reply 非 bool → None，daily_limit 负数 → 0（本群不限）",
          conf["auto_reply"] is None and conf["daily_limit"] == 0, str(conf))
    with open(bad, "w", encoding="utf-8") as f:
        json.dump({"auto_reply": None, "web_search": None, "enabled": True}, f)
    br._group_cache.clear()
    check("9c daily_limit 字段缺失 → null（跟随全局）",
          br._group_conf(BOTDIR, GA)["daily_limit"] is None,
          str(br._group_conf(BOTDIR, GA)))

    # ── 10) 界面写的形状（org.json JSONObject.toString(1)）Python 读得一样 ──
    reset(autochat=False)
    ui_raw = ('{\n "auto_reply": null,\n "persona": "",\n "daily_limit": 2,\n'
              ' "web_search": false,\n "enabled": true\n}')
    with open(os.path.join(BOTDIR, "groups", GA + ".json"), "w", encoding="utf-8") as f:
        f.write(ui_raw)
    br._group_cache.clear()
    conf = br._group_conf(BOTDIR, GA)
    check("10 界面写出的 JSON 形状与 Python 读取一致（null=跟随 / false=显式关，"
          "老文件里遗留的 persona 键被忽略）",
          conf["auto_reply"] is None and conf["web_search"] is False and
          conf["daily_limit"] == 2 and conf["enabled"] is True and "persona" not in conf,
          str(conf))

    # ── 12) 每日额度三态：null = 跟随全局 / 0 = 本群不限 / >0 = 本群额度 ──
    # 12a null → 用全局值（全局 2 → 第 3 条被拒）
    reset(autochat=False)
    write_llm(2)
    write_group(GA, daily_limit=None)
    for i in range(3):
        run(at(GA, "g%d" % i, "第%d条" % (i + 1)))
    check("12 daily_limit=null → 跟随全局（全局 2 → 只回 2 条，第 3 条静默）",
          len(API.sent) == 2, "posts=%d logs=%s" % (len(API.sent),
                                                    [x for x in logs() if "上限" in x][:1]))
    check("12b 日志写明来源：跟随全局",
          any("达到上限 2 条（来源：跟随全局）" in x for x in logs()),
          str([x for x in logs() if "上限" in x][:1]))

    # 12c 全局 0 = 无上限 → 跟随全局的群也不限
    reset(autochat=False)
    write_llm(0)
    write_group(GA, daily_limit=None)
    for i in range(3):
        run(at(GA, "g%d" % (i + 10), "第%d条" % (i + 1)))
    check("12c 全局 daily_limit=0 → 跟随全局也不限（回 3 条、无上限日志）",
          len(API.sent) == 3 and not any("上限" in x for x in logs()),
          "posts=%d logs=%s" % (len(API.sent), [x for x in logs() if "上限" in x][:1]))

    # 12d 本群 0 = 本群不限，全局再怎么限也不影响这个群
    reset(autochat=False)
    write_llm(1)
    write_group(GA, daily_limit=0)
    for i in range(3):
        run(at(GA, "g%d" % (i + 20), "第%d条" % (i + 1)))
    check("12d 本群 daily_limit=0 → 本群不限（全局 1 也不影响）", len(API.sent) == 3,
          "posts=%d" % len(API.sent))

    # 12e 本群 >0 覆盖全局
    reset(autochat=False)
    write_llm(50)
    write_group(GA, daily_limit=1)
    for i in range(2):
        run(at(GA, "g%d" % (i + 30), "第%d条" % (i + 1)))
    check("12e 本群 daily_limit=1 → 该群只回 1 条（本群值覆盖全局）", len(API.sent) == 1,
          "posts=%d logs=%s" % (len(API.sent), [x for x in logs() if "上限" in x][:1]))
    check("12f 日志写明来源：本群",
          any("达到上限 1 条（来源：本群）" in x for x in logs()),
          str([x for x in logs() if "上限" in x][:1]))

    # 12g 全局额度读盘缓存：同一 mtime 不重读，改文件后立刻生效
    reset(autochat=False)
    write_llm(3)
    br._global_daily_limit(BOTDIR)                  # 先读一次进缓存
    calls = {"n": 0}
    real_load = br._load_json

    def counting_load(path, default):
        calls["n"] += 1
        return real_load(path, default)

    br._load_json = counting_load
    try:
        br._global_daily_limit(BOTDIR)
        cached_calls = calls["n"]
        time.sleep(0.02)          # 这台机器文件系统 mtime 只有 10ms 刻度，等一下才有新 stamp
        write_llm(7)                                # mtime 变 → 缓存失效
        later = br._global_daily_limit(BOTDIR)
        after_calls = calls["n"]
    finally:
        br._load_json = real_load
    check("12g 全局额度缓存：同一 mtime 不重读盘 / 改了文件立刻生效",
          cached_calls == 0 and after_calls == 1 and later == 7,
          "cached=%d after=%d limit=%s" % (cached_calls, after_calls, later))

    # ── 11) 三层优先：群配置 > 该 bot 覆盖 > 全局 ─────────────────────
    drop_group(GB)                                # 用例 1 给 GB 写过 auto_reply=true，这里先清掉
    reset(autochat=False)
    br.set_group_autochat(APPID, "1", False)      # 全局关 + 该 bot 覆盖开
    run(plain(GB, "o1", "B 群闲聊"))
    check("11 全局关 + 该 bot 覆盖=开 → 非@ 也回（判定来源：该 bot 覆盖）",
          len(API.sent) == 1 and any("判定来源：该 bot 覆盖" in x for x in logs()),
          "posts=%d logs=%s" % (len(API.sent), [x for x in logs() if "判定来源" in x][:1]))

    reset(autochat=True)
    br.set_group_autochat(APPID, "0", True)       # 全局开 + 该 bot 覆盖关
    run(plain(GB, "o2", "B 群闲聊"))
    check("11b 全局开 + 该 bot 覆盖=关 → 非@ 不回（判定来源：该 bot 覆盖）",
          len(API.sent) == 0 and any("判定来源：该 bot 覆盖" in x for x in logs()),
          "posts=%d logs=%s" % (len(API.sent), [x for x in logs() if "判定来源" in x][:1]))

    reset(autochat=True)                          # 覆盖清空 = 跟随全局
    br.set_group_autochat(APPID, "", True)
    run(plain(GB, "o3", "B 群闲聊"))
    check("11c 该 bot 覆盖=跟随全局 → 用全局（判定来源：全局）",
          len(API.sent) == 1 and any("判定来源：全局" in x for x in logs()),
          "posts=%d logs=%s" % (len(API.sent), [x for x in logs() if "判定来源" in x][:1]))

    reset(autochat=False)
    br.set_group_autochat(APPID, "1", False)      # 群配置显式关优先于覆盖开
    write_group(GA, auto_reply=False)
    br.set_group_autochat(APPID, "1", False)      # write_group 清了群缓存，覆盖不受影响
    run(plain(GA, "o4", "A 群闲聊"))
    check("11d 群配置显式 false > 该 bot 覆盖开 → 不回（判定来源：该群配置）",
          len(API.sent) == 0 and any("判定来源：该群配置" in x for x in logs()),
          "posts=%d logs=%s" % (len(API.sent), [x for x in logs() if "判定来源" in x][:1]))

    reset(autochat=False)
    shutil.rmtree(BOTDIR, ignore_errors=True)
    shutil.rmtree(pdir, ignore_errors=True)
    bad_rows = [r for r in RESULTS if not r[1]]
    print("\n==== 群级配置：%d/%d 通过 ====" % (len(RESULTS) - len(bad_rows), len(RESULTS)))
    return 0 if not bad_rows else 1


if __name__ == "__main__":
    sys.exit(main())
