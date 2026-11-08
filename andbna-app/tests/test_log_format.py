# -*- coding: utf-8 -*-
"""日志体系：六类 + 两级详细度（simple / detail）。

直接 import 真模块 app/src/main/python/bot_runner.py，stub 掉 client / api，
不装 app、不连 QQ、不联网：

    cd andbna-app && python3 tests/test_log_format.py

覆盖：
  1) 类别严格只有六个，且每行日志的类别都在六个里
  2) simple 不出 detail 技术行；detail 才出（改 llm.json 即时生效，不用重启）
  3) 错误类不受详细度影响：simple 下照样出，且带原因
  4) 「消息」摘要：<@…> 归一化成 @我 / @别人、超 20 字截断加 …
  5) 一条群消息在 simple 下只有 2~3 行（消息 / 判定 / 回复）
"""
import asyncio
import json
import os
import re
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "app", "src", "main", "python"))

import bot_runner as br  # noqa: E402

APPID = "botA_appid"
GROUP = "group_openid_logfmt"
MYID = "MYOWNID123456"
SIX = ("消息", "判定", "回复", "系统", "更新", "错误")
# 重构后归入 detail 的技术行（simple 下一个都不许出现）
DETAIL_MARKS = ("去重键=", "去重：", "请求体字段：", "用量记一笔：", "称呼：",
                "注入称呼：", "总模型候选：", "发言人识别：", "回复清洗：", "余额：")
LINE_RE = re.compile(r"^\[\d\d-\d\d \d\d:\d\d:\d\d\] (?:\[[^\]]+\] )?(%s) · "
                     % "|".join(SIX))

RESULT = []
API = None


def check(name, cond, detail=""):
    RESULT.append((name, bool(cond)))
    print(("  ok   " if cond else "  FAIL ") + name
          + (("  <- " + str(detail)) if (detail and not cond) else ""))


def logs():
    return list(br._state["log"])


def clear():
    del br._state["log"][:]


class FakeAuthor(object):
    def __init__(self, oid):
        self.member_openid = oid
        self.user_openid = oid
        self.id = oid


class FakeApi(object):
    def __init__(self):
        self.sent = []

    async def post_group_message(self, **kw):
        self.sent.append(kw)


class FakeMsg(object):
    def __init__(self, mid, text, author, mentions=None):
        self.id = mid
        self.content = text
        self.group_openid = GROUP
        self.author = author
        self.mentions = mentions or []
        self.raw = {}
        self._api = API


PAD = {"simple": "s", "detail": "dd", "detail!": "ddd"}


def write_level(base, level):
    """改 llm.json 的 log_level。顺带按档位写不同长度的 _pad：simple / detail 两个词
    一样长，而这台机器的 mtime 粒度很粗（同尺寸快速重写可能拿到同一个 mtime_ns），
    让 size 一起变才能稳定验证 mtime + size 缓存确实失效。"""
    with open(os.path.join(base, "llm.json"), "w", encoding="utf-8") as f:
        json.dump({"log_level": level, "_pad": PAD.get(level, level)}, f)


def setup(level="simple"):
    """一套干净的运行态：base 里只有 log_level，模型配置缺省（走本机连通测试文案）。"""
    global API
    tmp = tempfile.mkdtemp(prefix="logfmt_")
    base = os.path.join(tmp, "localbot")       # filesDir/localbot，与线上一致
    botdir = os.path.join(base, APPID)
    os.makedirs(botdir, exist_ok=True)
    write_level(base, level)
    with open(os.path.join(botdir, "persona.txt"), "w", encoding="utf-8") as f:
        f.write("你是惜月。")
    br._base_dir = base
    br._register_self_id(APPID, MYID, None)
    try:
        br._seen_set.clear()
        del br._seen_ids[:]
        br._outbox.clear()
        br._rate.clear()
        br._intent.clear()
        br._mute.clear()
    except Exception:
        pass
    API = FakeApi()
    clear()
    return base, botdir


def run_group(text, mentions=None, mid="m1"):
    """跑一条群消息，只返回这一条产生的日志行。"""
    clear()
    msg = FakeMsg(mid, text, FakeAuthor("7F0E6FB8AA"), mentions or [])
    asyncio.run(br._handle_group(APPID, botdir_global, msg, "群消息(新事件)"))
    return logs()


botdir_global = ""


def write_bots(base, rows):
    """localbots.json 在 filesDir（= base 的上一级）→ _localbots_path 第一个候选。"""
    path = os.path.join(os.path.dirname(base), "localbots.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(rows, f, ensure_ascii=False)
    return path


# ── 7 · [bot] 显示名字，不是 appid ────────────────────────────
def test_display_name():
    print("[7] [bot] 显示名")
    base, botdir = setup("simple")
    write_bots(base, [{"appid": APPID, "name": "惜月", "enabled": True}])
    br.log("已上线：惜月", cat="系统", appid=APPID)
    line = logs()[-1]
    check("7a 清单里有名字 → [惜月]", "[惜月]" in line and APPID not in line, line)

    # 改名：清单变了，下一条就用新名字（缓存按 mtime/size 失效）
    write_bots(base, [{"appid": APPID, "name": "惜月二号", "enabled": True}])
    br.log("已上线", cat="系统", appid=APPID)
    check("7b 改名后立刻生效（缓存有失效）", "[惜月二号]" in logs()[-1], logs()[-1])

    # 清单里没名字 → 人格名
    with open(os.path.join(botdir, "persona_name.txt"), "w", encoding="utf-8") as f:
        f.write("大肥鱼")
    write_bots(base, [{"appid": APPID, "enabled": True}])
    br.log("已上线", cat="系统", appid=APPID)
    check("7c 清单没名字 → 人格名 [大肥鱼]", "[大肥鱼]" in logs()[-1], logs()[-1])

    # 清单里根本没有这个 appid → 整个 appid（不截断、不编名字）
    br.log("已上线", cat="系统", appid="1905616320")
    check("7d 认不出 → [1905616320]（完整 appid）", "[1905616320]" in logs()[-1], logs()[-1])
    br.log("已上线", cat="系统", appid="1905616320")
    check("7e 也不退化成前 8 位", "[19056163]" not in logs()[-1], logs()[-1])

    # 一条真实链路：消息行的 [bot] 也是名字
    global botdir_global
    botdir_global = botdir
    lines = run_group("在吗", [{"id": MYID}])
    check("7f 真实消息行同样显示名字", lines and lines[0].startswith("[")
          and "] [大肥鱼] 消息 · " in lines[0], lines[:1])


# ── 1 · 类别：严格六个，没有第七个 ─────────────────────────────
def test_cats():
    print("[1] 类别")
    check("1a 只有六类，名字与顺序严格匹配",
          tuple(br.LOG_CATS) == SIX and len(br.LOG_CATS) == 6, br.LOG_CATS)
    check("1b 六类互不重复（没有同义第七类）", len(set(br.LOG_CATS)) == 6, br.LOG_CATS)
    for cat in SIX:
        before = len(logs())
        br.log("冒烟", cat=cat)
        line = logs()[-1]
        check("1c 「%s」写得出且格式对" % cat,
              len(logs()) == before + 1 and LINE_RE.match(line) is not None, line)


# ── 2 · 详细度：simple 不出技术行，detail 才出 ──────────────────
def test_levels():
    print("[2] 详细度")
    global botdir_global
    base, botdir = setup("simple")
    botdir_global = botdir
    br.log("结论行", cat="系统")
    br.log("技术行", cat="系统", lv="detail")
    check("2a simple：结论行照出", any("结论行" in x for x in logs()), logs())
    check("2b simple：detail 技术行不出现", not any("技术行" in x for x in logs()), logs())

    write_level(base, "detail")            # 不重启，下一条就换档
    br.log("技术行", cat="系统", lv="detail")
    check("2c detail：技术行出现", any("技术行" in x for x in logs()), logs())

    write_level(base, "simple")
    clear()
    br.log("技术行", cat="系统", lv="detail")
    check("2d 改回 simple：技术行立刻又不出", not logs(), logs())

    write_level(base, "detail!")
    clear()
    br.log("技术行", cat="系统", lv="detail")
    check("2e 坏值（detail!）当 simple：不出", not logs(), logs())


# ── 3 · 错误类不受详细度影响 ───────────────────────────────────
def test_error():
    print("[3] 错误类")
    setup("simple")
    br.log("发送失败：HTTP 403（forbidden）", cat="错误")
    br.log("回群失败：URLError", cat="错误", lv="detail")
    br.log("判定失败（按不接处理）：RuntimeError", cat="错误")
    got = [x for x in logs() if "错误 · " in x]
    check("3a simple 下错误行照样出（3 条）", len(got) == 3, logs())
    check("3b 错误行带原因（HTTP 码 / 异常名）",
          any("HTTP 403" in x for x in got) and any("URLError" in x for x in got)
          and any("RuntimeError" in x for x in got), got)
    check("3c 错误行也是六类之一", all(LINE_RE.match(x) for x in got), got)


# ── 4 · 消息摘要：@ 归一化 + 截断 ──────────────────────────────
def test_summary():
    print("[4] 消息摘要")
    own = [MYID]
    s = br._msg_summary("你好 <@%s> 还有 <@OTHERID> 呢" % MYID, own)
    check("4a <@自己的id> → @我", "@我" in s and MYID not in s, s)
    check("4b <@别人的id> → @别人", "@别人" in s and "OTHERID" not in s, s)
    long = "一二三四五六七八九十" * 4
    cut = br._msg_summary(long, own)
    check("4c 超过 20 字截断加 …", len(cut) == 21 and cut.endswith("…"), (len(cut), cut))
    check("4d 20 字以内原样（不截断）", br._msg_summary("一二三四五六七八九十一二三四五六七八九十",
                                                       own) == "一二三四五六七八九十一二三四五六七八九十")
    check("4e 换行 / 多空格压成一个空格",
          br._msg_summary("a\n\n b\tc", own) == "a b c", br._msg_summary("a\n\n b\tc", own))
    check("4f 空正文 → (空)", br._msg_summary("", own) == "(空)", br._msg_summary("", own))
    line = br._msg_line("7F0E6FB8", "<@%s> 88 91 E7" % MYID, "@我", own)
    check("4g 消息行：作者 + @ 我 + 摘要", line == "7F0E6FB8 @ 我：@我 88 91 E7", line)
    line2 = br._msg_line("7F0E6FB8", "今天天气不错")
    check("4h 没人 @ 时消息行不带 @ 标记", line2 == "7F0E6FB8：今天天气不错", line2)


# ── 5 · 一条消息的日志行数（simple 2~3 行）───────────────────────
def test_one_message():
    print("[5] 一条消息的行数")
    global botdir_global
    base, botdir = setup("simple")
    botdir_global = botdir
    lines = run_group("他说的是 DeepSeek", [{"id": MYID}])
    check("5a simple：被 @ 的一条消息 2~3 行", 2 <= len(lines) <= 3, lines)
    check("5b 第一行是「消息」：作者 + @ 我 + 摘要",
          lines and " 消息 · " in lines[0] and "7F0E6FB8" in lines[0]
          and "@ 我：" in lines[0], lines[:1])
    check("5c 有「回复」行（发出去什么）",
          any(" 回复 · " in x for x in lines), lines)
    check("5d simple 下没有 detail 技术行",
          not any(m in x for x in lines for m in DETAIL_MARKS), lines)
    check("5e 每条都是六类之一", all(LINE_RE.match(x) for x in lines), lines)

    lines2 = run_group("今天天气不错", mid="m2")
    check("5f 不回的这条：消息 + 判定（≤3 行）", 2 <= len(lines2) <= 3, lines2)
    check("5g 判定行写清为什么不回",
          any(" 判定 · " in x and "跳过" in x for x in lines2), lines2)
    long_lines = run_group("一二三四五六七八九十" * 4, mid="m3")
    body = long_lines[0].partition("：")[2] if long_lines else ""
    check("5h 长正文在消息行里截断到 20 字 + …", body.endswith("…") and len(body) == 21, body)

    # detail：同一条消息多出技术行（去重键 / @ 语义 …）
    write_level(base, "detail")
    clear()
    lines3 = run_group("他说的是 DeepSeek", [{"id": MYID}], mid="m5")
    check("5i detail：同一条消息多出技术行", len(lines3) > len(lines), lines3)
    check("5j detail：出现去重键 / @ 语义技术行",
          any("去重键=" in x for x in lines3) and any("@ 语义" in x for x in lines3), lines3)
    check("5k detail 行也全是六类之一", all(LINE_RE.match(x) for x in lines3), lines3)


# ── 6 · 无 bot 的行不写 [bot] ───────────────────────────────────
def test_no_bot_tag():
    print("[6] [bot] 省略")
    setup("simple")
    br.log("已上线：惜月", cat="系统")
    br.log("发送失败：HTTP 403", cat="错误")
    line = logs()[0]
    check("6a 无 bot 的行省略 [bot]", "]" in line and "[botA" not in line
          and re.match(r"^\[\d\d-\d\d \d\d:\d\d:\d\d\] 系统 · ", line) is not None, line)
    br.log("已上线", cat="系统", appid="ABC123")
    check("6b 有 bot 的行带 [bot]", "[ABC123]" in logs()[-1], logs()[-1])


def main():
    test_cats()
    test_levels()
    test_error()
    test_summary()
    test_one_message()
    test_no_bot_tag()
    test_display_name()
    bad = [n for n, ok in RESULT if not ok]
    print("\n==== 日志格式：%d/%d 通过 ====" % (len(RESULT) - len(bad), len(RESULT)))
    if bad:
        print("失败的检查：" + "、".join(bad))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
