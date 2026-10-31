# -*- coding: utf-8 -*-
"""host 端验证：记忆的「一次操作多条」删除与撤销（真函数，/tmp 临时 botdir）。

直接 import 真模块 app/src/main/python/bot_runner.py，不装 app、不连 QQ：

    cd andbna-app && python3 tests/test_memory_multi_undo.py

覆盖：delete_many 删多条 → 只写一条 undo → undo 全部回来且行数正确 →
再 undo = 没有可撤销 → 下一次删除覆盖上一条 undo（只留最近一次）→ 老格式 undo 也能读。
"""
import json
import os
import shutil
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "app", "src", "main", "python"))

import bot_runner as br  # noqa: E402

FAILED = []


def check(name, cond, detail=""):
    print(("  ok   " if cond else "  FAIL ") + name + (("  <<< " + detail) if not cond else ""))
    if not cond:
        FAILED.append(name)


def row(n):
    return ["m%d" % n, "r%d" % n, 1700000000 + n, "u%d" % n]


def read_memory(botdir):
    with open(os.path.join(botdir, "memory.json"), "r", encoding="utf-8") as f:
        return json.load(f)


def read_undo(botdir):
    p = os.path.join(botdir, "memory.undo.json")
    if not os.path.exists(p):
        return None
    with open(p, "r", encoding="utf-8") as f:
        return json.load(f)


def main():
    botdir = tempfile.mkdtemp(prefix="pTEST_mem_", dir="/tmp")
    keys = ["pTEST_A", "pTEST_B", "pTEST_C"]
    seed = {keys[0]: [row(1), row(2)], keys[1]: [row(3)], keys[2]: [row(4), row(5), row(6)]}
    br._save_json(br._memory_path(botdir), seed)
    print("botdir = " + botdir)

    print("[1] memory_delete_many 删 2 个")
    out = br.memory_delete_many(botdir, [keys[0], keys[1]])
    print("    -> " + out)
    h = read_memory(botdir)
    check("删完只剩 1 个键", sorted(h) == [keys[2]], str(sorted(h)))
    check("剩下的行数正确（3）", len(h[keys[2]]) == 3, str(h.get(keys[2])))
    check("回执说「已删除 2 段记忆」", out.startswith("已删除 2 段记忆"), out)
    u = read_undo(botdir)
    check("undo 是新格式 {ts, items}", isinstance(u, dict) and "items" in u and "ts" in u, str(u)[:120])
    check("一条记录装下 2 段", isinstance(u, dict) and len(u.get("items", [])) == 2, str(u and u.get("items")))

    print("[2] memory_undo 全合并回来")
    out = br.memory_undo(botdir)
    print("    -> " + out)
    h = read_memory(botdir)
    check("3 个键全回来", sorted(h) == sorted(keys), str(sorted(h)))
    check("行数正确 2/1/3", [len(h[k]) for k in keys] == [2, 1, 3], str({k: len(h[k]) for k in keys}))
    check("回执说「已撤销 2 段记忆」", out == "已撤销 2 段记忆（共 3 条）", out)

    print("[3] 再 undo = 没有可撤销")
    out = br.memory_undo(botdir)
    print("    -> " + out)
    check("回执「没有可撤销的删除」", out == "没有可撤销的删除", out)
    check("memory.json 没被二次改动", read_memory(botdir) == h, "")

    print("[4] 下一次删除覆盖上一条 undo（只留最近一次）")
    br.memory_delete_many(botdir, [keys[0]])
    out2 = br.memory_undo(botdir)
    print("    -> " + out2)
    check("只撤销得到 1 段（更早那次已被覆盖）", out2.startswith("已撤销 1 段"), out2)
    h = read_memory(botdir)
    check("被覆盖那次删掉的键没回来", keys[0] in h and len(h) == 3, str(sorted(h)))
    br.memory_delete_many(botdir, [keys[1]])
    u = read_undo(botdir)
    check("undo 里只剩最近那次的 1 段", isinstance(u, dict) and len(u.get("items", [])) == 1, str(u)[:120])

    print("[5] 老格式 {key, rows} 也能读")
    br._save_json(br._undo_path(botdir), {"key": keys[1], "rows": [row(3)]})
    out = br.memory_undo(botdir)
    print("    -> " + out)
    h = read_memory(botdir)
    check("老格式撤销回 1 段", out.startswith("已撤销 1 段"), out)
    check("老格式的行回来了", len(h.get(keys[1], [])) == 1, str(h.get(keys[1])))

    print("[6] 键不存在 / 空入参")
    out = br.memory_delete_many(botdir, ["pTEST_NOT_EXIST"])
    print("    -> " + out)
    check("不存在的键不报「已删除」", not out.startswith("已删除"), out)
    check("不存在的键不留 undo", read_undo(botdir) is None, str(read_undo(botdir)))

    shutil.rmtree(botdir, ignore_errors=True)
    print("")
    if FAILED:
        print("FAILED %d: %s" % (len(FAILED), ", ".join(FAILED)))
        return 1
    print("ALL PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
