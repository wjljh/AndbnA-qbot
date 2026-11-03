# -*- coding: utf-8 -*-
"""纯 APK 模式的 bot 管理器（支持多个 bot 同时在线）。

为什么改成管理器：一个 AppID 对应一条独立的 WebSocket 连接，彼此不干扰，
所以「同时挂几个 bot」在协议层是成立的。每个 bot 需要：
  · 自己的线程 + 自己的 asyncio 事件循环（Python 3.10 起非主线程不再自动建循环）
  · 自己的数据目录（人格、日志；以后还有记忆 —— QQ 的 openid 按 bot 分配，
    不同 bot 看到同一个人的 openid 不同，记忆必须隔离）
  · 自己的 botpy 客户端
"""
import asyncio
import contextvars
import json
import os
import random
import re
import sys
import threading
import time
import traceback

_lock = threading.Lock()
_bots = {}          # appid -> 运行时信息
_state = {"log": []}
_base_dir = ""
LOG_LIMIT = 1200
_log_path = ""


def _stamp():
    return time.strftime("%m-%d %H:%M:%S")


def log(msg, appid=""):
    tag = ("[%s] " % appid) if appid else ""
    line = "[%s] %s%s" % (_stamp(), tag, msg)
    with _lock:
        _state["log"].append(line)
        if len(_state["log"]) > LOG_LIMIT:
            del _state["log"][:400]
    print(line)
    sys.stdout.flush()
    try:
        if _log_path:
            with open(_log_path, "a", encoding="utf-8") as f:
                f.write(line + "\n")
    except Exception:
        pass


def _bot_dir(base, appid):
    d = os.path.join(base, str(appid))
    try:
        os.makedirs(d, exist_ok=True)
    except Exception:
        pass
    return d


# ── 记忆与模型 ────────────────────────────────────────


def _load_json(path, default):
    try:
        import json
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return default


def _save_json(path, obj):
    try:
        import json
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8", newline="\n") as f:
            json.dump(obj, f, ensure_ascii=False, indent=1)
        os.replace(tmp, path)
        return True
    except Exception as e:
        log("写文件失败 %s: %r" % (path, e))
        return False


def _backup_memory(botdir):
    """落盘前先留一份备份：memory.bak.0.json 最新，滚动 7 份覆盖最旧的。

    误删可回滚；备份失败只 log，不阻塞主流程（删不掉记忆比备份缺失更严重）。
    备份文件权限 600，属主与源文件一致。
    """
    import shutil
    src = _memory_path(botdir)
    try:
        if not os.path.exists(src):
            return False
        names = [os.path.join(botdir, "memory.bak.%d.json" % i) for i in range(7)]
        if os.path.exists(names[6]):
            os.remove(names[6])
        for i in range(5, -1, -1):
            if os.path.exists(names[i]):
                os.replace(names[i], names[i + 1])
        shutil.copyfile(src, names[0])
        os.chmod(names[0], 0o600)
        try:
            st = os.stat(src)
            os.chown(names[0], st.st_uid, st.st_gid)
        except Exception:
            pass
        return True
    except Exception as e:
        log("记忆备份失败 %s: %r" % (src, e))
        return False


def _memory_path(botdir):
    return os.path.join(botdir, "memory.json")


def _memory(botdir):
    d = _load_json(_memory_path(botdir), {})
    return d if isinstance(d, dict) else {}


def _remember(botdir, key, role, text, uid=""):
    """和模块那边的记录格式保持一致：[[role, text, ts, uid], ...]
    这样以后把模块的历史搬过来不用改结构。"""
    h = _memory(botdir)
    rows = h.get(key)
    if not isinstance(rows, list):
        rows = []
    rows.append([role, text, int(time.time()), uid])
    if len(rows) > 400:
        del rows[:100]
    h[key] = rows
    _backup_memory(botdir)
    return _save_json(_memory_path(botdir), h)


# Kotlin 侧启动前推过来的模型配置（含明文 Key）。Key 不落盘：llm.json 里只有地址、模型名和识图模型。
_llm = {"key": "", "api": "", "model": "", "vision_model": "",
        "judge_model": "", "summary_model": "", "search_model": ""}

# 上下文窗口的默认值（token）。128K 是现在主流模型的入门档；用户可在模型配置页改。
# 注意：这是**估算阈值基数**（压缩阈值 = 它的 75%），不是硬上限 —— 真正发出去的只有
# system + 摘要 + 最近 12 条原文（见 _build_messages）。
_DEFAULT_WINDOW = 131072


def set_llm(key, api, model, vision_model="", judge_model="", summary_model="",
            search_model=""):
    """由 BotHost.startAll 在 start_all 之前调用，把 Key 放进本进程内存。

    五个槽位的模型名都可以留空：留空 = 该槽位沿用主模型（槽位取值见 _conf_for）。
    """
    _llm["key"] = str(key or "").strip()
    _llm["api"] = str(api or "").strip()
    _llm["model"] = str(model or "").strip()
    _llm["vision_model"] = str(vision_model or "").strip()
    _llm["judge_model"] = str(judge_model or "").strip()
    _llm["summary_model"] = str(summary_model or "").strip()
    _llm["search_model"] = str(search_model or "").strip()
    return "OK"


def set_reply_judge(judge, prob):
    """设置页「回复判定」：llm=让模型判要不要插话，random=按闲聊概率掷骰子。"""
    _judge["mode"] = "random" if str(judge or "").strip() == "random" else "llm"
    try:
        _judge["prob"] = max(0.0, min(1.0, float(str(prob or "").strip() or 0.3)))
    except Exception:
        _judge["prob"] = 0.3
    log("回复判定：%s%s" % (_judge["mode"], "（概率 %.2f）" % _judge["prob"] if _judge["mode"] == "random" else ""))
    return "OK"


def _llm_conf(base_dir):
    """{key, api, model, vision_model, judge_model, summary_model, reasoning_effort, ...}。

    api / model / 四个槽位模型 / reasoning_effort 都可能来自落盘的 llm.json；key 只在内存里
    （BotHost 推过来的）。槽位模型空串 = 沿用主模型（不复制 model，调用时由 _conf_for 回退）。
    reasoning_effort 空串 = 请求体里不带这个字段（用平台默认），见 _req_body。
    另带几个开关：context_window（压缩阈值基数，默认 131072）、web_search_enabled（联网，
    默认开）与 search_persona_style（#搜/#读 结果用人设口吻转述，默认开）。
    """
    c = _load_json(os.path.join(base_dir, "llm.json"), {}) or {}
    key = str(_llm.get("key") or c.get("key") or "").strip()
    api = str(_llm.get("api") or c.get("api") or "").strip()
    model = str(_llm.get("model") or c.get("model") or "").strip() or "deepseek-chat"
    vision_model = str(_llm.get("vision_model") or c.get("vision_model") or "").strip()
    judge_model = str(_llm.get("judge_model") or c.get("judge_model") or "").strip()
    summary_model = str(_llm.get("summary_model") or c.get("summary_model") or "").strip()
    search_model = str(_llm.get("search_model") or c.get("search_model") or "").strip()
    effort = str(c.get("reasoning_effort") or "").strip()
    try:
        window = int(c.get("context_window") or _DEFAULT_WINDOW)
    except Exception:
        window = _DEFAULT_WINDOW
    if window <= 0:
        window = _DEFAULT_WINDOW
    web_on = c.get("web_search_enabled")
    web_on = True if web_on is None else bool(web_on)
    style_on = c.get("search_persona_style")
    style_on = True if style_on is None else bool(style_on)
    auto_decide = c.get("search_auto_decide")
    auto_decide = True if auto_decide is None else bool(auto_decide)
    provider = str(c.get("search_provider") or "").strip().lower()
    if provider not in ("deepseek", "searxng", "custom", "off"):
        provider = "deepseek"        # 旧配置没这个键 → 仍按 DeepSeek 官方
    s_base = str(c.get("search_base") or "").strip()
    s_auth = str(c.get("search_auth") or "").strip() or (
        "bearer" if provider == "deepseek" else "none")
    s_fields = str(c.get("search_fields") or "").strip() or "results[].title,url,summary"
    # 余额接口地址只在特殊场景手填（中转站 / 验收时造坏地址）；留空按 api 推导
    balance_api = str(c.get("balance_api") or "").strip()
    if not api or not key:
        return None
    if not api.endswith("/chat/completions"):
        api = api.rstrip("/") + "/chat/completions"
    return {"key": key, "api": api, "model": model, "vision_model": vision_model,
            "judge_model": judge_model, "summary_model": summary_model,
            "search_model": search_model,
            "reasoning_effort": effort, "context_window": window,
            "web_search_enabled": web_on, "search_persona_style": style_on,
            "search_auto_decide": auto_decide,
            "search_provider": provider, "search_base": s_base,
            "search_auth": s_auth, "search_fields": s_fields,
            "balance_api": balance_api}


# 槽位 → 取值字段：reply 就是主模型本身，其余四个留空即回落主模型。
_SLOTS = {"reply": "", "judge": "judge_model", "vision": "vision_model",
          "summary": "summary_model", "search": "search_model"}


def _conf_for(kind, base_dir=""):
    """按槽位取一份配置：conf["model"] 已经是该槽位**实际会用**的模型名。

    kind ∈ reply/judge/vision/summary/search（未知或空串 = reply）。槽位模型留空就回落主模型，
    所以调用方只看 conf["model"] 即可，别自己去挑 vision_model 那一类字段。
    配置不全（缺 api/key）返回 None —— 与 _llm_conf 同一口径。
    """
    conf = _llm_conf(base_dir or _base_dir)
    if not conf:
        return None
    out = dict(conf)
    field = _SLOTS.get(str(kind or "").strip().lower(), "")
    if field:
        out["model"] = (conf.get(field) or "").strip() or conf["model"]
    out["slot"] = str(kind or "reply").strip().lower() or "reply"
    return out


def _extract_text(msg):
    """推理模型的 content 可能为空，正文藏在 reasoning_content 里。

    两个都取，优先 content。这套接口（deepseek-flash 一类）实测过：
    content 是空串、reasoning_content 才有东西，不兜底的话回复和判定都会变成「(空)」。
    """
    if not isinstance(msg, dict):
        return ""
    c = str(msg.get("content") or "").strip()
    if c:
        return c
    r = str(msg.get("reasoning_content") or "").strip()
    if not r:
        return ""
    lines = [x.strip() for x in r.splitlines() if x.strip()]
    for line in reversed(lines):
        cleaned = line.lstrip("0123456789.*-— 　").strip()
        if len(cleaned) > 8:
            return cleaned[:400]
    return lines[-1][:400] if lines else ""


def _req_body(conf, model, messages, max_tokens, temperature):
    """拼 chat/completions 请求体：思考强度**只在非空时**加进去。

    留空 = 不发送该字段（按平台默认跑）。各平台取值不同（DeepSeek 官方 off/low/high/max，
    百炼 default/low/medium/high/xhigh/max，部分模型只认 default），所以这里既不枚举也不校验，
    填什么就发什么 —— 校验归「验证思考强度」按钮，由平台自己回答。
    """
    body = {
        "model": model, "messages": messages,
        "max_tokens": max_tokens, "temperature": temperature,
    }
    effort = str(conf.get("reasoning_effort") or "").strip()
    if effort:
        body["reasoning_effort"] = effort
    return body


def _log_body(body):
    """请求体只记字段名 + 思考强度：识图那条带 base64 图片，整包不能进日志。"""
    fields = ",".join(body.keys())
    if "reasoning_effort" in body:
        log("请求体字段：%s（reasoning_effort=%s）" % (fields, body["reasoning_effort"]))
    else:
        log("请求体字段：%s（未设置思考强度，不带 reasoning_effort）" % fields)


# ── 成本统计（token / 余额差值 / 联网次数）──────────────────
#
# 只记**上游给的数**，不写本地价格表：
#   · chat/completions 的 usage → 按（日期 + 用途）累加 token（这个接口只给 token，没有钱）；
#   · GET /user/balance 的 total_balance → 今日消耗 = 今日首次余额 − 当前余额（上游真实扣费）。
# 余额每 10 分钟最多查一次，失败静默：金额留空，token 与次数照常显示，回复链路不受影响。
# 同一 Key 在别的端（网页/别的机器）的消耗会混进余额差值里 —— 所以界面写「约」。

_USAGE_SLOTS = ("reply", "judge", "vision", "summary", "search")
_USAGE_KEEP_DAYS = 30
_BALANCE_GAP = 600          # 秒：余额接口每 10 分钟最多打一次
_usage_lock = threading.Lock()
_balance_lock = threading.Lock()
_balance_running = set()
# 「这次模型调用算哪个 bot 的账」：_call_llm_sync 跑在 asyncio.to_thread 里（会复制上下文），
# 所以入口线程 set 一次就行，不用给每个调用点加参数。
_usage_appid = contextvars.ContextVar("andbna_usage_appid", default="")


def _day_str(offset_days=0):
    return time.strftime("%Y-%m-%d", time.localtime(time.time() + offset_days * 86400))


def _usage_path(botdir):
    return os.path.join(botdir, "usage.json")


def _balance_path(botdir):
    return os.path.join(botdir, "balance.json")


def _usage_dir(appid=""):
    """appid → <botdir>；拿不到就返回空串（不计账，也不影响回复）。"""
    aid = str(appid or _usage_appid.get() or "").strip()
    if not aid or not _base_dir:
        return ""
    return _bot_dir(_base_dir, aid)


def _trim_usage(d):
    """按天分桶只留最近 30 天（search_calls 也一起裁），防止文件无限长。"""
    keep = _day_str(-(_USAGE_KEEP_DAYS - 1))
    sc = d.pop("search_calls", None)
    for day in list(d.keys()):
        if not isinstance(day, str) or len(day) != 10 or day < keep:
            d.pop(day, None)
    if isinstance(sc, dict):
        d["search_calls"] = {k: int(v or 0) for k, v in sc.items()
                             if isinstance(k, str) and len(k) == 10 and k >= keep}
    return d


def _usage_add(botdir, slot, usage):
    """记一次 chat/completions 的 usage（调用次数 +1，token 累加）。"""
    slot = str(slot or "").strip().lower()
    if slot not in _USAGE_SLOTS:
        slot = "reply"
    u = usage if isinstance(usage, dict) else {}
    try:
        pin = int(u.get("prompt_tokens") or 0)
        pout = int(u.get("completion_tokens") or 0)
    except Exception:
        pin, pout = 0, 0
    day = _day_str()
    with _usage_lock:
        d = _load_json(_usage_path(botdir), {})
        if not isinstance(d, dict):
            d = {}
        day_map = d.get(day) if isinstance(d.get(day), dict) else {}
        old = day_map.get(slot) if isinstance(day_map.get(slot), dict) else {}
        day_map[slot] = {"calls": int(old.get("calls") or 0) + 1,
                         "in": int(old.get("in") or 0) + pin,
                         "out": int(old.get("out") or 0) + pout}
        d[day] = day_map
        _save_json(_usage_path(botdir), _trim_usage(d))
        today_calls = day_map[slot]["calls"]
    log("用量记一笔：%s 入 %d / 出 %d（今日该用途第 %d 次调用）"
        % (slot, pin, pout, today_calls), _usage_appid.get())
    _balance_tick(botdir)          # 顺手看一眼余额（内部 10 分钟节流，后台线程）


def _usage_search(botdir):
    """联网 Search / Fetch 单独计次：hero 上的「联网 N 次」。"""
    if not botdir:
        return
    day = _day_str()
    with _usage_lock:
        d = _load_json(_usage_path(botdir), {})
        if not isinstance(d, dict):
            d = {}
        m = d.get("search_calls") if isinstance(d.get("search_calls"), dict) else {}
        m[day] = int(m.get(day) or 0) + 1
        d["search_calls"] = m
        _save_json(_usage_path(botdir), _trim_usage(d))


def _usage_from_response(conf, d):
    """响应里取 usage 记账；取不到（中转站不回 usage / 字段缺失）就只记次数。"""
    try:
        botdir = _usage_dir()
        if not botdir:
            return
        u = d.get("usage") if isinstance(d, dict) else None
        _usage_add(botdir, (conf or {}).get("slot") or "reply", u or {})
    except Exception as e:
        log("用量记账失败（忽略）：%r" % e)


def _balance_url(conf):
    """余额接口地址：官方 Key 用 api.deepseek.com，中转站退回 api 主机根路径的 /user/balance。"""
    override = str(conf.get("balance_api") or "").strip()
    if override:
        return override
    api = str(conf.get("api") or "").strip()
    if "deepseek.com" in api:
        return "https://api.deepseek.com/user/balance"
    try:
        import urllib.parse
        p = urllib.parse.urlsplit(api)
        if p.scheme and p.netloc:
            return "%s://%s/user/balance" % (p.scheme, p.netloc)
    except Exception:
        pass
    return ""


def _balance_query(conf):
    """→ 余额（CNY，float）或 None。任何失败（网络/HTTP/字段缺失）都只返回 None。"""
    url = _balance_url(conf or {})
    if not url or not conf or not conf.get("key"):
        return None
    try:
        import urllib.request
        req = urllib.request.Request(url, headers={
            "Authorization": "Bearer " + conf["key"],
            "Accept": "application/json",
        })
        with urllib.request.urlopen(req, timeout=8) as r:
            d = json.loads(r.read().decode("utf-8", "replace"))
        infos = d.get("balance_infos") if isinstance(d, dict) else None
        for it in infos or []:
            if str((it or {}).get("currency") or "").upper() == "CNY":
                return round(float(it.get("total_balance")), 2)
        log("余额查询：响应里没有 CNY 余额，金额留空")
        return None
    except Exception as e:
        # 不打印 URL 里的任何凭据（Key 在头里，从来没进过日志/文件）
        log("余额查询失败（金额留空，不影响回复）：%r" % e)
        return None


def _balance_store(botdir, val):
    """落 balance.json：{date, first, last, ts, ok}；跨天把 first 重置为当天第一条读数。"""
    day = _day_str()
    with _balance_lock:
        old = _load_json(_balance_path(botdir), {})
        if not isinstance(old, dict):
            old = {}
        today = old.get("date") == day
        if val is None:
            # 失败也要落 ts（否则界面每 10 秒戳一次 = 刷接口），并标 ok=False → 界面把金额留空
            new = {"date": day,
                   "first": old.get("first") if today else None,
                   "last": old.get("last") if today else None,
                   "ts": time.time(), "ok": False}
        else:
            first = old.get("first") if today else None
            new = {"date": day, "first": val if first is None else first,
                   "last": val, "ts": time.time(), "ok": True}
        _save_json(_balance_path(botdir), new)
    if new.get("ok"):
        log("余额：今日首条 %.2f / 当前 %.2f → 今日约 ¥%.2f（每 10 分钟查一次）"
            % (float(new["first"]), float(new["last"]),
               max(0.0, float(new["first"]) - float(new["last"]))))
    else:
        log("余额：这次没取到，今日金额留空（10 分钟内不再重试）")
    return new


def _balance_tick(botdir, conf=None):
    """余额刷新：10 分钟最多一次，查询放后台线程（界面轮询调它不会卡）。"""
    if not botdir:
        return
    old = _load_json(_balance_path(botdir), {})
    try:
        ts = float((old or {}).get("ts") or 0)
    except Exception:
        ts = 0.0
    if time.time() - ts < _BALANCE_GAP:
        return
    with _balance_lock:
        if botdir in _balance_running:
            return
        _balance_running.add(botdir)
    conf = conf if conf is not None else (_llm_conf(_base_dir) if _base_dir else None)
    if not conf:
        with _balance_lock:
            _balance_running.discard(botdir)
        return

    def _run():
        try:
            _balance_store(botdir, _balance_query(conf))
        finally:
            with _balance_lock:
                _balance_running.discard(botdir)

    try:
        threading.Thread(target=_run, daemon=True).start()
    except Exception as e:
        with _balance_lock:
            _balance_running.discard(botdir)
        log("余额查询线程起不来（忽略）：%r" % e)


def _cost_money(botdir):
    """今日消耗（元）= 首次余额 − 当前余额；无数据 / 上次查询失败 / 跨天 → None。"""
    b = _load_json(_balance_path(botdir), {})
    if not isinstance(b, dict) or not b.get("ok"):
        return None
    if b.get("date") != _day_str():
        return None
    try:
        first = float(b.get("first"))
        last = float(b.get("last"))
    except Exception:
        return None
    return round(max(0.0, first - last), 2)      # 充过钱 → 负数按 0 显示，别显示负花费


def cost_snapshot(appid, base_dir=""):
    """hero 下段那一行的数据源（JSON 字符串，Kotlin 侧解析）。

    {calls, in_tokens, out_tokens, today_cost_yuan|null, search_calls}
    calls / tokens = 今日全部用途（reply/judge/vision/summary/search 槽位）之和；
    search_calls = 今日 Search/Fetch 的真实调用次数（不是模型调用次数）。
    余额在这顺手刷一次（内部 10 分钟节流 + 后台线程），读不到金额就返回 null。
    """
    botdir = _bot_dir(base_dir or _base_dir or os.getcwd(), appid)
    day = _day_str()
    d = _load_json(_usage_path(botdir), {})
    if not isinstance(d, dict):
        d = {}
    calls = tin = tout = 0
    buckets = d.get(day) if isinstance(d.get(day), dict) else {}
    for b in buckets.values():
        if not isinstance(b, dict):
            continue
        calls += int(b.get("calls") or 0)
        tin += int(b.get("in") or 0)
        tout += int(b.get("out") or 0)
    sc = d.get("search_calls") if isinstance(d.get("search_calls"), dict) else {}
    out = {"calls": calls, "in_tokens": tin, "out_tokens": tout,
           "today_cost_yuan": _cost_money(botdir),
           "search_calls": int(sc.get(day) or 0)}
    try:
        _balance_tick(botdir)
    except Exception:
        pass
    return json.dumps(out, ensure_ascii=False)


def _call_llm_sync(conf, messages, max_tokens=400, temperature=0.9):
    import json
    import urllib.request
    body_obj = _req_body(conf, conf["model"], messages, max_tokens, temperature)
    _log_body(body_obj)
    body = json.dumps(body_obj).encode("utf-8")
    req = urllib.request.Request(conf["api"], data=body, method="POST", headers={
        "Content-Type": "application/json",
        "Authorization": "Bearer " + conf["key"],
    })
    with urllib.request.urlopen(req, timeout=30) as r:
        d = json.loads(r.read().decode("utf-8", "replace"))
    _usage_from_response(conf, d)
    return _extract_text(d["choices"][0]["message"])


# ── 回复判定（非 @ 消息要不要插话）──────────────────────────
#
# 设置页那一行：模型判断（llm）/ 随机（random，按「闲聊概率」掷骰子）。
# 只影响「没 @ 本 bot 的群消息」，@ 的照旧必回。

_judge = {"mode": "llm", "prob": 0.3}


async def _should_reply(conf, text):
    """这条没 @ 的消息要不要接一句。

    conf 传主模型配置即可：判定槽位（judge_model，留空 = 主模型）在这里现取，
    所以日志里的「判定（模型 X）」永远是真正发出去的那个 model 字段。
    """
    if _judge["mode"] != "llm":
        import random as _random
        hit = _random.random() <= _judge["prob"]
        log("判定（随机 %.2f）：%s" % (_judge["prob"], "接" if hit else "不接"))
        return hit
    if not conf:
        log("判定跳过：模型配置没填（llm.json 缺 key / 地址）")
        return False
    jconf = _conf_for("judge") or conf
    prompt = (
        "群里有人说：%s\n\n"
        "如果你觉得这句话该由你接一句，只回 1；不该插话只回 0。\n"
        "只回一个字符，不要解释。" % str(text or "")[:200]
    )
    try:
        raw = await asyncio.to_thread(
            _call_llm_sync, jconf, [{"role": "user", "content": prompt}], 200, 0.2,
        )
        raw = (raw or "").strip()
        # 推理模型的正文可能只有「1」落在开头或结尾，两头都看一眼
        hit = ("1" in raw[:4]) or (raw[-1:] == "1") or (raw[:1] == "1")
        log("判定（模型 %s）：%s → %s" % (jconf["model"], (raw[:20] or "(空)").replace(chr(10), " "),
                                        "接" if hit else "不接"))
        return hit
    except Exception as e:
        log("判定失败（按不接处理）：%r" % e)
        return False


# ── 识图 ────────────────────────────────────────────────
#
# QQ 官方 bot 的图片在 message.attachments 里，带 url（有时效）。收到就当场下下来
# （≤10MB，超限跳过并 log）转成 base64 data URL 交给视觉模型看，描述拼回这一轮的入参文本
# —— 不另造回复链路。日志只记文件名和字节数，绝不打印 base64（体积会炸）。

_VISION_MAX_BYTES = 10 * 1024 * 1024
_VISION_HINT = "用一两句话说这张图里是什么。"


def _vision_model(conf):
    """识图用哪个模型：vision_model 非空用它，留空则沿用主模型。"""
    return (conf.get("vision_model") or "").strip() or conf["model"]


def _attachments_of(message):
    """消息里的图片附件：botpy 对象和 dict 两种形态都认。

    返回 [{"url", "filename", "content_type"}]；非图片（语音/文件）不算。
    """
    # 三种形态：botpy 对象（.attachments）/ 裸 dict（.attachments）/ 对象上的 .raw 原始推送
    if isinstance(message, dict):
        items = _entries(message.get("attachments"))
    else:
        items = _entries(_attr(message, "attachments"))
    if not items:
        raw = message.get("raw") if isinstance(message, dict) else getattr(message, "raw", None)
        if isinstance(raw, dict):
            d = raw.get("d") if isinstance(raw.get("d"), dict) else raw
            items = _entries(d.get("attachments") if isinstance(d, dict) else None)
    out = []
    for a in items:
        if isinstance(a, dict):
            url = str(a.get("url") or "").strip()
            ctype = str(a.get("content_type") or "").strip()
            name = str(a.get("filename") or "").strip()
        else:
            url = str(_attr(a, "url") or "").strip()
            ctype = str(_attr(a, "content_type") or "").strip()
            name = str(_attr(a, "filename") or "").strip()
        if not url:
            continue
        low = ctype.lower()
        # 明确不是图片的（语音 / 文件 / 视频）不送视觉模型；类型缺省时靠 sniff 兜底
        if low and "image" not in low and not low.startswith("application/octet-stream"):
            continue
        out.append({"url": url, "filename": name, "content_type": low})
    return out


def _sniff_mime(data):
    if data[:3] == b"\xff\xd8\xff":
        return "image/jpeg"
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return "image/png"
    if data[:6] in (b"GIF87a", b"GIF89a"):
        return "image/gif"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "image/webp"
    return "image/jpeg"


def _image_data_url(url, appid="", filename=""):
    """下载一张图 → (data_url, 字节数, 名字)；超 10MB / 下载失败返回 None（只 log，不抛）。"""
    import base64
    import urllib.request
    url = str(url or "").strip()
    if not url:
        return None
    name = filename or "图片"
    try:
        with urllib.request.urlopen(url, timeout=20) as r:
            ctype = (r.headers.get("Content-Type") or "").split(";")[0].strip().lower()
            try:
                declared = int(r.headers.get("Content-Length") or 0)
            except Exception:
                declared = 0
            if declared > _VISION_MAX_BYTES:
                log("识图跳过：%s 有 %.1fMB，超过 10MB 上限" % (name, declared / 1048576.0), appid)
                return None
            data = r.read(_VISION_MAX_BYTES + 1)
    except Exception as e:
        log("识图跳过：%s 下载失败 %r" % (name, e), appid)
        return None
    if len(data) > _VISION_MAX_BYTES:
        log("识图跳过：%s 超过 10MB 上限（%d 字节）" % (name, len(data)), appid)
        return None
    if not data:
        log("识图跳过：%s 下下来是空文件" % name, appid)
        return None
    mime = ctype if ctype.startswith("image/") else _sniff_mime(data)
    return ("data:%s;base64,%s" % (mime, base64.b64encode(data).decode("ascii")), len(data), name)


def _describe_image(conf, data_url, appid="", name="图片", nbytes=0):
    """把一张图交给视觉模型看一眼，返回一句描述；失败返回 ""。

    失败原因必须写进日志（模型不支持图片时接口一般回 400/422 + 一句 image 相关的话）。
    """
    import json
    import urllib.error
    import urllib.request

    model = _vision_model(conf)
    log("识图：%s（%d KB）交给模型 %s" % (name, max(1, nbytes // 1024), model), appid)
    content = [
        {"type": "text", "text": _VISION_HINT},
        {"type": "image_url", "image_url": {"url": data_url}},
    ]
    body_obj = _req_body(conf, model, [{"role": "user", "content": content}], 300, 0.3)
    _log_body(body_obj)
    body = json.dumps(body_obj).encode("utf-8")
    req = urllib.request.Request(conf["api"], data=body, method="POST", headers={
        "Content-Type": "application/json",
        "Authorization": "Bearer " + conf["key"],
    })
    try:
        with urllib.request.urlopen(req, timeout=40) as r:
            d = json.loads(r.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        code = int(getattr(e, "code", 0) or 0)
        detail = ""
        try:
            detail = e.read().decode("utf-8", "replace")[:300]
        except Exception:
            detail = repr(e)
        low = detail.lower()
        if code in (400, 413, 415, 422) or "image" in low or "vision" in low:
            log("识图失败：模型 %s 不支持图片输入（HTTP %d）：%s"
                % (model, code, detail.replace(chr(10), " ")[:160]), appid)
        else:
            log("识图失败：模型 %s 返回 HTTP %d：%s"
                % (model, code, detail.replace(chr(10), " ")[:160]), appid)
        return ""
    except Exception as e:
        log("识图失败：%r（模型 %s）" % (e, model), appid)
        return ""
    try:
        desc = _extract_text((d.get("choices") or [{}])[0].get("message") or {})
    except Exception:
        desc = ""
    if not desc:
        log("识图失败：模型 %s 返回里没有 content" % model, appid)
        return ""
    desc = desc.replace(chr(10), " ").strip()
    # 有的接口对图片只回 200 + 一句「我看不到这张图」：那也是不支持图片输入，得写进日志
    low = desc.lower()
    for bad in (
        "无法查看这张图片", "无法查看图片", "看不到这张图", "无法看到这张图",
        "不支持图片", "无法识别图片", "无法查看图片内容",
        "cannot see the image", "cannot view the image", "can't see the image",
        "unable to view the image", "does not support image", "cannot see this image",
    ):
        if bad in low:
            log("识图失败：模型 %s 不支持图片输入（HTTP 200，模型回答：%s）"
                % (model, desc[:60]), appid)
            return ""
    return desc


def verify_reasoning_effort(api, key, model, effort, base_dir=""):
    """「验证思考强度」：用当前配置发一次最小请求（hi / max_tokens=8），返回结论 JSON。

    三态：
      ok          HTTP 200 → 「支持：HTTP 200（返回含 reasoning_content：是/否）」
      unsupported HTTP 4xx → 「不支持：HTTP 400 · <响应体前 120 字>」
      fail        其余（5xx / 超时 / 网络）→ 「失败：<原因>」
    请求体走 [_req_body]，和真实调用同一处拼：验的就是真会发出去的那个包。
    留空 = 不带该字段（等于平台默认行为），这里照样发，结论由平台回答。
    完整请求体与响应写日志（前缀「思考强度验证：」），界面只显示一行结论。
    """
    import json as _json
    import urllib.error
    import urllib.request

    global _log_path
    if not _log_path and base_dir:
        _log_path = os.path.join(str(base_dir), "local_bot.log")

    def _out(state, line, detail=""):
        log("思考强度验证：%s" % line)
        return _json.dumps({"state": state, "line": line, "detail": detail}, ensure_ascii=False)

    api = str(api or "").strip()
    key = str(key or "").strip()
    model = str(model or "").strip() or "deepseek-chat"
    effort = str(effort or "").strip()
    if not api or not key:
        return _out("fail", "失败：接口地址或 API Key 没填")
    if not api.endswith("/chat/completions"):
        api = api.rstrip("/") + "/chat/completions"

    body_obj = _req_body({"reasoning_effort": effort}, model,
                         [{"role": "user", "content": "hi"}], 8, 0.0)
    body = _json.dumps(body_obj, ensure_ascii=False)
    log("思考强度验证：POST %s 请求体 %s" % (api, body))
    req = urllib.request.Request(api, data=body.encode("utf-8"), method="POST", headers={
        "Content-Type": "application/json",
        "Authorization": "Bearer " + key,
    })
    code = 0
    raw = ""
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            code = int(getattr(r, "status", 200) or 200)
            raw = r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        code = int(getattr(e, "code", 0) or 0)
        try:
            raw = e.read().decode("utf-8", "replace")
        except Exception:
            raw = repr(e)
    except Exception as e:
        reason = getattr(e, "reason", None)
        why = ("%s：%s" % (type(e).__name__, reason)) if reason is not None else str(e)
        return _out("fail", "失败：%s" % (why or type(e).__name__), body)

    log("思考强度验证：HTTP %d 响应 %s" % (code, raw.replace(chr(10), " ")[:2000]))
    head = raw.replace(chr(10), " ").strip()[:120] or "服务端没给原因"
    detail = "请求体：%s\n\nHTTP %d\n响应：%s" % (body, code, raw.strip()[:3000])
    if code == 200:
        try:
            has = "reasoning_content" in _json.dumps(_json.loads(raw), ensure_ascii=False)
        except Exception:
            has = "reasoning_content" in raw
        return _out("ok", "支持：HTTP 200（返回含 reasoning_content：%s）" % ("是" if has else "否"), detail)
    if 400 <= code < 500:
        return _out("unsupported", "不支持：HTTP %d · %s" % (code, head), detail)
    return _out("fail", "失败：HTTP %d · %s" % (code, head), detail)


async def _vision_suffix(appid, conf, images):
    """这一条消息里的图 → 要拼进上下文的一段话；读不出来就返回空串（按纯文本走）。

    多图只识第一张：一条消息里连发几张时，后面的只记日志不送模型（省一次调用，
    也不让上下文被同一张桌子的不同角度占满）。
    """
    if len(images) > 1:
        log("识图：这一条有 %d 张图，只识第一张，其余忽略：%s" % (
            len(images),
            ", ".join((x.get("filename") or "图片") for x in images[1:4])[:80],
        ), appid)
    got = []
    for a in images[:1]:
        part = await asyncio.to_thread(
            _image_data_url, a.get("url"), appid, a.get("filename") or ""
        )
        if part:
            got.append(part)
    if not got:
        return ""
    descs = []
    for data_url, nbytes, name in got:
        t0 = time.time()
        desc = await asyncio.to_thread(_describe_image, conf, data_url, appid, name, nbytes)
        if desc:
            log("识图成功（%.1fs）：%s" % (time.time() - t0, desc[:60]), appid)
            descs.append(desc)
    if not descs:
        return ""
    return chr(10) + "（图里是：" + "；".join(descs) + "）"


def _persona(botdir):
    """人格正文：优先 bot 目录下的 persona.txt（Kotlin 侧选中时写好的那份），
    没有就按 persona_name.txt 去 files/personas/ 找 —— 自定义人格（导入的 md/txt）
    就落在那里，名字可能带「自定义：」前缀。"""
    try:
        with open(os.path.join(botdir, "persona.txt"), encoding="utf-8") as f:
            text = f.read().strip()
        if text:
            return text
    except Exception:
        pass
    try:
        with open(os.path.join(botdir, "persona_name.txt"), encoding="utf-8") as f:
            name = f.read().strip().replace("自定义：", "").strip()
        if name:
            # botdir = <files>/localbot/<appid>；自定义人格在 <files>/personas/
            files_dir = os.path.dirname(os.path.dirname(os.path.abspath(botdir)))
            for base in (files_dir, os.path.dirname(os.path.abspath(botdir))):
                path = os.path.join(base, "personas", name + ".txt")
                if os.path.isfile(path):
                    with open(path, encoding="utf-8") as f:
                        return f.read().strip()
    except Exception:
        pass
    return ""


# 群聊回复的硬规则：拼在人格之后（人格为空时也拼），优先级高于人格原文。
# 为什么必须有：人格只写了「你是谁」，没写「不许怎么说话」——模型于是按助手套路
# 回大段技术回答，还列 1./2./3. 让用户选（见群聊截图）。
_CHAT_RULES = (
    chr(10) + chr(10) + chr(10).join([
        '',
        '【群聊说话规则（硬性，优先于以上任何设定）】',
        '1. 你是在群里以角色身份说话的人，不是助手、不是文档：不用 Markdown、不用代码块、不用反引号、不列编号清单、不做小标题。',
        '2. 不谈论也不输出文件路径、目录名、变量名、JSON、配置项、程序实现、日志、API；被问到就按角色搪塞一句（例如：这个我不懂，你问别人吧）。',
        '3. 每次回复 1~3 句、不超过 120 字；不要出现「我能帮你做什么」「请选择」这类引导语。',
        '4. 不复述用户原话，不说自己是一个 AI。',
    ])
)


# ── 称呼 / 随机问候（每个 bot 各自一份）──────────────────
#
# 人格不同，称呼与问候就不能全局共享，所以两份文件都落在 bot 自己的目录：
#   <botdir>/phrases.json    {"aliases": {"master": "怎么叫主人", "others": "怎么叫其他人"}}
#   <botdir>/greetings.json  {"preset": true|false, "enabled": false,
#                             "slots": [{"name": "早上", "at": "08:00", "window_min": 30,
#                                        "lines": ["…", "…"]}]}
#   <botdir>/greeted.json    {"date": "10-30", "sent": ["早上", "晚上"]}   当天已发的时段
#   <botdir>/owner.json      主人 openid（第一个私聊本 bot 的人，旧版就有）
#
# 口癖（旧 phrases.json 的 tics）整体下线：运行时不再读、不再往 prompt 里塞，
# 想要口癖就写进人格正文。问候也不再是首条消息的开场白：到了某个时段的 at，
# 在 window_min 分钟内**随机挑一个秒**主动私聊发一条，内容只从该时段自己的 lines 里抽。
# preset=true 是内置人格：时段与内容都由 _PRESET_GREETINGS 按人格名给出，用户改不了；
# preset=false 是自定义人格：slots 由页面写。enabled 默认 true、targets 默认主人+所有已知群，
# 页面上可以关；slots 空就不发。


def _bot_root(botdir):
    """文案文件所在的 bot 目录；botdir 空时退回 _base_dir（只有测试/兜底会这样）。"""
    if botdir:
        return os.path.abspath(botdir)
    return _base_dir or os.getcwd()


def _bot_words_paths(botdir):
    """(phrases.json, greetings.json) —— 都在 bot 自己目录下。"""
    root = _bot_root(botdir)
    return (os.path.join(root, "phrases.json"), os.path.join(root, "greetings.json"))


def _str_list(raw):
    out = []
    if isinstance(raw, list):
        for x in raw:
            s = str(x or "").strip()
            if s:
                out.append(s)
    return out


def _persona_name(botdir):
    """persona_name.txt 里的角色名（去掉「自定义：」前缀与 -完整/-精华/-精简 后缀）。"""
    try:
        with open(os.path.join(_bot_root(botdir), "persona_name.txt"), encoding="utf-8") as f:
            name = f.read().strip().replace("自定义：", "").strip()
    except Exception:
        return ""
    for suf in ("-完整", "-精华", "-精简"):
        if name.endswith(suf):
            return name[: -len(suf)]
    return name


# 内置预设人格的固定问候：名字键 = assets/personas 档名去掉后缀。
# 三个时段（早上 08:00 / 午间 12:00 / 深夜 22:00），每个时段 2~3 条，口吻照各角色的
# 表达 DNA 写（参考 DGP-Studio/Genshin.Skill，MIT；只借鉴语气，不照抄原句）。
# 鲸鱼娘 / 猫娘 没有对应 SKILL，按各自人格正文的语气写。
_PRESET_GREETINGS = {
    "哥伦比娅": [
        {"name": "早上", "at": "08:00", "window_min": 60, "lines": [
            "唔…早上。月亮刚躲起来，你就醒了。",
            "嗯…早安。窗外的光好薄，像还没睡醒。",
        ]},
        {"name": "午间", "at": "12:00", "window_min": 60, "lines": [
            "中午了。…你吃饭了吗？我问得有点晚吧。",
            "唔，正午的太阳太亮了。要不要找个有阴影的地方待一会儿。",
        ]},
        {"name": "深夜", "at": "22:00", "window_min": 60, "lines": [
            "夜深了呢。月亮出来了，我在这儿。",
            "还不睡吗？…那我陪你坐一会儿。",
            "嗯…夜里的风在唱歌，你听得到吗。",
        ]},
    ],
    "桑多涅": [
        {"name": "早上", "at": "08:00", "window_min": 60, "lines": [
            "哼，起来了？…茶在那儿。",
            "早。今天的算式我算好了，你那份自己算。",
        ]},
        {"name": "午间", "at": "12:00", "window_min": 60, "lines": [
            "午间了。吃饭，别拖。",
            "啧，这个点了还不吃？先把手里的东西放下。",
        ]},
        {"name": "深夜", "at": "22:00", "window_min": 60, "lines": [
            "半夜了还不睡？…烦人。",
            "哼，熬夜。随你，但别说我没提醒过。",
            "夜里效率高？我不信这一套。去睡。",
        ]},
    ],
    "奥黛塔": [
        {"name": "早上", "at": "08:00", "window_min": 60, "lines": [
            "定点。早上的功课从足尖开始。",
            "早安。霜还没散，先喝点热的。",
        ]},
        {"name": "午间", "at": "12:00", "window_min": 60, "lines": [
            "午间。休息也是功课的一部分。",
            "该进食了。别把身体的账记到明天。",
        ]},
        {"name": "深夜", "at": "22:00", "window_min": 60, "lines": [
            "夜里了。帷幕该落。",
            "该睡了。明天的定点不会等你。",
            "深夜的静，是用来睡的，不是用来想的。",
        ]},
    ],
    "阿蕾奇诺": [
        {"name": "早上", "at": "08:00", "window_min": 60, "lines": [
            "早安。今天的代价，最好由你自己先算清楚。",
            "该起了。孩子们已经在院子里——你不必学他们，但要起身。",
        ]},
        {"name": "午间", "at": "12:00", "window_min": 60, "lines": [
            "午间。填饱自己，这也是纪律的一部分。",
            "吃过了吗。别让饥饿替你做出判断。",
        ]},
        {"name": "深夜", "at": "22:00", "window_min": 60, "lines": [
            "这么晚还不睡。夜里的决定通常最贵。",
            "该歇了。明天的事，明天再付代价。",
            "深夜适合清算，不适合许愿。去睡。",
        ]},
    ],
    "沃雅妮莎": [
        {"name": "早上", "at": "08:00", "window_min": 60, "lines": [
            "喔，早呀！我先吊了两句嗓子，你慢慢起，嘻嘻嘻。",
            "早安哇。水边的风把人吹醒，比闹钟好听多了。",
        ]},
        {"name": "午间", "at": "12:00", "window_min": 60, "lines": [
            "中午啦？我唱到饿了，一起去吃点东西呗。",
            "午间休息喔。你吃饭，我清嗓子，各干各的。",
        ]},
        {"name": "深夜", "at": "22:00", "window_min": 60, "lines": [
            "夜里好唱呢…啊，不过嘛，你该睡了。",
            "这个点还醒着？喔，那我给你唱一段催眠的。",
            "咕嘟咕嘟…深夜的水声最软，去睡吧。",
        ]},
    ],
    "鲸鱼娘": [
        {"name": "早上", "at": "08:00", "window_min": 60, "lines": [
            "早上好呀，本鲸鱼娘醒了一会儿了，就是不太想动。",
            "早！主人今天也要好好吃饭喔。",
        ]},
        {"name": "午间", "at": "12:00", "window_min": 60, "lines": [
            "中午了呀。本鱼去盛饭，你也要吃。",
            "午间，先歇一会儿吧，活儿等会儿再干。",
        ]},
        {"name": "深夜", "at": "22:00", "window_min": 60, "lines": [
            "这么晚还不睡？本鲸鱼娘要关灯了哦。",
            "深夜了，明天的事明天再说，好不好。",
        ]},
    ],
    "猫娘": [
        {"name": "早上", "at": "08:00", "window_min": 60, "lines": [
            "喵~ 早上好，我刚在窗边晒了一会儿太阳。",
            "醒啦？那我也起来了。",
        ]},
        {"name": "午间", "at": "12:00", "window_min": 60, "lines": [
            "中午了喵。吃饭吗？我等着。",
            "午间休息一下嘛，别一直忙。",
        ]},
        {"name": "深夜", "at": "22:00", "window_min": 60, "lines": [
            "喵…这么晚了，你不困吗？",
            "夜深了，该睡了，我陪你一会儿。",
        ]},
    ],
}


def _aliases(botdir):
    """{"master": …, "others": …}；文件缺失/解析失败/结构不对一律当空。"""
    d = _load_json(_bot_words_paths(botdir)[0], {})
    al = d.get("aliases") if isinstance(d, dict) else None
    out = {}
    if isinstance(al, dict):
        for k in ("master", "others"):
            v = str(al.get(k) or "").strip()
            if v:
                out[k] = v
    return out


def _aliases_sentence(a):
    """「称呼主人为 X；称呼其他人 Y」；两样都没配返回空串。"""
    parts = []
    if a.get("master"):
        parts.append("称呼主人为 %s" % a["master"])
    if a.get("others"):
        parts.append("称呼其他人 %s" % a["others"])
    return "；".join(parts)


_HM_RE = re.compile(r"^(\d{1,2}):(\d{2})$")


def _hm_minutes(s):
    """"08:30" → 510；解析不出来返回 None。"""
    m = _HM_RE.match(str(s or "").strip())
    if not m:
        return None
    h, mi = int(m.group(1)), int(m.group(2))
    if 0 <= h <= 23 and 0 <= mi <= 59:
        return h * 60 + mi
    return None


def _norm_hm(s):
    """"8:30" → "08:30"；解析不出来返回空串（坏数据当没配）。"""
    n = _hm_minutes(s)
    if n is None:
        return ""
    return "%02d:%02d" % (n // 60, n % 60)


def _norm_slot(raw):
    """一条时段配置 → {"name","at","window_min","lines","disabled"}；解析不出来返回 None。

    坏数据（没 at / at 写错 / 没有一句问候）就当这条时段不存在，不让它把调度卡住。
    "disabled": true 的时段照样留在表里（页面要能把它打开），但调度时跳过 —— 关着的
    时段没有 lines 也算合法，不然「关掉一个还没写内容的时段」会直接消失。
    """
    if not isinstance(raw, dict):
        return None
    at = _norm_hm(raw.get("at"))
    if not at:
        return None
    try:
        win = int(float(str(raw.get("window_min") or 30)))
    except Exception:
        win = 30
    win = max(1, min(win, 24 * 60))
    disabled = bool(raw.get("disabled"))
    lines = _str_list(raw.get("lines"))
    if not lines and not disabled:
        return None
    name = str(raw.get("name") or "").strip() or at
    return {"name": name, "at": at, "window_min": win, "lines": lines,
            "disabled": disabled}


def _greet_targets(d):
    """发到哪：{"owner": bool, "groups": bool}。默认两个都开（群友嫌吵会自己开免打扰）。"""
    out = {"owner": True, "groups": True}
    t = d.get("targets") if isinstance(d, dict) else None
    if isinstance(t, dict):
        if "owner" in t:
            out["owner"] = bool(t.get("owner"))
        if "groups" in t:
            out["groups"] = bool(t.get("groups"))
    return out


def _greetings_conf(botdir):
    """本 bot 的问候配置 {"preset", "enabled", "slots"}。

    内置人格（名字命中 _PRESET_GREETINGS）：**内容锁死**，lines 一律取代码里的；
    文件里同名时段只能改「什么时候发」（at / window_min），名字对不上的一律忽略。
    其它情况（自定义人格）整份按文件来，preset 字段说了不算。
    enabled / targets 一律看文件，默认开（主人私聊 + 所有已知群），页面可关。
    """
    d = _load_json(_bot_words_paths(botdir)[1], {})
    if not isinstance(d, dict):
        d = {}
    fixed = _PRESET_GREETINGS.get(_persona_name(botdir))
    if fixed:
        # 预设人格的时段：文件里能改 at / window_min / disabled（内容仍从代码来，
        # 所以文件里的 lines 一律为空、也被忽略）。
        by_name = {}
        raw = d.get("slots")
        if isinstance(raw, list):
            for x in raw:
                if not isinstance(x, dict):
                    continue
                name = str(x.get("name") or "").strip()
                at = _norm_hm(x.get("at"))
                if not name or not at:
                    continue
                try:
                    win = int(float(str(x.get("window_min") or 30)))
                except Exception:
                    win = 30
                by_name[name] = {"at": at, "window_min": max(1, min(win, 24 * 60)),
                                 "disabled": bool(x.get("disabled"))}
        out = []
        if not by_name:
            # 文件里一个可用时段都没有（首次迁移 / 新建）：才用代码里的默认三条补齐。
            # 有 by_name 说明用户在页面上动过了 —— 缺的那几条是他删掉的或没配的，不补回。
            for s in fixed:
                out.append({"name": s["name"], "at": s["at"], "window_min": s["window_min"],
                            "lines": list(s["lines"]), "disabled": False})
        else:
            for s in fixed:
                o = by_name.get(s["name"])
                if not o:
                    continue
                out.append({"name": s["name"], "at": o["at"], "window_min": o["window_min"],
                            "lines": list(s["lines"]), "disabled": o["disabled"]})
        return {"preset": True, "enabled": bool(d.get("enabled", True)),
                "targets": _greet_targets(d), "slots": out}
    slots = []
    raw = d.get("slots")
    if isinstance(raw, list):
        for x in raw:
            s = _norm_slot(x)
            if s:
                slots.append(s)
    return {"preset": False, "enabled": bool(d.get("enabled", True)),
            "targets": _greet_targets(d), "slots": slots}


def _owner_openid(botdir):
    """主人私聊的 openid（「主人」就是这么认定的，不猜）。

    先读 <botdir>/owner.json 的 user_openid（第一个私聊本 bot 的人），
    没有再退回 known_chats.json 里第一条 c2c 的 openid；都没有 → 视为无主人。
    """
    root = _bot_root(botdir)
    d = _load_json(os.path.join(root, "owner.json"), {})
    if isinstance(d, dict):
        for k in ("user_openid", "member_openid"):
            v = str(d.get(k) or "").strip()
            if v:
                return v
    kc = _load_json(os.path.join(root, "known_chats.json"), {})
    chats = kc.get("chats") if isinstance(kc, dict) else None
    if isinstance(chats, list):
        for c in chats:
            if isinstance(c, dict) and str(c.get("kind") or "") == "c2c":
                oid = str(c.get("openid") or "").strip()
                if oid:
                    return oid
    return ""


def _oid8(s):
    """openid 只露前 8 位：日志里够定位，又不会把整串写出去。"""
    s = str(s or "")
    return s[:8] if s else ""


def _is_master_key(botdir, key):
    """这个会话是不是主人私聊：私聊的会话键是 "p" + 主人 openid。"""
    owner = _owner_openid(botdir)
    return bool(owner) and str(key or "") == "p" + owner


def _greeted_path(botdir):
    return os.path.join(_bot_root(botdir), "greeted.json")


def _greet_sent(botdir, date_s):
    """这一天已经发过的「时段|目标」键集合；日期变了就当没发过（跨天自然可再发）。"""
    d = _load_json(_greeted_path(botdir), {})
    if not isinstance(d, dict) or str(d.get("date") or "") != date_s:
        return set()
    return set(_str_list(d.get("sent")))


def _greet_mark(botdir, date_s, entry):
    """落盘「这个时段这一格今天发过了」。跨天时旧记录直接作废，文件不会一直长。"""
    path = _greeted_path(botdir)
    sent = _greet_sent(botdir, date_s)
    sent.add(entry)
    return _save_json(path, {"date": date_s, "sent": sorted(sent)})


def _slot_minutes(slot):
    return _hm_minutes(slot["at"])


def _in_slot(slot, minutes):
    """此刻是否落在 [at, at+window_min) 里（支持跨零点，22:00+120min）。"""
    start = _slot_minutes(slot)
    if start is None:
        return False
    return ((minutes - start) % 1440) < slot["window_min"]


def _window_label(slot):
    """窗口文案「08:00-09:00」（at 到 at+window_min，跨零点照算）。"""
    start = _slot_minutes(slot)
    end = (start + slot["window_min"]) % 1440
    return "%s-%02d:%02d" % (slot["at"], end // 60, end % 60)


def _slot_plan(botdir, date_s, slot, appid=""):
    """这个时段今天的随机触发时刻（epoch 秒）+ 当天取词的随机源。

    在 [at, at+window_min) 里挑一个随机（分钟+秒）；种子是 (appid, 日期, 时段名)：
    同一天同一 bot 重启还是同一刻，不同 bot 之间天然错开。
    """
    start = _slot_minutes(slot)
    seed = "%s|%s|%s" % (appid or os.path.basename(_bot_root(botdir)), date_s, slot["name"])
    rng = random.Random(seed)
    offset = rng.randrange(0, slot["window_min"] * 60)
    y, m, d = [int(x) for x in date_s.split("-")]
    minute = (start + offset // 60) % 1440
    plan = time.mktime((y, m, d, minute // 60, minute % 60, offset % 60, 0, 0, -1))
    return plan, rng


# 跳过原因每天只说一次：30 秒一轮的循环，不然日志页会被同一句刷满。
_greet_skip_logged = {}


def _greet_skip_once(botdir, date_s, key, msg, appid=""):
    k = "%s|%s|%s" % (_bot_root(botdir), date_s, key)
    with _lock:
        if k in _greet_skip_logged:
            return
        if len(_greet_skip_logged) > 240:
            _greet_skip_logged.clear()
        _greet_skip_logged[k] = True
    log(msg, appid)


def _date_and_minutes(now=None):
    t = time.localtime(time.time() if now is None else float(now))
    return time.strftime("%Y-%m-%d", t), t.tm_hour * 60 + t.tm_min


# ── 当前时间（bot 要知道「现在几点」）────────────────────
_WEEKDAYS = ("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")


def _now_text(ts=None):
    """本地时间一行：「2026-10-30 星期五 08:37」。prompt 与本地指令共用。"""
    t = time.localtime(time.time() if ts is None else float(ts))
    return "%s %s %s" % (time.strftime("%Y-%m-%d", t), _WEEKDAYS[t.tm_wday],
                         time.strftime("%H:%M", t))


# 本地指令：不走模型，直接回时间。群里/私聊都认这几种写法。
_TIME_CMDS = ("#时间", "#time", "现在几点", "几点了", "几点啦", "现在时间", "报时")


def _is_time_cmd(text):
    t = re.sub(r"[\s？?。！!~～,，、:：]+", "", str(text or "")).lower()
    return t in _TIME_CMDS


async def _local_time_reply(appid, message, text, key="", group_openid=""):
    """本地指令（#时间 / 现在几点 / 几点了…）：不经过模型，直接回本地时间。

    返回 True = 这条已经回了，调用方直接 return。key 以 "p" 开头走私聊，
    否则按群回（用传进来的 group_openid）。写日志便于排查「它怎么秒回的」。
    """
    if not _is_time_cmd(text):
        return False
    now_s = _now_text()
    log("本地指令 #时间：直接回 %s（不走模型）" % now_s, appid)
    api = getattr(message, "_api", None)
    try:
        if str(key or "").startswith("p"):
            await api.post_c2c_message(
                openid=getattr(message.author, "user_openid", ""), msg_type=0,
                msg_id=getattr(message, "id", ""), content=now_s)
        else:
            await api.post_group_message(
                group_openid=group_openid, msg_type=0,
                msg_id=getattr(message, "id", ""), content=now_s)
    except Exception as e:
        log("回消息失败（本地指令）：%r" % e, appid)
    return True


async def _maybe_greet(client, appid, botdir, now=None):
    """随机问候：时段 at 到点后，在 [at, at+window_min) 内随机一刻，按 targets 主动发。

    返回发出去的时段名（没发返回空串）。目标由 greetings.json 的
    targets={"owner":true,"groups":false} 决定：主人私聊 / 所有已知群（逐群 0.6s 间隔）。
    每个「时段|目标」当天只发一次（greeted.json）；enabled=false 一律不发；群发逐群 0.6s。
    由 _sched_loop 每 30 秒调一次，不另起线程。
    """
    ts = time.time() if now is None else float(now)
    date_s, minutes = _date_and_minutes(ts)
    cfg = _greetings_conf(botdir)
    if not cfg.get("enabled"):
        _greet_skip_once(botdir, date_s, "off", "随机问候跳过：未开启", appid)
        return ""
    if not cfg["slots"]:
        _greet_skip_once(botdir, date_s, "noslot", "随机问候跳过：未配置时段", appid)
        return ""
    tgt = cfg.get("targets") or {"owner": True, "groups": True}
    if not tgt.get("owner") and not tgt.get("groups"):
        _greet_skip_once(botdir, date_s, "notarget", "随机问候跳过：没有配置发送目标", appid)
        return ""
    sent = _greet_sent(botdir, date_s)
    owner = _owner_openid(botdir) if tgt.get("owner") else ""
    for slot in cfg["slots"]:
        if slot.get("disabled"):
            # 页面行首开关关掉的时段：留在配置里（随时能开回来），但今天不调度
            _greet_skip_once(botdir, date_s, "off:" + slot["name"],
                             "随机问候跳过：时段 %s 已关闭" % slot["name"], appid)
            continue
        if not _in_slot(slot, minutes):
            continue
        label = _window_label(slot)
        plan, rng = _slot_plan(botdir, date_s, slot, appid)
        if ts < plan:
            _greet_skip_once(botdir, date_s, "wait:" + slot["name"],
                             "随机问候：触发时刻 %s（时段 %s｜窗口 %s）" % (
                                 time.strftime("%H:%M", time.localtime(plan)),
                                 slot["name"], label), appid)
            return ""
        line = slot["lines"][rng.randrange(0, len(slot["lines"]))]
        log("随机问候：开始发送（时段 %s｜窗口 %s｜%s）" % (slot["name"], label, line[:20]), appid)
        did = ""
        if tgt.get("owner"):
            key = slot["name"] + "|owner"
            if key in sent:
                _greet_skip_once(botdir, date_s, "done:" + key,
                                 "随机问候跳过：%s 今天已经发过（时段 %s）" % ("主人私聊", slot["name"]), appid)
            elif not owner:
                _greet_skip_once(botdir, date_s, "noowner", "随机问候跳过：没有主人 openid", appid)
            else:
                try:
                    await client.api.post_c2c_message(openid=owner, msg_type=0, content=line)
                    _greet_mark(botdir, date_s, key)
                    log("随机问候：发主人私聊 成功（openid 前 8 位 %s｜时段 %s｜窗口 %s）" % (
                        _oid8(owner), slot["name"], label), appid)
                    did = slot["name"]
                except Exception as e:
                    log("随机问候：发主人私聊 失败：%r" % e, appid)
        if tgt.get("groups"):
            groups = _known_groups(botdir)
            if not groups:
                _greet_skip_once(botdir, date_s, "nogroup", "随机问候跳过：没有已知群", appid)
            for gid in groups:
                key = slot["name"] + "|group:" + gid
                if key in sent:
                    continue
                if not _group_conf(botdir, gid)["enabled"]:
                    log("随机问候跳过：群 %s 已静音（groups/%s.json enabled=false）"
                        % (_oid8(gid), _oid8(gid)), appid)
                    continue
                try:
                    await client.api.post_group_message(group_openid=gid, msg_type=0, content=line)
                    _greet_mark(botdir, date_s, key)
                    log("随机问候：发群 %s… 成功（时段 %s｜窗口 %s）" % (
                        _oid8(gid), slot["name"], label), appid)
                    did = did or slot["name"]
                except Exception as e:
                    log("随机问候：发群 %s… 失败：%r" % (_oid8(gid), e), appid)
                await asyncio.sleep(_GROUP_SEND_GAP)
        if did:
            return did
    return ""


# 旧全局 aliases 的键怎么归并到新格式的两个槽位：键里带这些字样就算「主人」
_MASTER_WORDS = ("主人", "自己", "我", "master", "owner")


def _migrate_words(botdir, appid=""):
    """把旧全局文案搬进 bot 自己的目录（每个 bot 一份，幂等）。

    · 旧 <files>/phrases.json 的 aliases：键里带「主人/自己/我」的归 master，其余归 others
      （新格式只认这两个槽位）；旧 tics 整个忽略 —— 口癖下线了。
    · 旧全局 <files>/greetings.json（["…"] 数组）、旧 bot 级文件的 "greetings" 数组：
      搬成一条自定义时段「问候」（12:00 + 30min），内置人格不带（它的问候是代码里定死的）。
    · 只补缺的键：<botdir>/greetings.json 里已有的 judge / chat_prob 等键原样留着。
    """
    if not botdir:
        return
    abs_dir = os.path.abspath(botdir)
    # botdir = <files>/localbot/<appid>，往上两级才是 <files>
    files_dir = os.path.dirname(os.path.dirname(abs_dir))
    ph_path, gr_path = _bot_words_paths(abs_dir)
    if not os.path.exists(ph_path):
        aliases = {}
        legacy = _load_json(os.path.join(files_dir, "phrases.json"), {})
        al = legacy.get("aliases") if isinstance(legacy, dict) else None
        if isinstance(al, dict):
            for k, v in al.items():
                vv = str(v or "").strip()
                if not vv:
                    continue
                key = "master" if any(s in str(k) for s in _MASTER_WORDS) else "others"
                aliases.setdefault(key, vv)
        if _save_json(ph_path, {"aliases": aliases}) and aliases:
            log("迁移称呼到本 bot：%s" % _aliases_sentence(aliases), appid)
    fixed = _PRESET_GREETINGS.get(_persona_name(abs_dir))
    raw = _load_json(gr_path, None)
    d = dict(raw) if isinstance(raw, dict) else {}
    changed = not isinstance(raw, dict)
    if "slots" not in d:
        legacy_lines = _str_list(_load_json(os.path.join(files_dir, "greetings.json"), []))
        if not legacy_lines:
            legacy_lines = _str_list(d.get("greetings"))
        d["slots"] = [] if (fixed or not legacy_lines) else [
            {"name": "问候", "at": "12:00", "window_min": 30, "lines": legacy_lines}
        ]
        changed = True
    if not isinstance(d.get("targets"), dict):
        d["targets"] = {"owner": True, "groups": True}
        changed = True
    if "enabled" not in d:
        # 内置人格装好即按时段错开发；自定义人格先关着，等用户在页面上配好再开
        d["enabled"] = bool(fixed)
        changed = True
    if fixed and not d.get("preset"):
        d["preset"] = True
        changed = True
    elif not fixed and "preset" not in d:
        d["preset"] = False
        changed = True
    if changed:
        _save_json(gr_path, d)
        log("问候配置归位：%s｜%s｜时段 %s｜%d 个" % (
            "内置（固定）" if fixed else "自定义",
            "已开启" if d.get("enabled") else "未开启（默认）",
            "、".join("%s %s+%dmin" % (s["name"], s["at"], s["window_min"])
                      for s in (fixed or _greetings_conf(abs_dir)["slots"])) or "未配置",
            len(fixed or d.get("slots") or [])), appid)


def _words_rules(botdir, key=""):
    """人格之后追加的称呼规则，按对话对象只给一个：主人 → master，其它 → others。

    口癖（旧 tics）已下线，这里不再注入；问候也不再塞进 prompt（改成时段随机私聊）。
    该场景没配（空串）就不加规则 —— 不拿默认词硬塞。
    """
    a = _aliases(botdir)
    if _is_master_key(botdir, key):
        log("称呼：对象=主人（openid 前 8 位 %s）" % _oid8(_owner_openid(botdir)))
        s = ("称呼主人为 %s" % a["master"]) if a.get("master") else ""
    else:
        who = str(key or "")
        peer = who[1:] if who[:1] in ("p", "g", "c") else ""
        log("称呼：对象=其他人" + (("（openid 前 8 位 %s）" % _oid8(peer)) if peer else ""))
        s = ("称呼其他人 %s" % a["others"]) if a.get("others") else ""
    if not s:
        return ""
    log("注入称呼：%s。" % s)
    return chr(10) + chr(10) + s + "。"


# ── 上下文压缩（只有达到窗口 75% 才压一次）────────────────────
#
# 估算口径（写清楚：这是**估算**，不追求与平台真正的 tokenizer 一致）：
#   · 中文按 字数 / 1.5
#   · 英文及其它字符按 字符数 / 4
#   · 两者相加取整
# 触发条件有两条，必须同时满足（第 2 条是成本闸）：
#   ① estimated_tokens(system + 历史) >= 0.75 * context_window；
#   ② 自上次压缩以来攒够的新内容 >= max(窗口 15%, 已并入摘要内容的一半)。
# ② 为什么不省：历史不会因为压缩而变短，只按 ① 判断的话越过阈值之后**每来一条消息
# 都会再发一次摘要请求**，而每次只并进 1~2 行 —— 那是持续烧钱。② 让摘要请求次数变成
# O(log 历史) 而不是 O(消息数)。
# ③ 采纳闸：摘要生成后**先算账**，压缩后（system + 新摘要 + 最近 12 条）必须
#    小于压缩前（system + 旧摘要 + 全部历史）的 90% 才落盘。历史很短时摘要会把
#    上下文撑大（实测 5805 → 5847 tokens），那种压缩纯属白花钱，直接放弃；放弃也记
#    一次 last_try，同一 key 10 分钟内不再重试。
# 压缩一次之后**本次回复**不再重复压（_maybe_compress 在 _reply 里只被调用一次）。
# 压缩产出的摘要落盘在 <botdir>/summary/<key>.json = {summary, covered, updated_at}，
# 于是 _build_messages 变成：system +（此前对话摘要：…）+ 最近 12 条原文。

_SUMMARY_KEEP = 12          # 压缩后仍保留的最近原文条数（_build_messages 也用它）
_SUMMARY_MAX_CHARS = 600    # 摘要字数上限
_CTX_RATIO = 0.75           # 阈值：窗口的 75%
_RESUME_RATIO = 0.15        # 两次压缩之间，新内容至少要占窗口的 15%
_ACCEPT_RATIO = 0.9         # 采纳闸：压缩后必须 < 压缩前的 90%，否则这次白花钱，放弃
_RETRY_GAP = 600            # 放弃后同一 key 的重试间隔（秒）：10 分钟内不再花第二次钱

# 最近一次尝试压缩的时间：{(botdir, key): ts}。只在内存里 —— 放弃时不写盘，
# 所以 summary 文件与 state 都不会因为一次无效压缩而变脏，重启进程后这张表自然清空。
_compress_try = {}


def _est_tokens(text):
    """粗估一段文本的 token 数：中文 字数/1.5 + 其它 字符数/4（只用于判断要不要压缩）。"""
    s = str(text or "")
    if not s:
        return 0
    cjk = 0
    for ch in s:
        if "\u4e00" <= ch <= "\u9fff":
            cjk += 1
    other = len(s) - cjk
    return int(cjk / 1.5 + other / 4.0 + 0.5)


def _ctx_window(base_dir=""):
    """context_window：落盘在 llm.json，默认 131072（单位 token，估算阈值基数）。"""
    c = _load_json(os.path.join(base_dir or _base_dir, "llm.json"), {}) or {}
    try:
        w = int(c.get("context_window") or _DEFAULT_WINDOW)
    except Exception:
        w = _DEFAULT_WINDOW
    return w if w > 0 else _DEFAULT_WINDOW


def _summary_path(botdir, key):
    """<botdir>/summary/<key>.json；key 里的非字母数字折成 _，别让文件名出花样。"""
    safe = re.sub(r"[^0-9A-Za-z_.-]", "_", str(key or "")) or "default"
    return os.path.join(botdir, "summary", safe + ".json")


def _summary_of(botdir, key):
    d = _load_json(_summary_path(botdir, key), {})
    return d if isinstance(d, dict) else {}


def _rows_text(rows):
    """记忆行 → 摘要器的输入文本（只取角色和正文，时间戳/uid 不喂模型）。"""
    out = []
    for r in rows:
        try:
            role, content = r[0], r[1]
        except Exception:
            continue
        if isinstance(content, str) and content.strip():
            out.append("%s：%s" % ("对方" if role == "user" else "我", content.strip()))
    return chr(10).join(out)


async def _maybe_compress(botdir, key, appid=""):
    """超阈值就把最旧约 2/3 历史交给摘要模型，返回 True = 这次真的压了。

    失败一律不打断回复：写日志、返回 False，调用方照旧走原逻辑（纯最近 12 条）。
    同一个 key 的摘要增量更新：把旧摘要一并交给模型，要求合并成一条新摘要。
    """
    if not botdir or not key:
        return False
    try:
        # 上次尝试（含「压了没变小所以放弃」）在 10 分钟内：不再花第二次摘要请求
        last = _compress_try.get((botdir, key)) or 0
        if last and time.time() - last < _RETRY_GAP:
            log("上下文压缩跳过：key=%s 上次尝试在 %d 秒前（%d 分钟内不再重试）→ 本次按原逻辑发最近 %d 条"
                % (key, int(time.time() - last), _RETRY_GAP // 60, _SUMMARY_KEEP), appid)
            return False
        rows = _memory(botdir).get(key) or []
        window = _ctx_window()
        threshold = int(window * _CTX_RATIO)
        sys_text = _system_prompt(botdir, key)
        est = _est_tokens(sys_text + chr(10) + _rows_text(rows))
        if est < threshold:
            # 快到阈值时留一行（方便验收「没到 75% 不压缩」），平时不刷日志
            if est * 2 >= threshold:
                log("上下文未达阈值：key=%s 约 %d tokens（阈值 %d，窗口 %d）→ 不压缩"
                    % (key, est, threshold, window), appid)
            return False
        n = len(rows)
        k = max(1, int(n * 2 / 3))
        old = _summary_of(botdir, key)
        start = int(old.get("covered") or 0)
        if start < 0 or start > k:
            start = 0          # 历史被裁剪过（>400 条时删掉最旧 100 条）→ 从头并
        chunk = rows[start:k]
        if not chunk:
            log("上下文压缩跳过：key=%s 已覆盖到第 %d 条，没有新增内容可合并" % (key, start), appid)
            return False
        # 成本闸（触发条件②）：攒够新内容才允许再压一次，门槛取两者较大者
        if start:
            pending_est = _est_tokens(_rows_text(chunk))
            need_est = max(int(window * _RESUME_RATIO),
                           _est_tokens(_rows_text(rows[:start])) // 2)
            if pending_est < need_est:
                log("上下文压缩跳过：key=%s 新增约 %d tokens < 门槛 %d"
                    "（窗口 %d，上次已并 %d 条）→ 不重复压缩"
                    % (key, pending_est, need_est, window, start), appid)
                return False
        sconf = _conf_for("summary")
        if not sconf:
            log("上下文压缩跳过：模型配置没填（llm.json 缺 key / 地址）", appid)
            return False
        old_sum = str(old.get("summary") or "").strip()
        prompt = (
            "把下面这段聊天记录压成一段中文摘要，最多 %d 字。\n"
            "只保留：聊了什么话题、对方的身份/偏好/重要事实、我答应过或说过的事。\n"
            "不要评价，不要分点，不要加标题，直接给摘要正文。\n%s\n"
            "【新增对话】\n%s"
        ) % (_SUMMARY_MAX_CHARS,
             ("【已有摘要（请与新增对话合并成一段，不要重复过去已经写过的话）】\n" + old_sum + "\n")
             if old_sum else "",
             _rows_text(chunk)[:12000])
        t0 = time.time()
        try:
            new_sum = await asyncio.to_thread(
                _call_llm_sync, sconf, [{"role": "user", "content": prompt}], 900, 0.2,
            )
        except Exception as e:
            log("上下文压缩失败：%r（按原逻辑继续，不压缩）" % e, appid)
            return False
        new_sum = str(new_sum or "").strip().replace(chr(10), " ")
        if not new_sum:
            log("上下文压缩失败：摘要模型 %s 没返回内容（按原逻辑继续）" % sconf["model"], appid)
            return False
        new_sum = new_sum[:_SUMMARY_MAX_CHARS]
        # 采纳闸：压完必须真的变小才落盘 —— 摘要把上下文撑大（历史短 / 模型话多）
        # 时这次压缩纯属白花钱，宁可不压。比较的是「发出去的那份」：
        #   压缩前 = system + 旧摘要 + 全部历史（不压就会这样发）
        #   压缩后 = system + 新摘要 + 最近 12 条原文
        before = _est_tokens(sys_text)
        if old_sum:
            before += _est_tokens("（此前对话摘要：%s）" % old_sum)
        before += _est_tokens(_rows_text(rows))
        keep = rows[-_SUMMARY_KEEP:]
        after = (_est_tokens(sys_text)
                 + _est_tokens("（此前对话摘要：%s）" % new_sum)
                 + _est_tokens(_rows_text(keep)))
        if after >= int(before * _ACCEPT_RATIO):
            # 放弃才记 last_try：采纳的那次由「成本闸 ②」自然节流，不必封 10 分钟
            _compress_try[(botdir, key)] = time.time()
            log("上下文压缩无效：压缩后 %d tokens >= 压缩前 %d tokens 的 %d%%，本次放弃"
                "（不落盘摘要、不写 state，%d 分钟内不再重试）"
                % (after, before, int(_ACCEPT_RATIO * 100), _RETRY_GAP // 60), appid)
            return False
        path = _summary_path(botdir, key)
        try:
            os.makedirs(os.path.dirname(path), exist_ok=True)
        except Exception:
            pass
        if not _save_json(path, {"summary": new_sum, "covered": k,
                                 "updated_at": int(time.time())}):
            log("上下文压缩失败：摘要写盘失败（按原逻辑继续）", appid)
            return False
        log("上下文压缩：key=%s 原 %d 条/约 %d tokens（阈值 %d）→ 摘要 + 最近 %d 条"
            "（约 %d tokens｜摘要模型 %s｜%.1fs）"
            % (key, n, est, threshold, len(keep), after, sconf["model"], time.time() - t0),
            appid)
        return True
    except Exception as e:
        log("上下文压缩异常：%r（按原逻辑继续）" % e, appid)
        return False


# ── 消息存档（<botdir>/messages.db）──────────────────────
#
# 每个 bot 一个库：QQ 的 openid 按 bot 分配，库跟着 bot 目录走 —— 删 bot 目录
# 就是删它的存档，也不会出现两个 bot 互相往对方库里写。
#
# 表：msg(id INTEGER PRIMARY KEY, chat_type, chat_id, author_openid, author_name,
#          ts, text, reply_text)
# 检索：分级探测设备上的 SQLite，启动日志里写明实际用了哪一档 ——
#   · trigram 分词器（首选）→ fts5(text, reply_text, content='msg', tokenize='trigram')，
#     中文可以按任意子串查（「张伟提」命中「张伟提过档期」），查询走 msg_fts MATCH；
#   · unicode61（退化档：老设备 / 不支持 trigram）→ 中文按整串匹配，检索补一次 LIKE；
#   · 两档都建不出（精简版 SQLite）→ 普通表 + text LIKE + ts 索引，功能不降级（只是慢）。
# 三个触发器（增/删/改）跟着 msg 同步索引；老库启动时自动重建成 trigram 并全量回填。
#
# 写入为什么是「收消息一条 + 回复 UPDATE 同一行」，而不是写第二条：
#   msg 只有一张表，第二条就得靠标记位区分谁说的，导出时还要再拼回去；
#   直接把 reply_text 补到同一行最省事 —— 导出一行就是「作者：原文 ⇢ 回复」，
#   不用额外状态、不用二次关联。收消息先进内存缓冲（20 条 / 5 秒 flush，
#   后台线程落盘），回复路径上那次 UPDATE 也不会阻塞发送；代价是进程被杀时
#   最多丢 5 秒内还没 flush 的几条（可接受，不做 WAL 式的强一致）。
_ARC_KEEP_DAYS = 30            # 保留天数（滚动删除）
_ARC_KEEP_PER_CHAT = 5000      # 每个会话最多留多少条
_ARC_FLUSH_N = 20              # 攒够 20 条立刻落盘
_ARC_FLUSH_SEC = 5.0           # 否则最多等 5 秒
_ARC_RECALL_N = 8              # 召回取最近 8 条
_ARC_HIT_CHARS = 200           # 召回单条截断
_ARC_EXPORT_MAX = 100000       # 导出口数上限（够用又不会把内存撑爆）

_arc_lock = threading.RLock()
_arc_buf = []                  # 待写缓冲：[{botdir, chat_type, chat_id, …, done}]
_arc_last = {}                 # (botdir, chat_type, chat_id) -> 已落盘那条请求行的 id
_arc_db = {}                   # botdir -> {"conn": …, "mode": "FTS5"|"LIKE"}
_arc_trim_ts = {}              # botdir -> 上次滚动清理的时间
_arc_off = ""                  # 非空 = sqlite3 用不了，整块功能静默关闭（不影响收发消息）
_arc_worker = {"on": False}

_ARC_TRIGGERS = (
    ("msg_ai", "AFTER INSERT ON msg BEGIN"
               " INSERT INTO msg_fts(rowid, text, reply_text)"
               " VALUES (new.id, new.text, new.reply_text); END"),
    ("msg_ad", "AFTER DELETE ON msg BEGIN"
               " INSERT INTO msg_fts(msg_fts, rowid, text, reply_text)"
               " VALUES ('delete', old.id, old.text, old.reply_text); END"),
    ("msg_au", "AFTER UPDATE ON msg BEGIN"
               " INSERT INTO msg_fts(msg_fts, rowid, text, reply_text)"
               " VALUES ('delete', old.id, old.text, old.reply_text);"
               " INSERT INTO msg_fts(rowid, text, reply_text)"
               " VALUES (new.id, new.text, new.reply_text); END"),
)

# 召回标记：与 <search> 并列的第二个本地工具，只认一行 <recall>关键词</recall>
_ARC_RECALL_MARK_RE = re.compile(r"<\s*recall\s*>(.*?)<\s*/\s*recall\s*>", re.I | re.S)
_ARC_RECALL_ANY_RE = re.compile(r"<\s*/?\s*recall\s*>", re.I)


def _arc_appid(botdir):
    """库所在目录名就是 appid（日志标签用）。"""
    return os.path.basename(str(botdir or "").rstrip("/"))


def _arc_path(botdir):
    return os.path.join(str(botdir or ""), "messages.db")


def _arc_bytes(path):
    try:
        return int(os.path.getsize(path))
    except Exception:
        return 0


def _arc_probe_fts5(conn):
    """设备 SQLite 到底有没有编译 FTS5？建一个假的虚表再删掉 —— 建不出来就是没有。"""
    conn.execute("CREATE VIRTUAL TABLE IF NOT EXISTS _fts_probe USING fts5(x)")
    conn.execute("DROP TABLE IF EXISTS _fts_probe")
    return True


# FTS5 分词器分级，按顺序探测（写成数据，日志里报实际生效的那一档）：
#   · trigram：三字一段建索引，中文可以用任意子串查（「张伟提」能查到「张伟提过档期」）；
#   · unicode61：SQLite 默认分词器，把一整串中文当一个词 —— 只作退化档；
#   · 两档都建不出来才退普通表 + text LIKE（功能还在，只是全表扫）。
# trigram 查询有个硬限制：关键词长度必须 ≥3 个字符，短词（「张伟」）MATCH 一条都查不到，
# 所以 archive_search 在 MATCH 没命中时仍补一次 LIKE —— 索引是快路，LIKE 是兜底。
_ARC_FTS_SPECS = (("trigram", "trigram"), ("unicode61", "unicode61"))


def _arc_make_fts(conn, tokenize):
    """按分词器建 msg_fts（外部内容表 content='msg'）。建不出来就抛，由调用方试下一档。"""
    conn.execute(
        "CREATE VIRTUAL TABLE msg_fts USING fts5(text, reply_text, content='msg',"
        " content_rowid='id', tokenize='%s')" % tokenize)


def _arc_fts_sql(conn):
    """库里 msg_fts 的建表语句（没这张表就是空串）。升级老库时靠它认出原来的分词器。"""
    try:
        row = conn.execute("SELECT sql FROM sqlite_master WHERE name = 'msg_fts'").fetchone()
    except Exception:
        row = None
    return str((row[0] if row else "") or "")


def _arc_make_triggers(conn):
    """三个触发器（增 / 删 / 改）让 msg_fts 跟着 msg 走。"""
    for name, body in _ARC_TRIGGERS:
        conn.execute("CREATE TRIGGER IF NOT EXISTS %s %s" % (name, body))


def _arc_setup_fts(conn, botdir):
    """建或升级 FTS5 索引 → "trigram" / "unicode61" / "LIKE"，并把用的哪一档写进日志。

    老库（上一版建的是默认 unicode61）在启动时**自动升级**：整张丢掉按 trigram 重建，
    再用 fts5 的 rebuild 命令从 msg 全量回填 —— 用户不用删库，历史消息照样能查。
    """
    appid = _arc_appid(botdir)
    try:
        _arc_probe_fts5(conn)
    except Exception as e:
        log("存档：FTS5 不可用（%r）→ 退回普通表 + text LIKE + ts 索引" % e, appid)
        return "LIKE"
    old = _arc_fts_sql(conn)
    old_mode = ("trigram" if "trigram" in old.lower() else "unicode61") if old else ""
    if old_mode == "trigram":
        _arc_make_triggers(conn)
        conn.commit()
        log("存档：FTS5(trigram) 可用 → %s 走 trigram 全文索引"
            "（msg_fts MATCH，中文可子串查）" % _arc_path(botdir), appid)
        return "trigram"
    if old:
        try:
            conn.execute("DROP TABLE msg_fts")      # 老索引整张丢掉，下面按新分词器重建
            conn.commit()
        except Exception as e:
            log("存档：旧 FTS 表删不掉（%r）→ 本次不建索引，走 LIKE" % e, appid)
            return "LIKE"
    mode = ""
    for tokenize, name in _ARC_FTS_SPECS:
        try:
            _arc_make_fts(conn, tokenize)
            mode = name
            break
        except Exception as e:
            log("存档：FTS5(%s) 建不出来（%r）→ 试下一档" % (name, e), appid)
            try:
                conn.execute("DROP TABLE IF EXISTS msg_fts")
            except Exception:
                pass
    if not mode:
        log("存档：FTS5 不可用（trigram / unicode61 都建不出）→ 退回普通表 + text LIKE"
            " + ts 索引", appid)
        return "LIKE"
    _arc_make_triggers(conn)
    conn.commit()
    n = 0
    try:
        n = int(conn.execute("SELECT COUNT(*) FROM msg").fetchone()[0] or 0)
        if n:
            conn.execute("INSERT INTO msg_fts(msg_fts) VALUES('rebuild')")   # 从 msg 全量回填
            conn.commit()
    except Exception as e:
        log("存档：索引回填失败（%r）→ 检索会补 LIKE" % e, appid)
    if old_mode and old_mode != mode:
        log("存档索引已升级为 %s，回填 %d 条（原 %s）" % (mode, n, old_mode), appid)
    elif mode == "trigram":
        log("存档：FTS5(trigram) 可用 → %s 走 trigram 全文索引"
            "（msg_fts MATCH，中文可子串查）" % _arc_path(botdir), appid)
    else:
        log("存档：FTS5(unicode61) 可用 → %s 走 unicode61 全文索引"
            "（中文按整串匹配，检索会补 LIKE）" % _arc_path(botdir), appid)
    return mode


def _arc_connect(botdir):
    """打开（必要时建 + 升级）本 bot 的库 → {"conn":…, "mode":"trigram"|"unicode61"|"LIKE"}。

    失败返回 None（这一轮就不落库，收发消息完全不受影响）。
    """
    global _arc_off
    botdir = str(botdir or "")
    if not botdir or _arc_off:
        return None
    with _arc_lock:
        got = _arc_db.get(botdir)
        if got is not None:
            return got
        appid = _arc_appid(botdir)
        try:
            import sqlite3
        except Exception as e:
            _arc_off = "sqlite3 不可用：%r" % e
            log("消息存档关闭：%s（收发消息照常，只是不落库）" % _arc_off, appid)
            return None
        try:
            try:
                os.makedirs(botdir, exist_ok=True)
            except Exception:
                pass
            conn = sqlite3.connect(_arc_path(botdir), timeout=5.0, check_same_thread=False)
            conn.execute(
                "CREATE TABLE IF NOT EXISTS msg(id INTEGER PRIMARY KEY, chat_type TEXT,"
                " chat_id TEXT, author_openid TEXT, author_name TEXT, ts INTEGER,"
                " text TEXT, reply_text TEXT)")
        except Exception as e:
            log("存档：打开 messages.db 失败（%r）→ 本次不落库" % e, appid)
            return None
        try:
            mode = _arc_setup_fts(conn, botdir)
        except Exception as e:
            log("存档：索引初始化失败（%r）→ 本次走 LIKE" % e, appid)
            mode = "LIKE"
        if mode == "LIKE":
            try:
                conn.execute("CREATE INDEX IF NOT EXISTS msg_ts ON msg(ts)")
                conn.commit()
            except Exception:
                pass
        info = {"conn": conn, "mode": mode}
        _arc_db[botdir] = info
        return info


def _arc_start_worker():
    """起后台 flush 线程（一次就够，全进程共用；守护线程，随进程退出）。"""
    with _arc_lock:
        if _arc_worker["on"]:
            return
        _arc_worker["on"] = True
    try:
        threading.Thread(target=_arc_loop, name="archive-flush", daemon=True).start()
    except Exception as e:
        _arc_worker["on"] = False
        log("存档：flush 线程起不来（%r）→ 改为每条直接落盘" % e)


def _arc_loop():
    """后台：每 0.5 秒看一次缓冲 —— 够 20 条或最旧那条满 5 秒就 flush；顺带每天清理一次。"""
    while True:
        time.sleep(0.5)
        try:
            _arc_flush()
        except Exception as e:
            log("存档：后台 flush 异常（忽略）：%r" % e)
        try:
            _arc_daily()
        except Exception as e:
            log("存档：每日清理异常（忽略）：%r" % e)


def _arc_daily(now=None):
    """每天滚动清理一次（启动时已经清过一次，见 _arc_boot）。"""
    now = time.time() if now is None else now
    for botdir in list(_arc_db.keys()):
        if now - float(_arc_trim_ts.get(botdir) or 0.0) < 86400:
            continue
        _arc_trim(botdir)


def _arc_trim(botdir, days=0, per_chat=0):
    """滚动删除：超保留期的一律删；每个会话超量的删最旧的。

    → (按时间删掉几条, 按条数删掉几条)；days/per_chat 传 0 就用默认值
    （默认 30 天 / 每会话 5000 条，没有「永久保留」开关）。
    """
    info = _arc_connect(botdir)
    if info is None:
        return 0, 0
    days = int(days or _ARC_KEEP_DAYS)
    per_chat = int(per_chat or _ARC_KEEP_PER_CHAT)
    conn = info["conn"]
    cutoff = int(time.time()) - days * 86400
    n_old = 0
    n_over = 0
    with _arc_lock:
        try:
            n_old = int(conn.execute("DELETE FROM msg WHERE ts < ?", (cutoff,)).rowcount or 0)
            rows = conn.execute(
                "SELECT chat_type, chat_id, COUNT(*) FROM msg GROUP BY chat_type, chat_id"
                " HAVING COUNT(*) > ?", (per_chat,)).fetchall()
            for ct, cid, cnt in rows:
                n_over += int(conn.execute(
                    "DELETE FROM msg WHERE id IN (SELECT id FROM msg WHERE chat_type = ?"
                    " AND chat_id = ? ORDER BY ts ASC, id ASC LIMIT ?)",
                    (ct, cid, int(cnt) - per_chat)).rowcount or 0)
            conn.commit()
            _arc_trim_ts[str(botdir)] = time.time()
        except Exception as e:
            log("存档：滚动清理失败（忽略）：%r" % e, _arc_appid(botdir))
            return 0, 0
    if n_old or n_over:
        log("存档清理：保留 %d 天 / 每会话 %d 条 → 删掉 %d 条（超期 %d + 超量 %d）"
            % (days, per_chat, n_old + n_over, n_old, n_over), _arc_appid(botdir))
    return n_old, n_over


def _arc_boot(botdir):
    """bot 启动时调一次：开库（顺带把 FTS5 探测结果写进日志）+ 至少清一次 + 起 flush 线程。"""
    _arc_connect(botdir)
    _arc_trim(botdir)
    _arc_start_worker()


def _arc_put(botdir, chat_type, chat_id, author_openid, text, author_name=""):
    """收消息入缓冲（不阻塞回复）。空文本 / 空会话直接丢。"""
    one = str(text or "").strip()
    if not one or not botdir:
        return
    row = {
        "botdir": str(botdir), "chat_type": str(chat_type or ""),
        "chat_id": str(chat_id or ""), "author_openid": str(author_openid or ""),
        "author_name": str(author_name or ""), "ts": int(time.time()),
        "text": one, "reply_text": "", "done": False,
    }
    with _arc_lock:
        _arc_buf.append(row)
    _arc_start_worker()


def _arc_flush(force=False):
    """把缓冲落盘（20 条 / 5 秒 / force）→ 写了几条。"""
    with _arc_lock:
        if not _arc_buf:
            return 0
        now = time.time()
        age = now - float(_arc_buf[0].get("ts") or now)
        if not force and len(_arc_buf) < _ARC_FLUSH_N and age < _ARC_FLUSH_SEC:
            return 0
        rows = list(_arc_buf)
        del _arc_buf[:]
        n = 0
        for r in rows:
            info = _arc_connect(r["botdir"])
            if info is None:
                continue
            try:
                cur = info["conn"].execute(
                    "INSERT INTO msg(chat_type, chat_id, author_openid, author_name, ts,"
                    " text, reply_text) VALUES(?,?,?,?,?,?,?)",
                    (r["chat_type"], r["chat_id"], r["author_openid"], r["author_name"],
                     r["ts"], r["text"], r["reply_text"]))
                rid = int(cur.lastrowid or 0)
                if rid and not r["reply_text"]:
                    # 只有「还没补过回复」的请求行才记下来，回复到了 UPDATE 它
                    _arc_last[(r["botdir"], r["chat_type"], r["chat_id"])] = rid
                n += 1
            except Exception as e:
                log("存档：写入失败（忽略）：%r" % e, _arc_appid(r["botdir"]))
        for bd in set([r["botdir"] for r in rows]):
            try:
                _arc_db[bd]["conn"].commit()
            except Exception:
                pass
        return n


def _arc_reply(botdir, chat_type, chat_id, reply_text):
    """给「刚收到的那条」补上这一轮发出的回复。

    还没 flush → 直接改缓冲里那条（连 reply_text 一起落盘）；
    已经落盘 → UPDATE 同一行。找不到请求行（例如主动问候、定时消息）就什么也不做。
    """
    one = str(reply_text or "").strip()
    if not one or not botdir:
        return False
    key = (str(botdir), str(chat_type or ""), str(chat_id or ""))
    with _arc_lock:
        for r in reversed(_arc_buf):
            if (r["botdir"], r["chat_type"], r["chat_id"]) == key and not r["done"]:
                r["reply_text"] = one
                r["done"] = True
                return True
        rid = _arc_last.get(key)
        info = _arc_connect(key[0])
        if not rid or info is None:
            return False
        try:
            info["conn"].execute("UPDATE msg SET reply_text = ? WHERE id = ?", (one, rid))
            info["conn"].commit()
            return True
        except Exception as e:
            log("存档：补 reply_text 失败（忽略）：%r" % e, _arc_appid(key[0]))
            return False


def _arc_reply_by_key(botdir, key, reply_text):
    """会话键 → (chat_type, chat_id)："g<群 openid>" / "p<对方 openid>"（与记忆同一套键）。"""
    k = str(key or "")
    if len(k) < 2:
        return False
    return _arc_reply(botdir, "group" if k[0] == "g" else "c2c", k[1:], reply_text)


def _arc_fts_query(q):
    """把自然语言切成 FTS5 认的词：每个词加引号，用 OR 连（查不到就退回 LIKE）。"""
    toks = re.findall(r"[\w\u4e00-\u9fff]+", str(q or ""))[:8]
    return " OR ".join(['"' + t.replace('"', "") + '"' for t in toks if t])


def _arc_like(conn, q, n):
    """LIKE 兜底检索：整库没有 FTS5，或关键词短于 3 个字喂不动 trigram 时用它。"""
    like = "%" + str(q) + "%"
    with _arc_lock:
        return conn.execute(
            "SELECT ts, author_openid, author_name, text, reply_text FROM msg"
            " WHERE text LIKE ? OR reply_text LIKE ? ORDER BY ts DESC, id DESC LIMIT ?",
            (like, like, int(n))).fetchall()


def archive_search(botdir, query, n=0):
    """检索存档 → (实际走的检索路径, 命中行列表[时间正序])。

    路径写进日志：trigram / unicode61 / LIKE（整库没有 FTS5）/ trigram→LIKE（短词兜底）。
    trigram 让中文子串走索引（「张伟提」能查到「张伟提过档期」）；但 trigram 要求关键词
    ≥3 个字符，「张伟」这种两字词 MATCH 必然 0 条 —— 所以 MATCH 没命中时补一次 text LIKE，
    日志里写清是哪条路命中的：宁可多查一次，也不要让「上周谁提过 X」问出来是空的。
    """
    n = int(n or _ARC_RECALL_N)
    q = str(query or "").strip()
    info = _arc_connect(botdir)
    if info is None or not q:
        return "", []
    mode = info["mode"]
    conn = info["conn"]
    rows = []
    if mode in ("trigram", "unicode61"):
        mq = _arc_fts_query(q)
        if mq:
            try:
                with _arc_lock:
                    rows = conn.execute(
                        "SELECT m.ts, m.author_openid, m.author_name, m.text, m.reply_text"
                        " FROM msg_fts JOIN msg m ON m.id = msg_fts.rowid"
                        " WHERE msg_fts MATCH ? ORDER BY m.ts DESC, m.id DESC LIMIT ?",
                        (mq, n)).fetchall()
            except Exception as e:
                log("存档：FTS5 查询失败（%r）→ 本次退回 LIKE" % e, _arc_appid(botdir))
                mode = "LIKE"
        if mode != "LIKE":
            if rows:
                return mode, list(reversed(rows))
            # trigram 要求关键词 ≥3 个字符：短词（「张伟」）MATCH 必然 0 条，补一次 LIKE
            extra = _arc_like(conn, q, n)
            return ((mode + "→LIKE") if extra else mode), list(reversed(extra))
    return "LIKE", list(reversed(_arc_like(conn, q, n)))


def _recall_line(r):
    """一条存档 → 「[时间] 作者：原文」（单条截 200 字）。"""
    ts, oid, name = r[0], r[1], r[2]
    when = time.strftime("%Y-%m-%d %H:%M", time.localtime(int(ts or 0)))
    who = str(name or "").strip() or (_oid8(oid) or "有人")
    one = " ".join(str(r[3] or "").split())
    if len(one) > _ARC_HIT_CHARS:
        one = one[:_ARC_HIT_CHARS] + "…"
    return "[%s] %s：%s" % (when, who, one)


def _recall_block(rows):
    """召回命中 → 拼进第二次请求的 system 段。"""
    return ("（聊天存档里翻到的原文，只是参考：用它回答，别念时间戳、别说自己在查库）"
            + chr(10) + chr(10).join([_recall_line(r) for r in rows]))


def _strip_recall_marks(text):
    """把 <recall>…</recall>（以及残缺的半截标记）从正文里剥掉 —— 用户不该看见。"""
    return _ARC_RECALL_ANY_RE.sub("", _ARC_RECALL_MARK_RE.sub("", str(text or ""))).strip()


_RECALL_HINT_RULE = (
    chr(10) * 2 + "【要不要先翻聊天存档】" + chr(10) + chr(10).join([
        "- 有人让你回忆以前聊过的事（例如「上周谁提过 X」「之前那件事是怎么说的」）时，"
        "只输出一行 <recall>关键词</recall>，不要猜、不要编。",
        "- 关键词只写要查的那个词或那件事，别写成整句话；中文给到 3 个字以上"
        "（写「张伟提过档期」而不是「张伟」），太短的词本地索引翻不动。",
        "- 不确定有没有，也可以先翻一次。",
    ])
)

_RECALL_STOP = (
    chr(10) * 2 + "【存档已经翻到了】" + chr(10) + chr(10).join([
        "- 用下面这些原文回答，说清是谁、在什么时候说的；照旧用你自己的语气。",
        "- 不要再输出 <recall>，也不要编存档里没有的事。",
    ])
)

_RECALL_EMPTY = "（聊天存档里一条都没翻到：老实说没印象，别编。）"


def archive_stats(botdir):
    """设置页的占用快照：{"bytes": …, "rows": …, "mode": "FTS5"|"LIKE"}（JSON 串）。"""
    botdir = str(botdir or "")
    _arc_flush(force=True)
    info = _arc_connect(botdir)
    rows = -1
    mode = ""
    if info is not None:
        mode = info["mode"]
        try:
            with _arc_lock:
                rows = int(info["conn"].execute("SELECT COUNT(*) FROM msg").fetchone()[0] or 0)
        except Exception:
            rows = -1
    return json.dumps({"bytes": _arc_bytes(_arc_path(botdir)), "rows": rows, "mode": mode},
                      ensure_ascii=False)


def archive_trim(botdir, days=0, per_chat=0):
    """手动触发一次滚动清理（验收用；日常走 _arc_boot + _arc_daily）。"""
    _arc_flush(force=True)
    n_old, n_over = _arc_trim(botdir, days, per_chat)
    return json.dumps({"expired": n_old, "over": n_over}, ensure_ascii=False)


def archive_clear(botdir):
    """清空本 bot 的存档（DELETE + VACUUM 收回文件大小）→ {"rows": …, "bytes": …}。"""
    botdir = str(botdir or "")
    info = _arc_connect(botdir)
    if info is None:
        return json.dumps({"rows": 0, "bytes": _arc_bytes(_arc_path(botdir))})
    conn = info["conn"]
    n = 0
    with _arc_lock:
        try:
            # 缓冲里还没落盘的也算「清空」：一起丢掉，否则清完又冒出来几条
            keep = [r for r in _arc_buf if r["botdir"] != botdir]
            n = len(_arc_buf) - len(keep)
            del _arc_buf[:]
            _arc_buf.extend(keep)
            for k in [k for k in list(_arc_last.keys()) if k[0] == botdir]:
                del _arc_last[k]
            n += int(conn.execute("DELETE FROM msg").rowcount or 0)
            conn.commit()
            try:
                conn.execute("VACUUM")
            except Exception:
                pass
        except Exception as e:
            log("存档：清空失败：%r" % e, _arc_appid(botdir))
            return json.dumps({"rows": 0, "bytes": _arc_bytes(_arc_path(botdir))})
    log("存档已清空：删掉 %d 条，文件现在 %d 字节（%s）"
        % (n, _arc_bytes(_arc_path(botdir)), _arc_path(botdir)), _arc_appid(botdir))
    return json.dumps({"rows": n, "bytes": _arc_bytes(_arc_path(botdir))}, ensure_ascii=False)


def archive_export_text(botdir, max_rows=0):
    """导出正文：一行一条「[2026-11-03 20:11] 群/私聊 作者：消息 ⇢ 回复」。

    时间正序（老的在前），最多 _ARC_EXPORT_MAX 行；界面上再拼 bot 标题。
    """
    botdir = str(botdir or "")
    _arc_flush(force=True)
    info = _arc_connect(botdir)
    if info is None:
        return ""
    limit = int(max_rows or _ARC_EXPORT_MAX)
    try:
        with _arc_lock:
            rows = info["conn"].execute(
                "SELECT chat_type, author_openid, author_name, ts, text, reply_text FROM msg"
                " ORDER BY ts ASC, id ASC LIMIT ?", (limit,)).fetchall()
    except Exception as e:
        log("存档：导出失败：%r" % e, _arc_appid(botdir))
        return ""
    if len(rows) >= limit:
        log("存档导出：只导了最早的 %d 行（上限），后面还有" % limit, _arc_appid(botdir))
    lines = []
    for ct, oid, name, ts, text, reply in rows:
        when = time.strftime("%Y-%m-%d %H:%M", time.localtime(int(ts or 0)))
        kind = "群" if str(ct) == "group" else "私聊"
        who = str(name or "").strip() or (_oid8(oid) or "有人")
        one = " ".join(str(text or "").split())
        rep = " ".join(str(reply or "").split())
        lines.append("[%s] %s %s：%s" % (when, kind, who, one) + ((" ⇢ " + rep) if rep else ""))
    return chr(10).join(lines)


def _system_prompt(botdir, key):
    """系统提示词：人格 → 称呼规则 → 当前时间 → 群聊硬规则。

    人格走 _persona_for：该群配了 persona（groups/<gid>.json）就用群人格，否则仍是 bot 人格。
    """
    persona = _persona_for(botdir, key)
    sys = persona or "你是一个 QQ 群里的机器人，说话自然简短，像真人聊天。"
    sys = sys + _words_rules(botdir, key)
    sys = sys + chr(10) + chr(10) + "现在是 %s。" % _now_text()
    sys = sys + _CHAT_RULES
    return sys


def _build_messages(botdir, key, who, text, first=False, search_hint=False):
    """system →（有摘要时）此前对话摘要 → 最近 12 条原文 → 这一条。

    first 参数只为兼容老调用点保留：首句不再注入问候语（问候改成时段随机主动私聊）。
    search_hint=True 时在 system 末尾补一段「要不要上网查」的规则（模型自判搜索用）。
    摘要由 _maybe_compress 在 _reply 里写盘，这里只读：读不到就还是纯最近 12 条。
    """
    sys_text = _system_prompt(botdir, key)
    if search_hint:
        sys_text = sys_text + _WEB_HINT_RULE
    # 存档召回与联网并列：跟 web_search 开关无关（翻的是本机聊天记录，不花钱、不出网）
    sys_text = sys_text + _RECALL_HINT_RULE
    msgs = [{"role": "system", "content": sys_text}]
    sm = _summary_of(botdir, key)
    summary = str(sm.get("summary") or "").strip()
    if summary:
        msgs.append({"role": "system",
                     "content": "（此前对话摘要：%s）" % summary})
    for r in (_memory(botdir).get(key) or [])[-_SUMMARY_KEEP:]:
        try:
            role, content = r[0], r[1]
        except Exception:
            continue
        if role in ("user", "assistant") and isinstance(content, str):
            msgs.append({"role": role, "content": content})
    msgs.append({"role": "user", "content": ("%s：%s" % (who, text)) if who else text})
    return msgs



# ── 输出清洗 ──────────────────────────────────────────
#
# 提示词已经拦了一道，这里再擦一遍地：模型偶尔还是会把目录、文件名、
# 1./2./3. 的清单带出来，群聊里那既不像角色也把内部实现抖出去了。
# 只做减法 —— 不补内容、不改语气，正常角色台词原样返回。

_FENCE_RE = re.compile(chr(96) * 3 + r"[^\n]*\n?|" + chr(96) * 3)
_PATH_RE = re.compile(
    r"(?:~|\.{1,2})/(?:[\w.\-]+/)*[\w.\-]*"            # ~/qqbot/texts/ 、/data/...
    r"|(?<![\w.])/(?:[\w.\-]+/)+[\w.\-]*"              # 绝对路径（至少两级，别把正文里的斜杠吃掉）
    r"|[A-Za-z]:\\[^\s，。！？；：、）)]*"                 # C:\Users\...
)
_FILE_RE = re.compile(
    r"(?<![\w])[\w\-.]*\.(?:txt|json|jsonl|py|kt|java|log|md|ya?ml|ini|cfg|conf|db|sqlite|js|ts|sh|apk)\b",
    re.I,
)
_LIST_RE = re.compile(r"(?m)^[ \t]*(?:\d{1,2}\s*[.、)]|[-*·•])[ \t]*")
_TAIL_PUNCT = "。！？…～.？！;；"


def _clean_reply(text, limit=120):
    """把模型回复擦成「群里的一句话」。

    去代码块与反引号 → 去路径片段 → 去文件名 → 去行首列表符号
    → 空行压平 → 超 limit 就在标点处截断（没有标点才硬切）。
    """
    s = str(text or "")
    s = _FENCE_RE.sub(" ", s).replace(chr(96), "")
    s = _PATH_RE.sub("", s)
    s = _FILE_RE.sub("", s)
    s = _LIST_RE.sub("", s)
    s = " ".join([ln.strip() for ln in s.splitlines() if ln.strip()])
    s = re.sub(r"[ \t]{2,}", " ", s).strip(" 　·-—")
    if len(s) > limit:
        cut = 0
        for i in range(limit, 0, -1):
            if s[i - 1] in _TAIL_PUNCT:
                cut = i
                break
        s = (s[:cut] if cut else s[:limit]).rstrip("，,、 　")
    return s.strip()


# ── 联网（DeepSeek 官方 Search / Fetch）──────────────────────
#
# 和主模型同一个 API Key，两个端点：
#   POST {base}/v1/tools/search  {"query","count","need_content":true}
#        → results[].title / .url / .summary
#   POST {base}/v1/tools/fetch   {"url"}
#        → content（Markdown）
# 计费：每 1000 次成功请求 5 元（模型配置页里也写了这句）。
# 失败（4xx / 5xx / 15 秒超时）只写日志，退回纯文本回复 —— 不向用户报错。
# 触发两种：指令（#搜 / #读，不走模型）与自动（消息里有明显需要外部信息的词）。

_WEB_TIMEOUT = 15
_WEB_COUNT = 5              # 问接口要几条
_WEB_REPLY_MAX = 3          # 回复 / 拼上下文最多用几条
_WEB_SUMMARY_CHARS = 300    # 自动搜索拼进上下文时每条摘要的截断
_WEB_FETCH_CHARS = 500      # #读 关掉人设化加工时回的前 500 字
_WEB_STYLE_PERSONA = 800    # 转述时喂给模型的人格上限
_WEB_STYLE_MAXTOK = 500     # 合成回答的 max_tokens（1~4 句，不用很长）
_WEB_ANSWER_CHARS = 300      # 合成回答的字数上限（1~4 句中文口语）
_SRCH_MAX = 5                # 一条消息最多搜几次（多轮追问；成本护栏的硬上限）
_HITS_SHOW_MAX = 12          # 拼给模型看的资料条数上限（去重后取最近的）
_FETCH_STYLE_CHARS = 3000   # #读 交给模型的正文上限
_FETCH_OUT_CHARS = 300      # #读 摘要的字数上限

# 链接里的跟踪参数：一律剥掉。fpid/fid/var/spm/from/utm_* 这类参数只用来埋点，
# 贴给用户看既脏又长（用户真机反馈的那条链接就是 ?fpid=…&fid=default&var=B）。
_TRACK_EXACT = {
    "fpid", "fid", "var", "spm", "from", "fromid", "from_source", "source", "ref",
    "referrer", "ref_src", "share", "share_token", "sharer", "scene", "chksm",
    "clickid", "s", "si", "feature", "trk", "trk_info", "vr", "topsubjectid",
    "for", "wfr", "gk", "vd_source", "gclid", "fbclid", "_f", "isappinstalled",
}
_TRACK_PREFIX = ("utm_", "spm", "from", "fpid", "fid", "var", "ref_", "share", "scene")


def _is_track(name):
    """这个 query 参数是不是纯埋点参数（大小写不敏感，前缀也算）。"""
    n = str(name or "").strip().lower()
    if not n:
        return False
    return n in _TRACK_EXACT or n.startswith(_TRACK_PREFIX)


def _strip_tracking(url):
    """洗链接：去掉 fpid/fid/var/spm/from/utm_* 之类的跟踪参数，别的参数原样留着。

    剥完没有参数了就连问号一起去掉；解析失败就原样返回（宁可脏，不能丢链接）。
    """
    u = str(url or "").strip()
    if not u or "?" not in u:
        return u
    try:
        from urllib.parse import urlsplit, urlunsplit, parse_qsl, urlencode, quote
        p = urlsplit(u)
        pairs = parse_qsl(p.query, keep_blank_values=True)
        q = [(k, v) for k, v in pairs if not _is_track(k)]
        if len(q) == len(pairs):
            return u          # 没有跟踪参数：原样返回，别顺手改掉别人链接的编码
        return urlunsplit((p.scheme, p.netloc, p.path,
                           urlencode(q, quote_via=quote, safe="/:,"), p.fragment))
    except Exception:
        return u


def _web_base(conf):
    base = str((conf or {}).get("api") or "").rstrip("/")
    if base.endswith("/chat/completions"):
        base = base[: -len("/chat/completions")]
    return base.rstrip("/")


def _web_url(conf, path):
    """{base}/v1/tools/...；地址里已经带 /v1 的不重复拼。"""
    base = _web_base(conf)
    return base + path if base.endswith("/v1") else base + "/v1" + path


def _plog(conf, msg, appid=""):
    """联网日志统一带后端前缀：联网(deepseek)：… / 联网(searxng)：… / 联网(custom)：…"""
    log("联网(%s)：%s" % (_search_provider(conf), msg), appid)


def _web_post(conf, path, body, appid="", tag="", provider="deepseek"):
    """POST 一个联网端点 → (ok, 响应 dict 或错误说明)。日志写清状态码与耗时（不写 Key）。"""
    import json
    import urllib.error
    import urllib.request

    url = _web_url(conf, path)
    t0 = time.time()
    req = urllib.request.Request(
        url, data=json.dumps(body).encode("utf-8"), method="POST",
        headers={"Content-Type": "application/json",
                 "Authorization": "Bearer " + str(conf.get("key") or "")},
    )
    code = 0
    try:
        with urllib.request.urlopen(req, timeout=_WEB_TIMEOUT) as r:
            code = int(getattr(r, "status", 200) or 200)
            raw = r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        code = int(getattr(e, "code", 0) or 0)
        detail = ""
        try:
            detail = e.read().decode("utf-8", "replace")[:200]
        except Exception:
            detail = repr(e)
        log("联网(%s)：%s HTTP %d（%.1fs）%s"
            % (provider, tag, code, time.time() - t0,
               detail.replace(chr(10), " ")[:160]), appid)
        return False, "HTTP %d" % code
    except Exception as e:
        log("联网(%s)：%s 失败 %r（%.1fs）" % (provider, tag, e, time.time() - t0), appid)
        return False, "网络错误"
    log("联网(%s)：%s HTTP %d（%.1fs）" % (provider, tag, code, time.time() - t0), appid)
    try:
        d = json.loads(raw or "{}")
    except Exception:
        log("联网(%s)：%s 响应不是 JSON（前 120 字）：%s" % (provider, tag, raw[:120]), appid)
        return False, "响应格式错误"
    return (True, d) if isinstance(d, dict) else (False, "响应格式错误")


# ── 搜索后端（deepseek / searxng / custom / off）────────────────
#
# 用户不一定用 DeepSeek：中转站、第三方模型都没有 /v1/tools/*，所以搜索做成可切换后端，
# 但上层（人设化合成 / 多轮循环 / 链接清洗）只认统一结构 [{"title","url","summary"}]。
#   deepseek：POST {api 去掉 /chat/completions}/v1/tools/search 与 /v1/tools/fetch，
#             Bearer 用主模型 Key（原有实现）
#   searxng ：GET {search_base}/search?q=…&format=json → results[].title/url/content
#   custom  ：GET search_base（含 {query} 占位就替换，否则自动加 ?q=），
#             鉴权 search_auth（none / bearer / header:Name=Value），
#             结果字段 search_fields（默认 results[].title,url,summary）
#   off     ：完全不联网
_SEARCH_PROVIDERS = ("deepseek", "searxng", "custom", "off")
_DEFAULT_FIELDS = "results[].title,url,summary"


def _search_provider(conf):
    """后端名：缺省/写错都按 deepseek（旧配置没这几个键也能照常跑）。"""
    p = str((conf or {}).get("search_provider") or "").strip().lower()
    return p if p in _SEARCH_PROVIDERS else "deepseek"


def _search_headers(conf):
    """按 search_auth 拼请求头（绝不把 Key 写进日志）。"""
    auth = str((conf or {}).get("search_auth") or "").strip()
    low = auth.lower()
    h = {"Accept": "application/json", "User-Agent": "AndbnA-bot/1.0"}
    if low in ("", "none"):
        return h
    if low == "bearer":
        h["Authorization"] = "Bearer " + str(conf.get("key") or "")
        return h
    if low.startswith("header:") and "=" in auth:
        k, v = auth[len("header:"):].split("=", 1)
        if k.strip():
            h[k.strip()] = v.strip()
    return h


def _http_get_json(url, headers, appid="", tag="", provider="deepseek"):
    """GET 一个 JSON 地址 → (ok, dict/list)。日志带后端前缀。"""
    import json
    import urllib.error
    import urllib.request

    t0 = time.time()
    req = urllib.request.Request(url, method="GET", headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=_WEB_TIMEOUT) as r:
            code = int(getattr(r, "status", 200) or 200)
            raw = r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        code = int(getattr(e, "code", 0) or 0)
        try:
            detail = e.read().decode("utf-8", "replace")[:200]
        except Exception:
            detail = repr(e)
        log("联网(%s)：%s HTTP %d（%.1fs）%s"
            % (provider, tag, code, time.time() - t0, detail.replace(chr(10), " ")[:160]),
            appid)
        return False, "HTTP %d" % code
    except Exception as e:
        log("联网(%s)：%s 失败 %r（%.1fs）" % (provider, tag, e, time.time() - t0), appid)
        return False, "网络错误"
    log("联网(%s)：%s HTTP %d（%.1fs）" % (provider, tag, code, time.time() - t0), appid)
    try:
        d = json.loads(raw or "{}")
    except Exception:
        log("联网(%s)：%s 响应不是 JSON（前 120 字）：%s" % (provider, tag, raw[:120]), appid)
        return False, "响应格式错误"
    return True, d


def _norm_hits(items):
    """任意后端的条目 → 统一结构；title/url/summary 的别名都认一点。"""
    out = []
    for it in (items or []):
        if not isinstance(it, dict):
            continue
        title = str(it.get("title") or it.get("name") or it.get("headline") or "").strip()
        url = str(it.get("url") or it.get("link") or it.get("href") or "").strip()
        summary = " ".join(str(it.get("summary") or it.get("content") or it.get("snippet")
                               or it.get("desc") or it.get("description") or "").split())
        if not (title or url):
            continue
        out.append({"title": title, "url": url, "summary": summary})
    return out


def _parse_fields(spec, data):
    """按 search_fields 取数组：'results[].title,url,summary' / 'data[].title,link,desc'。"""
    spec = str(spec or "").strip() or _DEFAULT_FIELDS
    if "[]" not in spec:
        spec = _DEFAULT_FIELDS
    base, fields = spec.split("[]", 1)
    names = [f.strip().lstrip(".") for f in fields.split(",") if f.strip()]
    names = names or ["title", "url", "summary"]
    node = data
    for key in [p for p in base.replace("[", ".").replace("]", "").split(".") if p]:
        node = node.get(key) if isinstance(node, dict) else None
    if node is None and isinstance(data, list):
        node = data
    rows = []
    for it in (node or []):
        if not isinstance(it, dict):
            continue
        row = {}
        for i, n in enumerate(names):
            row[n] = it.get(n)
            if i == 0:
                row.setdefault("title", it.get(n))
            elif i == 1:
                row.setdefault("url", it.get(n))
            elif i == 2:
                row.setdefault("summary", it.get(n))
        row.setdefault("title", it.get(names[0]) if names else "")
        row.setdefault("url", it.get(names[1]) if len(names) > 1 else "")
        rows.append(row)
    return rows


def web_search(query, conf, appid="", count=_WEB_COUNT, meta=None, source="", round_no=0):
    """搜索 → [{"title","url","summary"}]；失败或无结果返回 []。

    meta（可选 dict）回填 meta["ok"]：True = 接口通了（哪怕是 0 条结果），
    False = 网络/HTTP 出错或后端关闭。调用方靠它区分「没搜到」与「搜挂了」。
    后端由 llm.json 的 search_provider 决定，上层只认统一结构。
    """
    import urllib.parse
    q = str(query or "").strip()[:200]
    if meta is not None:
        meta["ok"] = False
    if not q or not conf:
        return []
    provider = _search_provider(conf)
    if provider == "off":
        log("联网(off)：搜索后端已关闭，不发请求", appid)
        return []
    t0 = time.time()
    base = str(conf.get("search_base") or "").strip().rstrip("/")
    if provider == "searxng":
        if not base:
            log("联网(searxng)：没填搜索地址（search_base），无法搜索", appid)
            return []
        url = base + "/search?q=" + urllib.parse.quote(q) + "&format=json"
        _usage_search(_usage_dir(appid))
        ok, d = _http_get_json(url, _search_headers(conf), appid,
                               "search「%s」" % q[:24], provider)
        rows = _norm_hits((d.get("results") if isinstance(d, dict) else d) or []) if ok else []
    elif provider == "custom":
        if not base:
            log("联网(custom)：没填搜索地址（search_base），无法搜索", appid)
            return []
        if "{query}" in base:
            url = base.replace("{query}", urllib.parse.quote(q))
        else:
            url = base + ("&" if "?" in base else "?") + "q=" + urllib.parse.quote(q)
        _usage_search(_usage_dir(appid))
        ok, d = _http_get_json(url, _search_headers(conf), appid,
                               "search「%s」" % q[:24], provider)
        rows = _norm_hits(_parse_fields(conf.get("search_fields"), d)) if ok else []
    else:
        _usage_search(_usage_dir(appid))
        ok, d = _web_post(conf, "/tools/search",
                          {"query": q, "count": count, "need_content": True},
                          appid, "search「%s」" % q[:24], "deepseek")
        rows = _norm_hits(d.get("results") or []) if ok else []
    if not ok:
        log("联网(%s)：搜索失败（后端 %s），按没结果处理" % (provider, provider), appid)
        return []
    if meta is not None:
        meta["ok"] = True
    log("联网(%s)：%s%s「%s」→ 命中 %d 条（%.1fs）"
        % (provider, _WEB_SRC.get(source, "搜索"),
           ("（第 %d 轮）" % round_no) if round_no else "",
           q[:24], len(rows), time.time() - t0), appid)
    return rows


def web_fetch(url, conf, appid=""):
    """抓正文（只有 DeepSeek 官方后端支持）→ Markdown；其它后端/失败返回空串。"""
    u = str(url or "").strip()
    if not u or not conf:
        return ""
    provider = _search_provider(conf)
    if provider == "off":
        log("联网(off)：搜索后端已关闭，不抓取", appid)
        return ""
    if provider != "deepseek":
        log("联网(%s)：这个后端不支持抓正文（#读 需要 DeepSeek 官方 Search/Fetch）"
            % provider, appid)
        return ""
    _usage_search(_usage_dir(appid))
    ok, d = _web_post(conf, "/tools/fetch", {"url": u}, appid, "fetch %s" % u[:24], provider)
    if not ok:
        return ""
    content = str(d.get("content") or "").strip()
    log("联网(%s)：fetch %s → %d 字" % (provider, u[:40], len(content)), appid)
    return content


# 指令：私聊/群里发「#搜 关键词」「#读 网址」。
# 默认（search_persona_style=1）先让模型按人格用中文口语转述，再贴洗过跟踪参数的链接；
# 关掉这个开关就退回老做法：原始标题/摘要直贴，不走模型（用来对比）。
_WEB_SEARCH_CMDS = ("#搜", "＃搜")
_WEB_FETCH_CMDS = ("#读", "＃读")


def _web_cmd_of(text):
    """→ (kind, arg)；kind 为 "" 表示不是联网指令。"""
    t = str(text or "").strip()
    for pre in _WEB_SEARCH_CMDS:
        if t.startswith(pre):
            return "search", t[len(pre):].strip()
    for pre in _WEB_FETCH_CMDS:
        if t.startswith(pre):
            return "fetch", t[len(pre):].strip()
    return "", ""


def _raw_one_liner(h):
    """一条命中的原始一行（标题｜首句摘要），原始贴法与转述失败垫底都用它。"""
    one = h.get("summary") or ""
    one = re.split(r"(?<=[。！？.!?])\s*", one)[0][:60] if one else ""
    head = h.get("title") or "(无标题)"
    return head + (("｜" + one) if one else "")


def _search_reply_text(hits):
    """指令 #搜 的原始贴法（关掉 search_persona_style 时就是它）：标题 + 一句摘要 + 链接。

    编号必须按**结果条**数走（每条结果占两行：标题行 + 链接行），
    用行数当前缀会得到 1./3./5. 这种错位编号。
    链接原样贴（老行为一字不改），便于与「人设化加工」那一版对比。
    """
    lines = []
    for i, h in enumerate(hits[:_WEB_REPLY_MAX], 1):
        lines.append("%d. %s" % (i, _raw_one_liner(h)))
        if h.get("url"):
            lines.append(h["url"])
    return chr(10).join(lines)


def _fetch_reply_text(content):
    """指令 #读 的原始贴法：前 500 字（连续空行压成一行空行，免得占满整屏）。"""
    s = re.sub(r"\n{3,}", chr(10) * 2, str(content or "")).strip()
    if not s:
        return ""
    return s[:_WEB_FETCH_CHARS] + ("…（后略）" if len(s) > _WEB_FETCH_CHARS else "")


# ── 人设化加工（B）──────────────────────────────────────────
#
# 用户真机反馈两轮：
#   ① 搜索结果原样贴出去：英文标题 + 埋点链接 + 一条韩文新闻，没有人格/语言加工；
#   ② 改成「1./2./3. 列表 + 每条一个链接」之后仍然不像聊天 —— 他要的是
#      「能上网搜到相关自己不知道的内容后正常回答」。
# 所以默认链路（search_persona_style=1）是**合成式**：
#   #搜 → ≤5 条结果喂给模型 → 一段 1~4 句中文口语直接回答问题，末尾至多留 1 条
#         最相关的干净链接；没搜到就老实说没搜到，不许编
#   #读 → 正文前 3000 字 → 1~3 句自然要点（≤300 字）+ 原链接
# 关掉开关（=0）= 原始列表贴法：标题｜摘要 + 链接逐条贴，只用于调试/对比。
# 模型调用失败就退回原始列表贴法（不向用户报错），日志写清走的是哪条路。
# 链接一律先洗掉 fpid/fid/var/spm/from/utm_* 之类跟踪参数再贴。
#
# 为什么「只留一条链接」也顺带修掉了一个真机 bug：上一版按「第几条」把模型的转述
# 与 hits 下标配对，模型少讲/串了顺序时会得到「标题说韩联社、链接却是 api-docs」的
# 错配（用户截图就是这个）。现在模型只回答一段话，并单独选一个序号 → 链接与内容
# 天然对齐。

_NUM_PREFIX_RE = re.compile(r"^\s*(?:\d{1,2}\s*[.、)]|[-*·•#＃])\s*")
# 模型偶尔把三条挤成一行（「1. … 2. … 3. …」）：先在序号前补换行，再逐行处理
_NUM_INLINE_RE = re.compile(r"(?<=\S)[ \t　]+(\d{1,2})\s*[.、)]\s*")
_URL_RE = re.compile(r"https?://\S+")


def _style_persona(botdir):
    """转述用的人格：没人格就给一句中性口吻（不让人格空着把提示词拼坏）。"""
    return (_persona(botdir) or "你是一个说话自然、语气亲切的中文聊天机器人。")[:_WEB_STYLE_PERSONA]


def _style_answer(text, limit=_WEB_ANSWER_CHARS):
    """合成式回答的清洗：去编号/清单/小标题/Markdown、去链接、压成一段口语。

    模型偶尔还是会把资料列成 1./2./3.，群聊里那既不像角色也不像聊天 —— 这里只做
    减法：删掉行首编号与符号，把多行并成一段，超长在标点处截断。
    """
    text = _NUM_INLINE_RE.sub(lambda m: " " + m.group(1) + ". ", str(text or ""))
    keep = []
    for raw in text.splitlines():
        s = _NUM_PREFIX_RE.sub("", str(raw or "")).strip()
        s = s.lstrip("#＃> 　").strip()
        if s:
            keep.append(s)
    s = _clean_reply(" ".join(keep), limit)
    return _URL_RE.sub("", s).strip()


# 末尾那一行「链接：N」：N 是模型挑的最相关结果序号，0 = 不挑。
# 模型不一定换行（实测会缩在最后一句后面），所以按「最后一次出现」定位，不要求行首。
_ANSWER_LINK_RE = re.compile(r"(?:【|\[)?\s*链接\s*(?:】|\])?\s*[:：]\s*(\d{1,2})")


def _split_answer(raw):
    """模型的回答 → (正文, 链接序号)。序号取不到就按 0 处理（不硬塞一个可能错的链接）。"""
    text = str(raw or "")
    idx = 0
    hits = list(_ANSWER_LINK_RE.finditer(text))
    if hits:
        m = hits[-1]
        try:
            idx = int(m.group(1))
        except Exception:
            idx = 0
        text = text[:m.start()]
    return _style_answer(text), idx


def _hits_text(hits):
    """命中 → 拼给模型看的编号资料（序号是全局的，链接就用这个序号选）。"""
    out = []
    for i, h in enumerate(hits[-_HITS_SHOW_MAX:], 1):
        one = " ".join(str(h.get("summary") or "").split())[:_WEB_SUMMARY_CHARS]
        out.append("%d. %s" % (i, str(h.get("title") or "(无标题)")[:120]))
        if one:
            out.append("   资料：%s" % one)
    return chr(10).join(out)


def _hits_block(hits):
    """自判路径拼进上下文的资料段（带全局编号，模型用编号选链接）。"""
    return ("以下是刚查到的资料（累计 %d 条，序号只是给我自己看的）：" % len(_shown_hits(hits))
            + chr(10) + _hits_text(hits))


def _shown_hits(hits):
    """模型看到的那批命中（与 _hits_text 的编号一一对应）。"""
    return list(hits[-_HITS_SHOW_MAX:])


def _search_more_rule(left):
    """还能不能再查：left > 0 允许继续要资料，left == 0 收尾。"""
    if left > 0:
        return ("- 如果这些资料还不够回答，只输出一行 <search>新的关键词</search> 继续查"
                "（还能查 %d 次）；够了就直接回答。" % left)
    return ("- 最后一次搜索已经用完了，不要再输出 <search>。按现有资料用自己的话回答；"
            "资料不够就老实说不清楚/没查到，可以一句话带过查到的线索，别编造。")


def _search_answer_prompt(botdir, hits, query, left=_SRCH_MAX):
    """#搜 / 自判的合成提示词：直接回答用户那句话，最多留一条最相关的链接。"""
    return (
        "你是下面这个人格。刚才有人问你：%s\n\n"
        "【人格】\n%s\n\n"
        "【查到的资料】（序号只是给我自己看的，别写进回答）\n%s\n\n"
        "【要求】\n"
        "- 用你自己的话直接回答那个问题，1~4 句中文口语，像刚翻完资料回朋友消息\n"
        "- 不要列清单、不要出现「1./2./3.」这类编号、不要小标题、不要「摘要如下」\n"
        "- 不要在句子里贴链接、不要念资料标题；英文、韩文内容用中文讲\n"
        "- 资料没提到的别编；拿不准就说拿不准，或者直接说没查到\n"
        "- 如果资料跟问题根本对不上，就说没搜到靠谱的，不要硬凑\n"
        "%s\n"
        "- 回答完另起一行，只写「链接：N」—— N 是你引用最多、最相关的资料序号；"
        "都不相关就写「链接：0」\n"
    ) % (str(query or "")[:120], _style_persona(botdir), _hits_text(hits),
         _search_more_rule(left))


async def _search_answer_styled(appid, botdir, hits, query, conf, left=_SRCH_MAX):
    """合成一句人设化回答（+ 至多一条干净链接）。返回 (文字, 模型是否还想要资料, query)。

    文字为空 = 这次没成（调用失败/没内容），调用方自己决定退回哪条路。
    """
    if not conf:
        return "", False, ""
    sconf = _conf_for("search") or conf
    t0 = time.time()
    try:
        raw = await asyncio.to_thread(
            _call_llm_sync, sconf,
            [{"role": "user", "content": _search_answer_prompt(botdir, hits, query, left)}],
            _WEB_STYLE_MAXTOK, 0.8,
        )
    except Exception as e:
        log("联网：合成回答失败 %r（模型 %s）" % (e, sconf["model"]), appid)
        return "", False, ""
    want = _WEB_MARK_RE.search(raw or "")
    if want:
        return "", True, (want.group(1) or "").strip()[:120]
    answer, idx = _split_answer(raw)
    if not answer:
        log("联网：合成回答没返回内容（模型 %s）" % sconf["model"], appid)
        return "", False, ""
    url = ""
    shown = _shown_hits(hits)
    if 1 <= idx <= len(shown):
        url = _strip_tracking(shown[idx - 1].get("url") or "")
    log("联网：已合成一句回答（%d 字｜模型 %s｜%.1fs｜引用第 %d 条%s）"
        % (len(answer), sconf["model"], time.time() - t0, idx,
           "：" + url[:40] if url else "（没选链接）"), appid)
    return answer + (chr(10) + url if url else ""), False, ""


# 兜底文案：模型没给出「不清楚」时用它（人格拿不到时也要能回一句人话）
_NO_RESULT_FALLBACK = "这个我是真不清楚诶，等我查明白再说吧。"


async def _no_result_reply(appid, botdir, query, conf, rounds=1, hits=None):
    """查了 N 轮还是没有结果：用该 bot 的人格语气说「不清楚」，一句带过查到的线索。

    返回 "" = 模型没给出可用的话（调用方用 _NO_RESULT_FALLBACK）。
    """
    if not conf:
        return ""
    sconf = _conf_for("search") or conf
    clues = ""
    for h in (hits or [])[-2:]:
        t = " ".join(str(h.get("title") or "").split())[:60]
        if t:
            clues += "- " + t + chr(10)
    prompt = (
        "你是下面这个人格。刚才有人问你：%s\n\n"
        "【人格】\n%s\n\n"
        "【情况】你联网查了 %d 轮，还是没找到能回答这个问题的靠谱资料。%s"
        "【要求】用 1~2 句中文口语告诉对方你不清楚/没查到；"
        "如果有沾边的线索，一句话带过就行；不要编造任何事实、不要列清单、不要贴链接。\n"
    ) % (str(query or "")[:120], _style_persona(botdir), rounds,
         ("查到过的沾边标题（仅供参考，别当成答案）：" + chr(10) + clues) if clues else "")
    try:
        raw = await asyncio.to_thread(_call_llm_sync, sconf,
                                     [{"role": "user", "content": prompt}], 200, 0.8)
    except Exception as e:
        log("联网：没查到的说辞没生成（%r）→ 用固定文案" % e, appid)
        return ""
    out = _style_answer(_strip_search_marks(raw), 140)
    if not out:
        return ""
    log("联网：没查到时已按人设回一句（模型 %s）" % sconf["model"], appid)
    return out


def _fetch_style_prompt(botdir, content):
    """#读 的提示词：正文前 3000 字 → 1~3 句自然要点（≤300 字），不要说明书腔。"""
    return (
        "你是下面这个人格。有人把这篇网页丢给你，让你看完说两句。\n\n"
        "【人格】\n%s\n\n"
        "【要求】\n"
        "- 用你自己的话把这篇讲了什么说出来，1~3 句中文口语，最多 %d 字\n"
        "- 像跟朋友转述刚看到的东西：不要「摘要如下」「本文介绍了」这种说明书腔\n"
        "- 不要编号、不要分点、不要小标题、不要照搬原文长句、不要写链接\n\n"
        "【网页开头】\n%s\n"
    ) % (_style_persona(botdir), _FETCH_OUT_CHARS,
         str(content or "")[:_FETCH_STYLE_CHARS])


async def _fetch_reply_styled(appid, botdir, content, url, conf):
    """#读 的人设化回复：≤300 字摘要 + 原链接。返回 "" = 没成（退回原始贴法）。"""
    if not conf or not str(content or "").strip():
        return ""
    sconf = _conf_for("search") or conf
    t0 = time.time()
    try:
        raw = await asyncio.to_thread(
            _call_llm_sync, sconf, [{"role": "user", "content": _fetch_style_prompt(botdir, content)}],
            _FETCH_OUT_CHARS * 2, 0.6,
        )
    except Exception as e:
        log("联网：网页转述失败 %r（模型 %s → 退回原始贴法）" % (e, sconf["model"]), appid)
        return ""
    body = _clean_reply(raw, _FETCH_OUT_CHARS)
    if not body:
        log("联网：网页转述没返回内容（模型 %s → 退回原始贴法）" % sconf["model"], appid)
        return ""
    log("联网：网页已按人设转述（%d 字｜模型 %s｜%.1fs）"
        % (len(body), sconf["model"], time.time() - t0), appid)
    return body + chr(10) + _strip_tracking(url)


def _is_owner_msg(botdir, message, key=""):
    """这条消息是不是主人发的（# 指令只认主人）。

    私聊：会话键就是 "p" + 主人 openid（_is_master_key 同一套判定）；
    群聊：拿消息作者的 openid 跟 _owner_openid 比。主人判定不另算一套 ——
    来源仍是 owner.json（第一个私聊 bot 的人）→ 回退 known_chats 的第一条 c2c。
    """
    owner = _owner_openid(botdir)
    if not owner:
        return False
    if str(key or "").startswith("p"):
        return str(key) == "p" + owner
    try:
        return owner in [str(x) for x in _author_ids(message)]
    except Exception:
        return False


async def _ask_next_query(appid, botdir, conf, user_query, tries, left):
    """一轮没搜到：问模型要不要换关键词再查。返回新关键词，"" = 它觉得够了。"""
    if not conf or left <= 0:
        return ""
    sconf = _conf_for("search") or conf
    prompt = (
        "你是下面这个人格。有人问你：%s\n\n"
        "【人格】\n%s\n\n"
        "【情况】已经用这些关键词查过：%s，都没搜到能用的资料。\n"
        "【要求】如果换个关键词还有希望，只输出一行 <search>新关键词</search>"
        "（还能查 %d 次）；觉得查不到了就只输出一行：够了\n"
    ) % (str(user_query or "")[:120], _style_persona(botdir),
         "、".join(tries[-5:]), left)
    try:
        raw = await asyncio.to_thread(_call_llm_sync, sconf,
                                     [{"role": "user", "content": prompt}], 120, 0.6)
    except Exception as e:
        log("联网：换关键词的追问失败 %r" % e, appid)
        return ""
    m = _WEB_MARK_RE.search(raw or "")
    return (m.group(1) or "").strip()[:120] if m else ""


async def _manual_search_answer(appid, botdir, conf, arg):
    """手动 #搜 的多轮：第 1 轮用主人给的词，之后模型可以继续 <search> 追问（≤5 轮）。

    → (文字, 状态, 命中列表)，状态：ok = 有合成回答 / none = 一条都没搜到 /
      fail = 搜到了但模型没给出可用回答（调用方自己决定退路）。
    """
    hits, last_q = [], str(arg or "").strip()[:120]
    tries = [last_q]
    for r in range(1, _SRCH_MAX + 1):
        got = await _self_web_context(appid, conf, last_q, r,
                                     "manual" if r == 1 else "decide")
        hits = _dedup_hits(hits + got)
        if not hits:
            # 这一轮没命中：问模型要不要换个关键词（最多查 _SRCH_MAX 轮）
            nxt = await _ask_next_query(appid, botdir, conf, arg, tries, _SRCH_MAX - r)
            if not nxt:
                log("联网：搜索 %d 轮未得结果，按「不清楚」回复" % r, appid)
                return "", "none", hits
            last_q = nxt
            tries.append(nxt)
            continue
        text, wants, q = await _search_answer_styled(
            appid, botdir, hits, arg, conf, left=_SRCH_MAX - r)
        if text:
            return text, "ok", hits
        if not wants:
            return "", "fail", hits       # 模型调用失败/空回答
        last_q = q
    log("联网：搜索 %d 轮未得结果，按「不清楚」回复" % _SRCH_MAX, appid)
    return "", "exhausted", hits


async def _local_web_cmd(appid, message, text, key="", group_openid="", botdir=""):
    """联网指令：#搜 / #读（只认主人）。返回 True = 这条已经回了；False = 交给正常链路。

    主人发来：按人格合成一段自然回答（#搜 多轮搜索 ≤5 轮、末尾至多一条干净链接）；
    接口挂了（网络/HTTP）不报错给用户、返回 False 交给正常回复；
    接口通了但没结果是「没查清楚」一句话；合成没成就退回原始列表贴法。
    非主人发来：完全不当指令（不搜索、不抓取、# 也不剥），整句按普通聊天走。
    botdir 用来读人格与主人 openid。
    """
    _usage_appid.set(appid)          # 这条链路里的模型调用记到该 bot 账上
    kind, arg = _web_cmd_of(text)
    if not kind:
        return False
    if not _is_owner_msg(botdir, message, key):
        # 不是主人：# 不当指令，整句（含 #）原样交给普通聊天，模型照人设回
        log("联网：非主人使用 # 指令，降级为普通聊天（不触发）", appid)
        return False
    api = getattr(message, "_api", None)
    conf = _llm_conf(_base_dir) if _base_dir else None

    async def _send(reply):
        try:
            if str(key or "").startswith("p"):
                await api.post_c2c_message(
                    openid=getattr(message.author, "user_openid", ""), msg_type=0,
                    msg_id=getattr(message, "id", ""), content=reply)
            else:
                await api.post_group_message(
                    group_openid=group_openid, msg_type=0,
                    msg_id=getattr(message, "id", ""), content=reply)
        except Exception as e:
            log("回消息失败（联网指令）：%r" % e, appid)

    if not arg:
        await _send("用法：#搜 关键词（我上网看看再答你）｜#读 网址（看完说两句）")
        return True
    if conf is None:
        log("联网指令跳过：模型配置没填（llm.json 缺 key / 地址）", appid)
        return False
    if not conf.get("web_search_enabled", True):
        log("联网指令 %s 跳过：联网搜索已关闭（web_search_enabled=0），一个请求都不发" % kind, appid)
        await _send("联网搜索关着呢，去设置里打开再问我吧。")
        return True
    if not _group_web_on(botdir, group_openid):
        # 主人也不行：群配置里显式关了联网，这条指令一个请求都不发
        log("联网指令 #%s 被拒：该群已关闭联网（groups/%s.json web_search=false，主人也不行）"
            % ("搜" if kind == "search" else "读", _oid8(group_openid)), appid)
        await _send("这个群关掉了联网，去「bot 详情 → 群配置」里打开再问我吧。")
        return True
    style_on = bool(conf.get("search_persona_style", True))
    if kind == "search":
        if style_on:
            # 人设化：多轮搜索（≤5 轮）后合成一段自然回答
            _plog(conf, "手动 #搜（主人）「%s」" % arg[:30], appid)
            answer, state, hits = await _manual_search_answer(appid, botdir, conf, arg)
            if state == "ok" and answer:
                await _send(answer)
                return True
            if state in ("none", "exhausted"):
                # 一条没搜到 / 5 轮都没查到：让人格说「不清楚」，一句带过沾边线索
                none_line = await _no_result_reply(
                    appid, botdir, arg, conf, _SRCH_MAX if state == "exhausted" else 1, hits)
                await _send(none_line or _NO_RESULT_FALLBACK)
                return True
            if not hits:
                log("联网指令 #搜「%s」接口失败，交给正常回复" % arg[:30], appid)
                return False
            # 有资料但模型没给出可用回答：退回原始列表贴法（用户至少看到结果）
            reply = _search_reply_text(hits)
            _plog(conf, "手动 #搜（主人）「%s」：回 %d 条（原始列表贴法）"
                % (arg[:30], min(len(hits), _WEB_REPLY_MAX)), appid)
            await _send(reply)
            return True
        hits = await asyncio.to_thread(web_search, arg, conf, appid, _WEB_COUNT, None,
                                      "manual", 1)
        if not hits:
            _plog(conf, "手动 #搜（主人）「%s」没搜到结果，交给正常回复" % arg[:30], appid)
            return False
        reply = _search_reply_text(hits)
        log("联网指令 手动 #搜（主人）「%s」：回 %d 条（原始列表贴法，人设化加工已关"
            " search_persona_style=0）" % (arg[:30], min(len(hits), _WEB_REPLY_MAX)), appid)
        await _send(reply)
        return True
    content = await asyncio.to_thread(web_fetch, arg, conf, appid)
    reply = await _fetch_reply_styled(appid, botdir, content, arg, conf) if style_on else ""
    if reply:
        log("联网指令 #读 %s：人设化加工（≤%d 字摘要 + 原链接）" % (arg[:40], _FETCH_OUT_CHARS),
            appid)
        await _send(reply)
        return True
    reply = _fetch_reply_text(content)
    if not reply:
        log("联网指令 #读 %s 没读到正文，交给正常回复" % arg[:40], appid)
        return False
    log("联网指令 #读 %s：回前 %d 字（原始贴法%s）"
        % (arg[:40], min(len(content), _WEB_FETCH_CHARS),
           "，人设化加工已关 search_persona_style=0" if not style_on else ""), appid)
    await _send(reply)
    return True


# 自动联网的触发词：出现这些就说明「答案不在聊天记录里」。宁可多搜一次（一次 0.005 元）。
# 有了「模型自判」之后它退成兜底：模型没主动要资料、但这句明显在问时效性内容时补一次。
_WEB_HINTS = ("最近", "最新", "今天", "新闻", "多少钱", "价格", "什么时候", "谁是",
              "查一下", "查下", "实时", "汇率", "股价", "比分", "发布会")


def _needs_web(text):
    t = str(text or "")[:200]
    return any(h in t for h in _WEB_HINTS)


# ── 模型自判搜索（一次性 mini tool-loop）────────────────────
#
# 不做 agent、不做多轮：只在 system 里加一段规则，模型不确定/需要最新信息时**只输出**
# 一行 <search>关键词</search>；本地解析到就搜一次、把资料拼进第二次请求，第二轮明确
# 禁止再要资料。单条消息最多 1 次 search + 1 次额外模型调用。
# 标记永远从最终回复里剥掉（用户不该看见 <search>）。
_WEB_MARK_RE = re.compile(r"<\s*search\s*>(.*?)<\s*/\s*search\s*>", re.I | re.S)
_WEB_MARK_ANY_RE = re.compile(r"<\s*/?\s*search\s*>", re.I)

_WEB_HINT_RULE = (
    chr(10) * 2 + "【要不要先上网查】" + chr(10) + chr(10).join([
        "- 你确定答案、也不需要最新信息时，照常回答，不要查。",
        "- 不确定、或需要最新/外部信息（新闻、版本、价格、赛事、人物近况等）时，"
        "只输出一行 <search>关键词</search>，不要猜、不要编、不要顺便给出答案。",
    ])
)

_WEB_HINT_STOP = (
    chr(10) * 2 + "【资料已经查到了】" + chr(10) + chr(10).join([
        "- 直接用下面这些资料，用你自己的语气回答对方那句话。",
        "- 不要再输出 <search>，也不要编资料里没有的东西。",
    ])
)

# 三种来源的日志标签（验收要能一眼看出是谁发起的搜索）
_WEB_SRC = {"manual": "手动 #搜", "decide": "模型自判搜索", "hint": "触发词搜索"}


def _strip_search_marks(text):
    """把 <search>…</search>（以及残缺的半截标记）从正文里剥掉。"""
    return _WEB_MARK_ANY_RE.sub("", _WEB_MARK_RE.sub("", str(text or ""))).strip()


def _web_context_block(hits):
    """自动搜索的命中 → 拼进上下文的 system 段（每条摘要截 300 字，链接去掉跟踪参数）。"""
    lines = []
    for h in hits[:_WEB_REPLY_MAX]:
        one = (h.get("summary") or "")[:_WEB_SUMMARY_CHARS]
        lines.append("- %s｜%s｜%s" % (h.get("title") or "(无标题)", one,
                                     _strip_tracking(h.get("url") or "")))
    return ("（联网搜索结果，只是参考信息：用它回答问题，但别念链接、别说自己在联网）"
            + chr(10) + chr(10).join(lines))


async def _auto_web_context(appid, conf, text):
    """触发词兜底：命中时效性触发词就搜一次，返回要拼进上下文的段（失败返回 ""）。

    模型自判开着的时候它是兜底（模型没主动要资料才轮到它），关掉自判就是老逻辑。
    """
    q = str(text or "").strip()
    if not q or not conf or not conf.get("web_search_enabled", True):
        return ""
    if not _needs_web(q):
        return ""
    hits = await asyncio.to_thread(web_search, q, conf, appid, _WEB_COUNT, None, "hint")
    if not hits:
        return ""
    return _web_context_block(hits)


async def _self_web_context(appid, conf, query, round_no=1, source="decide"):
    """搜一次（第 round_no 轮），返回命中列表（失败/没结果 = []）。"""
    q = str(query or "").strip()[:120]
    if not q or not conf:
        return []
    return await asyncio.to_thread(web_search, q, conf, appid, _WEB_COUNT, None, source,
                                  round_no)


def _dedup_hits(hits):
    """多轮累加：同一 url 只留第一次出现的，别把同一篇资料重复喂给模型。"""
    out, seen = [], set()
    for h in hits:
        u = str(h.get("url") or "")
        if u and u in seen:
            continue
        if u:
            seen.add(u)
        out.append(h)
    return out



# ── 定时消息 ──────────────────────────────────────────
#
# 任务表放在 base/tasks.json，一条长这样：
#   {"id": "...", "appid": "...", "kind": "group"/"c2c", "target": "openid",
#    "text": "...", "next": 时间戳, "enabled": true}
# next 到点就发，发完推到明天同一时刻（每天一次）。


def _tasks_path(base=""):
    root = base or _base_dir or os.getcwd()
    return os.path.join(root, "tasks.json")


def task_list(base=""):
    """返回任务表 JSON 字符串"""
    import json
    arr = _load_json(_tasks_path(base), [])
    if not isinstance(arr, list):
        arr = []
    return json.dumps(arr, ensure_ascii=False)


# 特殊目标：本 bot 已加入的所有群（Bot 页「目标」下拉里的「所有已加入的群聊」）。
# tasks.json 里就存这个字符串，UI 不用知道群 openid 列表，运行时现查运行记录。
ALL_GROUPS = "ALL_GROUPS"

# 群发之间的间隔：一口气连发会被平台限频（同一个群每分钟条数有限）
_GROUP_SEND_GAP = 0.6


def _known_groups(botdir):
    """本 bot 已加入的群 openid（只读已有的运行记录，与 ids_for 同一批来源）。

    known_chats.json 的 chats[kind=group] 为主，memory.json 的 g+openid 键兜底/补充；
    老设备上可能只写过其中一种，所以两边都读、按出现顺序去重。
    """
    out = []
    seen = set()
    if not botdir:
        return out
    kc = _load_json(os.path.join(botdir, "known_chats.json"), {})
    chats = kc.get("chats") if isinstance(kc, dict) else None
    if isinstance(chats, list):
        for c in chats:
            if not isinstance(c, dict):
                continue
            if str(c.get("kind") or "") != "group":
                continue
            oid = str(c.get("openid") or "").strip()
            if oid and oid not in seen:
                seen.add(oid)
                out.append(oid)
    h = _memory(botdir)
    for k in h.keys():
        s = str(k)
        if len(s) < 2 or not s.startswith("g"):
            continue
        oid = s[1:].strip()
        if oid and oid not in seen:
            seen.add(oid)
            out.append(oid)
    return out


def task_save(base, arr_json):
    import json
    try:
        arr = json.loads(arr_json) if isinstance(arr_json, str) else arr_json
    except Exception:
        return "任务格式不对"
    if not isinstance(arr, list):
        return "任务得是数组"
    _save_json(_tasks_path(base), arr)
    return "已保存 %d 条定时消息" % len(arr)


async def _send_all_groups(client, t, botdir=""):
    """target = ALL_GROUPS：遍历本 bot 已加入的群逐个发。

    拿不到群列表时**不静默失败**：写一条能读懂的日志（用户能在日志页看到），返回 False。
    """
    text = str(t.get("text") or "").strip()
    appid = str(t.get("appid") or "")
    groups = _known_groups(botdir)
    if not groups:
        log("定时消息跳过：还没记录到已加入的群（先让 bot 在群里被 @ 一次再等下一轮）", appid)
        return False
    sent = 0
    failed = 0
    for oid in groups:
        if not _group_conf(botdir, oid)["enabled"]:
            log("定时消息跳过：群 %s 已静音（groups/%s.json enabled=false）"
                % (_oid8(oid), _oid8(oid)), appid)
            continue
        try:
            await client.api.post_group_message(group_openid=oid, msg_type=0, content=text)
            sent += 1
        except Exception as e:
            failed += 1
            log("群发失败（%s）：%r" % (str(oid)[:12], e), appid)
        await asyncio.sleep(_GROUP_SEND_GAP)
    log("定时消息群发：%d/%d 个群成功" % (sent, len(groups)), appid)
    return sent > 0


async def _send_task(client, t, botdir=""):
    target = str(t.get("target") or "").strip()
    text = str(t.get("text") or "").strip()
    if not target or not text:
        return False
    if target == ALL_GROUPS:
        return await _send_all_groups(client, t, botdir)
    if str(t.get("kind") or "group") == "c2c":
        await client.api.post_c2c_message(openid=target, msg_type=0, content=text)
    else:
        await client.api.post_group_message(group_openid=target, msg_type=0, content=text)
    return True


# asyncio 只保留 task 的弱引用：create_task 后不留强引用，任务可能在跑到一半被回收
# （表现就是「定时消息/随机问候明明起了却从不动」）。这里显式留一份。
_sched_tasks = set()


def _spawn_sched(coro):
    t = asyncio.create_task(coro)
    _sched_tasks.add(t)
    t.add_done_callback(_sched_tasks.discard)
    return t


# 定时消息（tasks.json）整体下线：文件保留不删（数据留着，日后好恢复），但一条都不再执行。
# 30 秒一轮的循环里每轮都读得到，所以按 appid 只记一次日志，不刷屏。
_timers_offline_logged = set()


async def _sched_loop(client, appid, botdir):
    """每 30 秒扫一次任务表。只在 bot 自己的循环里跑，不跨线程。"""
    import time as _t
    log("定时/问候调度已起（每 30 秒一轮）", appid)
    try:
        while True:
            await asyncio.sleep(30)
            try:
                arr = _load_json(_tasks_path(), [])
                if isinstance(arr, list) and arr:
                    # 有历史任务也不执行：定时消息这块已经从编辑页删掉，改由「定时问候」承担
                    if appid not in _timers_offline_logged:
                        _timers_offline_logged.add(appid)
                        log("定时消息已下线（改由定时问候承担），本次不执行；tasks.json 还有 %d 条，文件保留未删" % len(arr), appid)
                    arr = []
                if not isinstance(arr, list):
                    continue
                now = _t.time()
                changed = False
                for t in arr:
                    if not isinstance(t, dict):
                        continue
                    if str(t.get("appid") or "") != str(appid):
                        continue
                    if not t.get("enabled", True):
                        continue
                    try:
                        due = float(t.get("next") or 0)
                    except Exception:
                        due = 0
                    if due <= 0 or due > now:
                        continue
                    try:
                        ok = await _send_task(client, t, botdir)
                        log("定时消息%s：%s" % (
                            "发出" if ok else "跳过（目标缺失，或群列表还没记录到）",
                            str(t.get("text") or "")[:30]), appid)
                    except Exception as e:
                        log("定时消息失败：%r" % e, appid)
                    # 每天一次：推到明天同一时刻
                    t["next"] = due + 86400
                    t["last"] = now
                    changed = True
                if changed:
                    _save_json(_tasks_path(), arr)
                # 随机问候：同一个循环里顺手看一眼，不另起线程
                try:
                    await _maybe_greet(client, appid, botdir)
                except Exception as e:
                    log("随机问候出错：%r" % e, appid)
            except Exception as e:
                log("调度出错：%r" % e, appid)
    except asyncio.CancelledError:
        log("定时/问候调度被取消", appid)
        raise


def _fix_botpy_logging(datadir):
    """botpy 默认把日志写到 os.getcwd()，Android 的 cwd 是只读的 "/"，
    构造 client 时会 OSError: Read-only file system: '/botpy.log'。
    这里把它的默认处理器指到可写目录（进程级，一次即可）。"""
    try:
        import logging as _logging
        import botpy.logging as _bl
        _bl.DEFAULT_FILE_HANDLER["filename"] = os.path.join(datadir, "botpy-%(name)s.log")
        _bl.DEFAULT_FILE_HANDLER["backupCount"] = 2
        # botpy 的 HTTP 收发日志（DEBUG）会把 access_token 明文写进文件：
        # 抬到 WARNING 后不落盘，日志里只剩真正的问题。
        _bl.DEFAULT_FILE_HANDLER["level"] = _logging.WARNING
        return True
    except Exception as e:
        log("改 botpy 日志路径失败（忽略）：%r" % e)
        return False


# ── openid 采集 ───────────────────────────────────────
#
# QQ 的 openid 按 bot 分配，所以这些都写在 bot 自己的目录里，一个 bot 一份：
#   owner.json        {"member_openid","user_openid"}  —— 第一个私聊 bot 的人（主人）
#   known_chats.json  {"chats":[{"openid","kind"}]}    —— 见过的群 / 私聊对象
#   last_sender.json  {"member_openid","group_openid","user_openid","ts"} —— 群里最近发言人
# 格式沿用旧版，老设备上已有的文件能直接读，不用迁移。


def _note_chat(botdir, kind, openid):
    """记一个会话（群或私聊）。重复的 openid 不重复写。"""
    openid = str(openid or "").strip()
    if not openid:
        return
    path = os.path.join(botdir, "known_chats.json")
    d = _load_json(path, {})
    chats = d.get("chats") if isinstance(d, dict) else None
    if not isinstance(chats, list):
        chats = []
    if not any(isinstance(c, dict) and c.get("openid") == openid for c in chats):
        chats.append({"openid": openid, "kind": kind})
        _save_json(path, {"chats": chats})
    if kind == "c2c":
        # 主人 = 第一个私聊 bot 的人。已经有记录就不覆盖（后续私聊的人不能顶掉主人）
        op = os.path.join(botdir, "owner.json")
        old = _load_json(op, {})
        if not (isinstance(old, dict) and str(old.get("user_openid") or "").strip()):
            _save_json(op, {"member_openid": openid, "user_openid": openid})


def _note_sender(botdir, member_openid, group_openid=""):
    openid = str(member_openid or "").strip()
    if not openid:
        return
    _save_json(os.path.join(botdir, "last_sender.json"), {
        "member_openid": openid,
        "group_openid": str(group_openid or ""),
        "user_openid": openid,
        "ts": int(time.time()),
    })


def _author_ids(message):
    """从消息里尽量掏出 openid：不同 botpy 版本字段名不完全一致，有哪个用哪个。"""
    a = getattr(message, "author", None)
    out = []
    for n in ("member_openid", "user_openid", "id"):
        v = getattr(a, n, None) if a is not None else None
        if v:
            out.append(str(v))
    return out


# ── 重复消息去重（每个 bot 一份最近 msg_id 的 LRU）──────────────────
#
# 平台有时把**同一条消息**投给两个事件回调（私聊的 c2c 双投、群里的 @/普通两种），
# 两条都跑一遍就会重复回复，而第二次发送必然被 QQ 拒：
#   ServerError('消息被去重，请检查请求msgseq')
# 所以按 msg_id 去重，覆盖所有发送路径（回复 / #搜 / #读 / 合成回答 / 群回复）：
#   · 每个 bot 一份 LRU，300 条 / 10 分钟窗口，只放内存（重启进程自然清空，不落盘）
#   · msg_id 优先；拿不到才退回 (作者 + 文本前 40 字) 的哈希，窗口缩到 30 秒 ——
#     免得把「同一个人连发两句一样的话」当成重复吃掉
#   · 命中重复：不重试、不报错，只记一条日志
_DEDUP_LIMIT = 300
_DEDUP_TTL = 600          # msg_id 的去重窗口（秒）
_DEDUP_HASH_TTL = 30      # 没有 msg_id 时的兜底窗口（秒）
_dedup = {}               # appid -> OrderedDict{key: ts}


def _dedup_key(message):
    """→ (键, msg_id, 是否用 msg_id)。"""
    mid = str(getattr(message, "id", "") or getattr(message, "msg_id", "") or "").strip()
    if mid:
        return "id:" + mid, mid, True
    author = ""
    try:
        ids = _author_ids(message)
        author = ids[0] if ids else ""
    except Exception:
        author = ""
    txt = str(getattr(message, "content", "") or "").strip()[:40]
    return "h:" + author + "|" + txt, "", False


def _msg_dup(appid, message):
    """这条消息是不是已经处理过？→ (是否重复, msg_id 或占位说明)。"""
    import collections

    key, mid, by_id = _dedup_key(message)
    now = time.time()
    ttl = _DEDUP_TTL if by_id else _DEDUP_HASH_TTL
    with _lock:
        d = _dedup.get(str(appid or ""))
        if not isinstance(d, collections.OrderedDict):
            d = collections.OrderedDict()
            _dedup[str(appid or "")] = d
        for k in [k for k, ts in list(d.items()) if now - ts > ttl]:
            d.pop(k, None)
        ts = d.get(key)
        if ts is not None and (now - ts) <= ttl:
            d.move_to_end(key)
            return True, (mid or "(无 msg_id)")
        d[key] = now
        while len(d) > _DEDUP_LIMIT:
            d.popitem(last=False)
    return False, (mid or "(无 msg_id)")


def _dedupe_message(appid, message, source):
    """重复消息的统一 choke point：True = 这条已经处理过，调用方直接 return。"""
    dup, mid = _msg_dup(appid, message)
    if dup:
        log("去重：msg_id=%s 已处理过，跳过（来源=%s）" % (mid, source), appid)
    return dup


# ── 群普通消息（group_message_create）────────────────────
#
# QQ 现在推的是 group_message_create（没有 at_），而 botpy 1.2.1 的
# ConnectionState 只注册了 parse_group_at_message_create / parse_c2c_message_create。
# gateway.on_message 拿 event 查 self._parser，查不到就 `_parser unknown event` 直接丢掉
# （设备上 botpy-botpy.log 里就是这么刷的），我们的 on_group_at_message_create 从来没被调用。
#
# 这里在运行时给 botpy.connection.ConnectionState 补一条解析器：
# 那个类的 __init__ 用 inspect.getmembers 收集所有 parse_* 方法建表，
# 所以只要在构造连接之前往类上加一个 parse_group_message_create 就够了 ——
# 不改 botpy 包，也不影响别的实例。分发走 botpy 自己的 _dispatch，
# 落到每个 bot 各自的 on_group_message_create 上（appid / botdir 由闭包带过去，不会串台）。

_SEEN_LIMIT = 200
_seen_ids = []          # 最近处理过的消息 id（保留 200 个）
_seen_set = set()
_seen_lock = threading.Lock()

_RAW_MAX_EVENTS = 50    # gateway_raw.jsonl 最多记这么多条，够确认字段名就行
_RAW_MAX_BYTES = 512 * 1024
_RAW_MAX_LINE = 1500
_raw_state = {"n": 0, "stop": False}
_SENSITIVE = ("content", "text", "msg", "prompt")


def _mark_seen(mid):
    """同一个消息 id 只处理一次。返回 True = 新消息。

    平台有可能同时推 group_at_message_create 和 group_message_create，
    两条的 id 一样，这里去重后就只回一次。
    """
    mid = str(mid or "").strip()
    if not mid:
        return True
    with _seen_lock:
        if mid in _seen_set:
            return False
        _seen_set.add(mid)
        _seen_ids.append(mid)
        while len(_seen_ids) > _SEEN_LIMIT:
            _seen_set.discard(_seen_ids.pop(0))
    return True


def _shape(v, key="", depth=0):
    """只留结构、不留内容：正文换成长度，openid 之类打码。"""
    if depth > 6:
        return "..."
    if isinstance(v, dict):
        return {str(k): _shape(x, str(k), depth + 1) for k, x in list(v.items())[:40]}
    if isinstance(v, list):
        return [_shape(x, key, depth + 1) for x in v[:5]]
    if isinstance(v, bool) or v is None:
        return v
    if isinstance(v, (int, float)):
        return v
    s = str(v)
    kl = key.lower()
    if kl in _SENSITIVE:
        return "<str len=%d>" % len(s)
    if "openid" in kl or kl in ("id", "union_openid", "member_id"):
        return (s[:4] + "…(%d)" % len(s)) if len(s) > 8 else "***"
    return (s[:40] + "…(%d)" % len(s)) if len(s) > 40 else s


def _dump_gateway_raw(botdir, event, payload):
    """把一条下行事件的结构写到 <botdir>/gateway_raw.jsonl（脱敏、限长）。

    字段名和文档对不上时，这一行就是取证材料：key 名全保留，值只留长度/掩码。
    """
    if _raw_state["stop"]:
        return
    try:
        path = os.path.join(botdir, "gateway_raw.jsonl")
        if _raw_state["n"] >= _RAW_MAX_EVENTS:
            _raw_state["stop"] = True
            return
        line = json.dumps({"ts": int(time.time()), "t": event, "payload": _shape(payload)},
                          ensure_ascii=False)
        if len(line) > _RAW_MAX_LINE:
            line = line[:_RAW_MAX_LINE] + "…(truncated)"
        try:
            if os.path.getsize(path) > _RAW_MAX_BYTES:
                _raw_state["stop"] = True
                return
        except Exception:
            pass
        with open(path, "a", encoding="utf-8") as f:
            f.write(line + "\n")
        _raw_state["n"] += 1
    except Exception as e:
        log("写 gateway_raw.jsonl 失败（忽略）：%r" % e)


class _GroupMsg:
    """把 group_message_create 的 payload 包成和 botpy GroupMessage 一样的字段。

    两种形态都兜底（谁命中用谁）：
      group_openid : d.group_openid            / d.message.group_openid
      author       : d.author.member_openid    / d.author.user_openid
                     d.message.author.member_openid / d.message.author.user_openid
    """

    __slots__ = ("id", "content", "group_openid", "author", "mentions", "_api", "raw")

    class _Author:
        __slots__ = ("member_openid", "user_openid", "id", "bot")

    def __init__(self, api, payload):
        p = payload if isinstance(payload, dict) else {}
        d = p.get("d") if isinstance(p.get("d"), dict) else p
        inner = d.get("message") if isinstance(d.get("message"), dict) else d
        a = d.get("author") if isinstance(d.get("author"), dict) else None
        ai = inner.get("author") if isinstance(inner.get("author"), dict) else None
        au = _GroupMsg._Author()
        au.member_openid = (ai or a or {}).get("member_openid") or (a or {}).get("member_openid")
        au.user_openid = (ai or a or {}).get("user_openid") or (a or {}).get("user_openid")
        au.id = (ai or a or {}).get("id")
        # 作者是不是机器人：哪个字段有就用哪个，都没有留 None（判不出 → 按人处理前先看 @）
        _bot = None
        for _src in (ai, a):
            if isinstance(_src, dict) and isinstance(_src.get("bot"), bool):
                _bot = _src["bot"]
        au.bot = _bot
        self.author = au
        self._api = api
        self.raw = p
        self.id = inner.get("id") or p.get("id") or ""
        self.content = inner.get("content") or ""
        # 群 id：d.group_openid → d.message.group_openid。
        # __slots__ 里有这一项却没赋值 → getattr 走默认值，日志「群=」永远空。
        self.group_openid = (d.get("group_openid") or (inner or {}).get("group_openid")
                             or p.get("group_openid") or "")
        # 被 @ 的人（含 bot）在 d.mentions / d.message.mentions；拿不到就是空表
        self.mentions = (_entries(inner.get("mentions")) or _entries(d.get("mentions"))
                         or _entries(p.get("mentions")))


def _install_group_message_parser():
    """给 botpy 的 ConnectionState 补解析器（运行时注册，幂等）。"""
    try:
        import botpy.connection as _bc
        if getattr(_bc.ConnectionState, "_andbna_group_parsed", False):
            return True

        def parse_group_message_create(self, payload):
            try:
                _msg = _GroupMsg(getattr(self, "api", None), payload)
            except Exception as e:
                _log_soft("组群消息解析失败：%r" % e)
                return None
            self._dispatch("group_message_create", _msg)
            return None

        _bc.ConnectionState.parse_group_message_create = parse_group_message_create
        _bc.ConnectionState._andbna_group_parsed = True
        return True
    except Exception as e:
        log("注册 group_message_create 解析器失败：%r" % e)
        return False


def _log_soft(msg):
    """解析器跑在 botpy 的调用栈里，拿不到 appid；写进总日志即可。"""
    try:
        log(msg)
    except Exception:
        pass


# ── 群级配置（<botdir>/groups/<group_openid>.json）──────────────────
#
# 一个群一份覆盖；**没有这份文件 = 这个群全部走 bot 全局配置**（升级后老用户零变化）。
# 字段缺项 / null / 0 都是「跟随」，不是 false：
#   {"auto_reply": true|null,   群里是否自动接话（覆盖该 bot 的覆盖值 / 全局开关）
#    "daily_limit": 0,          本群每天最多回几条（0 = 不限），计数在 <botdir>/group_quota.json
#    "web_search": true|null,   false = 本群禁止自判搜索与 #搜 / #读（主人也不行）
#    "enabled": true}           false = 本群完全静默（回复 / 随机问候 / 定时群发都不发）
# 群人格（persona / <gid>.persona.txt）已下线：人格恒取 bot 详情页那一份。
# 群消息是每条都要过的热路径，所以读盘带缓存：文件 mtime / size 变了才重读。

_GROUP_DEFAULT = {
    "auto_reply": None,
    "daily_limit": 0,
    "web_search": None,
    "enabled": True,
}

_group_cache = {}           # (botdir, gid) -> ((mtime_ns, size), conf)
_group_lock = threading.Lock()


def _groups_dir(botdir):
    return os.path.join(str(botdir or ""), "groups")


def _group_conf_path(botdir, gid):
    """群配置文件的路径；gid 里出现路径分隔符就返回空串（绝不拿它去拼路径）。"""
    gid = str(gid or "").strip()
    if not gid or gid in (".", "..") or os.sep in gid or (os.altsep and os.altsep in gid):
        return ""
    return os.path.join(_groups_dir(botdir), gid + ".json")


def _file_stamp(path):
    try:
        st = os.stat(path)
        return (st.st_mtime_ns, st.st_size)
    except OSError:
        return None


def _group_conf(botdir, gid):
    """该群的覆盖配置（缺项 = 跟随全局）。没有这份文件 = 全部跟随全局。"""
    path = _group_conf_path(botdir, gid)
    if not path:
        return dict(_GROUP_DEFAULT)
    key = (str(botdir or ""), str(gid))
    stamp = _file_stamp(path)
    if stamp is None:
        with _group_lock:
            _group_cache.pop(key, None)
        return dict(_GROUP_DEFAULT)
    with _group_lock:
        hit = _group_cache.get(key)
        if hit and hit[0] == stamp:
            return dict(hit[1])
    raw = _load_json(path, {})
    conf = dict(_GROUP_DEFAULT)
    if isinstance(raw, dict):
        for name in _GROUP_DEFAULT:
            if name not in raw:
                continue
            v = raw.get(name)
            if name in ("auto_reply", "web_search"):
                conf[name] = v if isinstance(v, bool) else None
            elif name == "enabled":
                conf[name] = v if isinstance(v, bool) else True
            elif name == "daily_limit":
                try:
                    conf[name] = max(0, int(v or 0))
                except (TypeError, ValueError):
                    conf[name] = 0
    with _group_lock:
        _group_cache[key] = (stamp, dict(conf))
    return conf


def _persona_for(botdir, _key):
    """这一轮用的人格正文：群人格已下线，恒用 bot 详情页那一份。"""
    return _persona(botdir)


def _group_of_key(key):
    """"g"+group_openid 这种会话键里的群 openid；不是群就返回空串。"""
    s = str(key or "")
    return s[1:].strip() if len(s) > 1 and s.startswith("g") else ""


def _group_web_on(botdir, gid):
    """本群允许联网吗：群配置 web_search 显式 false → False；null / 没有文件 → True。"""
    gid = str(gid or "").strip()
    if not gid:
        return True
    return _group_conf(botdir, gid).get("web_search") is not False


# ── 群资料（群名 / 人数）：GET /v2/groups/{group_openid}/info ──────────
#
# 官方只有这一个口径能拿到群名（30 次/分钟）。凭据就是 bot 自己的 AppID/AppSecret：
# 先换 app access token（与 botpy 登录同一套），再带 Authorization: QQBot <token> 调接口。
# token 按 appid 缓存到过期前一分钟；失败一律返回 {"ok": false}，界面用 openid 兜底。
_QQ_TOKEN_API = "https://bots.qq.com/app/getAppAccessToken"
_QQ_GROUP_API = "https://api.sgroup.qq.com/v2/groups/%s/info"

_gc_token_lock = threading.Lock()
_gc_tokens = {}     # appid -> (access_token, expire_ts)


def _app_access_token(appid, secret):
    """取（并缓存）bot 的 app access token；失败返回空串。"""
    appid = str(appid or "").strip()
    secret = str(secret or "").strip()
    if not appid or not secret:
        return ""
    now = time.time()
    with _gc_token_lock:
        hit = _gc_tokens.get(appid)
        if hit and hit[0] and hit[1] - 60 > now:
            return hit[0]
    try:
        import urllib.request
        req = urllib.request.Request(
            _QQ_TOKEN_API,
            data=json.dumps({"appId": appid, "clientSecret": secret}).encode("utf-8"),
            method="POST",
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(req, timeout=8) as r:
            raw = json.loads(r.read().decode("utf-8", "replace"))
    except Exception as e:
        log("群资料：取 token 失败 %r" % e)
        return ""
    token = str(raw.get("access_token") or "")
    try:
        ttl = int(raw.get("expires_in") or 7200)
    except (TypeError, ValueError):
        ttl = 7200
    if not token:
        log("群资料：token 响应里没有 access_token（%s）" % str(raw)[:120])
        return ""
    with _gc_token_lock:
        _gc_tokens[appid] = (token, now + max(60, ttl))
    return token


def group_info(appid, secret, gid):
    """一个群的资料：{"ok": true, "name": "…", "members": N}；拿不到就是 {"ok": false, "error": "…"}。

    7 天缓存由界面侧（GroupConfigScreen → group_names.json）负责，这里只做网络与 token 缓存。
    """
    gid = str(gid or "").strip()
    if not gid:
        return json.dumps({"ok": False, "error": "没有群 openid"}, ensure_ascii=False)
    token = _app_access_token(appid, secret)
    if not token:
        return json.dumps({"ok": False, "error": "取 token 失败"}, ensure_ascii=False)
    try:
        import urllib.request
        req = urllib.request.Request(_QQ_GROUP_API % gid, method="GET", headers={
            "Authorization": "QQBot " + token,
            "X-Union-Appid": str(appid or "").strip(),
        })
        with urllib.request.urlopen(req, timeout=8) as r:
            raw = json.loads(r.read().decode("utf-8", "replace"))
    except Exception as e:
        log("群资料：%s 读取失败 %r" % (_oid8(gid), e))
        return json.dumps({"ok": False, "error": "请求失败：%r" % e}, ensure_ascii=False)
    name = str(raw.get("group_name") or "").strip()
    try:
        members = int(raw.get("group_member_num") or 0)
    except (TypeError, ValueError):
        members = 0
    if not name and not members:
        return json.dumps(
            {"ok": False, "error": "响应里没有群名（%s）" % str(raw)[:120]}, ensure_ascii=False)
    return json.dumps({"ok": True, "name": name, "members": members}, ensure_ascii=False)


def _group_quota_path(botdir):
    return os.path.join(str(botdir or ""), "group_quota.json")


def _group_quota_take(botdir, gid, limit):
    """本群今天还能回吗：返回 (放行?, 今天已回条数)。放行时条数 +1 并落盘。

    计数和群配置分开存（group_quota.json）：界面改配置、甚至删掉覆盖，都不会把当天的
    计数冲掉。按群记 {gid: {"date": "YYYY-MM-DD", "n": N}}，跨天自然归零，
    7 天没动过的行顺手裁掉。
    """
    gid = str(gid or "")
    if not gid or not botdir or not os.path.isdir(str(botdir)):
        return True, 0
    path = _group_quota_path(botdir)
    today = _day_str()
    with _group_lock:
        d = _load_json(path, {})
        if not isinstance(d, dict):
            d = {}
        row = d.get(gid)
        used = 0
        if isinstance(row, dict) and str(row.get("date") or "") == today:
            try:
                used = int(row.get("n") or 0)
            except (TypeError, ValueError):
                used = 0
        if limit > 0 and used >= limit:
            return False, used
        d[gid] = {"date": today, "n": used + 1}
        keep = _day_str(-6)
        for other in [k for k, v in d.items()
                      if not isinstance(v, dict) or str(v.get("date") or "") < keep]:
            d.pop(other, None)
        _save_json(path, d)
    return True, used + 1


# ── 群消息闸门：作者过滤 / 回环 / @ 判定 / 限流 + 熔断 ──────────────
#
# 事故：群里 @ 一个 bot，4 个 bot 都回，互相触发 → 消息爆炸。
# 旧实现是「失败即放行」：拿不到自己的 id 就退化成 " <@" in content（任意 @ 都算叫我），
# 又把正文里第一个 <@xxx> 当成自己的 id 存盘 —— 记成别人的 id 就永久抢答。
# 这里每一步都反过来：判不出来就不回复，跳过原因写进应用内日志。
#
# 闸门顺序固定（前一道放行才进下一道）：
#   学 own_ids（仅可信 @ 事件）→ 作者过滤 → 回环 → @ 判定 → 限流/熔断

_MENTION_RE = re.compile(r"<@!?([0-9A-Fa-f]+)>")   # 平台把 @ 渲染成 <@hex> / <@!hex>
_TAG_RE = re.compile(r"<@!?[^>]*>")
_WS_RE = re.compile(r"\s+")

_OUTBOX_PER_GROUP = 32   # 每（bot, 群）记最近 32 条自己发出的文本（哈希）
_RATE_WINDOW = 60.0      # 限流窗口（秒）
_RATE_MAX = 6            # 每（bot, 群）窗口内最多回 6 条
_BREAK_MAX = 12          # 同一群窗口内累计 12 条「回复意图」→ 熔断
_BREAK_MUTE = 300.0      # 熔断静音 5 分钟

_gate_lock = threading.Lock()
_outbox = {}             # (appid, group) -> [hash(归一化文本), ...]
_rate = {}               # (appid, group) -> [ts, ...]
_intent = {}             # group -> [ts, ...]（含被限流挡下的，熔断用）
_mute = {}               # group -> 静音截止 ts
_self_ids = {}           # appid -> set(appid / robot.id 这类登记来的 id)
# 「群里自动接话」三层：全局开关（设置页那一行）+ 该 bot 的覆盖（Bot 编辑页三态）。
# 判定优先级：群配置显式值 > 该 bot 覆盖 > 全局。
_autochat = {"on": False}   # 全局开关（默认关）
_autochat_override = {}     # appid -> "1" / "0"；没有键 = 跟随全局


def set_group_autochat(appid="", override="", global_on=None):
    """Kotlin 侧推过来：global_on = 全局开关；appid + override = 该 bot 的覆盖（"" = 跟随全局）。

    老调用形式 set_group_autochat(True)（只有一个 bool）仍按「只改全局」处理。
    """
    global _autochat_hinted
    if isinstance(appid, bool) or appid is None:
        global_on = appid
        appid, override = "", ""
    if global_on is not None:
        _autochat["on"] = bool(global_on)
        if _autochat["on"]:
            _autochat_hinted = False      # 再关回来时还允许提示一条
    key = str(appid or "").strip()
    if key:
        v = str(override if override is not None else "").strip()
        if v in ("1", "0"):
            _autochat_override[key] = v
        else:
            _autochat_override.pop(key, None)
    log("群里自动接话：全局%s｜该 bot 覆盖=%s｜生效：%s" % (
        "开" if _autochat["on"] else "关（只回 @）",
        _autochat_override_label(key),
        "开" if _group_autochat_state(key, {})[0] else "关（只回 @）"))
    return "OK"


def _autochat_override_label(appid):
    """给人看的覆盖值：开 / 关 / 跟随全局。"""
    v = _autochat_override.get(str(appid or "").strip(), "")
    return "开" if v == "1" else ("关" if v == "0" else "跟随全局")


def _group_autochat_state(appid, gc):
    """该群的 auto_on 判定：群配置显式值 > 该 bot 覆盖 > 全局。返回 (on, 来源)。"""
    if gc.get("auto_reply") is not None:
        return bool(gc["auto_reply"]), "该群配置"
    v = _autochat_override.get(str(appid or "").strip(), "")
    if v in ("1", "0"):
        return v == "1", "该 bot 覆盖"
    return bool(_autochat["on"]), "全局"


def group_autochat(appid=""):
    """某 bot 的生效值（没有群上下文：只看覆盖 / 全局）。"""
    return _group_autochat_state(appid, {})[0]


_autochat_hinted = False


def _hint_autochat_once(appid=""):
    """群里自动接话关着时，本次进程只写一条提示（别每条消息刷屏）。"""
    global _autochat_hinted
    if _autochat_hinted or _group_autochat_state(appid, {})[0]:
        return False
    _autochat_hinted = True
    log("提示：群里自动接话关闭，当前只回 @（可在设置里打开「群里自动接话」）", appid)
    return True


def _register_self_id(appid, *ids):
    """登记本 bot 的 id：appid、robot.id。运行期登记，不落盘（own_ids.json 才落盘）。"""
    s = _self_ids.setdefault(str(appid), set())
    if appid:
        s.add(str(appid))
    for i in ids:
        if i:
            s.add(str(i))


def _self_id_set(appid):
    s = _self_ids.setdefault(str(appid), set())
    if appid:
        s.add(str(appid))
    return s


# ── 自己 id 的唯一可信来源：group_at_message_create（平台只在本 bot 被 @ 时推）──


def _own_ids_path(botdir):
    return os.path.join(botdir, "own_ids.json")


def _own_ids(botdir):
    d = _load_json(_own_ids_path(botdir), {})
    rows = d.get("ids") if isinstance(d, dict) else None
    return set(str(x) for x in rows) if isinstance(rows, list) else set()


def _note_own_ids(botdir, ids):
    ids = set(str(x) for x in ids if x)
    if not ids:
        return set()
    old = _own_ids(botdir)
    new = ids - old
    if new:
        _save_json(_own_ids_path(botdir), {"ids": sorted(old | new)})
    return new


def _learn_own_ids(botdir, content):
    """**只允许**在 group_at_message_create 里调用。

    普通群消息（group_message_create）推的是群里每一条消息，正文里出现的是别人
    的 @ —— 从那里学 id 正是旧实现把自己变成抢答器的原因。

    已知会发消息的 bot 的 openid（known_bots.json）和 mentions 里的 id 是同一套
    空间，所以学的时候剔除它们：一条 "@A @B" 不会让 A 把 B 的 id 记成自己的。
    """
    found = set(m.group(1) for m in _MENTION_RE.finditer(str(content or "")))
    if not found:
        return set()
    if len(found) > 1:
        # 一条 "@A @B" 会让 A 和 B 各收到一个 @ 事件，正文里两个 id 都在。
        # 判不出哪个是自己 → 这次一条都不学（学错就是永久抢答）。
        log("学 own_ids：正文里有 %d 个不同提及（%s），判不出哪个是自己 → 本次不学"
            % (len(found), ",".join(sorted(found))[:60]))
        return set()
    others = _known_bots(botdir)
    keep = found - others
    drop = found & others
    if drop:
        log("学 own_ids：正文里的 %s 是已知其它 bot 的 id，丢弃" % ",".join(sorted(drop))[:60])
    added = _note_own_ids(botdir, keep)
    if added:
        log("学 own_ids：%s（来源：可信 @ 事件）" % ",".join(sorted(added))[:80])
    return added


def _candidates(appid, botdir):
    """@ 判定用的候选 id 集合 = {appid} ∪ {robot.id} ∪ own_ids.json。"""
    ids = set(_self_id_set(appid)) | _own_ids(botdir)
    ids = set(x for x in ids if x)
    others = _known_bots(botdir)
    if others and (ids & others):
        drop = ids & others
        log("候选 id 里混进已知其它 bot 的 id，已剔除：%s" % ",".join(sorted(drop))[:60])
        ids -= drop
    return ids


# ── 已知 bot（author id）与自己的 author id ──────────────────────


def _known_bots_path(botdir):
    return os.path.join(botdir, "known_bots.json")


def _known_bots(botdir):
    d = _load_json(_known_bots_path(botdir), {})
    rows = d.get("bots") if isinstance(d, dict) else None
    return set(str(x) for x in rows) if isinstance(rows, list) else set()


def _note_bot_author(botdir, ids):
    """author.bot 为真时把它的 openid 落盘，后续哪怕没有 bot 字段也能滤掉。"""
    ids = set(str(x) for x in ids if x)
    if not ids:
        return
    old = _known_bots(botdir)
    if not ids <= old:
        _save_json(_known_bots_path(botdir), {"bots": sorted(old | ids)})


def _own_author_path(botdir):
    return os.path.join(botdir, "own_author.json")


def _own_author(botdir):
    d = _load_json(_own_author_path(botdir), {})
    rows = d.get("ids") if isinstance(d, dict) else None
    return set(str(x) for x in rows) if isinstance(rows, list) else set()


def _note_own_author(botdir, ids):
    """自己的 author id：只能从「我们发出去那条内容的回声」的作者里学到。"""
    ids = set(str(x) for x in ids if x)
    if not ids:
        return
    old = _own_author(botdir)
    new = ids - old
    if new:
        _save_json(_own_author_path(botdir), {"ids": sorted(old | new)})
        log("学到自己的 author id：%s（来源：本 bot 内容的回声）" % ",".join(sorted(new))[:80])


# ── 字段读取 ──────────────────────────────────────────────


def _attr(obj, *names):
    for n in names:
        v = getattr(obj, n, None)
        if v is not None:
            return v
    return None


def _entries(v):
    if isinstance(v, dict):
        return [v]
    if isinstance(v, (list, tuple)):
        return [x for x in v if x is not None]
    return []


def _mention_id(e):
    if isinstance(e, dict):
        for k in ("id", "member_openid", "user_openid"):
            if e.get(k):
                return str(e[k])
        return ""
    for n in ("id", "member_openid", "user_openid"):
        v = getattr(e, n, None)
        if v:
            return str(v)
    return ""


def _mentions_of(message):
    """把 mentions 尽量掏出来：botpy 属性 / payload 的 d.mentions / d.message.mentions。"""
    out = _entries(_attr(message, "mentions"))
    raw = getattr(message, "raw", None)
    if isinstance(raw, dict):
        for holder in (raw, raw.get("d") if isinstance(raw.get("d"), dict) else None):
            if not isinstance(holder, dict):
                continue
            out.extend(_entries(holder.get("mentions")))
            inner = holder.get("message")
            if isinstance(inner, dict):
                out.extend(_entries(inner.get("mentions")))
    uniq, seen = [], set()
    for e in out:
        k = _mention_id(e)
        if k in seen:
            continue
        seen.add(k)
        uniq.append(e)
    return uniq


def _id_is_mine(tok, cand):
    """<@!x> 归一化成 <@x> 后与候选集比（大小写不敏感，平台两处大小写不统一）。"""
    return str(tok or "").upper() in set(c.upper() for c in cand)


def _mention_flag(e, *names):
    """读 mention 里的布尔标记（默认 is_you）。没有就是 None，不是 False。"""
    for n in names:
        v = e.get(n) if isinstance(e, dict) else getattr(e, n, None)
        if isinstance(v, bool):
            return v
        if isinstance(v, str) and v.strip().lower() in ("true", "false"):
            return v.strip().lower() == "true"
    return None


def _learn_from_mentions(botdir, mentions):
    """平台权威判定 is_you=true 的那个 mention 就是本 bot：

    把它的 id 和 member_openid 都写进 own_ids.json —— 这就是本 bot 在这个群的
    64 位 openid（QQ 按 bot 分配，各 bot 看到的同一个人的 id 都不同）。
    以后 mentions 缺失时，正文 <@id> 还能靠它兜底。
    已知其它 bot 的 id（known_bots.json）不学，学错就是永久抢答。
    """
    others = _known_bots(botdir)
    keep = set()
    for e in mentions:
        if _mention_flag(e, "is_you", "isYou") is not True:
            continue
        for k in ("id", "member_openid", "user_openid"):
            v = e.get(k) if isinstance(e, dict) else getattr(e, k, None)
            v = str(v or "").strip()
            if v and v not in others:
                keep.add(v)
    added = _note_own_ids(botdir, keep)
    if added:
        log("学 own_ids：%s（来源：mentions.is_you=true）" % ",".join(sorted(added))[:80])
    return added


def _strip_mentions(text):
    """去掉正文里的 <@…> —— 模型不该看到一堆 hex。"""
    return _WS_RE.sub(" ", _TAG_RE.sub("", str(text or ""))).strip()


# ── 闸 3 · @ 判定（fail-closed）──────────────────────────────


def _at_me(appid, botdir, message):
    """返回 (状态, 说明)；状态三种：

    "yes"     = 能确定 @ 的是本 bot
    "no"      = 能确定不是 @ 本 bot
    "unknown" = 判不出来 —— 调用方必须「不回」（旧实现这时当 yes 用，才炸的）

    三级判定，platform 的 is_you 优先（真机证据：同一条消息，4 个 bot 各自收到的
    正文 <@id> 全都不一样 —— QQ 的 openid 按 bot 分配，拿 appid/robot.id/own_ids
    去比正文永远不命中，所以 is_you 才是权威）：
      1) d.mentions 里有 is_you=true            → yes，顺手学自己的 id；
      2) d.mentions 非空且声明了 is_you，无 true → no（明确不是叫我，不做 id 兜底比对，
         否则每个 bot 都会兜底比对，重新引入误判）；
      3) mentions 缺失/没声明 is_you            → 退回正文 <@id> 比候选集合，
         仍判不出 → unknown（不回）。
    """
    text = str(getattr(message, "content", "") or "")
    mentions = _mentions_of(message)
    flags = [_mention_flag(e, "is_you", "isYou") for e in mentions]

    if any(f is True for f in flags):
        _learn_from_mentions(botdir, mentions)
        return "yes", "mentions.is_you=true（平台权威判定）"
    if any(f is False for f in flags):
        return "no", "明确不是叫我（mentions 非空，但没有 is_you=true）"

    cand = _candidates(appid, botdir)
    if not cand:
        return "unknown", "候选 id 集合为空（appid / robot.id / own_ids.json 都没有）"
    mids = [_mention_id(e) for e in mentions]
    for mid in mids:
        if mid and _id_is_mine(mid, cand):
            return "yes", "mentions 命中本 bot（无 is_you，id 兜底）"
    toks = [m.group(1) for m in _MENTION_RE.finditer(text)]
    for t in toks:
        if _id_is_mine(t, cand):
            return "yes", "正文 <@%s> 命中本 bot（id 兜底）" % t
    if toks:
        # 有 @ 但 id 不在候选集合：不能当「不是我」，也不能当「是我」→ 判不出
        return "unknown", "正文里的提及 %s 不在候选集合" % ",".join(sorted(set(toks)))[:40]
    if any(mids):
        return "unknown", "mentions 里的 id %s 不在候选集合" % ",".join(sorted(set(m for m in mids if m)))[:40]
    if mentions:
        return "unknown", "mentions 里的 id 读不出来"
    return "no", "正文和 mentions 都没有 @"


# ── 闸 2 · 作者过滤（三信号）─────────────────────────────────


def _bot_author(botdir, message):
    """作者是 bot / 已知 bot / 本 bot 自己 → 返回跳过原因；否则空串。"""
    a = getattr(message, "author", None)
    ids = _author_ids(message)
    if _attr(a, "bot", "is_bot", "isBot") is True:
        _note_bot_author(botdir, ids)
        return "跳过：作者是 bot（信号：author.bot=true）"
    known = _known_bots(botdir)
    if known and any(i in known for i in ids):
        return "跳过：作者是 bot（信号：known_bots.json）"
    mine = _own_author(botdir)
    if mine and any(i in mine for i in ids):
        return "跳过：作者是 bot（信号：本 bot 自己的 author id）"
    return ""


# ── 闸 1 · 回环（回声）检测 ─────────────────────────────────


def _norm_text(text):
    """归一化：去 <@…> / 去空白 / 小写。"""
    s = _TAG_RE.sub("", str(text or ""))
    s = _WS_RE.sub("", s)
    return s.lower()


def _hash_text(text):
    import hashlib
    return hashlib.sha1(_norm_text(text).encode("utf-8")).hexdigest()


def _note_sent(appid, gid, text):
    """记下本 bot 刚发出的文本（按群分开，各留最近 32 条哈希）。"""
    norm = _norm_text(text)
    if not norm:
        return
    h = _hash_text(text)
    key = (str(appid), str(gid or ""))
    with _gate_lock:
        box = _outbox.setdefault(key, [])
        if h in box:
            box.remove(h)
        box.append(h)
        while len(box) > _OUTBOX_PER_GROUP:
            box.pop(0)


def _is_echo(appid, gid, text):
    """收到的内容与本 bot 刚发出的某条归一化后一致 → 别的 bot 在复读。"""
    if not _norm_text(text):
        return False
    h = _hash_text(text)
    with _gate_lock:
        return h in (_outbox.get((str(appid), str(gid or ""))) or [])


# ── 闸 4 · 限流 + 熔断 ─────────────────────────────────────


def _gate_rate(appid, gid):
    """返回 (放行?, 说明)。

    限流：每（bot, 群）60 秒滑动窗口 ≤ 6 条。
    熔断：同一群 60 秒内累计 12 条「回复意图」（含被限流挡下的）→ 静音 5 分钟。
          按意图而不是按成功条数计，是因为限流一挡成功数就停在 6，永远到不了 12；
          而 4 个 bot 互刷时「想回」的次数才是要刹的东西。
    """
    now = time.time()
    gid = str(gid or "")
    with _gate_lock:
        until = _mute.get(gid) or 0
        if until > now:
            return False, "跳过：本群熔断静音中（剩 %d 秒）" % int(until - now)
        if until:
            _mute.pop(gid, None)
        intents = [t for t in (_intent.get(gid) or []) if now - t < _RATE_WINDOW]
        intents.append(now)
        _intent[gid] = intents
        if len(intents) >= _BREAK_MAX:
            _mute[gid] = now + _BREAK_MUTE
            _rate.pop((str(appid), gid), None)
            return False, "熔断：本群 60s 内已发 %d 条，静音 5 分钟" % len(intents)
        key = (str(appid), gid)
        win = [t for t in (_rate.get(key) or []) if now - t < _RATE_WINDOW]
        if len(win) >= _RATE_MAX:
            _rate[key] = win
            return False, "跳过：触发限流（本 bot 在本群 60s 内已回 %d 条，上限 %d）" % (len(win), _RATE_MAX)
        win.append(now)
        _rate[key] = win
    return True, ""


# ── 统一处理链路 ──────────────────────────────────────────


async def _handle_group(appid, botdir, message, tag):
    """群消息的唯一处理入口：@ 事件和普通群消息都走这里。

    tag="群@" 是平台的可信 @ 事件（只在本 bot 被 @ 时推），@ 判定直接放行，
    但作者过滤 / 回环 / 限流一样要过 —— 它是复读的另一个入口。
    """
    if _dedupe_message(appid, message, "群"):
        return
    trusted_at = (tag == "群@")
    mid = str(getattr(message, "id", "") or "")
    if not _mark_seen(mid):
        log("%s：重复 id=%s，跳过（同一事件同时推了 @ / 普通两种）" % (tag, mid[:12]), appid)
        return
    if not trusted_at:
        _dump_gateway_raw(botdir, "group_message_create", getattr(message, "raw", None) or {})
    text = (getattr(message, "content", "") or "").strip()
    gid = str(getattr(message, "group_openid", "") or "")
    brief = "%s｜id=%s｜群=%s｜内容=%s" % (tag, mid[:12], gid[:8], text[:40])

    # 闸 5 · 群级配置（<botdir>/groups/<gid>.json）：整群静音直接掐掉，后面几道闸都不用走
    gc = _group_conf(botdir, gid)
    if not gc["enabled"]:
        log("跳过：该群已静音（groups/%s.json enabled=false）｜%s" % (_oid8(gid), brief), appid)
        return

    # 闸 0 · 只有可信 @ 事件允许从正文猜 id；mentions.is_you=true 是平台判定，两种事件都学
    if trusted_at:
        _learn_own_ids(botdir, text)
    _learn_from_mentions(botdir, _mentions_of(message))

    # 闸 2 · 作者过滤
    why = _bot_author(botdir, message)
    if why:
        log("%s（%s）" % (why, brief), appid)
        return

    # 闸 1 · 回环：命中就说明这条是我们自己刚说的话被推回来，顺手记下自己的 author id
    if _is_echo(appid, gid, text):
        _note_own_author(botdir, _author_ids(message))
        log("跳过：疑似复读本 bot 内容（%s）" % brief, appid)
        return

    # 闸 3 · @ 判定（fail-closed）
    if not trusted_at:
        state, why_at = _at_me(appid, botdir, message)
        if state == "unknown":
            log("跳过：判不出是否 @ 本 bot（%s｜%s）" % (why_at, brief), appid)
            return
        if state == "no":
            # 三层判定：群配置显式值 > 该 bot 覆盖 > 全局（日志写清是哪一层判的）
            auto_on, auto_src = _group_autochat_state(appid, gc)
            if not auto_on:
                log("跳过：未 @ 本 bot（%s），且「群里自动接话」关闭（判定来源：%s）"
                    "→ 默认只回 @（%s）" % (why_at, auto_src, brief), appid)
                return
            # 开着也写清是哪一层判的（群配置 / 该 bot 覆盖 / 全局），便于核对三态
            log("自动接话开（判定来源：%s）｜%s" % (auto_src, brief), appid)
            # 自动接话开着：交给「回复判定」（设置页那一行）决定要不要插话
            conf_j = _llm_conf(_base_dir) if _base_dir else None
            if not await _should_reply(conf_j, text):
                log("跳过：回复判定不接这句（%s）" % brief, appid)
                return
            log("回复判定接这句（%s）" % brief, appid)

    # 闸 4 · 限流 + 熔断（只有真要回才占额度）
    ok, why_rate = _gate_rate(appid, gid)
    if not ok:
        if why_rate.startswith("熔断"):
            log("！！！ %s ！！！（%s）" % (why_rate, brief), appid)
        else:
            log("%s（%s）" % (why_rate, brief), appid)
        return

    # 闸 6 · 群级每日额度（groups/<gid>.json daily_limit，0 = 不限）：超了静默
    limit = int(gc.get("daily_limit") or 0)
    if limit > 0:
        ok_quota, used = _group_quota_take(botdir, gid, limit)
        if not ok_quota:
            log("跳过：该群今日已回 %d 条，达到上限 %d 条 → 今日不再回复"
                "（groups/%s.json daily_limit）｜%s" % (used, limit, _oid8(gid), brief), appid)
            return

    log("%s：%s" % (tag, text[:60]), appid)
    # 存档：过了作者过滤与复读闸的消息一律入库 —— 不为了这条要回复，只为以后能被回忆
    # （没 @ 本 bot、判定不接的那些句子也照存，否则「上周谁提过 X」根本没东西可查）
    _arc_put(botdir, "group", gid, (_author_ids(message) or [""])[0], _strip_mentions(text))
    try:
        _note_chat(botdir, "group", gid)
        for _id in _author_ids(message):
            _note_sender(botdir, _id, gid)
    except Exception as e:
        log("采集 openid 失败（忽略）：%r" % e, appid)
    # 图片附件：这一条消息可能同时带图（QQ 把图放在 attachments 里带 url）
    images = _attachments_of(message)
    if images:
        log("收到 %d 张图片附件（%s）" % (
            len(images), "，".join([a.get("filename") or "未命名" for a in images])[:60]), appid)
    clean = _strip_mentions(text)
    # 本地指令先判：过了四道闸就算「该回这条」，回时间不必打扰模型
    if await _local_time_reply(appid, message, clean, group_openid=gid):
        return
    # 联网指令（#搜 / #读）：搜完交给模型按人格转述（人设化加工），链接洗净后贴出去
    if await _local_web_cmd(appid, message, clean, group_openid=gid, botdir=botdir):
        return
    try:
        # 模型不该看到 <@hex>：先去掉 @ 标签再进模型/记忆
        reply = await _reply(appid, botdir, "g" + gid, "", clean, images=images)
        _note_sent(appid, gid, reply)
        await message._api.post_group_message(
            group_openid=gid, msg_type=0, msg_id=mid, content=reply,
        )
    except Exception as e:
        log("回群失败：%r" % e, appid)


def _make_client(appid, secret, botdir):
    """每个实例一套自己的 Client 类，避免事件回调串台"""
    import botpy
    _install_group_message_parser()

    class Bot(botpy.Client):
        async def on_ready(self):
            name = getattr(self.robot, "name", "?")
            # @ 判定只认本 bot 自己的 id：appid 一定算，robot 上还有别的就一起登记
            _register_self_id(appid, getattr(self.robot, "id", None),
                              getattr(self.robot, "union_openid", None))
            log("本 bot 的 @ 判定 id 集合：%s｜own_ids.json：%s｜群里自动接话：%s（全局%s / 该 bot 覆盖=%s）" % (
                (",".join(sorted(_self_id_set(appid))[:4]) or "(空)")[:60],
                (",".join(sorted(_own_ids(botdir))[:4]) or "(空，等可信 @ 事件学习)"),
                "开" if _group_autochat_state(appid, {})[0] else "关（默认只回 @）",
                "开" if _autochat["on"] else "关",
                _autochat_override_label(appid)), appid)
            # 定时消息 + 随机问候：上线后起一个循环，隔 30 秒看一次任务表
            try:
                _spawn_sched(_sched_loop(self, appid, botdir))
            except Exception as e:
                log("定时消息调度没起来：%r" % e, appid)
            with _lock:
                if appid in _bots:
                    _bots[appid]["status"] = "已上线 · %s" % name
                    _bots[appid]["name"] = name
                    _bots[appid]["online_at"] = time.time()
            log("已上线：「%s」" % name, appid)

        async def on_at_message_create(self, message):
            if _dedupe_message(appid, message, "频道"):
                return
            text = (getattr(message, "content", "") or "").strip()
            log("频道@：%s" % text[:60], appid)
            try:
                await message._api.post_message(
                    channel_id=message.channel_id, msg_id=message.id,
                    content=await _reply(appid, botdir, "c" + str(message.channel_id), "", text,
                                         images=_attachments_of(message)),
                )
            except Exception as e:
                log("回频道失败：%r" % e, appid)

        async def on_group_at_message_create(self, message):
            # botpy 原生解析器（平台推 group_at_message_create 时走这里）
            await _handle_group(appid, botdir, message, "群@")

        async def on_group_message_create(self, message):
            # 我们自己补的解析器推过来的（平台推 group_message_create 时走这里）。
            # 采集 → _reply → post_group_message 全复用 _handle_group，没有第二套回复链路。
            await _handle_group(appid, botdir, message, "群消息(新事件)")

        async def on_c2c_message_create(self, message):
            # 同一条私聊消息有时会被两个事件回调各投一次：按 msg_id 去重，只处理一次
            if _dedupe_message(appid, message, "私聊"):
                return
            text = (getattr(message, "content", "") or "").strip()
            images = _attachments_of(message)
            if images:
                log("私聊收到 %d 张图片附件" % len(images), appid)
            log("私聊：%s" % text[:60], appid)
            # 存档：私聊收到的每一条都记（会话 id 就是对方的 user_openid）
            _arc_put(botdir, "c2c", str(getattr(message.author, "user_openid", "") or ""),
                     (_author_ids(message) or [""])[0], text)
            try:
                # 采集：私聊对方的 user_openid；第一次私聊的人记成主人
                for _id in _author_ids(message):
                    _note_chat(botdir, "c2c", _id)
            except Exception as e:
                log("采集 openid 失败（忽略）：%r" % e, appid)
            # 本地指令（#时间 / 现在几点）：不经过模型，直接回本地时间
            if await _local_time_reply(
                    appid, message, text,
                    key="p" + str(getattr(message.author, "user_openid", ""))):
                return
            # 联网指令（#搜 / #读）：私聊也认，同一套人设化加工
            if await _local_web_cmd(
                    appid, message, text,
                    key="p" + str(getattr(message.author, "user_openid", "")),
                    botdir=botdir):
                return
            try:
                # 私聊的会话键用 p + 对方 openid（之前误用了群的 g + group_openid，
                # 而 C2CMessage 根本没有 group_openid 属性 → 回私聊必报 AttributeError）
                await message._api.post_c2c_message(
                    openid=message.author.user_openid, msg_type=0,
                    msg_id=message.id,
                    content=await _reply(appid, botdir,
                                         "p" + str(message.author.user_openid), "", text,
                                         images=images),
                )
            except Exception as e:
                log("回私聊失败：%r" % e, appid)

    try:
        intents = botpy.Intents(public_guild_messages=True, public_messages=True)
    except TypeError:
        intents = botpy.Intents(public_guild_messages=True)
        log("当前 botpy 不支持 public_messages，只开频道事件", appid)
    return Bot(intents=intents)


async def _reply(appid, botdir, key, who, incoming, images=None):
    """把这一轮记进记忆，再让模型按人格回一句。

    带附件（图片）时先识图，把「（图里是：…）」拼在这一轮的入参文本后面 ——
    识图失败就退回纯文本处理，消息照常回，不会静默丢掉。
    LLM 没配（localbot/llm.json 缺失或没 key）时退回连通测试文案，
    这样至少能看出「收到消息了」，而不是静默不回复。
    """
    _usage_appid.set(appid)          # 这条链路里的模型调用（含判定/识图/摘要/搜索合成）记到该 bot 账上
    if images and not incoming:
        incoming = "（发了张图）"
    conf = _llm_conf(_base_dir) if _base_dir else None
    if images:
        if conf is None:
            log("识图跳过：模型配置没填（llm.json 缺 key / 地址），这条按纯文本处理", appid)
        else:
            # 识图槽位（vision_model，留空 = 主模型）：实际用的就是 conf["model"]
            extra = await _vision_suffix(appid, _conf_for("vision") or conf, images)
            if extra:
                incoming = incoming + extra
    # 「第一句」要在丢进记忆之前判：_remember 之后 key 下就已经有这条了
    first = not (_memory(botdir).get(key) or [])
    if incoming:
        _remember(botdir, key, "user", ("%s：%s" % (who, incoming)) if who else incoming, who)
    if conf is None:
        return "（本机模式连通测试）收到：%s" % (incoming or "")[:40]
    try:
        # 上下文压缩：达到窗口 75% 才压一次（压完这次不再压，见 _maybe_compress）
        await _maybe_compress(botdir, key, appid)
        # 群级：该群 web_search=false 时自判搜索与触发词兜底一起关（#搜 / #读 在 _local_web_cmd 里另拦）
        gid_key = _group_of_key(key)
        group_web = _group_web_on(botdir, gid_key)
        web_on = bool(conf.get("web_search_enabled", True)) and group_web
        if not group_web and _needs_web(incoming or ""):
            log("联网：该群已关闭联网（groups/%s.json web_search=false），本条跳过触发词搜索"
                % _oid8(gid_key), appid)
        auto_decide = web_on and bool(conf.get("search_auto_decide", True))
        msgs = _build_messages(botdir, key, who, incoming, first=first, search_hint=auto_decide)
        # ① 触发词兜底：明显在问时效性内容就先搜一次（与「模型自判」互斥，一条消息只搜一次）
        web_block = await _auto_web_context(appid, conf, incoming) if web_on else ""
        if web_block:
            msgs.insert(len(msgs) - 1, {"role": "system", "content": web_block})
        text = await asyncio.to_thread(_call_llm_sync, conf, msgs)
        # ①' 存档召回：模型只输出一行 <recall>关键词</recall> → 查本地库、拼进第二次请求。
        #    最多 1 轮（不像联网能连续追问）：翻本机记录一次就够，翻到了也用不着再翻。
        #    同一条回复里两种标记都出现时只认召回（先翻本地记录，再谈上网）。
        rc = _ARC_RECALL_MARK_RE.search(text or "")
        if rc:
            rq = (rc.group(1) or "").strip()[:60]
            rmode, rrows = await asyncio.to_thread(archive_search, botdir, rq, _ARC_RECALL_N)
            log("存档召回：key=%s 「%s」→ %s" % (
                key, rq, ("命中 %d 条（%s）" % (len(rrows), rmode)) if rrows
                else ("未命中（%s）" % (rmode or "无库"))), appid)
            second = (_recall_block(rrows) + _RECALL_STOP) if rrows else _RECALL_EMPTY
            text = await asyncio.to_thread(
                _call_llm_sync, conf, list(msgs) + [
                    {"role": "assistant", "content": "<recall>%s</recall>" % rq},
                    {"role": "system", "content": second}])
            if text and _ARC_RECALL_ANY_RE.search(text):
                text = _strip_recall_marks(text)
        # ② 模型自判：可以连续追问，一条消息最多 _SRCH_MAX 次搜索
        #    （成本护栏：首轮 1 次 + 每次追问后各 1 次 = 最多 6 次模型调用）
        asked = _WEB_MARK_RE.search(text or "")
        hits, rounds = [], 0
        while asked and auto_decide and not web_block and rounds < _SRCH_MAX:
            rounds += 1
            q = (asked.group(1) or "").strip()[:120]
            hits = _dedup_hits(hits + await _self_web_context(appid, conf, q, rounds))
            if hits:
                log("联网：模型自判要查「%s」→ 第 %d 轮（累计 %d 条资料）"
                    % (q[:30], rounds, len(_shown_hits(hits))), appid)
                second = (_hits_block(hits) + _WEB_HINT_STOP
                          + chr(10) + _search_more_rule(_SRCH_MAX - rounds))
            else:
                log("联网：模型自判要查「%s」→ 第 %d 轮没搜到" % (q[:30], rounds), appid)
                second = ("（刚查了，一条资料都没搜到：按你确实知道的回答；不确定就说不知道，"
                          "不要编造。" + chr(10) + _search_more_rule(_SRCH_MAX - rounds) + "）")
            msgs2 = list(msgs) + [
                {"role": "assistant", "content": "<search>%s</search>" % q},
                {"role": "system", "content": second},
            ]
            text = await asyncio.to_thread(_call_llm_sync, conf, msgs2)
            asked = _WEB_MARK_RE.search(text or "")
        if rounds:
            # 用满 5 轮还在要资料 / 一条都没查到 → 收尾，不再花钱搜
            if asked or not hits:
                log("联网：搜索 %d 轮未得结果，按「不清楚」回复" % rounds, appid)
            text = _strip_search_marks(text)
            if not text:
                log("自判搜索后没有可用正文 → 让人格说一句「不清楚」（模型 %s）" % conf["model"],
                    appid)
                text = await asyncio.to_thread(
                    _call_llm_sync, conf,
                    list(msgs) + [{"role": "system",
                                  "content": "直接回答对方，或者老实说不清楚；"
                                             "不要输出任何 <search> 标记。"}])
        # 标记永远不给用户看（关掉自判时模型也可能残留一个；<recall> 同理，含残缺的半截标记）
        if text and _ARC_RECALL_ANY_RE.search(text):
            text = _strip_recall_marks(text)
        if text and _WEB_MARK_ANY_RE.search(text):
            text = _strip_search_marks(text)
        if not text and not rounds:
            # 第一轮只说「我要查」但资料已经由触发词搜来了（或自判已关）：补一次纯回答
            log("回复只剩 <search> 标记没有正文 → 要求它直接回答", appid)
            text = await asyncio.to_thread(
                _call_llm_sync, conf,
                list(msgs) + [{"role": "system",
                              "content": "直接用一句话回答对方，不要输出任何 <search> 标记。"}])
        # 和判定/识图/摘要同一个格式：日志里写清回复实际用的模型
        log("回复（模型 %s）：%s" % (conf["model"],
                                    (text or "(空)")[:40].replace(chr(10), " ")), appid)
        if not text:
            text = "（模型没返回内容）"
        raw_len = len(text)
        text = _clean_reply(text)
        log("回复清洗：原 %d 字 → 现 %d 字" % (raw_len, len(text)), appid)
        _remember(botdir, key, "assistant", text)
        # 存档：把这一轮发出去的话补到「刚收到那条」的 reply_text 上（一行 = 原文 ⇢ 回复）
        _arc_reply_by_key(botdir, key, text)
        return text
    except Exception as e:
        log("调模型失败：%r" % e, appid)
        return "（模型调用失败：%s）" % str(e)[:60]



def _own(appid, gen):
    """这条线程还持有该 appid 的运行位吗？

    restart_one 会先停旧线程再起新线程，旧线程总归晚一步才从 run() 里出来；
    没有这道判断，它收尾时会把新实例的状态写成「已停止」/「连接已结束」。
    """
    return _bots.get(appid, {}).get("gen") == gen


def _run_bot(appid, secret, botdir, gen=None):
    loop = None
    try:
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        try:
            os.chdir(botdir)
        except Exception:
            pass
        _fix_botpy_logging(botdir)
        log("准备连接…", appid)
        client = _make_client(appid, secret, botdir)
        with _lock:
            if _own(appid, gen):
                _bots[appid]["status"] = "连接中"
                _bots[appid]["client"] = client
        log("开始 run()（阻塞直到连接结束）", appid)
        client.run(appid=appid, secret=secret)
        log("run() 返回，连接已结束", appid)
        with _lock:
            if _own(appid, gen):
                _bots[appid]["status"] = "连接已结束"
    except Exception as e:
        log("启动失败：%s" % e, appid)
        log(traceback.format_exc()[:1200], appid)
        with _lock:
            if _own(appid, gen):
                _bots[appid]["status"] = "启动失败：%s" % str(e)[:60]
    finally:
        with _lock:
            if _own(appid, gen):
                _bots[appid]["alive"] = False
        if loop is not None:
            try:
                asyncio.set_event_loop(None)
                loop.close()
            except Exception:
                pass


def start_one(appid, secret, base_dir):
    global _log_path, _base_dir
    _base_dir = base_dir
    appid = str(appid or "").strip()
    secret = str(secret or "").strip()
    if not appid or not secret:
        return "缺少 AppID 或 AppSecret"
    with _lock:
        if appid in _bots and _bots[appid].get("alive"):
            return "已经在跑了"
    botdir = _bot_dir(base_dir, appid)
    try:
        _migrate_words(botdir, appid)
    except Exception as e:
        log("文案迁移出错（忽略）：%r" % e, appid)
    if not _log_path:
        _log_path = os.path.join(base_dir, "local_bot.log")
        try:
            with open(_log_path, "a", encoding="utf-8") as f:
                f.write("\n===== %s 启动 =====\n" % _stamp())
        except Exception:
            pass
    # 消息存档：开库（顺带探测设备 SQLite 有没有 FTS5）、滚动清理一次、起 flush 线程。
    # 放在 _log_path 之后：探测结果要进日志文件（「日志」页和验收都要看得见）。
    try:
        _arc_boot(botdir)
    except Exception as e:
        log("消息存档初始化失败（忽略，收发照常）：%r" % e, appid)
    # 「群里只回 @」的一次性提示：状态页那行小字是界面侧，日志这边只在本次进程写一条
    _hint_autochat_once(appid)
    gen = time.time()
    with _lock:
        _bots[appid] = {
            "appid": appid, "dir": botdir, "status": "正在启动",
            "alive": True, "started_at": time.time(), "name": "",
            "client": None, "gen": gen,
        }
    t = threading.Thread(target=_run_bot, args=(appid, secret, botdir, gen), daemon=True)
    t.start()
    return "OK"


def stop_one(appid):
    appid = str(appid or "").strip()
    with _lock:
        info = _bots.get(appid)
    if not info:
        return "没在跑"
    log("收到停止指令", appid)
    try:
        c = info.get("client")
        if c is not None:
            c.close()
    except Exception as e:
        log("close() 出错（忽略）：%r" % e, appid)
    with _lock:
        if appid in _bots:
            _bots[appid]["status"] = "已停止"
            _bots[appid]["alive"] = False
            _bots[appid]["client"] = None
    return "OK"


def restart_one(appid, secret, base_dir, reason="手动重启"):
    """只停/起**一个** bot：人格或记忆变了之后让它立刻用新状态，其它 bot 不断线。

    为什么必须在 Python 侧单个重启：Kotlin 侧如果为了生效去调 stop_all/start_all，
    同一台机上另外几个 bot 也会跟着断线重连 —— 用户明确不要这个。
    """
    appid = str(appid or "").strip()
    if not appid:
        return "缺少 AppID"
    reason = str(reason or "").strip() or "手动重启"
    if not is_running(appid):
        return ""          # 没在跑：不替用户拉起，下一次 startAll 自然用新状态
    stop_one(appid)
    secret = str(secret or "").strip()
    if not secret:
        log("已停止 bot %s（原因：%s），但本机清单里拿不到 AppSecret，没能重新拉起" % (appid, reason))
        return "已停止 bot %s，但缺 AppSecret 没能重新拉起" % appid
    r = start_one(appid, secret, base_dir)
    if r != "OK":
        log("重启 bot %s 失败（原因：%s）：%s" % (appid, reason, r))
        return "重启 bot %s 失败：%s" % (appid, r)
    log("已重启 bot %s（原因：%s）" % (appid, reason))
    return "已重启 bot %s（原因：%s）" % (appid, reason)


def start_all(bots_json, base_dir):
    """bots_json: [{"appid","secret","persona"}]，只启动 enabled 的那些

    注意：从 Kotlin 侧传过来的是 JSON **字符串**（Chaquopy 不帮你反序列化）。
    之前直接当列表迭代，拿到的是单个字符 → 每条都抛异常 → 静默什么都没起。
    """
    global _log_path
    if isinstance(bots_json, str):
        try:
            import json as _json
            bots_json = _json.loads(bots_json or "[]")
        except Exception as e:
            log("解析 bot 清单失败：%r" % e)
            bots_json = []
    if not isinstance(bots_json, list):
        bots_json = []
    if not _log_path:
        _log_path = os.path.join(base_dir, "local_bot.log")
    results = []
    for b in bots_json or []:
        appid = str(b.get("appid") or "").strip()
        secret = str(b.get("secret") or "").strip()
        if not appid:
            continue
        if not b.get("enabled", True):
            results.append("%s: 未启用" % appid)
            continue
        r = start_one(appid, secret, base_dir)
        results.append("%s: %s" % (appid, r))
    return "\n".join(results)


def stop_all():
    for appid in list(_bots.keys()):
        stop_one(appid)
    return "OK"


def snapshot():
    """给界面读的汇总（JSON 字符串）"""
    import json
    with _lock:
        out = []
        for appid, i in _bots.items():
            out.append({
                "appid": appid,
                "status": i.get("status", ""),
                "alive": bool(i.get("alive")),
                "name": i.get("name", ""),
                "uptime": int(time.time() - i.get("started_at", time.time()))
                if i.get("alive") and i.get("started_at") else 0,
            })
    return json.dumps(out, ensure_ascii=False)


def state_text():
    """给主页 hero 用的一句话状态（不带运行时长）"""
    with _lock:
        if not _bots:
            return "未启动"
        parts = []
        for appid, i in _bots.items():
            st = i.get("status", "")
            parts.append("%s %s" % (appid, st) if len(_bots) > 1 else st)
        return " | ".join(parts)


def is_running(appid=""):
    with _lock:
        if appid:
            return bool(_bots.get(appid, {}).get("alive"))
        return any(i.get("alive") for i in _bots.values())


def log_text(n=120):
    with _lock:
        return "\n".join(_state["log"][-int(n):])


def probe():
    out = ["python   %s" % sys.version.split()[0], "platform %s" % sys.platform]
    for name in ("aiohttp", "yaml", "apscheduler", "botpy"):
        try:
            mod = __import__(name)
            out.append("%-8s %s" % (name, getattr(mod, "__version__", "ok")))
        except Exception as e:
            out.append("%-8s ✗ %r" % (name, e))
    return "\n".join(out)


# ── 供界面调用的记忆接口 ──────────────────────────────

def memory_list(botdir):
    """返回 [{key, count, last, who}] 的 JSON 字符串"""
    import json
    h = _memory(botdir)
    out = []
    for k, v in h.items():
        if not isinstance(v, list):
            continue
        ts = [r[2] for r in v if isinstance(r, list) and len(r) > 2 and isinstance(r[2], (int, float))]
        who = []
        for r in v:
            if isinstance(r, list) and len(r) > 3 and r[3] and r[3] not in who:
                who.append(r[3])
        out.append({
            "key": k,
            "type": "群" if str(k).startswith("g") else ("私聊" if str(k).startswith("p") else "其他"),
            "count": len(v),
            "last": time.strftime("%m-%d %H:%M", time.localtime(max(ts))) if ts else "",
            "who": who[:8],
        })
    out.sort(key=lambda x: x["last"], reverse=True)
    return json.dumps(out, ensure_ascii=False)


def _memory_rows(h):
    """memory.json 里还剩多少条对话（所有会话加起来）。"""
    return sum(len(v) for v in h.values() if isinstance(v, list))


def memory_delete(botdir, key):
    """删掉一段会话记忆 = delete_many 的单元素调用（单条和批量走同一条代码路径）。"""
    return memory_delete_many(botdir, [key])


def _parse_keys(keys):
    """界面可能传 list / JSON 数组字符串 / 单个键，统一成去重后的 list。"""
    import json
    if isinstance(keys, str):
        s = keys.strip()
        if s.startswith("["):
            try:
                keys = json.loads(s)
            except Exception:
                keys = [s]
        else:
            keys = [s]
    if not isinstance(keys, (list, tuple)):
        keys = [keys]
    out = []
    for k in keys:
        k = str(k or "").strip().strip('"').strip("'").strip()
        if k and k not in out:
            out.append(k)
    return out


def memory_delete_many(botdir, keys):
    """删掉若干段会话记忆，一次操作只写一条 undo 记录。

    _reply → _build_messages 每次都是现读 memory.json 拼 prompt 的
    （memory.json 是唯一的正文存储，没有第二个历史/摘要文件，也没有进程内缓存），
    所以这里删掉键之后，下一句话就引用不到这段了。
    界面传进来的 key 就是 memory_list 给的原始键；每个键再做一层兜底：
      · 首尾空白 / 引号
      · 只给了裸 openid（采集页那种）时，按 p / g / c 前缀各试一遍
    """
    raws = _parse_keys(keys)
    h = _memory(botdir)
    hits = []
    miss = []
    for raw in raws:
        hit = raw if raw in h else None
        if hit is None:
            for pre in ("p", "g", "c"):
                if pre + raw in h:
                    hit = pre + raw
                    break
        if hit is None or hit in [x[0] for x in hits]:
            miss.append(raw)
        else:
            hits.append((hit, h[hit]))
    if not hits:
        log("记忆删除：%s 都不存在，剩余 %d 条（现有键：%s）" % (
            ",".join(raws)[:80], _memory_rows(h), ",".join(sorted(h)[:6])[:160]))
        return "没有这个会话：%s" % ",".join(raws)[:80]
    items = [{"key": hit, "rows": rows} for hit, rows in hits]
    for hit, _rows in hits:
        h.pop(hit, None)
    # 撤销按「一次操作」记：这一批删掉的键全部进同一个 undo 文件（只留最近一次，覆盖写）
    _write_undo(botdir, items)
    if not _save_json(_memory_path(botdir), h):
        for hit, rows in hits:      # 写失败：整批回滚，别留半删的内存状态
            h[hit] = rows
        _clear_undo(botdir)         # 一个键都没删成，undo 也别留，省得界面给人假希望
        left = _memory_rows(h)
        log("记忆删除失败：%s 写不进去，剩余 %d 条（%s 没删掉）" % (
            _memory_path(botdir), left, ",".join(x[0] for x in hits)[:120]))
        return "删除失败：磁盘写入错误，" + ",".join(x[0] for x in hits)[:80] + " 没删掉（剩余 %d 条）" % left
    left = _memory_rows(h)
    gone = sum(len(rows) if isinstance(rows, list) else 0 for _hit, rows in hits)
    log("记忆删除：%s，剩余 %d 条（删掉 %d 条，%s）" % (
        ",".join(x[0] for x in hits)[:120], left, gone, botdir))
    text = "已删除 %d 段记忆（剩余 %d 条）" % (len(hits), left)
    if miss:
        text += "，%d 个键没找到" % len(miss)
    return text


def memory_clear(botdir):
    h = _memory(botdir)
    # 清空 = 所有键一次删光；undo 只记单键删除，所以这里不提供撤销（也不做滚动备份）
    _clear_undo(botdir)
    if not _save_json(_memory_path(botdir), {}):
        log("记忆清空失败：%s 写不进去，%d 条还在" % (botdir, _memory_rows(h)))
        return "清空失败：磁盘写入错误，%d 条还在" % _memory_rows(h)
    log("记忆清空：%s，清掉 %d 条" % (botdir, _memory_rows(h)))
    return "已清空 %d 条" % _memory_rows(h)


def _undo_path(botdir):
    return os.path.join(botdir, "memory.undo.json")


def _write_undo(botdir, items):
    """删之前先记下这一次删的内容：{ts, items:[{key, rows}]}。

    一次操作（可能多条）只有一条 undo 记录；只留最近一次（覆盖写）——
    用户明确不要滚动备份，撤销就一步。
    """
    clean = []
    for it in items or []:
        if not isinstance(it, dict):
            continue
        rows = it.get("rows")
        clean.append({"key": str(it.get("key")), "rows": rows if isinstance(rows, list) else []})
    if not clean:
        return False
    return _save_json(_undo_path(botdir), {
        "ts": int(time.time()),
        "items": clean,
    })


def _undo_items(u):
    """把 undo 文件读成 [{key, rows}]。

    新格式 {ts, items:[{key, rows}]}；老格式 {key, rows} 也认（升级前留下的文件）。
    """
    if not isinstance(u, dict):
        return []
    items = u.get("items")
    if isinstance(items, list):
        out = []
        for it in items:
            if not isinstance(it, dict):
                continue
            k = str(it.get("key") or "").strip()
            rows = it.get("rows")
            if k and isinstance(rows, list) and rows:
                out.append({"key": k, "rows": rows})
        return out
    k = str(u.get("key") or "").strip()
    rows = u.get("rows")
    if k and isinstance(rows, list) and rows:
        return [{"key": k, "rows": rows}]
    return []


def _clear_undo(botdir):
    try:
        p = _undo_path(botdir)
        if os.path.exists(p):
            os.remove(p)
        return True
    except Exception as e:
        log("清撤销文件失败 %s: %r" % (_undo_path(botdir), e))
        return False


def memory_undo(botdir):
    """把最近一次删除（可能多条）全部合并回 memory.json，返回「已撤销 N 段记忆」。

    某个键还在（删完又聊了几句）→ 旧行 prepend 回去，时间顺序仍是旧的在前；
    该键已不在 → 整键还原。成功后清空 undo（一次只倒一步）。
    """
    u = _load_json(_undo_path(botdir), None)
    items = _undo_items(u)
    if not items:
        if u is not None:
            _clear_undo(botdir)
        return "没有可撤销的删除"
    h = _memory(botdir)
    back = 0
    for it in items:
        key, rows = it["key"], it["rows"]
        cur = h.get(key)
        cur = cur if isinstance(cur, list) else []
        h[key] = rows + cur
        back += len(rows)
    if not _save_json(_memory_path(botdir), h):
        log("记忆撤销失败：%s 写不进去，%d 段没恢复" % (_memory_path(botdir), len(items)))
        return "撤销失败：磁盘写入错误，%d 段没恢复" % len(items)
    _clear_undo(botdir)
    log("记忆撤销：%s 恢复 %d 段 / %d 条，剩余 %d 条" % (
        ",".join(it["key"] for it in items)[:120], len(items), back, _memory_rows(h)))
    return "已撤销 %d 段记忆（共 %d 条）" % (len(items), back)


def ids_for(botdir):
    """采集本 bot 自己见过的 openid（QQ 的 openid 按 bot 分配，所以一个 bot 一份）。

    四个真实来源，全部是 app 私有目录里本 bot 落盘的运行记录（没有一个是编的）：
      owner.json        {"user_openid"}                  → 主人（第一个私聊 bot 的人）
      known_chats.json  chats[].openid / kind            → 见过的群与私聊对象
      last_sender.json  member_openid / group_openid     → 群里最近的发言人
      memory.json       key = "g"/"p" + openid           → 运行期记忆的会话键
    平台本身不下发 ownerid（botpy 的 Robot 只有 name / id / avatar），
    所以 owner 这一项来自 owner.json，没有这个文件就是空串。
    """
    import json
    groups = {}   # openid -> 行
    users = {}

    def _add(bucket, openid, source, count=0, last=""):
        openid = str(openid or "").strip()
        if not openid:
            return
        cur = bucket.get(openid)
        if cur is None:
            bucket[openid] = {"id": openid, "count": count, "last": last, "source": source}
        else:
            cur["count"] = max(cur["count"], count)
            if not cur["last"]:
                cur["last"] = last

    # 1) 运行期记忆：key = g+group_openid / p+user_openid
    h = _memory(botdir)
    for k, rows in h.items():
        if not isinstance(rows, list) or not rows:
            continue
        ts = [r[2] for r in rows
              if isinstance(r, list) and len(r) > 2 and isinstance(r[2], (int, float))]
        last = time.strftime("%m-%d %H:%M", time.localtime(max(ts))) if ts else ""
        s = str(k)
        if len(s) < 2:
            continue
        if s.startswith("g"):
            _add(groups, s[1:], "memory.json", len(rows), last)
        elif s.startswith("p"):
            _add(users, s[1:], "memory.json", len(rows), last)

    # 2) 旧的（也可能是新版刚写的）known_chats.json
    kc = _load_json(os.path.join(botdir, "known_chats.json"), {})
    chats = kc.get("chats") if isinstance(kc, dict) else None
    if isinstance(chats, list):
        for c in chats:
            if not isinstance(c, dict):
                continue
            if str(c.get("kind") or "") == "group":
                _add(groups, c.get("openid"), "known_chats.json")
            else:
                _add(users, c.get("openid"), "known_chats.json")

    # 3) 群消息里最近的发言人
    ls = _load_json(os.path.join(botdir, "last_sender.json"), {})
    if isinstance(ls, dict):
        _add(groups, ls.get("group_openid"), "last_sender.json")
        _add(users, ls.get("member_openid"), "last_sender.json")

    # 4) owner.json = 主人（第一个私聊 bot 的人）
    oj = _load_json(os.path.join(botdir, "owner.json"), {})
    owner = ""
    if isinstance(oj, dict):
        owner = str(oj.get("user_openid") or oj.get("member_openid") or "").strip()
        _add(users, owner, "owner.json")

    def _rows(b):
        out = list(b.values())
        out.sort(key=lambda x: (x["last"], x["count"]), reverse=True)
        return out

    return json.dumps({
        "owner": owner,
        "groups": _rows(groups),
        "users": _rows(users),
        "dir": botdir,
    }, ensure_ascii=False)

# ── 识图缓存（<botdir>/images/ 下落盘的图片）───────────────────────────────

def _image_dir(botdir):
    """识图缓存的目录就是每个 bot 目录下的 images（不另造目录）。"""
    return os.path.join(str(botdir or ""), "images")


def image_cache_size(botdir):
    """<botdir>/images/ 下所有文件的字节数；目录不存在 / 读不了都算 0。"""
    d = _image_dir(botdir)
    total = 0
    try:
        names = os.listdir(d)
    except OSError:
        return 0
    for name in names:
        p = os.path.join(d, name)
        try:
            if os.path.isfile(p):
                total += os.path.getsize(p)
        except OSError:
            continue
    return total


def clear_image_cache(botdir):
    """删 <botdir>/images/ 下的文件，返回 {"files": 文件数, "bytes": 字节数} 的 JSON。

    只删文件，不删目录本身（目录还在，识图流程继续往里写）。
    """
    d = _image_dir(botdir)
    files = 0
    total = 0
    try:
        names = os.listdir(d)
    except OSError:
        return json.dumps({"files": 0, "bytes": 0})
    for name in names:
        p = os.path.join(d, name)
        try:
            if not os.path.isfile(p):
                continue
            size = os.path.getsize(p)
            os.remove(p)
            files += 1
            total += size
        except OSError:
            continue
    return json.dumps({"files": files, "bytes": total})


def python_version():
    """Chaquopy 里真在跑的解释器版本（platform.python_version()，如 3.13.0）。"""
    import platform
    return platform.python_version()

