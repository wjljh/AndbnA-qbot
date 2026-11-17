# -*- coding: utf-8 -*-
"""表情库（Python 侧）：候选注入 / 标记解析 / 频率与冷却 / 失败回退。

直接 import 真模块 app/src/main/python/bot_runner.py，stub 掉模型调用与 QQ 接口，
不装 app、不连 QQ、不联网、不碰 /sdcard：

    cd andbna-app && python3 tests/test_sticker.py

覆盖：
  1) 候选过滤：空描述 / 缺文件 / 超 5MB 全跳过，mtime 缓存
  2) 提示词原文（引导语 / 条目 / 标记写法 / 不要解释）
  3) 开关：llm.json sticker_enabled，缺省开；关掉时标记照摘但不发图
  4) 标记清理（含残缺半截标记）
  5) id 不在候选 → 丢弃 + 错误行
  6) 频率：每 N 条回复最多 1 张
  7) 同图冷却：cooldown 内不重复，换一张仍可发
  8) 上传失败 → 只发文字 + 已回退纯文字（冷却回滚）
  9) 顺序：先文字后表情；上传走群/私聊各自路径；此时现传 file_info
 10) 正文只有标记 → 只发图，不发文字
 11) 文件读不到 → 不发 HTTP、只记错误
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

APPID = "botA"
GID = "group_openid_stk"
UOID = "USER_openid_1"
TMP = tempfile.mkdtemp(prefix="sticker_test_")
BASE = os.path.join(TMP, "localbot")          # = filesDir/localbot
BOT = os.path.join(BASE, APPID)
STK = os.path.join(BOT, "stickers")
PNG = b"\x89PNG\r\n\x1a\n" + b"x" * 512      # 假图：发送链路只读字节、不解析
RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), str(detail)))
    print(("PASS " if ok else "FAIL ") + name + (("   " + str(detail)) if detail else ""))


# ── 假件：模型 / QQ 接口 / 消息 ───────────────────────────

class FakeHttp(object):
    """顶掉 botpy 的 BotHttp：只记下 route.path 与请求体。"""

    def __init__(self):
        self.calls = []
        self.err = None

    async def request(self, route, **kw):
        self.calls.append({"method": getattr(route, "method", ""),
                           "path": getattr(route, "path", ""),
                           "json": kw.get("json") or {}})
        if self.err:
            raise self.err
        return {"file_uuid": "uuid-1", "file_info": "FILEINFO" * 8}


class FakeApi(object):
    def __init__(self):
        self.http = FakeHttp()
        self.sent = []

    @property
    def _http(self):
        return self.http

    async def post_group_message(self, **kw):
        self.sent.append(("group", kw))

    async def post_c2c_message(self, **kw):
        self.sent.append(("c2c", kw))


API = FakeApi()
MODEL = {"out": "", "systems": []}


def fake_llm(conf, messages, max_tokens=400, temperature=0.9):
    MODEL["systems"].append(messages[0]["content"])
    return MODEL["out"]


class FakeAuthor(object):
    def __init__(self, oid):
        self.member_openid = oid
        self.user_openid = oid
        self.id = oid


class FakeMsg(object):
    def __init__(self, mid, text, oid=UOID, gid=GID):
        self.id = mid
        self.content = text
        self.group_openid = gid
        self.author = FakeAuthor(oid)
        self.mentions = []
        self.raw = {}
        self._api = API


def write_json(path, obj):
    d = os.path.dirname(path)
    if d and not os.path.isdir(d):
        os.makedirs(d)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False)
    return path


def write_llm(**over):
    conf = {"api": "http://127.0.0.1:9/chat/completions", "key": "test-key",
            "model": "deepseek-chat", "web_search_enabled": False,
            "reply_delay_min_ms": 0, "reply_delay_max_ms": 0, "log_level": "detail"}
    conf.update(over)
    write_json(os.path.join(BASE, "llm.json"), conf)


def make_img(name, data=PNG):
    if not os.path.isdir(STK):
        os.makedirs(STK)
    p = os.path.join(STK, name)
    with open(p, "wb") as f:
        f.write(data)
    return p


def write_lib(rows):
    return write_json(os.path.join(STK, "library.json"), {"items": rows})


def reset():
    br._base_dir = BASE
    br._call_llm_sync = fake_llm
    del br._state["log"][:]
    br._sticker_state["replies"].clear()
    br._sticker_state["sent"].clear()
    br._sticker_lib_cache.clear()
    br._sticker_conf_cache.clear()
    br._sticker_pending.set(None)
    br._send_seq.clear()
    br._dedup.clear()
    br._seen_set.clear()
    del br._seen_ids[:]
    MODEL["out"] = ""
    MODEL["systems"][:] = []
    API.sent[:] = []
    API.http.calls[:] = []
    API.http.err = None


def logs():
    return list(br._state["log"])


def has(sub):
    return any(sub in x for x in logs())


def reply(text, key="g" + GID):
    """回复一次并取走待发表情：pending 是 contextvar，必须在同一个 task 里读。"""
    async def go():
        out = await br._reply(APPID, BOT, key, "小明", text)
        return out, br._sticker_take()
    return asyncio.run(go())


def group_send(mid="m1", body="在吗"):
    return asyncio.run(br._group_send(APPID, BOT, FakeMsg(mid, body), GID, "", body))


# ── 1 · 候选过滤 + mtime 缓存 + 提示词原文 ─────────────────────

def test_library():
    print("[1] 候选过滤 / mtime 缓存 / 提示词原文")
    reset()
    make_img("s1.png")
    big = make_img("s4.png")
    with open(big, "wb") as f:
        f.truncate(br._STICKER_MAX + 1)                 # 稀疏文件：>5MB 但不占盘
    write_lib([
        {"id": "s1", "file": "s1.png", "desc": "无语的翻白眼", "tags": ["无语", "嫌弃"]},
        {"id": "s2", "file": "s2.png", "desc": "   ", "tags": []},        # 空描述
        {"id": "s3", "file": "s3.png", "desc": "文件没了"},                # 缺文件
        {"id": "s4", "file": "s4.png", "desc": "太大了"},                  # >5MB
        {"id": "s5", "file": "s5.png"},                                    # 没 desc 键
    ])
    items = br._sticker_library(BOT)
    check("1a 空描述 / 缺文件 / 超 5MB 的条目一律不进候选",
          [i["id"] for i in items] == ["s1"], [i["id"] for i in items])
    check("1b 描述与标签原样取出",
          items and items[0]["desc"] == "无语的翻白眼"
          and items[0]["tags"] == ["无语", "嫌弃"], items[:1])
    check("1c 库没改 → 走 mtime 缓存（同一个对象，不重读盘）",
          br._sticker_library(BOT) is items)

    make_img("s2.png")
    write_lib([
        {"id": "s1", "file": "s1.png", "desc": "无语的翻白眼", "tags": ["无语", "嫌弃"]},
        {"id": "s2", "file": "s2.png", "desc": "比心", "tags": []},
    ])
    items2 = br._sticker_library(BOT)
    check("1d 库改了（mtime/size 变）→ 缓存失效、重新读",
          [i["id"] for i in items2] == ["s1", "s2"] and items2 is not items,
          [i["id"] for i in items2])

    hint, cands = br._sticker_hint(BOT)
    lines = hint.splitlines()
    check("2a 提示词：引导语 + 每张一行 id｜描述｜标签 + 标记写法 + 不要解释",
          len(lines) == 5
          and lines[0] == "你可以偶尔发表情图，让对话更生动。可用的表情（id｜描述｜标签）："
          and lines[1] == "s1｜无语的翻白眼｜无语,嫌弃"
          and lines[2] == "s2｜比心｜-"
          and lines[3] == "想发时在回复末尾单独写一行：<sticker>s1</sticker>"
          and lines[4] == "不想发就不要写。不要解释这个标记。", lines)
    check("2b 候选条目数 = 过滤后的库", len(cands) == 2, len(cands))


# ── 3 · 开关 ──────────────────────────────────────────────

def test_switch():
    print("[3] 开关 sticker_enabled（缺省开）")
    reset()
    write_llm(sticker_enabled=False)
    check("3a 开关关 → _sticker_enabled 为假、不注入候选",
          br._sticker_enabled(BASE) is False and br._sticker_hint(BOT) == ("", []))
    write_llm()
    check("3b 没写这个键 → 默认开",
          br._sticker_enabled(BASE) is True and br._sticker_hint(BOT)[0] != "")

    write_llm(sticker_enabled=False, sticker_every_n=1)
    MODEL["out"] = "嗯嗯<sticker>s1</sticker>"
    out, pend = reply("在吗")
    check("3c 开关关：标记照样摘干净（绝不外泄），但不选图",
          out == "嗯嗯" and pend is None, out)
    check("3d 开关关：日志里没有「表情候选」", not has("表情候选"))

    write_llm(sticker_every_n=1)
    MODEL["out"] = "嗯<sticker>s1</sticker>"
    out_ch, pend_ch = reply("在吗", key="cCHANNEL_1")
    check("3e 频道回复（会话键 c 开头）不注入候选、也不发表情",
          out_ch == "嗯" and pend_ch is None and "表情" not in MODEL["systems"][-1], out_ch)


# ── 4 / 5 · 标记解析 ───────────────────────────────────────

def test_parse():
    print("[4] 标记清理 / 候选注入")
    reset()
    write_llm(sticker_every_n=1)
    MODEL["out"] = "笑死，这什么<sticker>s1</sticker>"
    out, item = reply("在吗")
    check("4a 正文里的 <sticker> 标记被删干净", out == "笑死，这什么", out)
    check("4b 挑中的图挂在待发表情上", item and item["id"] == "s1", item)
    check("4c 候选注入在 system 末尾（追加，不替换人格）",
          MODEL["systems"] and MODEL["systems"][0].endswith("不想发就不要写。不要解释这个标记。")
          and "无语的翻白眼" in MODEL["systems"][0], MODEL["systems"][0][-40:])
    check("4d detail 日志：表情候选：2 个", has("表情候选：2 个"),
          [x for x in logs() if "表情候选" in x])

    MODEL["out"] = "嗯嗯<sticker>s1"
    out2, pend2 = reply("在吗")
    check("4e 残缺的半截标记（没有 </sticker>）也不给用户看",
          out2 == "嗯嗯" and pend2 is None, out2)

    MODEL["out"] = "纯文字，没有标记"
    out3, pend3 = reply("在吗")
    check("4f 没写标记 → 不发图", out3 == "纯文字，没有标记" and pend3 is None)

    print("[5] 非法 id")
    reset()
    write_llm(sticker_every_n=1)
    MODEL["out"] = "哈哈<sticker>zzz</sticker>"
    out, pend = reply("在吗")
    check("5a id 不在候选 → 丢弃，正文照发", out == "哈哈" and pend is None, out)
    check("5b 日志：错误 · 表情：id 不在候选里（zzz）",
          has("错误 · 表情：id 不在候选里（zzz）"),
          [x for x in logs() if "表情" in x])


# ── 6 / 7 · 频率与冷却 ─────────────────────────────────────

def test_rate():
    print("[6] 频率：每 N 条回复最多 1 张")
    reset()
    write_llm(sticker_every_n=3, sticker_cooldown_h=0)
    got = []
    for i in range(4):
        MODEL["out"] = "第%d句<sticker>s1</sticker>" % i
        got.append((reply("在吗")[1] or {}).get("id"))
    check("6a 3 条 1 张：前 2 条不发、第 3 条发、第 4 条重新计数",
          got == [None, None, "s1", None], got)
    check("6b 没到窗口时 detail 日志写清进度",
          has("表情：跳过 s1（本会话 1/3 条，窗口未到）"),
          [x for x in logs() if "表情：跳过" in x])

    print("[7] 同图冷却")
    reset()
    write_llm(sticker_every_n=1, sticker_cooldown_h=24)
    MODEL["out"] = "一<sticker>s1</sticker>"
    first = (reply("在吗")[1] or {}).get("id")
    MODEL["out"] = "二<sticker>s1</sticker>"
    second = (reply("在吗")[1] or {}).get("id")
    MODEL["out"] = "三<sticker>s2</sticker>"
    third = (reply("在吗")[1] or {}).get("id")
    check("7a 同一张图 24h 内不重复，换一张照样能发",
          (first, second, third) == ("s1", None, "s2"), (first, second, third))
    check("7b 日志写清是冷却拦下的",
          has("表情：跳过 s1（同一张图冷却中，还要"),
          [x for x in logs() if "冷却" in x])


# ── 8 / 9 / 10 · 发送链路 ──────────────────────────────────

def test_send():
    print("[8] 上传失败 → 只发文字 + 已回退纯文字")
    reset()
    write_llm(sticker_every_n=1)
    API.http.err = RuntimeError("连接被重置")
    MODEL["out"] = "在的<sticker>s1</sticker>"
    group_send()
    kinds = [k for k, _ in API.sent]
    check("8a 文字照发（表情失败绝不影响文字）",
          kinds == ["group"] and API.sent[0][1].get("content") == "在的", API.sent)
    check("8b 没有发出第二条富媒体消息", not any(kw.get("msg_type") == 7 for _k, kw in API.sent))
    check("8c 错误行：表情发送失败：…｜已回退纯文字",
          has("错误 · 表情发送失败：连接被重置｜已回退纯文字"),
          [x for x in logs() if "表情发送失败" in x])
    check("8d 冷却被打点回滚（一次抖动不白占 24 小时）",
          br._sticker_state["sent"] == {}, br._sticker_state["sent"])

    print("[9] 先文字后表情 + 两跳接口")
    reset()
    write_llm(sticker_every_n=1)
    MODEL["out"] = "在的在的<sticker>s1</sticker>"
    group_send(mid="m_seq")
    kinds = [k for k, _ in API.sent]
    check("9a 顺序：先文字，再表情", kinds == ["group", "group"], kinds)
    check("9b 第二条是 msg_type=7 的图",
          API.sent[1][1].get("msg_type") == 7 and API.sent[1][1].get("content") == "",
          API.sent[1][1])
    up = API.http.calls[0] if API.http.calls else {}
    body = up.get("json") or {}
    check("9c 群上传走 /v2/groups/{gid}/files，file_type=1 + base64 + srv_send_msg=false",
          up.get("path") == "/v2/groups/%s/files" % GID
          and body.get("file_type") == 1 and body.get("srv_send_msg") is False
          and len(body.get("file_data") or "") > 0, up.get("path"))
    check("9d 发图用本次现传的 file_info（不缓存复用）",
          API.sent[1][1].get("media") == {"file_info": "FILEINFO" * 8},
          API.sent[1][1].get("media"))
    check("9e 图与文字不共用 msg_seq（否则平台判「消息被去重」）",
          API.sent[1][1].get("msg_seq") != API.sent[0][1].get("msg_seq"),
          (API.sent[0][1].get("msg_seq"), API.sent[1][1].get("msg_seq")))
    check("9f 日志：表情：发送 s1（本群 1 条内第 1 张）",
          has("表情：发送 s1（本群 1 条内第 1 张）"),
          [x for x in logs() if "表情：发送" in x])

    print("[10] 正文只有标记 → 只发图")
    reset()
    write_llm(sticker_every_n=1)
    MODEL["out"] = "<sticker>s1</sticker>"
    ret = group_send()
    kinds = [k for k, _ in API.sent]
    check("10a 不发文字、只发一条图", ret == "" and kinds == ["group"]
          and API.sent[0][1].get("msg_type") == 7, kinds)
    check("10b 记忆里留了句子占位（下一轮知道它说过话）",
          any("发了个表情" in str(r) for r in (br._memory(BOT).get("g" + GID) or [])),
          br._memory(BOT).get("g" + GID))


# ── 11 / 12 · 私聊路径与读文件失败 ──────────────────────────

def test_private_and_file():
    print("[11] 私聊：上传 / 发送走 users 路径")
    reset()
    write_llm(sticker_every_n=1)
    item = br._sticker_library(BOT)[0]
    ok = asyncio.run(br._sticker_send(APPID, API, BOT, "p" + UOID, item, "m9"))
    check("11a 私聊上传走 /v2/users/{openid}/files",
          ok and API.http.calls and API.http.calls[0]["path"] == "/v2/users/%s/files" % UOID,
          API.http.calls[:1])
    check("11b 私聊发表情用 post_c2c_message + msg_type=7",
          len(API.sent) == 1 and API.sent[0][0] == "c2c"
          and API.sent[0][1].get("msg_type") == 7, API.sent[:1])
    check("11c 日志用「本会话」而不是「本群」",
          has("表情：发送 s1（本会话 1 条内第 1 张）"),
          [x for x in logs() if "表情：发送" in x])

    print("[12] 文件读不到 → 不发 HTTP")
    reset()
    write_llm(sticker_every_n=1)
    res = asyncio.run(br._sticker_send(APPID, API, BOT, "g" + GID,
                                       {"id": "s1", "path": "/nope/none.png"}, "m1"))
    check("12a 读文件失败 → False，一个接口都不调",
          res is False and API.http.calls == [] and API.sent == [])
    check("12b 仍然只记一行错误（已回退纯文字）",
          has("表情发送失败：读文件失败") and has("已回退纯文字"),
          [x for x in logs() if "表情" in x])


def main():
    os.makedirs(BOT, exist_ok=True)
    with open(os.path.join(BOT, "persona.txt"), "w", encoding="utf-8") as f:
        f.write("你是桑多涅，说话自然简短。")
    br._base_dir = BASE
    write_llm()

    test_library()
    test_switch()
    test_parse()
    test_rate()
    test_send()
    test_private_and_file()

    shutil.rmtree(TMP, ignore_errors=True)
    bad = [r for r in RESULTS if not r[1]]
    print("\n==== 表情库：%d/%d 通过 ====" % (len(RESULTS) - len(bad), len(RESULTS)))
    for name, _ok, detail in bad:
        print("FAIL %s   %s" % (name, detail))
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
