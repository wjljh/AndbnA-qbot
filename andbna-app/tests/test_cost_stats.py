# -*- coding: utf-8 -*-
"""host 端验证：成本统计（usage.json / 余额差值 / 联网计次）与失败路径。

直接 import 真模块，起一个本地假接口（chat/completions + /user/balance），
不装 app、不连 QQ、不花 Key：

    cd andbna-app && python3 tests/test_cost_stats.py
"""
import json
import os
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "app", "src", "main", "python"))

import bot_runner as br  # noqa: E402

APP = "botA_appid"
BAL = {"cny": 100.00}


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _j(self, code, obj):
        d = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(d)))
        self.end_headers()
        self.wfile.write(d)

    def do_GET(self):
        if self.path == "/user/balance":
            return self._j(200, {"balance_infos": [
                {"currency": "CNY", "total_balance": "%.2f" % BAL["cny"]}]})
        return self._j(404, {})

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        self.rfile.read(n)
        if self.path.endswith("/chat/completions"):
            return self._j(200, {"choices": [{"message": {"content": "好呀。"}}],
                                 "usage": {"prompt_tokens": 1000, "completion_tokens": 100}})
        if "/tools/search" in self.path:
            return self._j(200, {"results": []})
        if "/tools/fetch" in self.path:
            return self._j(200, {"content": ""})
        return self._j(404, {})


def main():
    br.log = lambda m, appid="": None
    srv = ThreadingHTTPServer(("127.0.0.1", 0), H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    api = "http://127.0.0.1:%d/chat/completions" % srv.server_address[1]

    base = tempfile.mkdtemp(prefix="cost_")
    botdir = os.path.join(base, APP)
    os.makedirs(botdir)
    br._base_dir = base
    br.set_llm("sk-test", api, "deepseek-chat", "", "", "", "")
    br._usage_appid.set(APP)
    day = br._day_str()

    # ① 三次调用按用途分桶，一次失败也不炸
    for slot in ("reply", "reply", "judge"):
        br._call_llm_sync(br._conf_for(slot), [{"role": "user", "content": "hi"}])
    d = json.load(open(os.path.join(botdir, "usage.json"), encoding="utf-8"))
    assert d[day]["reply"] == {"calls": 2, "in": 2000, "out": 200}, d
    assert d[day]["judge"] == {"calls": 1, "in": 1000, "out": 100}, d

    # ② 联网单独计次（Search + Fetch）
    conf = br._conf_for("reply")
    br.web_search("天气", conf, APP)
    br.web_fetch("https://x", conf, APP)
    d = json.load(open(os.path.join(botdir, "usage.json"), encoding="utf-8"))
    assert d["search_calls"][day] == 2, d

    # ③ 余额差值：首读 100.00，花到 99.58 → 今日 0.42
    br._balance_store(botdir, br._balance_query(conf))
    BAL["cny"] = 99.58
    br._balance_store(botdir, br._balance_query(conf))
    snap = json.loads(br.cost_snapshot(APP, base))
    assert snap["calls"] == 3 and snap["in_tokens"] == 3000 and snap["out_tokens"] == 300, snap
    assert snap["today_cost_yuan"] == 0.42, snap
    assert snap["search_calls"] == 2, snap

    # ④ 10 分钟节流：ts 不动就不重查
    ts = json.load(open(os.path.join(botdir, "balance.json"), encoding="utf-8"))["ts"]
    br._balance_tick(botdir)
    assert json.load(open(os.path.join(botdir, "balance.json"), encoding="utf-8"))["ts"] == ts

    # ⑤ 失败 / 跨天：金额 None，token 照旧
    br._balance_store(botdir, None)
    assert json.loads(br.cost_snapshot(APP, base))["today_cost_yuan"] is None
    b = json.load(open(os.path.join(botdir, "balance.json"), encoding="utf-8"))
    b.update({"date": "2000-01-01", "ok": True})
    json.dump(b, open(os.path.join(botdir, "balance.json"), "w", encoding="utf-8"))
    assert json.loads(br.cost_snapshot(APP, base))["today_cost_yuan"] is None

    # ⑥ 30 天裁剪
    d = json.load(open(os.path.join(botdir, "usage.json"), encoding="utf-8"))
    d["2020-01-01"] = {"reply": {"calls": 9, "in": 9, "out": 9}}
    d["search_calls"]["2020-01-01"] = 9
    json.dump(d, open(os.path.join(botdir, "usage.json"), "w", encoding="utf-8"))
    br._usage_add(botdir, "reply", {"prompt_tokens": 1, "completion_tokens": 1})
    d = json.load(open(os.path.join(botdir, "usage.json"), encoding="utf-8"))
    assert "2020-01-01" not in d and "2020-01-01" not in d["search_calls"], d
    srv.shutdown()
    print("成本统计：全部通过")


if __name__ == "__main__":
    main()
