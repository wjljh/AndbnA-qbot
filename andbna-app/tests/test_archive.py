# -*- coding: utf-8 -*-
"""消息存档（<botdir>/messages.db）：FTS5 探测 / 缓冲写入 / 滚动清理 / 召回 / 导出。

直接 import 真模块，消息与模型调用都 stub 掉，不装 app、不连 QQ、不联网：

    cd andbna-app && python3 tests/test_archive.py
"""
import asyncio
import json
import os
import re
import shutil
import sqlite3
import sys
import tempfile
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "app", "src", "main", "python"))

import bot_runner as br  # noqa: E402

APPID = "botA_appid"
RESULTS = []
CALLS = []


def check(name, cond, detail):
    RESULTS.append((name, bool(cond), detail))
    print(("  PASS  " if cond else "  FAIL  ") + name + " :: " + str(detail))


def logs():
    return list(br._state["log"])


def newdir(prefix):
    return tempfile.mkdtemp(prefix=prefix)


def count(botdir):
    info = br._arc_connect(botdir)
    if info is None:
        return -1
    return int(info["conn"].execute("SELECT COUNT(*) FROM msg").fetchone()[0])


def rows(botdir, sql, args=()):
    return br._arc_connect(botdir)["conn"].execute(sql, args).fetchall()


def main():
    global CALLS
    # ── ① 探测：主机 SQLite 有 FTS5 → 建虚表 + 三个触发器 ──────────
    d1 = newdir("arc_fts_")
    info = br._arc_connect(d1)
    check("探测1 FTS5(trigram) 可用 → mode=trigram",
          info is not None and info["mode"] == "trigram", info and info["mode"])
    check("探测1b 日志写明走了 trigram",
          any("存档：FTS5(trigram) 可用" in x for x in logs()), logs()[-1:])
    fts_sql = rows(d1, "SELECT sql FROM sqlite_master WHERE name='msg_fts'")[0][0]
    check("探测1e 建表语句带 tokenize='trigram'", "trigram" in str(fts_sql).lower(), fts_sql)
    kinds = dict((r[0], r[1]) for r in rows(
        d1, "SELECT name, type FROM sqlite_master WHERE type IN ('table','trigger')"
            " AND name IN ('msg','msg_fts','msg_ai','msg_ad','msg_au')"))
    check("探测1c 表 + 虚表 + 三个触发器都在",
          kinds == {"msg": "table", "msg_fts": "table", "msg_ai": "trigger",
                    "msg_ad": "trigger", "msg_au": "trigger"}, kinds)
    cols = [r[1] for r in rows(d1, "PRAGMA table_info(msg)")]
    check("探测1d 表结构照任务书",
          cols == ["id", "chat_type", "chat_id", "author_openid", "author_name", "ts",
                   "text", "reply_text"], cols)

    # ── ①' 同一句中文查询：unicode61 命中 0、trigram 命中（换分词器的理由）──
    real_specs = br._ARC_FTS_SPECS
    br._ARC_FTS_SPECS = (("unicode61", "unicode61"),)
    try:
        info_u = br._arc_connect(newdir("arc_cmp_u_"))
    finally:
        br._ARC_FTS_SPECS = real_specs
    check("对比0 退化档 → mode=unicode61",
          info_u is not None and info_u["mode"] == "unicode61", info_u and info_u["mode"])
    check("对比0b 日志写明 FTS5(unicode61)",
          any("存档：FTS5(unicode61) 可用" in x for x in logs()), logs()[-1:])
    d_u = [k for k, v in br._arc_db.items() if v is info_u][0]
    d_t = newdir("arc_cmp_t_")
    br._arc_worker["on"] = True
    for bd in (d_u, d_t):
        br._arc_put(bd, "group", "gC", "user_z", "张伟提过档期的事")
        br._arc_flush(force=True)
    def fts_hits(bd, q):
        """直接问索引：msg_fts MATCH 命中几条（不带 LIKE 兜底）。"""
        return int(br._arc_connect(bd)["conn"].execute(
            "SELECT COUNT(*) FROM msg_fts WHERE msg_fts MATCH ?", ('"%s"' % q,)).fetchone()[0])

    hits_u = fts_hits(d_u, "张伟提")
    hits_t = fts_hits(d_t, "张伟提")
    check("对比1 同一个中文查询「张伟提」：unicode61 MATCH 命中 0、trigram 命中 1",
          hits_u == 0 and hits_t == 1, (hits_u, hits_t))
    mu, hu = br.archive_search(d_u, "张伟提")
    mt, ht = br.archive_search(d_t, "张伟提")
    check("对比2 检索路径：unicode61 只能靠 LIKE 兜（unicode61→LIKE），trigram 走 MATCH",
          mu == "unicode61→LIKE" and len(hu) == 1 and mt == "trigram" and len(ht) == 1,
          ((mu, len(hu)), (mt, len(ht))))
    plan = br._arc_connect(d_t)["conn"].execute(
        "EXPLAIN QUERY PLAN SELECT m.id FROM msg_fts JOIN msg m ON m.id = msg_fts.rowid"
        " WHERE msg_fts MATCH ?", ('"张伟提"',)).fetchall()
    plan_like = br._arc_connect(d_t)["conn"].execute(
        "EXPLAIN QUERY PLAN SELECT id FROM msg WHERE text LIKE ?", ("%张伟提%",)).fetchall()
    plan_s = " | ".join(str(r[-1]) for r in plan)
    plan_l = " | ".join(str(r[-1]) for r in plan_like)
    check("对比3 MATCH 走 FTS 索引（VIRTUAL TABLE INDEX），LIKE 才是全表扫（SCAN msg）",
          "VIRTUAL TABLE INDEX" in plan_s and "SCAN msg" in plan_l,
          "MATCH=[%s] LIKE=[%s]" % (plan_s, plan_l))
    ms, hs = br.archive_search(d_t, "张伟")
    check("对比4 两字词喂不动 trigram → 补 LIKE 兜底（trigram→LIKE）",
          len(hs) == 1 and ms == "trigram→LIKE", (ms, len(hs)))

    # ── ①'' 老库（上一版建的 unicode61）启动时自动升级到 trigram 并回填 ──────
    d_mig = newdir("arc_mig_")
    old_conn = sqlite3.connect(os.path.join(d_mig, "messages.db"))
    old_conn.execute(
        "CREATE TABLE msg(id INTEGER PRIMARY KEY, chat_type TEXT, chat_id TEXT,"
        " author_openid TEXT, author_name TEXT, ts INTEGER, text TEXT, reply_text TEXT)")
    old_conn.execute(
        "CREATE VIRTUAL TABLE msg_fts USING fts5(text, reply_text, content='msg',"
        " content_rowid='id')")
    old_conn.execute(
        "CREATE TRIGGER msg_ai AFTER INSERT ON msg BEGIN INSERT INTO msg_fts(rowid, text,"
        " reply_text) VALUES (new.id, new.text, new.reply_text); END")
    for i in range(3):
        old_conn.execute(
            "INSERT INTO msg(chat_type, chat_id, author_openid, author_name, ts, text,"
            " reply_text) VALUES('group','gM','u','',?,'张伟提过档期的事','')",
            (int(time.time()) - i,))
    old_conn.commit()
    old_conn.close()
    br._state["log"][:] = []
    info_m = br._arc_connect(d_mig)
    up = [x for x in logs() if "存档索引已升级为 trigram" in x]
    check("升级1 老库自动重建为 trigram 并回填 3 条",
          info_m["mode"] == "trigram" and up and "回填 3 条" in up[0], up[:1])
    mm, hm = br.archive_search(d_mig, "张伟提")
    check("升级2 升级后历史消息能被 trigram 查到", len(hm) == 3 and mm == "trigram",
          (mm, len(hm)))

    # ── ② 没有 FTS5 的设备 → 普通表 + LIKE + ts 索引（功能不降级）──────
    d2 = newdir("arc_like_")
    real_probe = br._arc_probe_fts5

    def boom(conn):
        raise Exception("no such module: fts5")

    br._arc_probe_fts5 = boom
    try:
        info2 = br._arc_connect(d2)
    finally:
        br._arc_probe_fts5 = real_probe
    check("降级1 建不出虚表 → mode=LIKE", info2 is not None and info2["mode"] == "LIKE",
          info2 and info2["mode"])
    check("降级1b 日志写明退回 LIKE", any("FTS5 不可用" in x for x in logs()), logs()[-1:])
    idx = [r[0] for r in rows(d2, "SELECT name FROM sqlite_master WHERE type='index'"
                                 " AND name='msg_ts'")]
    check("降级1c ts 索引在", idx == ["msg_ts"], idx)
    br._arc_put(d2, "c2c", "user_1", "user_1", "档期定在周三下午")
    br._arc_flush(force=True)
    mode2, hit2 = br.archive_search(d2, "档期")
    check("降级2 LIKE 检索命中", mode2 == "LIKE" and len(hit2) == 1 and "档期" in hit2[0][3],
          (mode2, len(hit2)))

    # ── ③ 写入：缓冲（20 条 / 5 秒）+ 回复 UPDATE 同一行 ──────────────
    br._arc_worker["on"] = True          # 假称后台线程已在跑：用例里手动 flush，结果才确定
    d3 = newdir("arc_buf_")
    br._arc_connect(d3)
    br._arc_put(d3, "group", "g1", "user_a", "我们上周团建去了杭州")
    check("写入1 不足 20 条且不满 5 秒 → 还在缓冲（库里 0 条）", count(d3) == 0, count(d3))
    br._arc_flush(force=True)
    check("写入2 flush 后 1 条", count(d3) == 1, count(d3))
    for i in range(20):
        br._arc_put(d3, "group", "g2", "user_b", "第 %d 句" % i)
    # 后台线程每 0.5 秒调一次无参 _arc_flush()：攒够 20 条就落盘（这里手动替它调一次）
    wrote = br._arc_flush()
    check("写入3 攒够 20 条 → 后台那一跳不 force 也落盘",
          wrote == 20 and count(d3) == 21, (wrote, count(d3)))
    br._arc_put(d3, "", "g3", "user_c", "空会话键也照存")
    br._arc_put(d3, "group", "g3", "user_c", "   ")
    br._arc_flush(force=True)
    check("写入4 空文本不入库（只多 1 条空 chat_id 的）", count(d3) == 22, count(d3))

    # ── ④ 回复：未 flush 改缓冲 / 已落盘 UPDATE，FTS5 跟着同步 ─────────
    d4 = newdir("arc_reply_")
    br._arc_worker["on"] = True
    br._arc_put(d4, "group", "gT", "user_x", "明天开会吗")
    ok = br._arc_reply_by_key(d4, "ggT", "下午三点，别迟到")
    br._arc_flush(force=True)
    got = rows(d4, "SELECT text, reply_text FROM msg")
    check("回复1 未 flush 时补回复 → 落盘就带着 reply_text",
          ok and len(got) == 1 and got[0][1] == "下午三点，别迟到", got)
    br._arc_put(d4, "group", "gT", "user_x", "档期改到周四了")
    br._arc_flush(force=True)
    ok2 = br._arc_reply_by_key(d4, "ggT", "好，周四见")
    got2 = rows(d4, "SELECT text, reply_text FROM msg ORDER BY id")
    check("回复2 已落盘 → UPDATE 同一行（不新增行）",
          ok2 and len(got2) == 2 and got2[-1][1] == "好，周四见", got2)
    mode4, hit4 = br.archive_search(d4, "周四见")
    check("回复3 触发器同步：新补的 reply_text 能被搜到",
          len(hit4) == 1 and hit4[0][4] == "好，周四见", (mode4, len(hit4)))
    check("回复4 没有对应请求行时不乱写（主动问候那种）",
          br._arc_reply_by_key(d4, "gno_such", "自言自语") is False, "False")

    # ── ⑤ 滚动清理：超期 + 每会话超量 ────────────────────────────
    d5 = newdir("arc_trim_")
    conn5 = br._arc_connect(d5)["conn"]
    now = int(time.time())
    for i, ts in enumerate([now - 2 * 86400] * 3 + [now] * 12):
        conn5.execute(
            "INSERT INTO msg(chat_type, chat_id, author_openid, author_name, ts, text,"
            " reply_text) VALUES('group','gTrim','u','',?,?,'')", (ts, "第 %d 条" % i))
    conn5.commit()
    out = json.loads(br.archive_trim(d5, days=1, per_chat=10))
    check("清理1 保留 1 天 → 超期 3 条被删", out["expired"] == 3, out)
    check("清理2 每会话 10 条上限 → 超量删最旧 2 条", out["over"] == 2, out)
    check("清理3 剩下 10 条", count(d5) == 10, count(d5))
    oldest = rows(d5, "SELECT MIN(ts) FROM msg")[0][0]
    check("清理4 剩下的都是新的（保留期内）", int(oldest) >= now - 86400, oldest)

    # ── ⑥ 召回检索：最近 8 条、时间正序、单条截 200 字 ───────────────
    d6 = newdir("arc_recall_")
    br._arc_worker["on"] = True
    for i in range(12):
        br._arc_put(d6, "group", "gR", "user_%d" % i, "第 %d 条：张伟提过档期的事" % i)
    br._arc_flush(force=True)
    mode6, hit6 = br.archive_search(d6, "张伟")
    check("召回1 中文关键词查得到（trigram；两字词补 LIKE）",
          len(hit6) == 8 and mode6 in ("trigram", "trigram→LIKE"), (mode6, len(hit6)))
    mode6b, hit6b_ = br.archive_search(d6, "张伟提")
    check("召回1b 三字关键词直接走 trigram 索引",
          len(hit6b_) == 8 and mode6b == "trigram", (mode6b, len(hit6b_)))
    check("召回2 时间正序，最后一条最新", "第 11 条" in hit6[-1][3], hit6[-1][3])
    line = br._recall_line(hit6[0])
    check("召回3 单条格式 [时间] 作者：原文",
          bool(re.match(r"^\[\d{4}-\d\d-\d\d \d\d:\d\d\] user_\d+：第 \d+ 条", line)), line)
    long_text = "长" * 400
    br._arc_put(d6, "group", "gR", "user_long", long_text)
    br._arc_flush(force=True)
    _, hit6b = br.archive_search(d6, "长" * 10)
    check("召回4 单条截到 200 字", hit6b and len(br._recall_line(hit6b[0])) <= 240,
          hit6b and len(br._recall_line(hit6b[0])))
    check("召回5 标记剥离", br._strip_recall_marks("好<recall>张伟</recall>的") == "好的"
          and br._strip_recall_marks("</recall>嗯") == "嗯", br._strip_recall_marks("</recall>嗯"))

    # ── ⑦ 与 <search> 并列的规则进 system；_reply 里走完一轮召回 ─────────
    base = newdir("arc_base_")
    botdir = os.path.join(base, APPID)
    os.makedirs(botdir, exist_ok=True)
    br._base_dir = base
    br.set_llm("sk-test", "http://127.0.0.1:9/v1", "test-model")
    msgs = br._build_messages(botdir, "g" + "gR", "", "在上周谁提过张伟？", search_hint=True)
    sysmsg = msgs[0]["content"]
    check("规则1 存档规则进 system", "<recall>" in sysmsg, sysmsg[-160:])
    check("规则2 与联网规则并列（两个都在）", "<search>" in sysmsg and "<recall>" in sysmsg,
          "search=%s recall=%s" % ("<search>" in sysmsg, "<recall>" in sysmsg))

    br._arc_worker["on"] = True
    br._arc_put(botdir, "group", "gR", "user_k", "张伟提过档期，说定在周三")
    br._arc_flush(force=True)

    CALLS = []

    def fake_llm(conf, msgs, *a, **kw):
        CALLS.append([dict(m) for m in msgs])
        if len(CALLS) == 1:
            return "<recall>张伟提</recall>"
        return "张伟上周提过档期，说定在周三。"

    br._call_llm_sync = fake_llm
    br._state["log"][:] = []
    reply = asyncio.run(br._reply(APPID, botdir, "ggR", "", "上周谁提过张伟？"))
    hit_log = [x for x in logs() if "存档召回：" in x]
    check("召回6 _reply 里召回一轮、日志写清命中数与路径",
          len(CALLS) == 2 and hit_log and "命中 1 条（trigram）" in hit_log[0], hit_log[:1])
    check("召回7 第二次请求里带的是原文 [时间] 作者：原文",
          any(re.search(r"\[\d{4}-\d\d-\d\d \d\d:\d\d\] user_k：张伟提过档期，说定在周三",
                        str(m.get("content"))) for m in CALLS[1]), len(CALLS[1]))
    check("召回8 最终回复里没有 <recall> 标记", "<recall>" not in reply, reply)
    check("召回9 回复补到那一行的 reply_text 上",
          any(r[1] == reply for r in rows(botdir, "SELECT text, reply_text FROM msg")),
          rows(botdir, "SELECT text, reply_text FROM msg"))

    # 0 条也记日志（未命中），且照常回一句
    br._arc_put(botdir, "group", "gR", "user_k", "今天天气不错")
    br._arc_flush(force=True)
    CALLS = []

    def fake_llm2(conf, msgs, *a, **kw):
        CALLS.append([dict(m) for m in msgs])
        if len(CALLS) == 1:
            return "<recall>没提过的词</recall>"
        return "没什么印象。"

    br._call_llm_sync = fake_llm2
    br._state["log"][:] = []
    reply2 = asyncio.run(br._reply(APPID, botdir, "ggR", "", "上周谁提过小行星？"))
    miss = [x for x in logs() if "存档召回：" in x]
    check("召回10 0 条也记日志（未命中）", miss and "未命中" in miss[0], miss[:1])
    check("召回11 没召回也照常回一句", reply2.strip() != "" and "<recall>" not in reply2,
          reply2)

    # 半截标记（只有 </recall>、没有配对的开口）也要剥掉，不能让用户看见
    br._arc_put(botdir, "group", "gR", "user_k", "随便聊聊")
    br._arc_flush(force=True)
    br._call_llm_sync = lambda conf, msgs, *a, **kw: "好</recall>的"
    reply3 = asyncio.run(br._reply(APPID, botdir, "ggR", "", "随便聊聊"))
    check("召回12 半截 <recall> 标记也从最终回复里剥掉",
          "recall" not in reply3 and "<" not in reply3, reply3)

    # ── ⑧ 统计 / 导出 / 清空（给设置页那一行用）────────────────────
    st = json.loads(br.archive_stats(botdir))
    check("统计1 有占用字节与条数", st["bytes"] > 0 and st["rows"] >= 2, st)
    text = br.archive_export_text(botdir)
    first = text.splitlines()[0] if text else ""
    last = text.splitlines()[-1] if text else ""
    check("导出1 格式 [时间] 群/私聊 作者：消息",
          bool(re.match(r"^\[\d{4}-\d\d-\d\d \d\d:\d\d\] (群|私聊) \S+：.+", first)), first)
    check("导出2 有过回复的行带 ⇢ 回复", " ⇢ " in last or " ⇢ " in text, last)
    before = st["bytes"]
    cleared = json.loads(br.archive_clear(botdir))
    check("清空1 条数归零", count(botdir) == 0 and cleared["rows"] >= 2, cleared)
    check("清空2 文件被 VACUUM 收回（不再变大）", cleared["bytes"] <= before,
          (before, cleared["bytes"]))
    check("清空3 清完还能继续用", (br._arc_put(botdir, "c2c", "u9", "u9", "清完再聊一句"),
                                   br._arc_flush(force=True), count(botdir) == 1)[-1],
          count(botdir))

    bad = [r for r in RESULTS if not r[1]]
    print("")
    print("通过 %d / %d" % (len(RESULTS) - len(bad), len(RESULTS)))
    for r in bad:
        print("  FAIL " + r[0] + " :: " + str(r[2]))
    return 1 if bad else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    finally:
        shutil.rmtree(os.path.join(br._base_dir or "/nonexistent"), ignore_errors=True)
