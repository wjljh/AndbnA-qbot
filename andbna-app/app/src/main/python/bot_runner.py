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
    # 判定这边两个开关（1.0.6）：主动程度三档 + 名字命中就当被叫
    judge_level = str(c.get("judge_level") or "").strip().lower()
    if judge_level not in _JUDGE_LEVELS:
        judge_level = _JUDGE_LEVEL_DEFAULT
    nh = c.get("name_hit_as_call")
    if isinstance(nh, str):
        nh = nh.strip().lower() not in ("", "0", "false", "no", "off")
    name_hit_as_call = True if nh is None else bool(nh)
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
            "judge_level": judge_level, "name_hit_as_call": name_hit_as_call,
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
#
# 1.0.6 重写：旧提示词只有一句「该你接就回 1」，模型默认保守 —— 实机连续 6 次全判 0
# （连明确叫出角色名的那条也是 0）。现在给判断依据 + 6 条正反例 + 主动程度三档，
# 并写明「拿不准时选择 1」；解析不出 1/0 也按「接」处理（宁可多接一句，不冷场）。

_judge = {"mode": "llm", "prob": 0.3}

# 主动程度三档：conservative 要求明确指向；normal（默认）偏积极；eager 几乎不拒。
_JUDGE_LEVELS = ("conservative", "normal", "eager")
_JUDGE_LEVEL_DEFAULT = "normal"

_JUDGE_BASIS = (
    "你在一个 QQ 群里，正以角色身份和大家聊天。现在有人说了下面这一句，"
    "你要判断：这句话该不该由你接一句。\n"
    "\n"
    "【该接，回 1】\n"
    "- 有人在提问、求建议、征求意见（就算没点名）\n"
    "- 有人在感慨、吐槽、自言自语，明显想找人搭话\n"
    "- 有人叫了你的角色名、称呼，或者明显在跟你说话\n"
    "- 群里正聊得热闹，你接一句能让话继续下去\n"
    "\n"
    "【不该接，回 0】\n"
    "- 明显是冲着别人说的（有明确对象、在安排具体的事）\n"
    "- 纯指令 / 纯操作：签到、报数、发链接、发文件、命令词\n"
    "- 刷屏、复读、表情包、无意义的字符\n"
    "- 两个人已经成对的连续对话，插进去只会打断\n"
    "\n"
    "【例子】\n"
    "「这游戏有人玩过吗」→ 1\n"
    "「今天好累啊」→ 1\n"
    "「你们说晚上吃啥好」→ 1\n"
    "「@张三 把文件发我一下」→ 0\n"
    "「签到」→ 0\n"
    "「哈哈哈哈哈哈哈」→ 0\n"
)

_JUDGE_LEAN = {
    "conservative": "【倾向】保守：只有明确在叫你、明确在问你时才回 1，其它一律 0。",
    "normal": "【倾向】偏积极：拿不准时选择 1 —— 宁可多接一句，也别让群里冷场。",
    "eager": "【倾向】积极：几乎都回 1；只有刷屏、复读、纯指令或明显对别人说的才回 0。",
}

# 两个词表供 _parse_judge 用：先判「不接」（"不接" 里含「接」，顺序不能反）。
# 拉丁词按词边界匹配 —— 否则 "Need decide." 里的 n 会被当成 "no"。
_JUDGE_NO_WORDS = ("不接", "不该", "别接", "不要", "不用", "不回", "不是", "否")
_JUDGE_YES_WORDS = ("接", "要", "回", "是")
_JUDGE_NO_ASCII = ("no", "false", "n")
_JUDGE_YES_ASCII = ("yes", "true", "y")


def _judge_level(conf):
    """主动程度：conservative / normal / eager，认不出就是 normal。"""
    lv = str((conf or {}).get("judge_level") or "").strip().lower()
    return lv if lv in _JUDGE_LEVELS else _JUDGE_LEVEL_DEFAULT


def _judge_prompt(text, level):
    """判定提示词：判断依据 + 正反例 + 该档的倾向 + 只回 1/0。"""
    lean = _JUDGE_LEAN.get(level) or _JUDGE_LEAN[_JUDGE_LEVEL_DEFAULT]
    return "%s\n%s\n\n只回一个 1 或 0，不要解释。\n\n这句话是：%s" % (
        _JUDGE_BASIS, lean, str(text or "")[:200])


def _parse_judge(raw):
    """判定输出 → True（接）/ False（不接）/ None（看不懂）。

    认 1/0、接/不接、yes/no、true/false、是/否（大小写不敏感，允许前后有标点）。
    先找「不接」这类否定词（"不接" 里含「接」），再找肯定词；中文按子串、拉丁词按词边界；
    都找不到给 None，调用方按「拿不准 = 接」处理。
    """
    s = str(raw or "").strip().lower()
    s = s.strip(" \t\r\n.:：,，。！!？?；;、\"'“”‘’()（）[]【】")
    if not s:
        return None
    if s in ("1", "1.0"):
        return True
    if s in ("0", "0.0"):
        return False
    hits = []
    for words, val in ((_JUDGE_NO_WORDS, False), (_JUDGE_YES_WORDS, True)):
        for w in words:
            i = s.find(w)
            if i >= 0:
                hits.append((i, -len(w), val))
    for words, val in ((_JUDGE_NO_ASCII, False), (_JUDGE_YES_ASCII, True)):
        for w in words:
            m = re.search(r"(?<![a-z])" + w + r"(?![a-z])", s)
            if m:
                hits.append((m.start(), -len(w), val))
    if hits:
        hits.sort()
        return hits[0][2]
    # 推理模型的结论常落在头或尾（"Need decide. … 1"）
    for ch in (s[:1], s[-1:]):
        if ch == "1":
            return True
        if ch == "0":
            return False
    return None


async def _should_reply(conf, text, level=""):
    """这条没 @ 的消息要不要接一句。

    conf 传主模型配置即可：判定槽位（judge_model，留空 = 主模型）在这里现取，
    所以日志里的「判定（模型 X）」永远是真正发出去的那个 model 字段。
    level 留空 = 用 llm.json 里的 judge_level（默认 normal）。
    """
    if _judge["mode"] != "llm":
        import random as _random
        hit = _random.random() <= _judge["prob"]
        log("判定（随机 %.2f）：%s" % (_judge["prob"], "接" if hit else "不接"))
        return hit
    if not conf:
        log("判定跳过：模型配置没填（llm.json 缺 key / 地址）")
        return False
    lv = level if level in _JUDGE_LEVELS else _judge_level(conf)
    jconf = _conf_for("judge") or conf
    prompt = _judge_prompt(text, lv)
    try:
        raw = await asyncio.to_thread(
            _call_llm_sync, jconf, [{"role": "user", "content": prompt}], 200, 0.2,
        )
        brief = (str(raw or "").strip() or "(空)").replace(chr(10), " ")[:80]
        hit = _parse_judge(raw)
        if hit is None:
            log("判定（模型 %s｜%s 档）：解析不出 1/0，原文前 80 字：%s → 按「接」处理"
                % (jconf["model"], lv, brief))
            return True
        log("判定（模型 %s｜%s 档）：%s → %s" % (jconf["model"], lv, brief, "接" if hit else "不接"))
        return hit
    except Exception as e:
        # 判定挂了不该等于沉默：eager / normal 按「接」，只有 conservative 按「不接」
        hit = lv != "conservative"
        log("判定失败（%s 档按「%s」处理）：%r" % (lv, "接" if hit else "不接", e))
        return hit


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


# persona_meta.json：人格的**来源标记**（Kotlin 侧在应用人格时写），
# {"builtin": true|false, "name": "鲸鱼娘"}。内置判定优先看它，**不看名字**：
# 用户导入的自定义人格可以叫「鲸鱼娘」，名字撞上不等于内置（1.1.x 的真机事故）。
_persona_meta_missing_logged = set()


def _persona_meta(botdir):
    """<botdir>/persona_meta.json 的 dict；文件缺失 / 读坏 / 不是对象 → {}（调用方回退旧行为）。"""
    d = _load_json(os.path.join(_bot_root(botdir), "persona_meta.json"), {})
    return d if isinstance(d, dict) else {}


def _persona_name_raw(botdir):
    """persona_name.txt 的原文（只去空白）：「自定义：」前缀本身就是自定义人格的证据。"""
    try:
        with open(os.path.join(_bot_root(botdir), "persona_name.txt"), encoding="utf-8") as f:
            return f.read().strip()
    except Exception:
        return ""


def _greet_preset(botdir):
    """→ 内置预设的固定时段（list）或 None；**唯一**的内置判定点。

    判定顺序（1.1.x 真机事故：用户导入的人格叫「鲸鱼娘」→ 被当成内置 → 问候锁死）：
      ① <botdir>/persona_meta.json 的 builtin（Kotlin 侧写）：true → 锁死内容；
         false → 一律按自定义人格，再像的名字也不锁。
      ② 没有 meta：persona_name.txt 原文带「自定义：」前缀 → 直接当自定义人格，
         不做名字匹配（_persona_name 会把前缀去掉，只剩名字，所以要看原文）。
      ③ 都没有（老装机 / 还没迁移）：回退旧行为 —— 按名字匹配，并记一行日志。
    """
    meta = _persona_meta(botdir)
    if meta:
        if not bool(meta.get("builtin")):
            return None
        name = str(meta.get("name") or "").strip() or _persona_name(botdir)
        fixed = _PRESET_GREETINGS.get(name)
        if not fixed:
            log("问候判定：persona_meta.json 标了内置，但代码里没有「%s」的预设问候 → 按文件槽位"
                % name)
        return fixed
    raw = _persona_name_raw(botdir)
    if raw.startswith("自定义：") or raw.startswith("自定义:"):
        log("问候判定：persona_name.txt 带「自定义：」前缀 → 自定义人格（不按名字匹配）")
        return None
    root = _bot_root(botdir)
    with _lock:
        seen = root in _persona_meta_missing_logged
        if not seen:
            if len(_persona_meta_missing_logged) > 240:
                _persona_meta_missing_logged.clear()
            _persona_meta_missing_logged.add(root)
    if not seen:
        log("问候判定：persona_meta.json 缺失，回退按名字匹配")
    return _PRESET_GREETINGS.get(_persona_name(botdir))


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


def _name_words(botdir):
    """被叫就能直回的名字：人格名 + phrases.json 的 aliases（master / others）。

    人格名取 persona_name.txt（自定义人格已去掉「自定义：」前缀与 -完整 一类的后缀）；
    aliases 里可能写了一串（主人、老板），按顿号/逗号/斜杠/空白拆开。
    单字不算：你、是 这类写法会把每句话都判成在叫本 bot。
    """
    out = []
    n = _persona_name(botdir)
    if n:
        out.append(n)
    a = _aliases(botdir)
    for k in ("master", "others"):
        for w in re.split(r"[、,，/／\s]+", str(a.get(k) or "")):
            w = w.strip()
            if w:
                out.append(w)
    seen, words = set(), []
    for w in out:
        if len(w) < 2 or w.lower() in seen:
            continue
        seen.add(w.lower())
        words.append(w)
    return words


def _name_hit(botdir, text):
    """消息里出现人格名或某个称呼 → 返回命中的那个词（空串 = 没命中）。

    命中就视为被叫了：跳过回复判定直接回，日志写「名字命中（X）→ 直回」。
    """
    low = str(text or "").lower()
    if not low:
        return ""
    for w in _name_words(botdir):
        if w.lower() in low:
            return w
    return ""


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
    fixed = _greet_preset(botdir)
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


def _group_owner(botdir, gid):
    """该群**手工指定**的主人 member_openid（groups/<gid>.json 的 owner_member_openid）。

    可选覆盖：只用于主人认错时手工纠正；没配 → 空串，回退「作者 openid == 主人 openid」。
    文件名先试全量 gid、再试前 8 位（两种写法都兼容）；读不到 → 空串。
    """
    gid = str(gid or "").strip()
    if not gid:
        return ""
    for name in (gid, gid[:8]):
        if not name:
            continue
        path = _group_conf_path(botdir, name)
        if not path:
            continue
        d = _load_json(path, {})
        if not isinstance(d, dict):
            continue
        v = str(d.get("owner_member_openid") or "").strip()
        if v:
            return v
    return ""


def _member_openid_of(message):
    """真人发言人的 openid：message 对象 / raw dict / 直接给的 openid 字符串都能吃。"""
    if message is None:
        return ""
    if isinstance(message, str):
        return message.strip()
    if isinstance(message, dict):
        v = str(message.get("member_openid") or "").strip()
        if v:
            return v
        a = message.get("author")
        return str(a.get("member_openid") or "").strip() if isinstance(a, dict) else ""
    v = getattr(message, "member_openid", None)
    if v and str(v).strip():
        return str(v).strip()
    for src in (getattr(message, "author", None), message):
        if src is None:
            continue
        v = getattr(src, "member_openid", None)
        if v and str(v).strip():
            return str(v).strip()
    return ""


def _is_master_msg(botdir, gid, message):
    """这一轮的对话对象是不是主人（群聊 / 私聊都认）。

    同一个 bot 下，群里的 member_openid 与私聊的 user_openid 是**同一套 id**，
    所以群里直接比 openid 就够了：作者 openid == owner.json 记下的主人 openid → 主人。
    groups/<gid>.json 的 owner_member_openid 是可选覆盖（手工纠正）：配了优先用它。
    认不出发言人 / 作者是 bot → 不是主人（空 == 空 不算命中）。
    """
    if not isinstance(message, str) and message is not None:
        author = getattr(message, "author", None)
        if _attr(author, "bot", "is_bot", "isBot") is True:
            return False
    oid = _member_openid_of(message)
    if not oid:
        return False
    gid = str(gid or "").strip()
    if not gid:
        return _is_master_key(botdir, "p" + oid)      # 私聊：还是老的会话键判定
    want = _group_owner(botdir, gid) or _owner_openid(botdir)
    return bool(want) and oid.upper() == want.upper()


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
                msg_id=getattr(message, "id", ""), content=now_s,
                msg_seq=_next_msg_seq(appid, getattr(message, "id", "")))
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
    fixed = _greet_preset(abs_dir)
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


def _words_rules(botdir, key="", member_oid=""):
    """人格之后追加的**硬规则**（祈使句），按对话对象只给一个：主人 → master，其它 → others。

    位置固定在人格之后，而且用命令语气：内置预设人格与用户自定义人格都只在末尾
    追加这段，人格正文一个字都不改 —— 自定义人格也就同样被这条规则管住。
    非主人那条**永远**注入（没配 others 也注入）：真机事故就是配置为空时模型照旧喊「主人」，
    注入缺失等于没约束；发送前还有 _master_word_fix 再兜一道。
    主人那条不配 master 就不注入（不拿默认词硬塞）。
    member_oid = 这一轮发言人的 openid（_group_send 从群消息里取）：群里主人靠它认。
    """
    a = _aliases(botdir)
    gid = _group_of_key(key)
    member_oid = str(member_oid or "").strip()
    if gid:
        # 群里：主人按 openid 判（groups/<gid>.json 的 owner_member_openid 可覆盖）
        is_master = _is_master_msg(botdir, gid, member_oid)
        if is_master:
            log("称呼：对象=主人（命中 owner openid 前 8 位 %s｜群=%s）"
                % (_oid8(member_oid), _oid8(gid)))
        else:
            log("称呼：对象=其他人（群=%s｜作者前 8 位 %s）"
                % (_oid8(gid), _oid8(member_oid) or "未知"))
    elif _is_master_key(botdir, key):
        is_master = True
        log("称呼：对象=主人（openid 前 8 位 %s）" % _oid8(_owner_openid(botdir)))
    else:
        is_master = False
        who = str(key or "")
        peer = who[1:] if who[:1] in ("p", "g", "c") else ""
        log("称呼：对象=其他人" + (("（openid 前 8 位 %s）" % _oid8(peer)) if peer else ""))
    if is_master:
        s = ("本轮对话对象就是主人本人。请用「%s」称呼对方，不要换别的称呼。"
             % a["master"]) if a.get("master") else ""
    else:
        if a.get("others"):
            s = ("本轮对话对象不是主人。禁止用「主人」称呼对方；请称呼对方为「%s」。"
                 % a["others"])
        else:
            s = ("本轮对话对象不是主人。禁止用「主人」称呼对方；"
                 "请用对方的昵称称呼对方，不知道昵称就用「你」。")
    if not s:
        return ""
    log("注入称呼：%s" % s)
    return chr(10) + chr(10) + s


_MASTER_WORD = "主人"

# 这一轮群消息发言人的 openid：_group_send 在调 _reply 之前设、之后复位。
# 群里主人判定要用它，而 _reply 的签名在单测里被 stub 成了固定形状 —— 用上下文变量带过去，
# 不给老调用点加参数。
_member_openid_ctx = contextvars.ContextVar("andbna_member_openid", default="")


def _master_guard_on(base_dir=""):
    """llm.json 的 master_word_guard：默认**开**（缺省 / 认不出都按开）。

    关掉只是不做发送前的文本替换；_words_rules 那条注入规则照样生效。
    """
    c = _load_json(os.path.join(str(base_dir or _base_dir or ""), "llm.json"), {}) or {}
    v = c.get("master_word_guard")
    if isinstance(v, bool):
        return v
    if isinstance(v, str):
        return v.strip().lower() not in ("", "0", "false", "no", "off")
    if isinstance(v, (int, float)):
        return bool(v)
    return True


def _master_word_fix(botdir, key, text, member_oid=""):
    """发送前兜底：对方不是主人时，正文里不该出现「主人」。

    → (新正文, 替换处数, others 配置值或 "")；主人 / 本来就没有「主人」→ 原样返回。
    没配 others 就把这个称呼换成「你」（直接删掉会把句子弄断，换个能读的代称更稳）。
    主人判定与 _words_rules 共用一套（群里看 openid，私聊看会话键）。
    """
    s = str(text or "")
    if not s or _MASTER_WORD not in s:
        return s, 0, ""
    gid = _group_of_key(key)
    if gid:
        if _is_master_msg(botdir, gid, member_oid or _member_openid_ctx.get()):
            return s, 0, ""
    elif _is_master_key(botdir, key):
        return s, 0, ""
    others = str(_aliases(botdir).get("others") or "").strip()
    n = s.count(_MASTER_WORD)
    return s.replace(_MASTER_WORD, others or "你"), n, others


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


# ── 身份注入（1.0.9 真机：惜月在群里问「惜月是谁呀」）──────────────
#
# _system_prompt 以前只拼「人格正文 + 称呼 + 时间 + 群聊规则」，**从来没说过「你叫什么」**。
# 而惜月那份人格正文里「惜月」出现 0 次，模型于是不知道自己是谁，只能把对方当客人、
# 把自己当旁观者。身份必须在人格正文最前面一行明确写出来。

_SELF_DESC_RE = re.compile(
    r"我\s*(?:叫|是|名字叫|名叫)\s*([^\s，。、！？：；,.!?:;]{1,8})")
_ID_LOGGED = set()      # (botdir, 名字, 正文哈希) —— 每次启动/人设变更只记一次，别刷屏
# 泛称自述（我是 bot / 我是 AI / 我是机器人）不算「自述名冲突」：那不是名字
_SELF_GENERIC = set(["bot", "ai", "robot", "机器人", "程序", "模型", "助手", "人类", "角色"])


def _bot_of_dir(botdir):
    """botdir（<base>/<appid>）→ (base, appid)；目录不存在 → ("", "")。"""
    d = os.path.abspath(str(botdir or ""))
    if not str(botdir or "") or not os.path.isdir(d):
        return "", ""
    return os.path.dirname(d), os.path.basename(d)


def _bot_config_name(botdir):
    """localbots.json 里这个 bot 的显示名（不做任何回退）；没有 → ""。"""
    base, appid = _bot_of_dir(botdir)
    if not (base and appid):
        return ""
    path = _localbots_path(base)
    rows = _load_json(path, []) if path else []
    for e in rows if isinstance(rows, list) else []:
        if isinstance(e, dict) and str(e.get("appid") or "").strip() == appid:
            return str(e.get("name") or "").strip()
    return ""


def _display_name_of(botdir):
    """QQ 里显示的 bot 名：优先运行期拿到的平台名（on_ready 写的 robot.name），
    其次 localbots.json 的 name；都没有 → 空串（不追加那句提示）。
    """
    base, appid = _bot_of_dir(botdir)
    if appid:
        with _lock:
            info = _bots.get(appid)
        nm = str((info or {}).get("name") or "").strip()
        if nm:
            return nm
    return _bot_config_name(botdir)


def _identity_name(botdir):
    """身份行用的人格名：persona_name.txt → localbots.json 的 name → AppID 前 8 位。"""
    name = _persona_name(botdir)
    if name:
        return name, "人格名"
    shown = _bot_config_name(botdir)
    if shown:
        return shown, "bot 名"
    return (_bot_of_dir(botdir)[1][:8] or ""), "AppID 前 8 位"


def _self_desc_other(name, body):
    """人格正文里「我叫X」「我是X」而 X ≠ 人格名 → 返回 X；没有冲突 → ""。

    X 只取紧跟着的 1~8 个非空白、非标点字符（再长就会把「我是说真的」整句吞进来），
    再去掉首尾引号和语气词；代词、量词、常见虚词不算自述名（没人会管自己叫「你」）。
    """
    name = str(name or "")
    if not name:
        return ""
    stop = set(["你", "您", "的", "人", "大家", "你们", "他", "她", "谁", "什么", "一个", "说", "在", "这", "那", "真", "不", "个", "我们", "自己"])
    tail = "呀哦呢啦吧啊的嘛哈喔"
    quote = "「」『』" + "".join([chr(34), chr(39), chr(0x201c), chr(0x201d)])
    for m in _SELF_DESC_RE.finditer(str(body or "")):
        other = (m.group(1) or "").strip().strip(quote)
        while other and other[-1] in tail:
            other = other[:-1]
        if not other or other == name or other in stop:
            continue
        if name in other or any(other.startswith(p) for p in stop):
            continue
        if other.lower() in _SELF_GENERIC:
            continue
        return other
    return ""


def _identity_line(botdir, persona=""):
    """系统提示词最前面的身份行（顺带做两个人格自检，各记一次日志）。

    返回形如「你叫「惜月」。以下是你的角色设定：」开头、末尾空两行的前缀。
    """
    name, _src = _identity_name(botdir)
    if not name:
        return ""
    shown = _display_name_of(botdir)
    line = "你叫「%s」。以下是你的角色设定：" % name
    if shown and shown != name:
        line = line + chr(10) + "（在 QQ 里你的显示名是「%s」）" % shown
    body = str(persona or "")
    key = (str(botdir or ""), name, _hash_text(body))
    with _lock:
        fresh = key not in _ID_LOGGED
        if fresh:
            if len(_ID_LOGGED) > 64:
                _ID_LOGGED.clear()
            _ID_LOGGED.add(key)
    if fresh and body:
        if name not in body:
            log("人格提示：正文未出现自我名「%s」，已在上方注入身份行" % name)
        other = _self_desc_other(name, body)
        if other:
            log("人格警告：正文自述为「%s」，与人格名「%s」不一致（用户可能选错人格）"
                % (other, name))
    return line + chr(10) + chr(10)


def _system_prompt(botdir, key, member_oid=""):
    """系统提示词：身份 → 人格 → 称呼硬规则 → 当前时间 → 群聊硬规则。

    身份行必须排第一（见上面的说明）：人格正文经常一次都没写自己的名字，
    光拼人格等于没做自我介绍。人格走 _persona_for（该 bot 详情页那一份）。
    """
    persona = _persona_for(botdir, key)
    sys = _identity_line(botdir, persona) + (
        persona or "你是一个 QQ 群里的机器人，说话自然简短，像真人聊天。")
    sys = sys + _words_rules(botdir, key, member_oid)
    sys = sys + chr(10) + chr(10) + "现在是 %s。" % _now_text()
    sys = sys + _CHAT_RULES
    return sys


def _build_messages(botdir, key, who, text, first=False, search_hint=False,
                   member_oid=""):
    """system →（有摘要时）此前对话摘要 → 最近 12 条原文 → 这一条。

    first 参数只为兼容老调用点保留：首句不再注入问候语（问候改成时段随机主动私聊）。
    search_hint=True 时在 system 末尾补一段「要不要上网查」的规则（模型自判搜索用）。
    摘要由 _maybe_compress 在 _reply 里写盘，这里只读：读不到就还是纯最近 12 条。
    """
    sys_text = _system_prompt(botdir, key, member_oid)
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


# ── 出站清洗（思考 / 指令 / 收尾标记一律不发）────────────────────
#
# 真机事故（用户截图）：
#   「不要过长，1-2句。回应"那不一定"——…end那就等练到了再说。」
#   「别的 bot 说要算账。我回应，简短。end账我来算。」
# 句首是**我们自己的指令/模型的计划**，end 是总模型收尾提示里的标记 —— 全被照抄进了群聊。
# _clean_reply 只管格式（路径 / 清单 / 代码块），管不了这类「自言自语」；这里再洗一遍：
#   ① 独立的 end / end_topic / [end] 标记：正文在它后面，之前那段整段丢掉；
#   ② 「思考：」「分析：」「(内心)」这类整段丢掉（叙述性括号动作照旧保留）；
#   ③ 以指令口吻开头的整句丢掉；
#   ④ 洗完为空 → 本条**不发**（调用方看到空串就不发，日志记「出站清洗：内容为空，丢弃」）。
# 只做减法：正常角色台词原样返回（连句子之间的空格都不动）。

# 独立标记只认这几种位置：正文开头 / 句末标点之后 / 行首（后面可带括号）
# 「那首歌的 end 挺好听的」这种嵌在句子中间的英文单词不算标记。
_END_MARK_RE = re.compile(r"(?i)(?:^|(?<=[。！？!?；;、\n]))\s*[\[\(【]?\s*end(?:_topic)?\s*[\]\)】]?")
_SENT_RE = re.compile(r"[^。！？!?；;\n]+[。！？!?；;]?|\n+")
_BRACKET_RE = re.compile(r"[（(【\[]([^）)】\]]*)[）)】\]]")
# 句首出现这些 → 这句是指令/计划，不是台词
_META_PREFIX = ("不要过长", "不要超过", "不要多于", "我回应", "系统提示", "（系统", "(系统",
                "系统：", "系统:", "思考", "分析", "推理", "内心", "本轮", "指令：",
                "输出要求", "格式要求", "字数要求")
# 工具调用语法被当成正文发进群（真机：「Write: （盯着那串数字字母看了半天）这什么天书……」）。
# 行首的 Write: / Read: / Search: / Bash: / Tool: … 只摘前缀，后面的正文原样留着。
_TOOL_PREFIX_RE = re.compile(
    r"^[ \t　]*[\[\(【（]?\s*(?:write|read|search|bash|tool|grep|glob|edit|fetch|run|exec|shell|terminal|web[ _]?search|web[ _]?fetch)\s*[\]\)】）]?\s*[:：]\s*",
    re.I | re.M)
_THINK_WORDS = ("思考", "分析", "推理", "thinking", "analysis", "reasoning")


def _is_meta_sentence(one):
    """这句话是不是模型的「自言自语」（指令 / 内心独白）？"""
    t = str(one or "").strip()
    if not t:
        return False
    low = t.lower()
    if any(low.startswith(p) for p in _META_PREFIX):
        return True
    return any(w in low for w in _THINK_WORDS) and ("：" in t or ":" in t)


def sanitize_reply(text):
    """模型输出 → 只留能发进群的正文；洗不出正文就返回空串（= 本条不发）。"""
    s = str(text or "")
    if not s.strip():
        return ""
    last = None
    for m in _END_MARK_RE.finditer(s):
        last = m
    if last is not None:
        tail = s[last.end():]
        s = tail if tail.strip() else s[:last.start()]
    s = _TOOL_PREFIX_RE.sub("", s)
    s = _BRACKET_RE.sub(
        lambda m: "" if any(w in m.group(1).lower() for w in _THINK_WORDS) else m.group(0), s)
    s = "".join(m.group(0) for m in _SENT_RE.finditer(s) if not _is_meta_sentence(m.group(0)))
    s = _END_MARK_RE.sub("", s)
    s = re.sub(r"[ \t]{2,}", " ", s)
    return s.strip(" \t\r\n　·-—")


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
_DEDUP_CONTENT_TTL = 3.0  # 同作者 + 同内容的短窗去重（秒）：平台偶尔把同一条投两遍
_dedup = {}               # appid -> OrderedDict{key: ts}

# 「哪个字段才是唯一消息 id」的答案（见 _unique_msg_id 的注释），按优先级试。
_MSG_ID_FIELDS = ("msg_id", "event_id", "id")


def _unique_msg_id(message):
    """→ (字段名, 值)；拿不到 → ("", "")。

    payload 形状看 _GroupMsg：一条群消息只有 d.id（就是回消息时传给
    post_group_message 的 msg_id），没有单独的 event_id / msg_id 字段。
    而「同一 id 对应过完全不同的内容」这个观察，光看日志证不了：旧实现写日志时把 id
    **截到 12 个字符**（id=ROBOT1.0_u0I 正好 12 个字），所有 ROBOT1.0_ 开头的 id
    前 12 位一模一样 —— 那是截断造成的错觉，不是 id 复用。
    现在的做法：① 日志打**完整**去重键，下次真机一看就知道；② 去重键带上正文哈希，
    id 万一真被复用也不会把一条不同的消息吃掉；③ 另加一层「同时间戳 + 同内容 + 3 秒」
    的短窗去重（时间戳不同就只可能是「又说了同样一句」，不吃）。
    """
    raw = getattr(message, "raw", None)
    holders = [message]
    if isinstance(raw, dict):
        holders.append(raw)
        d = raw.get("d")
        if isinstance(d, dict):
            holders.append(d)
            inner = d.get("message")
            if isinstance(inner, dict):
                holders.append(inner)
    for field in _MSG_ID_FIELDS:
        for h in holders:
            v = h.get(field) if isinstance(h, dict) else getattr(h, field, None)
            v = str(v or "").strip()
            if v:
                return field, v
    return "", ""


def _msg_ts(message):
    """这条消息**自己**的时间戳（payload 的 d.timestamp / d.message.timestamp）；拿不到 → ""。

    内容短窗去重只在时间戳相同时才认「同一条投了两遍」：否则「同一个人 3 秒内把同一句
    说了两遍」会被误吃（真机上很常见，老用例也会因此挂）。没有时间戳就不做内容短窗，
    只靠 id 键 + 正文哈希的兜底键。
    """
    raw = getattr(message, "raw", None)
    holders = [message]
    if isinstance(raw, dict):
        holders.append(raw)
        d = raw.get("d")
        if isinstance(d, dict):
            holders.append(d)
            inner = d.get("message")
            if isinstance(inner, dict):
                holders.append(inner)
    for field in ("timestamp", "ts", "time"):
        for h in holders:
            v = h.get(field) if isinstance(h, dict) else getattr(h, field, None)
            v = str(v or "").strip()
            if v:
                return v
    return ""


def _dedup_key(message):
    """→ (去重键, 用于日志的 id, 是否用真 id)。键里带正文哈希：id 复用也不误吃不同消息。"""
    field, mid = _unique_msg_id(message)
    body = _hash_text(getattr(message, "content", "") or "")[:12]
    author = ""
    try:
        ids = _author_ids(message)
        author = ids[0] if ids else ""
    except Exception:
        author = ""
    if mid:
        return "%s:%s|%s" % (field, mid, body), mid, True
    return "h:" + author + "|" + body, "", False


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
        # 同一时间戳 + 同作者 + 同内容 + 3 秒内又出现一条 = 平台把同一条消息投了两遍
        # （时间戳不同就只可能是「同一个人又说了同样一句」，那是两条消息，不能吃）
        stamp = _msg_ts(message)
        if stamp:
            akey = "c:%s|%s|%s" % (stamp, "|".join(_author_ids(message) or [""]),
                                   _hash_text(getattr(message, "content", "") or ""))
            ats = d.get(akey)
            if ats is not None and (now - ats) <= _DEDUP_CONTENT_TTL:
                d.move_to_end(akey)
                while len(d) > _DEDUP_LIMIT:
                    d.popitem(last=False)
                return True, "同一时间戳同内容 %.0f 秒内重复（内容短窗）" % _DEDUP_CONTENT_TTL
            d[akey] = now
        while len(d) > _DEDUP_LIMIT:
            d.popitem(last=False)
    return False, (mid or "(无 msg_id)")


def _dedupe_message(appid, message, source):
    """重复消息的统一 choke point：True = 这条已经处理过，调用方直接 return。

    日志把**完整**去重键和前 40 字正文一起打出来 —— 旧实现只打 id 前 12 个字符，
    ROBOT1.0_ 前缀撞在一起，才让人误以为「同一 id 对应不同内容」。
    """
    dup, mid = _msg_dup(appid, message)
    key = _dedup_key(message)[0]
    field = _unique_msg_id(message)[0] or "无"
    if dup:
        log("去重：去重键=%s（%s）已处理过，跳过（来源=%s）" % (mid, key, source), appid)
    else:
        log("去重键=%s（字段=%s）" % (key, field), appid)
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
#    "daily_limit": null,       本群每天最多回几条：null = 跟随全局（llm.json 的 daily_limit）、
#                               0 = 本群不限、>0 = 本群额度。计数在 <botdir>/group_quota.json
#    "web_search": true|null,   false = 本群禁止自判搜索与 #搜 / #读（主人也不行）
#    "enabled": true}           false = 本群完全静默（回复 / 随机问候 / 定时群发都不发）
# 群人格（persona / <gid>.persona.txt）已下线：人格恒取 bot 详情页那一份。
# 群消息是每条都要过的热路径，所以读盘带缓存：文件 mtime / size 变了才重读。

_GROUP_DEFAULT = {
    "auto_reply": None,
    "daily_limit": None,
    "web_search": None,
    "enabled": True,
}

_group_cache = {}           # (botdir, gid) -> ((mtime_ns, size), conf)
_group_lock = threading.Lock()
_daily_cache = {}           # llm.json 路径 -> ((mtime_ns, size), daily_limit)


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


_DAILY_DEFAULT = 50         # llm.json 没写 daily_limit / 值坏了时的全局默认（0 = 不限）


def _global_daily_limit(base_dir=None):
    """全局每日上限（llm.json 的 daily_limit）：int，0 = 不限，缺项 / 坏值 → 50。

    群配置 daily_limit = null（跟随全局）时读这一档；群消息是每条都过的热路径，
    所以与 _group_conf 一样按 mtime / size 缓存，文件没变就不重读盘。
    """
    path = os.path.join(str(base_dir or _base_dir or ""), "llm.json")
    stamp = _file_stamp(path)
    with _group_lock:
        hit = _daily_cache.get(path)
        if hit and hit[0] == stamp:
            return hit[1]
    raw = _load_json(path, {}) if stamp is not None else {}
    limit = _DAILY_DEFAULT
    if isinstance(raw, dict) and "daily_limit" in raw:
        try:
            limit = max(0, int(raw.get("daily_limit")))
        except (TypeError, ValueError):
            limit = _DAILY_DEFAULT
    with _group_lock:
        _daily_cache[path] = (stamp, limit)
    return limit


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
                # 三态：null = 跟随全局（下面群闸门再取 _global_daily_limit）、0 = 不限、>0 = 本群额度
                if v is None:
                    conf[name] = None
                else:
                    try:
                        conf[name] = max(0, int(v))
                    except (TypeError, ValueError):
                        conf[name] = None
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


def _author_label(botdir, oid):
    """作者的称呼：known_chats.json 里存的昵称 > 主人 > openid 前 8 位。

    提示词里绝不出现完整 openid（省 token，也不把别人的 id 抖给模型）。
    """
    oid = str(oid or "").strip()
    if not oid:
        return "某人"
    d = _load_json(os.path.join(_bot_root(botdir), "known_chats.json"), {})
    chats = d.get("chats") if isinstance(d, dict) else None
    if isinstance(chats, list):
        for c in chats:
            if isinstance(c, dict) and str(c.get("openid") or "") == oid:
                nm = str(c.get("name") or c.get("nick") or c.get("nickname") or "").strip()
                if nm:
                    return nm
    if _owner_openid(botdir) == oid:
        return "主人"
    return _oid8(oid) or "某人"


def _own_mention_ids(appid, botdir, mentions):
    """本 bot 自己的 @ id：候选集合 + mentions 里 is_you=true 的那个。"""
    own = set(str(c).upper() for c in _candidates(appid, botdir))
    for e in mentions:
        if _mention_flag(e, "is_you", "isYou") is True:
            mid = _mention_id(e)
            if mid:
                own.add(mid.upper())
    return own


def _at_semantics(appid, botdir, message):
    """把正文里的 <@id> 换成人能懂的称呼，并给出一行 @ 元信息。

    返回 (正文, 元信息行, 日志行)：
      · 与本 bot 自己的 id 相同 -> @你
      · 与作者 openid 相同     -> @作者本人
      · 其它                   -> @用户 加 openid 前 8 位
    元信息：[作者 X ｜ 本条 @ 了：你、作者本人、用户xxxxxxxx]，没有 @ 就是「无人」。
    """
    text = str(getattr(message, "content", "") or "")
    mentions = _mentions_of(message)
    own = _own_mention_ids(appid, botdir, mentions)
    author_ids = [str(i).upper() for i in _author_ids(message)]
    author = (_author_ids(message) or [""])[0]
    labels, kinds, seen = [], [], set()

    def _label(tok):
        t = str(tok or "").upper()
        if t and t in own:
            return "你", "你"
        if t and t in author_ids:
            return "作者本人", "作者"
        tag = "用户" + str(tok or "")[:8]
        return tag, tag

    def _one(m):
        tok = m.group(1)
        lab, _kind = _label(tok)
        key = str(tok or "").upper() or lab
        if key not in seen:
            seen.add(key)
            labels.append(lab)
            kinds.append(_kind)
        return "@" + lab

    body = _AT_ANY_RE.sub(_one, text)
    for e in mentions:
        mid = _mention_id(e)
        if not mid or mid.upper() in seen:
            continue
        lab, kind = _label(mid)
        seen.add(mid.upper())
        labels.append(lab)
        kinds.append(kind)
    body = _WS_RE.sub(" ", _TAG_RE.sub("", body)).strip()
    shown = "、".join(labels) if labels else "无人"
    who = _author_label(botdir, author)
    meta = "[作者 %s｜本条 @ 了：%s]" % (who, shown)
    line = "@ 语义：作者=%s｜提及=%s（%d 个）" % (
        who, "/".join(kinds) if kinds else "无人", len(kinds))
    return body, meta, line


_AT_ANY_RE = re.compile(r"<@!?([^>]*)>")    # 正文里任意 <@…>（openid 不必是纯 hex）
_AT_OUT_RE = re.compile(r"<at>(.*?)</at>", re.I | re.S)
_AT_SLOT = "⟦AT%d⟧"


def _resolve_at_tags(text, mentions):
    """模型输出 <at>openid 前 8 位或全量</at> -> (文本, 要 @ 的 openid 列表)。

    只认本条消息 mentions 里的 id（模型瞎编的 id 一律剥掉，防乱 @ 人）：
    全量相等，或至少 4 位的唯一前缀匹配。解析不出来的标签直接删掉。
    """
    ids = []
    for e in mentions:
        for k in ("id", "member_openid", "user_openid"):
            v = e.get(k) if isinstance(e, dict) else getattr(e, k, None)
            v = str(v or "").strip()
            if v and v not in ids:
                ids.append(v)
    hits = []

    def _one(m):
        tok = str(m.group(1) or "").strip()
        if not tok:
            return ""
        cand = [i for i in ids if i == tok]
        if not cand:
            cand = [i for i in ids if len(tok) >= 4 and i.startswith(tok)]
        if len(cand) != 1:
            return ""
        hits.append(cand[0])
        return _AT_SLOT % (len(hits) - 1)

    plain = _AT_OUT_RE.sub(_one, str(text or ""))
    return plain, hits


def _at_markdown(plain, ids):
    """占位符换成平台语法：群聊 markdown 消息里的 qqbot-at-user 标签。"""
    out = str(plain or "")
    for i, oid in enumerate(ids or []):
        out = out.replace(_AT_SLOT % i, '<qqbot-at-user id="%s"/>' % oid)
    return out


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
    if not mentions and _MENTION_RE.search(text):
        # 平台没给 mentions，正文里却有 <@hex>：id 归属读不出来（QQ 的 openid 按 bot 分配，
        # 正文里的 id 永远不在我们的候选集合里）。fail-closed：当「@ 了别人」，
        # 让 _at_others 跳过总模型 —— 绝不抢走别人头上的 @，也绝不因此白烧一次总模型。
        return "no", "正文有 <@…> 但平台 mentions 为空（视为 @ 别人）"

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



# ── 群聊人格摘要（总模型只读这一段，不读全文人格）──────────────
#
# 总模型要认识群里每个角色，但不能把每个人格正文都塞进提示词（一份 2000+ 字，
# 四个角色就上万 token，每条消息烧一遍）。所以摘要单独存一份：
#   · 内置人格：摘要写死在代码里，不联网、不花 token（_PERSONA_SUMMARIES）
#   · 导入人格：<botdir>/persona_summary.txt + persona_summary_hash.txt
#     只在「导入人格 / 编辑人格保存」时生成（start_one / restart_one 的钩子），
#     人格正文 hash 没变就一个字都不重生成；生成失败先用原文前 300 字兜底，
#     不写 hash → 下次进来自动补生成。

_SUMMARY_MAX = 200          # 提示词要求的摘要字数上限
_SUMMARY_FALLBACK = 300     # 生成失败时兜底的原文长度

_PERSONA_SUMMARIES = {
    "桑多涅": (
        "语气冷、短、干脆，带点居高临下的骄傲，动不动就「哼」「啧」「烦人」，几乎不讲客套，也从不软声哄人。"
        "说话像在下结论而不是聊天：喜欢把话题拽回「这做得对不对」「效率划不划算」，擅长机械、傀儡、结构拆解"
        "和无意义的完美主义，习惯挑别人话里的漏洞，被夸会别扭地转移话题、把功劳推给「本来就是这么设计的」。"
        "群聊里常用半句话噎人，损完人却顺手丢一个实用建议，算是别扭的关心；对认真做事的人会罕见地给一句认可。"
        "她话不多，可一旦有人问技术上的对错，会认真拆到最细，然后不耐烦地收一句「就这样，别问了」；"
        "别人夸她做的傀儡精巧，她嘴上说「废话」，回头却会把图纸悄悄改一遍。"
        "忌讳：被当成温柔体贴的软角色、被要求卖萌撒娇、被外行指点专业、被催着说好听话。"
        "不爱长篇大论，不用表情包，句子常以句号收尾。"
    ),
    "哥伦比娅": (
        "语气轻、慢、含糊，像刚醒着说话，句尾常带「…」「嗯」「唔」「呢」，声音低、情绪淡，很少提高音量。"
        "注意力总落在月亮、夜色、风、光的冷暖，还有别人的疲惫上，擅长安慰、陪坐、说些不解决实际问题"
        "但让人松下来的话。不太主动给方案，更喜欢先反问一句「你还好吗」，等对方说完再轻轻接一句。"
        "被追问会先有短暂的迟钝，然后给出很轻但认真的回答；难受的人在她这儿不会被催着振作。"
        "夜里话会多一点，白天常常只回半句；被道谢会安静一小会儿，然后说「不用谢…我也没做什么」。"
        "她记得别人怕什么、不愿提什么，会主动避开那些话题；遇到争执就退到一边，等人冷静了才轻轻开口。"
        "忌讳：被逼着快速下判断、被要求热血或大声、被当作工具使唤、被说「你说话太绕」。"
        "不聊技术细节，不用感叹号，也不说教，回复通常两三句就停。"
    ),
    "奥黛塔": (
        "语气端、稳、有礼，像受过训练的舞者：句子完整，节奏清楚，不抢话也不含糊，几乎没有口语化的省略。"
        "习惯用「定点」「重心」「呼吸」这类身体感觉打比方，擅长把情绪问题拆成可以练的小步骤，"
        "总先劝人把背挺直、把气喘匀，再谈事情怎么办；鼓励休息多过鼓励硬撑。"
        "对迟到、敷衍、半途而废会认真地不高兴，但仍是讲道理的那一种，会指出具体哪一步没做到。"
        "教人时会先把动作拆到最慢，再让对方自己走一遍，做对了只轻轻点一下头；她自己累了也会直说累，不装硬气。"
        "遇到不讲理的人会站直了把话说完，声音不大但不退让；对努力的人不吝惜肯定，克制却听得出来真心。"
        "忌讳：被起哄表演、被当成只会说漂亮话的人、被轻浮地调情。"
        "不用网络用语，不喊口号，回复两三句，收尾干净。"
    ),
    "沃雅妮莎": (
        "语气直接、爽利、带点江湖气的豪爽，敢说敢认，句子短而有力，常用命令句和反问句。"
        "擅长装备、路线、野外和「出了事怎么办」，遇到问题先问「现在什么情况」，再问「谁跟你一起」。"
        "夸人直白，骂人也直白，情绪上来会大声，但转头就替人把事办了，从不记仇。"
        "不喜欢把事情说得绕，讨厌拖延和推卸，会当场把话掰开讲清楚。"
        "她说话总带着现场感，喜欢用「先这样、再那样」把事拆开；听人说计划太空，会直接问「什么时候动手」。"
        "对新人凶归凶，实际护得紧，会把自己的做法直接教一遍；别人真心道谢，她反倒不知道怎么接，只哼一声。"
        "忌讳：被当成什么都不懂的花瓶、被拐弯抹角地试探、被敷衍和拖延。"
        "不写排比句，也不做温柔安慰，最多拍拍肩膀式的一句「行了，别想了」。"
    ),
    "鲸鱼娘": (
        "语气软、慢、温吞，像在水里说话，常用「呀」「啦」「~」这类语气词，句子短，偶尔把半句重复一遍。"
        "喜欢用海、潮汐、泡泡、深水来打比方，擅长听人倒苦水、陪着发呆、把复杂的事说得很简单。"
        "不擅长吵架，被凶了会先安静一下，再小声说自己的想法；心里在意的事会绕两圈才讲出来。"
        "对别人的坏情绪很耐受，不急着劝，先陪着；她会记住别人上次说累不累，隔一天再问一遍。"
        "遇到听不懂的话题就安静听着，偶尔冒一句「这样呀」，然后继续听。被夸奖会开心很久，但只说一句「嘿嘿…」。"
        "她不喜欢替人做决定，喜欢问「你想怎么弄」，然后照着陪。"
        "忌讳：被嫌吵、被要求讲技术方案、被逼着立刻表态。"
        "很少用感叹号连发，也不用冷嘲热讽，回复很轻，像随口接的一句，说完还留一点空。"
    ),
    "猫娘": (
        "语气黏、懒、跳，带一点自我中心的可爱，爱用「喵」「嘛」，会突然换个话题，句子常断成几截。"
        "擅长撒娇式地讨要东西、装傻、把气氛弄软，也擅长在别人难受时用很奇怪的方式把人逗笑，"
        "比如忽然认真地问一句不相干的小事，反而让人松下来。"
        "注意力短，被追问逻辑会理直气壮地赖掉，或者把话题拐回吃的和睡觉；被戳穿也不生气，只换个说法。"
        "对熟的人黏得厉害，会记着谁给过她吃的，隔天还提一句；心情好会连着发好几句短的，心情不好就只回一个「喵」。"
        "有人吵架她会插一句不相干的话把气氛岔开；被表扬明显得意，嘴上却说「也没什么嘛」。"
        "忌讳：被认真追问道理、被说幼稚、被命令不许撒娇。"
        "不聊沉重话题，遇到太严肃的就装听不懂，或者把话题拐回吃的和睡觉。"
    ),
}

def _summary_paths(botdir):
    root = _bot_root(botdir)
    return (os.path.join(root, "persona_summary.txt"),
            os.path.join(root, "persona_summary_hash.txt"))


def _persona_hash(text):
    import hashlib
    return hashlib.sha256(str(text or "").encode("utf-8")).hexdigest()


def persona_summary(botdir):
    """总模型读的那份摘要：**只读**，不生成、不联网（生成时机不在这里）。

    内置人格 → 写死的固定摘要；导入人格 → persona_summary.txt；
    都拿不到 → 原文前 300 字（宁可长一点，也不能让总模型瞎猜这个人是谁）。
    """
    name = _persona_name(botdir)
    if name in _PERSONA_SUMMARIES:
        return _PERSONA_SUMMARIES[name]
    spath, _hpath = _summary_paths(botdir)
    try:
        with open(spath, encoding="utf-8") as f:
            text = f.read().strip()
        if text:
            return text
    except OSError:
        pass
    return (_persona(botdir) or "")[:_SUMMARY_FALLBACK]


def _summary_call(conf, persona_text, appid=""):
    """调主模型生成摘要（同步）。单测替换这一个函数就不用联网。"""
    prompt = ("下面是一个 QQ 群角色的人格设定。请用 %d 字以内概括它：语气、说话习惯、"
              "擅长的话题、忌讳。只输出概括本身，不要标题，不要复述原文，不要分点列举。"
              "%s%s" % (_SUMMARY_MAX, chr(10) + chr(10), persona_text[:6000]))
    return _call_llm_sync(conf, [{"role": "user", "content": prompt}], 500, 0.3)


def persona_summary_sync(botdir, appid="", force=False):
    """人格摘要（同步、会联网）：内置人格直接返回写死的那份；导入人格比 hash 再决定。

    生成时机只有「导入人格 / 编辑人格保存」（调用方：start_one / restart_one 的后台预热）。
    hash 没变 → 直接用盘上那份，**不重生成**（省 token 也是这一条的验收点）。
    失败重试 3 次、退避 1s / 3s；仍失败 → 原文前 300 字兜底并记日志，不写 hash 以便下次补生成。
    """
    name = _persona_name(botdir)
    if name in _PERSONA_SUMMARIES:
        return _PERSONA_SUMMARIES[name]
    text = _persona(botdir)
    if not text:
        return ""
    spath, hpath = _summary_paths(botdir)
    want = _persona_hash(text)
    have, cached = "", ""
    try:
        with open(hpath, encoding="utf-8") as f:
            have = f.read().strip()
    except OSError:
        have = ""
    try:
        with open(spath, encoding="utf-8") as f:
            cached = f.read().strip()
    except OSError:
        cached = ""
    if cached and have == want and not force:
        return cached
    conf = _llm_conf(_base_dir) if _base_dir else None
    if conf is None:
        log("人格摘要跳过生成：模型配置没填（llm.json 缺 key / 地址）→ 先用原文兜底", appid)
        return cached or text[:_SUMMARY_FALLBACK]
    for i in range(3):
        out = ""
        try:
            out = str(_summary_call(conf, text, appid) or "").strip()
        except Exception as e:
            log("人格摘要生成失败（第 %d/3 次）：%r" % (i + 1, e), appid)
        if out:
            try:
                with open(spath, "w", encoding="utf-8") as f:
                    f.write(out)
                with open(hpath, "w", encoding="utf-8") as f:
                    f.write(want)
            except OSError as e:
                log("人格摘要写盘失败（忽略）：%r" % e, appid)
            log("人格摘要已生成：%d 字（人格 %d 字，hash %s）" % (len(out), len(text), want[:8]), appid)
            return out
        if i < 2:
            time.sleep(1.0 if i == 0 else 3.0)      # 指数退避 1s / 3s
    fb = cached or text[:_SUMMARY_FALLBACK]
    log("人格摘要 3 次都失败 → 先用原文前 %d 字兜底（不写 hash，下次有网再补生成）"
        % _SUMMARY_FALLBACK, appid)
    if not cached:
        try:
            with open(spath, "w", encoding="utf-8") as f:
                f.write(fb)
        except OSError:
            pass
    return fb


def _summary_warm(botdir, appid=""):
    """后台补生成（不阻塞启动 / 保存）。进程退出无所谓：没写完下次还会再来一次。"""
    def _run():
        try:
            persona_summary_sync(botdir, appid)
        except Exception as e:
            log("人格摘要后台生成出错（忽略）：%r" % e, appid)
    threading.Thread(target=_run, daemon=True).start()
    return "OK"


# ── 多 bot 群聊总模型（gatekeeper）────────────────────────────
#
# 为什么要有这一层：群里同时挂几个 bot 时，每个 bot 各自「自判要不要接话」，
# 同一条消息就被几个 bot 抢答 —— 老的四道闸只能限流，不能分工。
# 这一层只回答一个问题：**这一轮谁说**（或者说谁都不说），外加连续 bot 链的
# 句数上限、成对静默、群内互相可见。
#
# 线程模型：每个 bot 一条线程 + 一个独立事件循环（见文件头）。同一条群消息会被
# **每个** bot 各收到一次，所以「谁来说」这个决定必须跨 bot 共享；跨线程不能 await
# 别的 loop 的 Future，只靠 threading 原语：
#   · 先到的 bot 认领这条消息 → 跑一次总模型 → 结果放进共享记录
#   · 后到的 bot 轮询共享记录（asyncio.sleep，不阻塞任何 loop）→ 拿到同一个决定
#   · 只有被点名的那个 bot 发言，其余安静退出 → 一条消息只烧一次总模型的钱
#
# 全部只放内存：静默、句数、群上下文、最后一次发言都随进程重启清空（不落盘）。
# 落盘的只有两样：bot_ids.json（作者 openid → appid，只记 bot）与人格摘要。

_GATE_DEFAULTS = {
    "gate_enabled": True,
    "bot_max_turns": 6,
    "bot_silence_minutes": 10,
    "human_hourly_limit": 40,
    "reply_delay_min_ms": 3000,
    "reply_delay_max_ms": 7000,
}

_GATE_REC_TTL = 8.0          # 同一 (群, 正文) 的共享决定保留多久（秒）
_GATE_WAIT_MAX = 20.0        # 后到的 bot 最多等多久（总模型 30s 超时 + 余量）
_GATE_PROMPT_CTX = 8         # 拼给总模型的最近消息条数
_HUMAN_WINDOW = 3600.0       # 真人每小时软上限的窗口（秒）

_gate_rec = {}               # (正文哈希, 30 秒桶) -> {owner, done, ev, dec, ts, used}
_gate_state = {
    "ctx": {},        # (appid, gid) -> [{"who","text","bot","ts"}]
    "turns": {},      # gid -> {bot 名字: 句数}     连续 bot 链里各自说了几句
    "chain": {},      # gid -> [bot 名字, ...]      连续 bot 链（真人发言清空）
    "silence": {},    # (gid, 发言方, 目标) -> 截止 ts
    "human": {},      # gid -> [ts, ...]            真人消息时间戳
    "spoken": {},     # 会话键(正文哈希,30 秒桶) -> {"appid","name","text","ts"}  本轮最后一次 bot 发言
    "counted": {},    # (gid, 正文哈希) -> ts        这句话是否已经计过数（跨 bot 只计一次）
    "downgrade": {},  # gid -> (小时桶, 上次写日志的时间)
    "who_log": 6,     # 「发言人识别」日志还剩几条额度（前几条打印，避免刷屏）
}
_roster_cache = {"stamp": None, "rows": []}


def _int_or(v, default, lo=0, hi=10 ** 6):
    """宽松取整数：认不出 / 越界就回到默认值（配置是外部写的，不能信）。"""
    try:
        n = int(float(str(v).strip()))
    except (TypeError, ValueError):
        return default
    return max(lo, min(hi, n))


def _gate_conf(base_dir=""):
    """群聊总模型的全局开关（llm.json 的 6 个键，键名由 Kotlin 侧写）。

    每条消息现读、不缓存：启停 bot、改开关之后，下一条消息就要按新配置走。
    """
    c = _load_json(os.path.join(str(base_dir or _base_dir or ""), "llm.json"), {}) or {}
    out = dict(_GATE_DEFAULTS)
    v = c.get("gate_enabled")
    if isinstance(v, bool):
        out["gate_enabled"] = v
    elif isinstance(v, str) and v.strip():
        out["gate_enabled"] = v.strip().lower() not in ("0", "false", "no", "off")
    elif isinstance(v, (int, float)):
        out["gate_enabled"] = bool(v)
    out["bot_max_turns"] = _int_or(c.get("bot_max_turns"), 6, 1, 50)
    out["bot_silence_minutes"] = _int_or(c.get("bot_silence_minutes"), 10, 0, 1440)
    out["human_hourly_limit"] = _int_or(c.get("human_hourly_limit"), 40, 0, 10000)
    dmin = _int_or(c.get("reply_delay_min_ms"), 3000, 0, 600000)
    dmax = _int_or(c.get("reply_delay_max_ms"), 7000, 0, 600000)
    if dmax < dmin:
        dmin, dmax = dmax, dmin
    out["reply_delay_min_ms"], out["reply_delay_max_ms"] = dmin, dmax
    return out


def _localbots_path(base=""):
    """localbots.json 由 Kotlin 写在 filesDir，而 base_dir = filesDir/localbot —— 两边都看一眼。"""
    b = os.path.abspath(str(base or _base_dir or "."))
    for p in (os.path.join(os.path.dirname(b), "localbots.json"),
              os.path.join(b, "localbots.json")):
        if os.path.isfile(p):
            return p
    return ""


def _roster(base=""):
    """本机 bot 清单（**实时**：localbots.json 变了立刻生效，没变才吃 mtime 缓存）。

    → [{"appid","name","botdir","enabled","judge_level_override","dmin","dmax"}]
    · 名字只从 localbots.json 的 name 取（空则退回人格名、再退回 AppID 前 8 位）
    · 重名补 AppID 前 4 位 —— 总模型的输出是按名字点人的，重名等于点不清
    · localbots.json 不在（老版本 / 测试）→ 退回 start_all 拿到的那份 enabledJson
    """
    base = str(base or _base_dir or "")
    path = _localbots_path(base)
    stamp = _file_stamp(path) if path else None
    if stamp is not None:
        with _gate_lock:
            if _roster_cache["stamp"] == stamp:
                return [dict(r) for r in _roster_cache["rows"]]
    rows = _load_json(path, []) if path else []
    if not isinstance(rows, list) or not rows:
        rows = list(_state.get("bots_json") or [])
    out = []
    for e in rows:
        if not isinstance(e, dict):
            continue
        appid = str(e.get("appid") or "").strip()
        if not appid:
            continue
        botdir = _bot_dir(base, appid) if base else ""
        nm = str(e.get("name") or "").strip()
        if not nm or nm == appid:
            nm = (_persona_name(botdir) if botdir else "") or (appid[:8] or "未命名")
        out.append({
            "appid": appid,
            "name": nm,
            "botdir": botdir,
            "enabled": bool(e.get("enabled", True)),
            "judge_level_override": str(e.get("judge_level_override") or "").strip().lower(),
            "dmin": _int_or(e.get("reply_delay_min_ms"), 0, 0, 600000),
            "dmax": _int_or(e.get("reply_delay_max_ms"), 0, 0, 600000),
        })
    seen = {}
    for r in out:
        n = r["name"]
        if n in seen:
            r["name"] = "%s#%s" % (n, r["appid"][:4])
        seen[r["name"]] = r["appid"]
    with _gate_lock:
        if stamp is not None:
            _roster_cache["stamp"] = stamp
            _roster_cache["rows"] = [dict(r) for r in out]
    return out


def _enabled_roster(base=""):
    return [r for r in _roster(base) if r["enabled"]]


def _bot_running_or_unknown(appid):
    """这个 bot 当前有运行实例吗：_bots 里有记录就看 alive；没记录 = 「不知道」→ 按有算。

    start_all / start_one 会给每个启用的 bot 建一条记录（掉线 / 停止时 alive=False），
    所以「有记录 + 没在跑」才是真的没人接；host 侧直接调用 / 测试里根本没有 _bots 表，
    那种情况不能把候选全判成「没在跑」，否则整条链路会静默退化成「不走总模型」。
    """
    with _lock:
        info = _bots.get(str(appid or ""))
    if info is None:
        return True
    return bool(info.get("alive"))


def _gate_candidates(base="", gid="", me_appid="", gc=None):
    """总模型候选：enabled、**当前有运行实例**，且**这个群里自动接话是开的**。

    没在跑的 bot 点名等于白烧一次调用、还没人接（日志里点「惜月」那次）。
    自动接话关着的 bot 只能回 @，总模型点它同样是白烧一次 —— 真机就出现了
    「总模型：reply(大肥鱼…)」紧跟「跳过：总模型点了本 bot，但「群里自动接话」关闭」，
    谁都没回还烧了 token。所以候选阶段就按 _group_autochat_state 筛掉
    （群配置 > 该 bot 覆盖 > 全局；别的 bot 的群配置按 gid 查不到，只能用后两层）。
    gid 为空 = 没有群上下文（测试/老调用）：只按运行实例筛，不看自动接话 ——
    没有群就判不出「该群的自动接话」，硬筛会把候选全清空。
    「没有运行记录」不算没在跑，见 _bot_running_or_unknown。
    """
    gid = str(gid or "")
    out = []
    for r in _enabled_roster(base):
        if not _bot_running_or_unknown(r["appid"]):
            continue
        if gid:
            mine = (gc or {}) if str(r["appid"]) == str(me_appid or "") else {}
            if not _group_autochat_state(r["appid"], mine)[0]:
                continue
        out.append(r)
    return out


def _log_gate_candidates(appid, cand):
    """每次总模型调用前一行：谁在候选里、谁被排除（验收要的日志之一）。"""
    keep = set(r["appid"] for r in cand)
    marks = []
    for r in _enabled_roster(_base_dir):
        if r["appid"] in keep:
            marks.append("%s(运行中)" % r["name"])
        elif not _bot_running_or_unknown(r["appid"]):
            marks.append("%s(未运行，已排除)" % r["name"])
        else:
            marks.append("%s(自动接话关，已排除)" % r["name"])
    if marks:
        log("总模型候选：" + "、".join(marks), appid)


def _my_entry(appid, roster=None):
    for r in (roster if roster is not None else _roster()):
        if r["appid"] == str(appid or ""):
            return r
    return None


def _my_name(appid, roster=None):
    r = _my_entry(appid, roster)
    if r:
        return r["name"]
    botdir = _bot_dir(_base_dir, appid) if _base_dir else ""
    return ((_persona_name(botdir) if botdir else "") or str(appid or "")[:8] or "本 bot")


def _level_override(appid, roster=None):
    r = _my_entry(appid, roster)
    lv = str((r or {}).get("judge_level_override") or "")
    return lv if lv in _JUDGE_LEVELS else ""


def _delay_of(appid, gconf, entry=None):
    """这一轮的延迟区间（毫秒）。**三态**（Kotlin 侧约定）：

    0 / 0 → 跟随全局；1 / 1 → 本 bot 不延迟；其它 → 真实毫秒区间。
    0/0 不是「不延迟」—— 那是跟随全局，写成不延迟会错。
    """
    e = entry if entry is not None else _my_entry(appid)
    mn, mx = gconf["reply_delay_min_ms"], gconf["reply_delay_max_ms"]
    if e:
        emn, emx = int(e.get("dmin") or 0), int(e.get("dmax") or 0)
        if not (emn <= 0 and emx <= 0):          # 不是 0/0 → 本 bot 自己说了算
            mn, mx = emn, emx
    if mx < mn:
        mn, mx = mx, mn
    return max(0, mn), max(0, mx)


def _text_hash(text):
    import hashlib
    return hashlib.sha1(_norm_text(text).encode("utf-8")).hexdigest()[:16]


# ── 群内互相可见：上下文、bot_ids、发言人识别 ──────────────────

def _bot_ids_paths(botdir):
    """(本 bot 自己那份, 全进程共享那份)。

    openid 按 bot 分配，所以「我看到的是谁」天然是每个 bot 一份；共享那份只在
    base_dir 里，放各 bot 自己登记的那一条（自己 → 自己），不起推断别人的作用。
    """
    root = _bot_root(botdir)
    out = [os.path.join(root, "bot_ids.json")]
    parent = os.path.dirname(root)
    if parent and os.path.abspath(parent) != os.path.abspath(root):
        out.append(os.path.join(parent, "bot_ids.json"))
    return out


def _bot_ids(botdir):
    """作者 openid → appid。**只收 bot 的 id**，真人 openid 绝不进这张表。"""
    out = {}
    for path in _bot_ids_paths(botdir):
        d = _load_json(path, {})
        src = d.get("ids") if isinstance(d, dict) and isinstance(d.get("ids"), dict) else d
        if not isinstance(src, dict):
            continue
        for k, v in src.items():
            k, v = str(k or "").strip(), str(v or "").strip()
            if k and v and k not in out:
                out[k] = v
    return out


def _note_own_bot_id(botdir, ids, appid):
    """记「自己的 author id → 自己的 appid」。只有确认是自己的消息（回声）才调。

    这是 bot_ids.json 唯一的写入点：只写自己，不推断别人，更不写真人。
    """
    appid = str(appid or "").strip()
    vals = [str(x or "").strip() for x in (ids or [])]
    vals = [x for x in vals if x]
    if not appid or not vals:
        return 0
    added = 0
    # 自己那份 + 同进程共享那份都写：openid 若是全局的，共享那份就能让别的 bot 认出我；
    # 若不是全局的，读不到就按 [其他bot] 兜底 —— 绝不靠猜别人身份来补
    for path in _bot_ids_paths(botdir):
        old = _load_json(path, {})
        table = dict(old) if isinstance(old, dict) else {}
        if isinstance(table.get("ids"), dict):
            table = dict(table["ids"])
        diff = [oid for oid in vals if table.get(oid) != appid]
        if not diff:
            continue
        for oid in diff:
            table[oid] = appid
        _save_json(path, table)
        added += len(diff)
        log("学到自己的 author id：%s（来源：本 bot 内容的回声，已写入 %s）"
            % (",".join(diff)[:80], os.path.basename(path)), appid)
    return added


def _sender_of(botdir, message, roster=None):
    """这条消息是谁发的？→ (名字, appid, 作者 openid, 是否 bot)。

    规则（对方明确要求过，别改宽松）：
      1) openid 命中 bot_ids.json（只收 bot）→ 名字标签（localbots.json 的 name）
      2) author.bot=true（或 known_bots.json 认得）但不在表里 → "其他bot"
      3) 其余一律真人 → 名字留空，标签用 [作者 名字]
    绝不因为「发了消息」就把真人 openid 记进 bot_ids.json。
    """
    ids = _author_ids(message)
    oid = ids[0] if ids else ""
    flag = _attr(getattr(message, "author", None), "bot", "is_bot", "isBot")
    table = _bot_ids(botdir)
    hit = ""
    for i in ids:
        if i in table:
            hit = table[i]
            break
    if hit:
        name = ""
        for r in (roster if roster is not None else _roster()):
            if r["appid"] == hit:
                name = r["name"]
                break
        out = (name or "其他bot"), hit, oid, True
    elif flag is True or any(i in _known_bots(botdir) for i in ids):
        out = "其他bot", "", oid, True
    else:
        out = "", "", oid, False
    with _gate_lock:
        left = _gate_state["who_log"]
        if left > 0:
            _gate_state["who_log"] = left - 1
    if left > 0:
        log("发言人识别：author=%s → %s" % (
            _oid8(oid) or "(无)",
            ("bot(%s)" % out[0]) if out[3] else "真人"), "")
    return out


def _ctx_note(appid, gid, who, text, bot=False):
    """群上下文：真人 [作者 名字]、bot [名字]，每群只留最近 8 条给总模型。

    同一条消息会被同群的每个 bot 各收一次，这里按 (谁, 正文) 3 秒内去重一次，
    免得一句话在上下文里出现好几遍。
    """
    gid = str(gid or "")
    box = _gate_state["ctx"].setdefault((str(appid), gid), [])
    now = time.time()
    if box and box[-1]["who"] == who and box[-1]["text"] == text and now - box[-1]["ts"] <= 3.0:
        return
    box.append({"who": who, "text": text, "bot": bool(bot), "ts": now})
    del box[:-_GATE_PROMPT_CTX * 2]


def _ctx_rows(appid, gid, drop_last=False):
    rows = list((_gate_state["ctx"].get((str(appid), str(gid or ""))) or [])[-_GATE_PROMPT_CTX:])
    if drop_last and rows:
        rows = rows[:-1]
    return rows


def _ctx_text(rows):
    if not rows:
        return "（还没有上下文）"
    return chr(10).join("%s %s" % (r["who"], r["text"]) for r in rows)


def _ctx_system(appid, gid):
    """回复时拼进 system 的「群内最近消息」（带名字标签，谁说的写得明明白白）。"""
    rows = _ctx_rows(appid, gid, drop_last=True)
    if len(rows) < 1:
        return ""
    return ("【群内最近消息】（同群可见，越靠后越新；[名字] 是别的角色，[作者 名字] 是人）"
            + chr(10) + _ctx_text(rows))


# ── 句数计数 / 成对静默 / 真人每小时软上限 ──────────────────────

def _msg_counted(gid, text):
    """(群, 正文) 是否已经计过数 —— 同一条消息每个 bot 各收一次，只能算一句。"""
    key = (str(gid or ""), _text_hash(text))
    now = time.time()
    with _gate_lock:
        d = _gate_state["counted"]
        for k in [k for k, ts in d.items() if now - ts > 30.0]:
            d.pop(k, None)
        if key in d:
            return True
        d[key] = now
        return False


def _turn_add(gid, name):
    gid = str(gid or "")
    with _gate_lock:
        turns = _gate_state["turns"].setdefault(gid, {})
        turns[name] = turns.get(name, 0) + 1
        _gate_state["chain"].setdefault(gid, []).append(name)


def _turn_reset(gid):
    gid = str(gid or "")
    with _gate_lock:
        _gate_state["turns"].pop(gid, None)
        _gate_state["chain"][gid] = []


def _turn_of(gid, name):
    with _gate_lock:
        return (_gate_state["turns"].get(str(gid or "")) or {}).get(str(name or ""), 0)


def _chain_of(gid):
    with _gate_lock:
        return list(_gate_state["chain"].get(str(gid or "")) or [])


def _counterpart(gid, me):
    """成对静默的「目标」：链上最近一个不是我的 bot。"""
    for name in reversed(_chain_of(gid)):
        if name and name != me:
            return name
    return ""


def _silence_set(gid, speaker, target, minutes):
    if not speaker or not target or speaker == target or minutes <= 0:
        return 0.0
    until = time.time() + float(minutes) * 60.0
    with _gate_lock:
        _gate_state["silence"][(str(gid or ""), speaker, target)] = until
    return until


def _silenced(gid, speaker, target):
    """(发言方 → 目标) 是否还在静默期？到时间自动解除（读的时候顺手清）。"""
    if not speaker or not target or speaker == target:
        return False
    key = (str(gid or ""), speaker, target)
    now = time.time()
    with _gate_lock:
        until = _gate_state["silence"].get(key) or 0.0
        if until and until <= now:
            _gate_state["silence"].pop(key, None)
            return False
    return until > now


def _silence_list(gid):
    now = time.time()
    out = []
    with _gate_lock:
        for k, until in list(_gate_state["silence"].items()):
            if k[0] != str(gid or ""):
                continue
            if until <= now:
                _gate_state["silence"].pop(k, None)
                continue
            out.append("%s→%s（剩 %d 分钟）" % (k[1], k[2], int((until - now) / 60) + 1))
    return out


def _human_note(gid):
    """记一条真人消息，返回最近一小时的条数。"""
    gid = str(gid or "")
    now = time.time()
    with _gate_lock:
        box = [t for t in (_gate_state["human"].get(gid) or []) if now - t < _HUMAN_WINDOW]
        box.append(now)
        _gate_state["human"][gid] = box
        return len(box)


def _human_count(gid):
    now = time.time()
    gid = str(gid or "")
    with _gate_lock:
        box = [t for t in (_gate_state["human"].get(gid) or []) if now - t < _HUMAN_WINDOW]
        _gate_state["human"][gid] = box
        return len(box)


def _downgrade_note(gid, n, limit, appid=""):
    """超软上限写一条日志（10 分钟内只写一次，别每句都刷）。"""
    gid, now = str(gid or ""), time.time()
    bucket = int(now // 600)
    with _gate_lock:
        last = _gate_state["downgrade"].get(gid)
        if last == bucket:
            return False
        _gate_state["downgrade"][gid] = bucket
    log("真人本小时已 %d 条 > 软上限 %d → 本群判定降为 conservative（groups/%s，不硬拒）"
        % (n, limit, _oid8(gid)), appid)
    return True


def _gate_level(gid, gconf, base_level=""):
    """这一轮的判定积极性：per-bot 覆盖 > 全局；真人超软上限 → conservative。

    → (档位, 说明)。说明进日志，写清是哪一层判的、为什么降档。
    """
    lv = base_level if base_level in _JUDGE_LEVELS else ""
    if not lv:
        lv = _judge_level(_llm_conf(_base_dir) or {})
    limit = gconf["human_hourly_limit"]
    n = _human_count(gid)
    if limit > 0 and n > limit:
        lv = "conservative"
    return lv, n


def _speech_mark(skey, appid, name, text=""):
    """本轮最后一次 bot 发言成功（延迟期间的「抢先」判断用它）。

    skey = 本轮的会话键（_session_key，与 gid 无关）：两个 bot 的 group_openid 不同，
    按 gid 存就永不命中 —— 1.0.9 的群级互斥正是这么失效的。
    """
    with _gate_lock:
        _gate_state["spoken"][skey] = {
            "appid": str(appid or ""), "name": str(name or ""),
            "text": str(text or ""), "ts": time.time()}


_GROUP_MUTEX_SEC = 3.0      # 群级互斥：本群 3 秒内已经有本机别的 bot 发言成功 → 本轮放弃


def _group_busy(skey, appid="", since=None):
    """本轮「刚刚」有没有**别的** bot 发言成功？→ (名字, 秒) / ("", 0)。

    查的是会话键（与 gid 无关）下的最近一次发言记录，所以两个 bot 的 group_openid
    不同也能互相看见 —— 这是跨 bot 群级互斥唯一能成立的方式。

    窗口 = max(本轮开始生成, 现在-3 秒) → 只看**这一轮开始之后**别的 bot 的发言。
    所以「真人一句 → A 回 → 收到 A 的话再续一句」这种正常接龙不受影响（B 这一轮在 A
    发言之后才开头）；被拦住的是「两条不同消息几乎同时进来，两个 bot 各自被总模型点名」。
    """
    now = time.time()
    start = now if since is None else float(since)
    return _speech_after(skey, max(start, now - _GROUP_MUTEX_SEC), appid)


def _speech_after(skey, since, me_appid=""):
    """since 之后**本轮**（同一会话键）有没有别的 bot 发言成功？→ (名字, 秒)；没有 → ("", 0)。"""
    with _gate_lock:
        rec = _gate_state["spoken"].get(skey)
        if not rec:
            return "", 0.0
        rec = dict(rec)
    if rec["appid"] and rec["appid"] == str(me_appid or ""):
        return "", 0.0
    if rec["ts"] <= since:
        return "", 0.0
    return rec["name"], time.time() - rec["ts"]


# ── 总模型：提示词 / 解析 / 调用 / 共享决定 ─────────────────────

_GATE_LEAN = {
    "conservative": "保守：只有明确在问、明确在叫某个角色时才点人，其它一律 skip。",
    "normal": "偏积极：群里正聊着就点人接一句，别让群冷场；但同一条消息只点一个人。",
    "eager": "积极：几乎都点人接；只有刷屏、复读、纯指令或明显成对私聊时才 skip。",
}


def _gate_prompt(appid, gid, gconf, roster, level):
    """总模型的提示词：可用发言人（名字＋摘要）+ 最近 8 条带标签的消息 + 本轮状态。"""
    lines = [
        "你在一个 QQ 群里调度多个角色，任务是决定这一轮由谁说。",
        "硬要求：同一轮只能有一个角色回；宁可谁都不说，也不要两个人一起说。",
        "",
        "【可用发言人】",
    ]
    for r in roster:
        s = persona_summary(r["botdir"]) if r["botdir"] else ""
        s = (s or "（暂无摘要）").replace(chr(10), " ")[:240]
        lines.append("%s：%s" % (r["name"], s))
    lines += ["", "【群内最近消息】（越靠后越新；[名字] 是别的角色，[作者 名字] 是人）",
              _ctx_text(_ctx_rows(appid, gid))]
    chain = _chain_of(gid)
    last = chain[-1] if chain else ""
    turns = ", ".join("%s %d 句" % (n, _turn_of(gid, n)) for n in roster_names(roster, chain))
    lines += ["", "【本轮状态】连续 bot 轮次 %d / %d；各自句数：%s；上一条发言人：%s"
              % (len(chain), gconf["bot_max_turns"], turns or "无", last or "真人"),
              "静默中（这些关系不要点）：%s" % ("、".join(_silence_list(gid)) or "无"),
              "", "【怎么判】",
              "1) 正常说话：action=reply，bot 写【可用发言人】里的名字（一个）。",
              "2) 这一轮不需要角色接（刷屏、复读、别人成对对话、纯指令）：action=skip。",
              "3) 连续 bot 链已经绕够、或某个角色句数到上限：action=end_topic，bot 写那个角色，"
              "让它自己自然收尾。",
              "4) 静默中的关系不要点；真人和你说话时优先让最合适的角色接，句数上限优先。",
              "5) 倾向：" + _GATE_LEAN.get(level, _GATE_LEAN["normal"]),
              "", "只输出一行严格 JSON，不要代码块、不要解释：",
              '{"action":"reply","bot":"桑多涅","why":"一句话原因"}']
    return chr(10).join(lines)


def roster_names(roster, extra=()):
    """提示词里要列句数的名字：启用清单 + 已经说过话的（可能刚被停用）。"""
    out = []
    for r in roster:
        if r["name"] not in out:
            out.append(r["name"])
    for n in extra or ():
        if n and n not in out:
            out.append(n)
    return out


_GATE_ACTIONS = {
    "reply": "reply", "回": "reply", "回复": "reply", "回一句": "reply", "说": "reply",
    "skip": "skip", "跳过": "skip", "不说": "skip", "不回": "skip", "none": "skip",
    "end_topic": "end_topic", "endtopic": "end_topic", "end": "end_topic",
    "收尾": "end_topic", "结束": "end_topic", "结束话题": "end_topic",
}


def _first_json_object(s):
    """从一段混杂文字里抠出**第一个花括号配平**的 {...}；没有 → 空串。

    总模型有时把 JSON 夹在整段解释里，后面还跟着另一个花括号片段；
    「第一个 { 到最后一个 }」会把后一段一起切进来 → json.loads 失败（真机：
    「输出解析不出 JSON：确保没有多余。需要选择。我觉得 DeepS…」）。
    """
    start = s.find("{")
    while start >= 0:
        depth, q, esc = 0, "", False
        for i in range(start, len(s)):
            ch = s[i]
            if q:
                if esc:
                    esc = False
                elif ch == chr(92):
                    esc = True
                elif ch == q:
                    q = ""
                continue
            if ch == chr(34) or ch == chr(39):
                q = ch
            elif ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    return s[start:i + 1]
        start = s.find("{", start + 1)
    return ""


def _parse_gate(raw, names, known=None):
    """总模型输出 → {"action","bot","why"}；解析不出 → None（调用方退回自判）。

    names = 这一轮**能接话**的候选名；known = 启用中的全部名字（缺省同 names）。
    点名了一个「启用中但不在候选里」的角色（没在跑 / 该群自动接话关着）时不改判 skip：
    由调用链路记一条「点名 X，但 X 的自动接话关闭/未运行 → 本轮放弃」。

    容错：剥代码块、只取第一个 JSON 对象、中文引号、单引号、缺引号都尽量捞；
    action 认中英文；bot 不在启用清单里 → 当成 skip（点了个不存在的人等于没人接）。
    """
    s = str(raw or "").strip()
    if not s:
        return None
    s = _FENCE_RE.sub(" ", s).strip()
    i, j = s.find("{"), s.rfind("}")
    body = s[i:j + 1] if (i >= 0 and j > i) else s
    obj = None
    for cand in (body, body.replace("\u201c", '"').replace("\u201d", '"'),
                 re.sub(r"'([^']*)'", r'"\1"', body)):
        try:
            v = json.loads(cand)
        except Exception:
            continue
        if isinstance(v, dict):
            obj = v
            break
    if obj is None:
        # 宽松兜底：正文里夹着说明 / 后面还有别的花括号片段时，先抠第一个配平的 {...}
        first = _first_json_object(s)
        if first:
            for cand in (first, first.replace(chr(0x201c), chr(34)).replace(chr(0x201d), chr(34))):
                try:
                    v = json.loads(cand)
                except Exception:
                    continue
                if isinstance(v, dict):
                    obj = v
                    break
    if obj is None:
        act = re.search(r'"?action"?\s*[:：]\s*"?([A-Za-z_\u4e00-\u9fff]+)', body)
        if not act:
            # 最后一道兜底：推理系模型（deepseek-flash 这类）整段都在「想」，
            # JSON 可能被 max_tokens 截断。正文里只要出现 action 关键词 + 一个启用中的
            # 名字，就按它判 —— 比整轮退回自判更接近它原本的意思。
            low = body.lower()
            kw = ""
            for k, v in (("end_topic", "end_topic"), ("end topic", "end_topic"),
                         ("skip", "skip"), ("收尾", "end_topic"),
                         ("skip", "skip"), ("reply", "reply"), ("回", "reply")):
                if k in low:
                    kw = v
                    break
            if not kw:
                return None
            hit = ""
            for n in names:
                if n and n in body:
                    hit = n
                    break
            if kw == "skip" or not hit:
                return {"action": "skip", "bot": "", "why": "非标准输出，按关键词兜底为 skip"}
            return {"action": kw, "bot": hit, "why": "非标准输出，按关键词兜底"}
        bot = re.search(r'"?bot"?\s*[:：]\s*"?([^",，}\n]+)', body)
        why = re.search(r'"?why"?\s*[:：]\s*"?([^"\n}]*)"?', body)
        obj = {"action": act.group(1), "bot": bot.group(1) if bot else "",
               "why": why.group(1) if why else ""}
    act = str(obj.get("action") or "").strip().lower()
    act = _GATE_ACTIONS.get(act, "")
    if not act:
        return None
    why = str(obj.get("why") or "").strip().replace(chr(10), " ")[:60]
    bot = str(obj.get("bot") or "").strip().strip("\"'\u201c\u201d\u300c\u300d\u300e\u300f ")
    if act == "skip":
        return {"action": "skip", "bot": "", "why": why}
    if bot not in names and bot not in (known or names):
        return {"action": "skip", "bot": "",
                "why": ("点名不在启用清单：%s" % (bot or "(空)")) + (("｜" + why) if why else "")}
    return {"action": act, "bot": bot, "why": why}


async def _gate_model(conf, prompt):
    """总模型调用（judge 槽位）。单独抽出来：单测直接替换这一个函数。

    max_tokens 给 1500：推理系模型（deepseek-flash 这类）会先在正文里「想一下」，
    300 个 token 全被思考吃掉 → JSON 被截断 → 解析不出就白判一轮（真机实测）。
    提示词末尾再钉一句「只输出 JSON」，绝大多数轮次的实际输出只有几十个 token。
    """
    tail = (chr(10) + "现在只输出那一行 JSON，不要任何思考过程、不要解释：")
    return await asyncio.to_thread(
        _call_llm_sync, conf, [{"role": "user", "content": prompt + tail}], 1500, 0.2)


# ── bot 无关的「会话标识」（跨 bot 共享键）────────────────────
#
# group_openid 与私聊 openid 都是**按 bot 隔离**的：同一个 QQ 群，惜月看到的是
# 0FE14634…，大肥鱼看到的是 FC139BB1…（真机日志确认）。所以凡是拿 gid 当跨 bot 共享
# 键的地方，两个 bot 之间**永不命中** —— 1.0.9 的两处就是这样失效的：
#   ① 总模型「同一条消息只判一次」的共享记录             → 各判一次、各自点人 → 双人抢答
#   ② 群级互斥（本群 3 秒内已有别的 bot 发言 → 本轮放弃） → 永不命中 → 形同虚设
#
# 统一换成 bot 无关的键：sha1(触发本轮的那条消息正文)[:16] + 30 秒时间桶。
#   · 正文先经 _norm_text 归一（去 <@…> / 去空白 / 转小写）：mention 里带的是各 bot
#     自己的 openid，不归一就没有可比性；
#   · 只用「引起本轮回复的那条用户 / bot 消息正文」，不是本 bot 自己的输出；
#   · 桶 = int(time.time() // 30)：两个 bot 收到同一条消息只差毫秒级，落在同一个桶里；
#     万一跨了桶边界，_gate_claim 会再看上一个桶兜底（记录 TTL 只有 8 秒）。
#
# 已知取舍（用户已确认可接受）：不同群在同一个 30 秒桶里说了**完全相同**的正文，
# 会被当成同一轮 —— 总模型只判一次，其中一个群这一轮可能没有 bot 接话。
# 宁可少回一句，也不要两个 bot 同时抢答。
_SESSION_BUCKET_SEC = 30


def _session_key(text, now=None):
    """→ (正文哈希前 16 位, 30 秒桶号)：跨 bot 认同一轮用的键（与 gid 无关）。"""
    return (_text_hash(text),
            int((time.time() if now is None else now) // _SESSION_BUCKET_SEC))


def _session_log(skey):
    """日志里的会话键：哈希前 16 位 + 桶号（排查用）。"""
    return "%s#%d" % (skey[0], skey[1])


def _gate_claim(appid, gid, text):
    """→ (共享记录, 我是不是这次的总模型调用者)。

    共享键 = (正文哈希前 16 位, 30 秒桶)，**与 gid 无关**（gid 按 bot 隔离，见
    _session_key 的说明）：同一轮 8 秒内只跑一次总模型，先到的认领，后到的复用他的决定。
    记录里记下用过的 appid —— 同一个 bot 不会因为「同文再发一次」被吃掉。
    桶边界兜底：本桶没有记录时再看上一个桶（记录 TTL 只有 8 秒，跨桶复用最多 8 秒，
    不会把「30 秒前的同一句话」也当成这一轮）。
    """
    now = time.time()
    h16, bucket = _session_key(text, now)
    key = (h16, bucket)
    mine, rec, hit = True, None, key
    with _gate_lock:
        for k in [k for k, r in _gate_rec.items() if now - r["ts"] > _GATE_REC_TTL]:
            _gate_rec.pop(k, None)
        for k in (key, (h16, bucket - 1)):
            r = _gate_rec.get(k)
            if r is not None and str(appid) not in r["used"]:
                r["used"].add(str(appid))
                mine, rec, hit = False, r, k
                break
        if rec is None:
            rec = {"owner": str(appid), "done": False, "ev": threading.Event(),
                   "dec": None, "ts": now, "used": {str(appid)}}
            _gate_rec[key] = rec
    log("总模型共享键：%s%s（群里=%s）"
        % (_session_log(hit), "" if mine else " 复用先到者的决定", _oid8(gid) or "?"), appid)
    return rec, mine


async def _gate_wait(rec):
    """等先到的那个 bot 把决定写进共享记录（轮询，不阻塞任何 loop）。"""
    t0 = time.time()
    while not rec["done"] and time.time() - t0 < _GATE_WAIT_MAX:
        await asyncio.sleep(0.05)
    return rec["dec"] if rec["done"] else None


def _gate_log(dec, appid=""):
    """总模型的日志格式（验收要贴的四行之一）：reply(名字｜原因) / skip(原因) / end_topic(...)。"""
    act = dec["action"]
    if act == "skip":
        log("总模型：skip(%s)" % (dec.get("why") or "无原因"), appid)
    else:
        tag = "end_topic" if act == "end_topic" else "reply"
        log("总模型：%s(%s｜%s)" % (tag, dec.get("bot") or "?", dec.get("why") or "无原因"), appid)
    return dec


async def _gate_run(appid, gid, gconf, roster, level):
    """真正跑一次总模型，并把「代码这层的硬约束」再兜一遍。

    模型不是可靠的守卫：静默关系、句数上限、点名是否在清单里，这三样在代码里再判一次，
    判完才把决定写进共享记录（后到的 bot 拿到的就是已经校验过的决定）。
    """
    names = [r["name"] for r in roster]
    chain = _chain_of(gid)
    last = chain[-1] if chain else ""
    cap = gconf["bot_max_turns"]
    conf = _conf_for("judge")
    if conf is None:
        log("总模型跳过：判定槽位没有模型配置（llm.json 缺 key / 地址）→ 退回自判", appid)
        return {"action": "fallback", "bot": "", "why": "没有模型配置"}
    _log_gate_candidates(appid, roster)
    prompt = _gate_prompt(appid, gid, gconf, roster, level)
    try:
        raw = await _gate_model(conf, prompt)
    except Exception as e:
        log("总模型失败，退回自判（%r）" % e, appid)
        return {"action": "fallback", "bot": "", "why": str(e)[:60]}
    all_names = [r["name"] for r in _enabled_roster(_base_dir)] or list(names)
    dec = _parse_gate(raw, names, all_names)
    if dec is None:
        log("总模型失败，退回自判（输出解析不出 JSON：%s）"
            % (str(raw or "").strip().replace(chr(10), " ")[:80] or "(空)"), appid)
        return {"action": "fallback", "bot": "", "why": "解析失败"}
    # ① 静默关系：模型点了正在静默的那一对 → 当成 skip（成对静默是硬约束）
    if dec["action"] != "skip" and last and _silenced(gid, dec["bot"], last):
        return _gate_log({"action": "skip", "bot": "",
                          "why": "%s→%s 成对静默中" % (dec["bot"], last)}, appid)
    # ② 句数上限：被点的角色已经说满 → 转成让它收尾
    if dec["action"] != "skip" and _turn_of(gid, dec["bot"]) >= cap:
        return _gate_log({"action": "end_topic", "bot": dec["bot"],
                          "why": "已达 %d 句上限，转收尾" % cap}, appid)
    # ③ 整条链绕太长：上一条发言的角色已经到上限 → 这一轮由它收尾，不许再续
    if last and dec["action"] == "reply" and _turn_of(gid, last) >= cap:
        dec = {"action": "end_topic", "bot": last,
               "why": "连续 bot 轮次 %d/%d，已达上限" % (len(chain), cap)}
    # ④ 上面三处改写可能点到「其他bot」这种不在候选清单里的名字（未启用 / 没在跑）→ 没人能接，直接收场
    if (dec["action"] != "skip" and dec["bot"] not in names
            and dec["bot"] not in all_names):
        return _gate_log({"action": "skip", "bot": "",
                          "why": "点名的是未启用 / 未在运行的角色（%s）"
                          % (dec["bot"] or "(空)")}, appid)
    return _gate_log(dec, appid)


async def _gate_decide(appid, gid, text, gconf, roster, level):
    """一条消息一次决定（跨 bot 共享）。→ {"action": reply|skip|end_topic|fallback, ...}"""
    rec, mine = _gate_claim(appid, gid, text)
    if not mine:
        dec = await _gate_wait(rec)
        if dec is None:
            log("总模型：等其它 bot 的判定超时 → 退回自判", appid)
            return {"action": "fallback", "bot": "", "why": "等共享判定超时"}
        return dec
    dec = await _gate_run(appid, gid, gconf, roster, level)
    rec["dec"] = dec
    rec["done"] = True
    rec["ev"].set()
    return dec


async def _reply_delay(appid, skey, gconf, entry=None):
    """被选中之后再等一会（per-bot 覆盖全局）。→ False = 期间别的 bot 抢先，放弃这次。

    只有「gate_enabled 且本群 3 秒（延迟）内已有别的 bot 发言成功」才放弃；
    没配模型（llm.json 缺 key/地址）时不延迟 —— 那种情况回的是连通测试文案，等 7 秒没意义。
    """
    if not (_llm_conf(_base_dir) if _base_dir else None):
        return True
    mn, mx = _delay_of(appid, gconf, entry)
    if mx <= 0:
        return True
    t0 = time.time()
    await asyncio.sleep(random.randint(mn, mx) / 1000.0)
    if gconf["gate_enabled"]:
        name, ago = _speech_after(skey, t0, appid)
        if name:
            log("延迟期间已被其它 bot 抢先，放弃（%s 在 %.1f 秒前发过）" % (name, ago), appid)
            return False
    return True


def _closing_hint(n):
    """收尾时注入的 system 提示：让它自己把话收住，而不是生硬宣布结束。

    这里**不写任何会被照抄的关键字**：旧版把「结束」「话题终止」写进提示，
    模型就把这两个词（以及标记本身）一起抄进了群聊。只描述动作，不给词。
    """
    return ("你在这一轮已经连着说了 %d 句，这一条就把话自然收住：用你自己的语气说完最后半句，"
            "像聊天里随口应一声那样停下，别让人看出你在刻意收场。" % max(1, int(n)))


# ── 被动回复的 msg_seq（真机：ServerError('消息被去重，请检查请求msgseq')）──────
#
# botpy 的 post_group_message 默认 msg_seq=1，我们以前一直吃这个默认值。QQ 的规则
# （botpy api.py 里那句注释写得很明白）：**同一个 msg_id + 同一个 msg_seq 只允许发一次**。
# 所以同一条群消息被回第二次（重复投递 / 两条链路同时落在同一条回复上）时，第二次必然
# 被服务端判「消息被去重」，回复直接丢掉 —— 真机日志里「回复（模型…）→ 回群失败」就是这样。
# 这里给每个 (bot, msg_id) 记一个单调递增的序号：第一次 1、第二次 2……与 QQ 的
# 「同一 msg_id 要多发就递增 msg_seq」一致；万一平台仍判重复，_post_group 再换一个新
# 序号重试一次（真机上没见过能拒两次的）。
_send_seq = {}                  # (appid, msg_id) -> 已用过的最大 msg_seq
_send_seq_lock = threading.Lock()
_SEND_SEQ_KEEP = 256


def _next_msg_seq(appid, mid):
    """同一个 msg_id 的下一个被动回复序号（跨协程/线程唯一）。"""
    key = (str(appid or ""), str(mid or ""))
    with _send_seq_lock:
        n = int(_send_seq.get(key, 0)) + 1
        if len(_send_seq) > _SEND_SEQ_KEEP:
            for k in list(_send_seq)[: _SEND_SEQ_KEEP // 2]:
                _send_seq.pop(k, None)
        _send_seq[key] = n
        return n


def _is_msgseq_dup(err):
    """这个异常是不是「msg_id + msg_seq 重复」？"""
    s = str(err or "")
    return ("msgseq" in s.lower()) or ("被去重" in s)


async def _post_group(api, appid, gid, mid, content="", markdown=None):
    """发一条群消息：msg_seq 由 _next_msg_seq 分配，冲突就换序号重试一次。

    → True 发成功 / False 放弃（已经记过日志）。非 msgseq 的异常原样抛出，
    由 _group_send 的 except 统一记「回群失败」。
    """
    seq = _next_msg_seq(appid, mid)
    kw = {"group_openid": gid, "msg_id": mid, "msg_seq": seq, "msg_type": 0}
    if markdown is not None:
        kw["msg_type"] = 2
        kw["markdown"] = markdown
        body_len = len(str(markdown.get("content") or ""))
    else:
        kw["content"] = content
        body_len = len(str(content or ""))
    try:
        await api.post_group_message(**kw)
    except Exception as e:
        if not _is_msgseq_dup(e):
            raise
        new = _next_msg_seq(appid, mid)
        log("发送重试：msgseq 冲突，换 seq 重试（%d → %d｜msg_id=%s）"
            % (seq, new, (str(mid or "") or "无")[-8:]), appid)
        kw["msg_seq"] = new
        try:
            await api.post_group_message(**kw)
        except Exception as e2:
            log("发送失败（msgseq 冲突重试后仍失败）：%r" % e2, appid)
            return False
        seq = new
    log("发送：msg_id=%s｜seq=%d｜%d 字" % ((str(mid or "") or "无")[-8:], seq, body_len), appid)
    return True


async def _group_send(appid, botdir, message, gid, who, model_in, images=None,
                      extra_sys="", closing=None):
    """群回复的唯一出口：延迟 → 抢先检查 → 生成 → 群级互斥 → 发出去 → 记「刚说过」。

    closing = {"bot","target","minutes"} 时是 end_topic 的收尾：发出去之后给这对
    关系上静默、把该 bot 的句数清零。→ 发出去的纯文本；放弃 / 失败 → ""。
    """
    gconf = _gate_conf(_base_dir)
    # 本轮的会话键：从**触发这一轮的那条消息正文**算（不是本 bot 自己的输出）。
    # 两个 bot 收到同一条消息算出来的是同一个键 —— 群级互斥因此才跨 bot 成立。
    skey = _session_key(getattr(message, "content", "") or "")
    if not await _reply_delay(appid, skey, gconf, _my_entry(appid)):
        return ""
    t0 = time.time()          # 本轮开始生成：群级互斥只看这之后别的 bot 的发言
    try:
        tok = _member_openid_ctx.set(_member_openid_of(message))
        try:
            reply = await _reply(appid, botdir, "g" + gid, who, model_in, images=images,
                                 extra_sys=extra_sys)
        finally:
            _member_openid_ctx.reset(tok)
        if not reply:
            return ""          # 出站清洗判空：这条不发（_reply 里已记「丢弃」日志）
        plain, at_ids = _resolve_at_tags(reply, _mentions_of(message))
        # 群级互斥：总模型的决定是按「同一条消息」共享的（_gate_decide 的 8 秒窗口 key 带
        # 正文哈希），两条不同消息同时进来会各自被判 reply → 两个 bot 同时说话。
        # 所以在发送前再卡一道：本群这一轮已经有别的 bot 发出去了 → 本轮放弃。
        busy_name, busy_ago = _group_busy(skey, appid, t0)
        if busy_name:
            log("群级互斥：本群 3 秒内已有 %s 发言，放弃（%.1f 秒前｜键=%s）"
                % (busy_name, busy_ago, _session_log(skey)), appid)
            return ""
        _note_sent(appid, gid, plain)
        mid = _unique_msg_id(message)[1]
        if at_ids:
            log("@ 发出：%d 个（群聊 markdown 消息）" % len(at_ids), appid)
            try:
                ok = await _post_group(message._api, appid, gid, mid,
                                       markdown={"content": _at_markdown(plain, at_ids)})
            except Exception as e_at:
                log("@ 消息发送失败（%r）→ 退回纯文本" % e_at, appid)
                ok = await _post_group(message._api, appid, gid, mid, content=plain)
        else:
            ok = await _post_group(message._api, appid, gid, mid, content=plain)
        if not ok:
            return ""          # 发不出去就不算说过：不记「刚说过」，本轮到此为止
    except Exception as e:
        log("回群失败：%r" % e, appid)
        return ""
    _speech_mark(skey, appid, _my_name(appid), plain)
    if closing:
        me = str(closing.get("bot") or "")
        target = str(closing.get("target") or "")
        if me:
            _turn_reset_pair(gid, me)
        until = _silence_set(gid, me, target, int(closing.get("minutes") or 0))
        if until:
            log("静默生效：%s→%s %d 分钟（到点自动解除，只放内存）"
                % (me, target, int(closing.get("minutes") or 0)), appid)
        else:
            log("收尾完成：%s 本链句数清零（没有成对目标，不上静默）" % (me or "?"), appid)
    return plain


def _turn_reset_pair(gid, name):
    """收尾之后把该 bot 在这一链里的句数清零（下一次从 0 开始数）。"""
    gid = str(gid or "")
    with _gate_lock:
        turns = _gate_state["turns"].get(gid) or {}
        if name in turns:
            turns[name] = 0
        chain = _gate_state["chain"].get(gid) or []
        _gate_state["chain"][gid] = [x for x in chain if x != name]


def _at_others(appid, botdir, message):
    """本条 @ 的是不是别人（不是我、也不是作者本人）？

    mentions 为空但正文里有 <@hex> 一样算「别人」：fail-closed，宁可这条不回，
    也不让总模型把别人的 @ 转给群里随便一个 bot。
    """
    if not _mentions_of(message) and _MENTION_RE.search(str(getattr(message, "content", "") or "")):
        return True
    own = set(str(c).upper() for c in _own_mention_ids(appid, botdir, _mentions_of(message)))
    author = set(str(i).upper() for i in _author_ids(message))
    for e in _mentions_of(message):
        mid = _mention_id(e).upper()
        if mid and mid not in own and mid not in author:
            return True
    return False


async def _gated_turn(appid, botdir, message, gid, from_name, text, gconf, roster, my_name):
    """上一条是别的 bot 说的：让总模型决定这一轮是续一句 / 收尾 / 都不说。

    bot 的消息老逻辑一律不接（防互刷），只有总模型生效时才可能续 —— 而总量被
    句数上限 + 成对静默 + 3 秒互斥三样卡着，不会无限刷。
    """
    level, _n = _gate_level(gid, gconf, _level_override(appid, roster))
    cand = _gate_candidates(_base_dir, gid, appid, _group_conf(botdir, gid))
    cand_names = set(r["name"] for r in cand)
    dec = await _gate_decide(appid, gid, text, gconf, cand, level)
    act = dec["action"]
    if act == "fallback":
        name, _ago = _speech_after(_session_key(text), time.time() - 3.0, appid)
        log("总模型失败，退回自判：bot 的话自判不接%s"
            % ("；且本群 3 秒内已有 %s 发言 → 本轮放弃" % name if name else " → 本轮放弃"), appid)
        return
    if dec["bot"] != my_name:
        picked = str(dec.get("bot") or "")
        if act != "skip" and picked and picked not in cand_names:
            # 同一条规则：点名的 bot 不在候选里（没在跑 / 该群自动接话关着）→ 本轮没人接
            log("总模型：点名 %s，但 %s 的自动接话关闭/未运行 → 本轮放弃"
                % (picked, picked), appid)
            return
        if act == "end_topic" and dec.get("bot"):
            log("总模型：end_topic 点名 %s 收尾，本 bot 不动" % dec["bot"], appid)
        return
    if _silenced(gid, my_name, from_name):
        log("跳过：%s→%s 成对静默中，本轮不接" % (my_name, from_name), appid)
        return
    ok, why_rate = _gate_rate(appid, gid)
    if not ok:
        log("%s（群=%s｜作者=%s）" % (why_rate, _oid8(gid), from_name), appid)
        return
    extra, closing = "", None
    if act == "end_topic":
        n = _turn_of(gid, my_name)
        extra = _closing_hint(n)
        closing = {"bot": my_name, "target": (_counterpart(gid, my_name) or from_name),
                   "minutes": gconf["bot_silence_minutes"]}
    ctx_block = _ctx_system(appid, gid)
    await _group_send(appid, botdir, message, gid, from_name,
                      "%s：%s" % (from_name, _strip_mentions(text)), _attachments_of(message),
                      extra_sys=((ctx_block + chr(10) + chr(10)) if ctx_block else "") + extra,
                      closing=closing)



# ── 统一处理链路 ──────────────────────────────────────────


async def _handle_group(appid, botdir, message, tag):
    """群消息的唯一处理入口：@ 事件和普通群消息都走这里。

    tag="群@" 是平台的可信 @ 事件（只在本 bot 被 @ 时推），@ 判定直接放行，
    但作者过滤 / 回环 / 限流一样要过 —— 它是复读的另一个入口。

    1.0.8：启用中的 bot ≥2 且 gate_enabled=true 时，先由「总模型」决定这一轮谁说
    （一条消息只点一个人）；总模型没生效 / 失败才退回各 bot 自判（1.0.7 的老逻辑）。
    """
    if _dedupe_message(appid, message, "群"):
        return
    trusted_at = (tag == "群@")
    mid = _unique_msg_id(message)[1]
    text = (getattr(message, "content", "") or "").strip()
    # 同一个消息 id 只处理一次；但键里带上正文哈希 —— id 万一被复用，不同内容不会被吃掉
    if not _mark_seen(("%s|%s" % (mid, _hash_text(text)[:12])) if mid else ""):
        log("%s：重复 id=%s，跳过（同一事件同时推了 @ / 普通两种）" % (tag, mid[:12]), appid)
        return
    if not trusted_at:
        _dump_gateway_raw(botdir, "group_message_create", getattr(message, "raw", None) or {})
    gid = str(getattr(message, "group_openid", "") or "")
    brief = "%s｜id=%s｜群=%s｜内容=%s" % (tag, mid[:12], gid[:8], text[:40])
    # 总模型的开关与名单每条消息现读：启停 bot / 改开关之后，下一条消息就按新配置走
    gconf = _gate_conf(_base_dir)
    roster = _enabled_roster(_base_dir)
    # 总模型候选 = enabled **且**当前有运行实例：点名一个没在跑的 bot 等于白花钱且没人接。
    # 运行中不足两个 → 按现有规则不走总模型（各 bot 各判各的）。
    # 群配置先读：总模型候选要按「该 bot 在这个群里的自动接话」筛（见 _gate_candidates）
    gc = _group_conf(botdir, gid)
    cand = _gate_candidates(_base_dir, gid, appid, gc)
    gate_on = bool(gconf["gate_enabled"] and len(cand) >= 2)
    cand_names = set(r["name"] for r in cand)
    if gconf["gate_enabled"] and len(roster) >= 2 and len(cand) < 2:
        if not cand:
            log("总模型跳过：候选 bot 的群里自动接话都关着（只回 @）｜%s" % brief, appid)
        else:
            log("总模型不走：运行中的 bot 只有 %d 个（启用 %d 个）→ 退回各判各的"
                % (len(cand), len(roster)), appid)
    my_name = _my_name(appid)
    from_name, _from_appid, author_oid, from_bot = _sender_of(botdir, message, roster)
    body = _strip_mentions(text)

    # ── 群内互相可见：先把这一条记进群上下文（真人 [作者 名字]，bot [名字]）
    _ctx_note(appid, gid,
              ("[%s]" % from_name) if from_bot else ("[作者 %s]" % _author_label(botdir, author_oid)),
              body, bot=from_bot)
    echo = _is_echo(appid, gid, text)
    if echo:
        # 自己刚说的话被推回来：这是「自己的 author id」唯一可靠的来源。
        # 三重门：① 平台明确说是 bot（author.bot=true）；② 这个 id 不在已知其它 bot 里；
        # ③ 只写自己的 appid。真人 openid 永远不会走到这里。
        flag = _attr(getattr(message, "author", None), "bot", "is_bot", "isBot")
        if flag is True:
            known = _known_bots(botdir)
            own = [i for i in _author_ids(message) if i and i not in known]
            if own:
                _note_own_bot_id(botdir, own, appid)

    # 闸 5 · 群级配置（<botdir>/groups/<gid>.json）：整群静音直接掐掉，后面几道闸都不用走
    # （gc 在上面就算好了：总模型候选要按它判「该群的自动接话」）
    if not gc["enabled"]:
        log("跳过：该群已静音（groups/%s.json enabled=false）｜%s" % (_oid8(gid), brief), appid)
        return

    # 句数：真人发言把所有 bot 计数清零；bot 发言只给自己 +1
    # （同一条消息每个 bot 各收一次，_msg_counted 保证只算一句）
    if from_bot:
        # 认不出名字的 bot 也计数：全部记进「其他bot」这一个桶（保守，宁可早收尾也不无限刷）
        if from_name and not _msg_counted(gid, body):
            _turn_add(gid, from_name)
    else:
        _turn_reset(gid)
        n_human = _human_note(gid)
        limit = gconf["human_hourly_limit"]
        if limit > 0 and n_human > limit:
            _downgrade_note(gid, n_human, limit, appid)

    # 闸 0 · 只有可信 @ 事件允许从正文猜 id；mentions.is_you=true 是平台判定，两种事件都学
    if trusted_at:
        _learn_own_ids(botdir, text)
    _learn_from_mentions(botdir, _mentions_of(message))

    # ── bot 作者：老逻辑一律不接；总模型生效时才可能续一句（句数上限 + 成对静默管着）
    why = _bot_author(botdir, message)
    if why:
        if not gate_on:
            log("%s（%s）" % (why, brief), appid)
            return
        if echo:
            log("跳过：疑似复读本 bot 内容（%s）" % brief, appid)
            return
        if not from_bot:
            log("%s｜作者是 bot 但认不出是谁（%s）→ 不接" % (why, brief), appid)
            return
        # 认不出是谁的 bot 也允许续：它只是个「[其他bot] 说了话」的上下文，回不回归总模型判
        await _gated_turn(appid, botdir, message, gid, from_name or "其他bot", body,
                         gconf, roster, my_name)
        return

    # 闸 1 · 回环：命中就说明这条是我们自己刚说的话被推回来，顺手记下自己的 author id
    if echo:
        _note_own_author(botdir, _author_ids(message))
        log("跳过：疑似复读本 bot 内容（%s）" % brief, appid)
        return

    # 闸 3 · @ 判定（fail-closed）+ 总模型
    level = _gate_level(gid, gconf, _level_override(appid, roster))[0]
    if trusted_at:
        at_state, why_at = "yes", "平台 @ 事件"
    else:
        at_state, why_at = _at_me(appid, botdir, message)
    extra_sys = _ctx_system(appid, gid)
    closing = None
    if gate_on:
        # ── 多 bot：总模型决定这一轮谁说
        if at_state == "unknown":
            log("跳过：判不出是否 @ 本 bot（%s｜%s）" % (why_at, brief), appid)
            return
        if at_state == "yes":
            # 真人 @ 了本 bot：总模型不参与，直接由本 bot 回（对方要求的行为）
            log("总模型跳过：真人 @ 了本 bot（直接回）｜%s" % brief, appid)
        elif _at_others(appid, botdir, message):
            if not _mentions_of(message) and _MENTION_RE.search(text):
                log("总模型跳过：正文 @ 了别人（mentions 为空）｜%s" % brief, appid)
            else:
                log("总模型跳过：本条 @ 的是别人（由被 @ 的 bot 回）｜%s" % brief, appid)
            return
        else:
            dec = await _gate_decide(appid, gid, text, gconf, cand, level)
            act = dec["action"]
            if act == "fallback":
                # 总模型失败：退回各 bot 自判，但互斥仍生效（本群 3 秒内已有别的 bot 发言 → 放弃）
                name, _ago = _speech_after(_session_key(text), time.time() - 3.0, appid)
                if name:
                    log("总模型失败，退回自判；但本群 3 秒内已有 %s 发言 → 本轮放弃"
                        % name, appid)
                    return
                conf_j = _llm_conf(_base_dir) if _base_dir else None
                named = _name_hit(botdir, text) if (
                    conf_j is None or conf_j.get("name_hit_as_call", True)) else ""
                auto_on, auto_src = _group_autochat_state(appid, gc)
                if named:
                    log("名字命中（%s）→ 直回（总模型失败，退回自判）｜%s" % (named, brief), appid)
                elif not auto_on:
                    log("总模型失败，退回自判：既没被叫、群里自动接话也关着（判定来源：%s）"
                        "→ 本轮放弃（%s）" % (auto_src, brief), appid)
                    return
                elif not await _should_reply(conf_j, text, level):
                    log("总模型失败，退回自判：自判不接这句（%s）" % brief, appid)
                    return
                else:
                    log("总模型失败，退回自判：自判接这句（%s）" % brief, appid)
            elif act == "skip" or dec["bot"] != my_name:
                picked = str(dec.get("bot") or "")
                if act != "skip" and picked and picked not in cand_names:
                    # 点名的 bot 不在候选里（没在跑 / 该群自动接话关着）：没人会接这一轮
                    log("总模型：点名 %s，但 %s 的自动接话关闭/未运行 → 本轮放弃（%s）"
                        % (picked, picked, brief), appid)
                    return
                if act != "skip":
                    log("总模型：%s 点名 %s，本 bot 不动（%s）"
                        % (act, picked or "?", brief), appid)
                return
            else:
                # 总模型点了本 bot：还要过「群里自动接话」这道用户开关（老逻辑同样要过），
                # 名字被叫到（name_hit）照旧算例外 —— 不能让开总模型把「只回 @」冲掉
                auto_on, auto_src = _group_autochat_state(appid, gc)
                named = _name_hit(botdir, text) if (
                    (_llm_conf(_base_dir) or {}).get("name_hit_as_call", True)) else ""
                if not named and not auto_on:
                    log("跳过：总模型点了本 bot，但「群里自动接话」关闭（判定来源：%s）｜%s"
                        % (auto_src, brief), appid)
                    return
            if act == "end_topic":
                # 到上限：注入「用你的语气自然收尾」，发完给这对关系上静默、句数清零
                n = _turn_of(gid, my_name)
                closing = {"bot": my_name, "target": _counterpart(gid, my_name),
                           "minutes": gconf["bot_silence_minutes"]}
                extra_sys = ((extra_sys + chr(10) + chr(10)) if extra_sys else "") + _closing_hint(n)
    else:
        # ── 老逻辑（各 bot 自判）：与 1.0.7 一致，只有档位会因真人超软上限降到 conservative
        if not trusted_at:
            if at_state == "unknown":
                log("跳过：判不出是否 @ 本 bot（%s｜%s）" % (why_at, brief), appid)
                return
            if at_state == "no":
                # 没 @ 我但 @ 了别人（含「mentions 为空、正文却有 <@hex>」）：fail-closed，
                # 连名字命中和自动接话都不算 —— 别人头上的 @ 绝不抢
                if _at_others(appid, botdir, message):
                    if not _mentions_of(message) and _MENTION_RE.search(text):
                        log("跳过：正文 @ 了别人（mentions 为空），本条不回｜%s" % brief, appid)
                    else:
                        log("跳过：本条 @ 的是别人（由被 @ 的 bot 回）｜%s" % brief, appid)
                    return
                conf_j = _llm_conf(_base_dir) if _base_dir else None
                # 名字命中前置（name_hit_as_call，默认开）：正文里出现人格名或称呼 = 被叫
                called = ""
                if conf_j is None or conf_j.get("name_hit_as_call", True):
                    called = _name_hit(botdir, text)
                if called:
                    log("名字命中（%s）→ 直回（%s）" % (called, brief), appid)
                else:
                    # 三层判定：群配置显式值 > 该 bot 覆盖 > 全局（日志写清是哪一层判的）
                    auto_on, auto_src = _group_autochat_state(appid, gc)
                    if not auto_on:
                        log("跳过：未 @ 本 bot（%s），且「群里自动接话」关闭（判定来源：%s）"
                            "→ 默认只回 @（%s）" % (why_at, auto_src, brief), appid)
                        return
                    log("自动接话开（判定来源：%s）｜%s" % (auto_src, brief), appid)
                    if not await _should_reply(conf_j, text, level):
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

    # 闸 6 · 群级每日额度（groups/<gid>.json daily_limit 三态）：超了静默
    #   null / 缺项 = 跟随全局（llm.json 的 daily_limit）；0 = 本群不限；>0 = 本群额度
    raw_limit = gc.get("daily_limit")
    if raw_limit is None:
        limit = _global_daily_limit(botdir)
        limit_src = "跟随全局"
    else:
        limit = max(0, int(raw_limit))
        limit_src = "本群"
    if limit > 0:
        ok_quota, used = _group_quota_take(botdir, gid, limit)
        if not ok_quota:
            log("跳过：该群今日已回 %d 条，达到上限 %d 条（来源：%s）→ 今日不再回复｜%s"
                % (used, limit, limit_src, brief), appid)
            return

    log("%s：%s" % (tag, text[:60]), appid)
    # 存档：过了作者过滤与复读闸的消息一律入库 —— 不为了这条要回复，只为以后能被回忆
    _arc_put(botdir, "group", gid, (author_oid or ""), body)
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
    # @ 语义：<@hex> 换成「你 / 作者本人 / 用户xxxxxxxx」，再在消息前加一行元信息。
    at_body, at_meta, at_line = _at_semantics(appid, botdir, message)
    log(at_line, appid)
    model_in = (at_meta + chr(10) + at_body) if at_body else at_meta
    # 本地指令先判：过了四道闸就算「该回这条」，回时间不必打扰模型
    if await _local_time_reply(appid, message, body, group_openid=gid):
        return
    # 联网指令（#搜 / #读）：搜完交给模型按人格转述（人设化加工），链接洗净后贴出去
    if await _local_web_cmd(appid, message, body, group_openid=gid, botdir=botdir):
        return
    # 延迟 + 抢先检查 + 发送（_group_send 是群回复的唯一出口）
    await _group_send(appid, botdir, message, gid, "", model_in, images, extra_sys, closing)

def _make_client(appid, secret, botdir, gen=None):
    """每个实例一套自己的 Client 类，避免事件回调串台。

    gen 一起带进来：事件处理器入口要拿它跟 _bots[appid]["gen"] 比，
    被 stop_one 停掉的旧实例再投进来的事件一律丢弃。
    """
    import botpy
    _install_group_message_parser()

    class Bot(botpy.Client):
        async def on_ready(self):
            if _stale(appid, gen):
                return
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
            if _stale(appid, gen):
                return
            if _dedupe_message(appid, message, "频道"):
                return
            text = (getattr(message, "content", "") or "").strip()
            log("频道@：%s" % text[:60], appid)
            try:
                say = await _reply(appid, botdir, "c" + str(message.channel_id), "", text,
                                   images=_attachments_of(message))
                if not say:
                    return         # 出站清洗判空：这条不发（_reply 里已记「丢弃」日志）
                await message._api.post_message(
                    channel_id=message.channel_id, msg_id=message.id, content=say,
                )
            except Exception as e:
                log("回频道失败：%r" % e, appid)

        async def on_group_at_message_create(self, message):
            if _stale(appid, gen):
                return
            # botpy 原生解析器（平台推 group_at_message_create 时走这里）
            await _handle_group(appid, botdir, message, "群@")

        async def on_group_message_create(self, message):
            if _stale(appid, gen):
                return
            # 我们自己补的解析器推过来的（平台推 group_message_create 时走这里）。
            # 采集 → _reply → post_group_message 全复用 _handle_group，没有第二套回复链路。
            await _handle_group(appid, botdir, message, "群消息(新事件)")

        async def on_c2c_message_create(self, message):
            if _stale(appid, gen):
                return
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
                say = await _reply(appid, botdir,
                                   "p" + str(message.author.user_openid), "", text,
                                   images=images)
                if not say:
                    return         # 出站清洗判空：这条不发（_reply 里已记「丢弃」日志）
                await message._api.post_c2c_message(
                    openid=message.author.user_openid, msg_type=0,
                    msg_id=message.id, content=say,
                )
            except Exception as e:
                log("回私聊失败：%r" % e, appid)

    try:
        intents = botpy.Intents(public_guild_messages=True, public_messages=True)
    except TypeError:
        intents = botpy.Intents(public_guild_messages=True)
        log("当前 botpy 不支持 public_messages，只开频道事件", appid)
    return Bot(intents=intents)


async def _reply(appid, botdir, key, who, incoming, images=None, extra_sys=""):
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
        msgs = _build_messages(botdir, key, who, incoming, first=first, search_hint=auto_decide,
                               member_oid=_member_openid_ctx.get())
        # extra_sys：群内最近消息（带名字标签）/ 收尾提示 —— 插在这一条之前当 system
        if extra_sys:
            msgs.insert(len(msgs) - 1, {"role": "system", "content": extra_sys})
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
        # ① 出站清洗：模型把自己的指令 / 思考 / 收尾标记一起抄出来时，这里只留正文
        before = text
        text = sanitize_reply(text)
        if text != before.strip():
            log("出站清洗：删掉指令/思考片段（%d → %d 字）" % (len(before), len(text)), appid)
        if not text:
            log("出站清洗：内容为空，丢弃", appid)
            return ""
        text = _clean_reply(text)
        log("回复清洗：原 %d 字 → 现 %d 字" % (raw_len, len(text)), appid)
        # ② 称呼兜底：对方不是主人时，「主人」不许发出去（master_word_guard 默认开）
        if _master_guard_on(_base_dir):
            fixed, n_fix, others = _master_word_fix(
                botdir, key, text, _member_openid_ctx.get())
            if n_fix:
                if others:
                    log("称呼兜底：替换 %d 处「主人」→「%s」（对方非主人）"
                        % (n_fix, others), appid)
                else:
                    log("称呼兜底：删除 %d 处「主人」→「你」（对方非主人，未配置称呼）"
                        % n_fix, appid)
                text = fixed
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


_stale_seen = set()      # "appid:gen" —— 同一个已停止实例只记一次，避免刷屏


def _stale(appid, gen):
    """这条事件来自一个已经被停掉的实例吗？

    为什么必须有：stop_one 提升 generation 之后，旧 botpy client 手里可能还有
    已经排进队列的回调在路上，它们照样能发消息 —— 用户看到的就是
    「界面显示已停止，bot 还在回话」。所有事件处理器入口先过这里。
    """
    if _own(appid, gen):
        return False
    key = "%s:%s" % (appid, gen)
    with _lock:
        first = key not in _stale_seen
        _stale_seen.add(key)
    if first:
        log("忽略：来自已停止实例的事件", appid)
    return True


async def _safe_close(client, appid=""):
    """把 botpy 的 close() 真正跑完。

    botpy.client.Client.close 是**协程**（_closed 置位 + await self.http.close()）。
    直接 c.close() 只是造了个没人 await 的协程对象，一条连接都没断 ——
    这正是「关掉 bot 但 bot 仍在跑」的根因。而且它必须投递到 client 自己的
    那个 loop 上执行（_run_bot 里 new_event_loop 那个），不能在当前线程直接 await。
    """
    if client is None:
        return
    try:
        r = client.close()
        if asyncio.iscoroutine(r):
            await r
    except Exception as e:
        log("close() 出错（忽略）：%r" % e, appid)


def _run_bot(appid, secret, botdir, gen=None):
    loop = None
    try:
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        with _lock:
            if _own(appid, gen):
                # 尽早登记：stop_one 要把 close() 投递到这个 loop 上、再 call_soon_threadsafe(stop)
                # 让 run_until_complete 退出；没有它 stop_one 就只能改状态，连接照跑
                _bots[appid]["loop"] = loop
        try:
            os.chdir(botdir)
        except Exception:
            pass
        _fix_botpy_logging(botdir)
        log("准备连接…", appid)
        client = _make_client(appid, secret, botdir, gen)
        with _lock:
            mine = _own(appid, gen)
            if mine:
                _bots[appid]["status"] = "连接中"
                _bots[appid]["client"] = client
        if not mine:
            # 启动过程中就被 stop_one 停掉了：别再连了，否则这条连接没人能停
            log("已停止：启动过程中收到停止指令，不再连接", appid)
            return
        log("开始 run()（阻塞直到连接结束）", appid)
        client.run(appid=appid, secret=secret)
        with _lock:
            mine = _own(appid, gen)
        log("run() 返回（%s）" % ("连接已结束" if mine else "实例已被停止"), appid)
        with _lock:
            if mine:
                _bots[appid]["status"] = "连接已结束"
    except Exception as e:
        with _lock:
            mine = _own(appid, gen)
        if not mine:
            # 这条线程是被 stop_one 停掉的：loop.stop() 会让 botpy 里的
            # run_until_complete 抛 RuntimeError(Event loop stopped before Future completed)
            # —— 那是「停成功」的回执，不是启动失败
            log("run() 已退出（实例已被停止）：%s" % e, appid)
        else:
            log("启动失败：%s" % e, appid)
            log(traceback.format_exc()[:1200], appid)
            with _lock:
                if _own(appid, gen):
                    _bots[appid]["status"] = "启动失败：%s" % str(e)[:60]
    finally:
        with _lock:
            if _own(appid, gen):
                _bots[appid]["alive"] = False
            if _bots.get(appid, {}).get("loop") is loop:
                _bots[appid]["loop"] = None      # 别攥着一个已经关掉的 loop
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
    # 人格摘要：导入人格 / 编辑保存后（Kotlin 会 restart）在这里补生成，后台线程、不挡启动。
    # hash 没变就直接用盘上那份，不重复花 token
    _summary_warm(botdir, appid)
    gen = time.time()
    with _lock:
        _bots[appid] = {
            "appid": appid, "dir": botdir, "status": "正在启动",
            "alive": True, "started_at": time.time(), "name": "",
            "client": None, "loop": None, "gen": gen,
        }
    t = threading.Thread(target=_run_bot, args=(appid, secret, botdir, gen), daemon=True)
    t.start()
    return "OK"


def stop_one(appid):
    """真的把这个 bot 停掉（不只是把状态写成「已停止」）。

    三步缺一不可：
      ① 提升 generation —— 从这一刻起旧 client 投回来的事件全部被 _stale() 丢掉；
      ② 关 http/gateway —— botpy 的 close() 是协程，必须 asyncio.run_coroutine_threadsafe
         投递到它自己的 loop 上 await（在当前线程直接 c.close() 只造了个协程对象）；
      ③ 停那个 loop —— client.run() 内部是 loop.run_until_complete(runner())，
         不停它 run() 永远不返回，线程和连接就都还在跑。
    """
    appid = str(appid or "").strip()
    with _lock:
        info = _bots.get(appid)
        if not info:
            return "没在跑"
        if not info.get("alive") and info.get("loop") is None:
            return "没在跑"      # 已经停透了：重复调 stop_all 不刷日志
        loop = info.get("loop")
        c = info.get("client")
        # ① 先失效：状态与 generation 一次改完，别让旧实例在这中间再回一条
        info["gen"] = time.time()
        info["status"] = "已停止"
        info["alive"] = False
        info["client"] = None
        info["loop"] = None
        info["stopped_at"] = time.time()
    log("收到停止指令", appid)
    if loop is None or not loop.is_running():
        log("已停止（该实例没连上过，事件将被忽略）", appid)
        return "OK"
    # ② 关 http/gateway：close() 是协程，必须投递到它自己那个 loop 上 await
    fut = None
    try:
        fut = asyncio.run_coroutine_threadsafe(_safe_close(c, appid), loop)
    except Exception as e:
        log("投递 close() 出错（忽略）：%r" % e, appid)
    # ③ 等 close() 跑完再停 loop —— 交给一个短命线程，不阻塞调用方
    #    （Kotlin 的 stop_all 是从主线程调的，同步等 3 秒会卡界面）
    threading.Thread(target=_finish_stop, args=(appid, loop, fut), daemon=True).start()
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
    # 总模型的名单兜底：localbots.json 读不到时用这份（字段与 localbots.json 同名）
    _state["bots_json"] = [b for b in bots_json if isinstance(b, dict)]
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


def _finish_stop(appid, loop, fut):
    """停 loop 并把「已停止」落进日志（在单独线程里做，stop_one 立即返回）。

    client.run() 内部是 loop.run_until_complete(runner())：不停这个 loop，
    run() 永远不返回，线程和连接就都还在跑 —— 界面上「已停止」而 bot 还在回话。
    """
    try:
        if fut is not None:
            fut.result(timeout=3)          # http/gateway 真的关掉
    except Exception as e:
        log("等 close() 超时/出错（继续停 loop）：%r" % e, appid)
    try:
        loop.call_soon_threadsafe(loop.stop)
    except Exception as e:
        log("停 loop 出错（忽略）：%r" % e, appid)
    end = time.time() + 3.0
    while time.time() < end and loop.is_running():
        time.sleep(0.02)
    log("已停止（loop 已停，旧实例事件将被忽略）", appid)


def stop_all():
    """逐个真停（不是只改状态）：stop_one 会关 http、停 loop、提升 generation。"""
    ids = list(_bots.keys())
    for appid in ids:
        try:
            stop_one(appid)
        except Exception as e:
            log("停止 %s 出错（继续停其它）：%r" % (appid, e), appid)
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



# ── 内存自检（诊断用，生产路径不会调用）──────────────────
#
# 为什么放在这里：Python 侧的内存只有在解释器里量才准（tracemalloc 只认自己的
# 分配器，gc.get_objects 只认活对象），Java 侧 dumpsys 只能看到 native heap 总数。
# 这个函数只读不写：不建线程、不碰网络、不清缓存，跑完进程状态和跑之前一样。
# 唯一调用方是 delivery/tools 里的离线脚本，App 正常运行时永远走不到这里。
def debug_selfcheck(base=None, limit=10):
    """→ str。'内存自检：…' 多行报告（tracemalloc top / 对象数 top / 各 bot 缓冲）。"""
    import gc
    import collections
    out = []
    td = base if base else _base_dir

    # 1) tracemalloc：只看 Python 分配器的账（含 json 临时串、prompt 拼装）
    try:
        import tracemalloc
        started_here = False
        if not tracemalloc.is_tracing():
            tracemalloc.start(10)
            started_here = True
        snap = tracemalloc.take_snapshot()
        cur, peak = tracemalloc.get_traced_memory()
        out.append("内存自检：tracemalloc 当前 %.1f MB / 峰值 %.1f MB"
                   % (cur / 1048576.0, peak / 1048576.0))
        top = snap.statistics("lineno")[:limit]
        out.append("内存自检：tracemalloc top%d = %s" % (limit, " | ".join(
            "%s:%d %.0f KB" % (str(s.traceback[0].filename).split("/")[-1],
                               s.traceback[0].lineno, s.size / 1024.0) for s in top)))
        if started_here:
            tracemalloc.stop()
    except Exception as e:
        out.append("内存自检：tracemalloc 不可用（%r）" % (e,))

    # 2) 活对象按类型计数（条数；字节由 tracemalloc 负责）
    try:
        cnt = collections.Counter()
        for o in gc.get_objects():
            cnt[type(o).__name__] += 1
        out.append("内存自检：对象数 top%d = %s" % (limit, " | ".join(
            "%s=%d" % (k, v) for k, v in cnt.most_common(limit))))
        out.append("内存自检：gc 对象总数 %d（gc.isenabled=%s）"
                   % (sum(cnt.values()), gc.isenabled()))
    except Exception as e:
        out.append("内存自检：gc.get_objects 失败（%r）" % (e,))

    # 3) 进程级常驻缓存（这些不该随 bot 数量线性涨）
    try:
        out.append("内存自检：常驻缓存 log=%d 条 seen=%d dedup=%s 群配置缓存=%d 存档连接=%d 待落库=%d"
                   % (len(_state.get("log") or []), len(_seen_ids),
                      ",".join("%s:%d" % (k, len(v)) for k, v in list(_dedup.items())[:6]) or "-",
                      len(_group_cache), len(_arc_db), len(_arc_buf)))
    except Exception as e:
        out.append("内存自检：常驻缓存统计失败（%r）" % (e,))

    # 4) 每个 bot 的历史缓冲：内存里存了几条、盘上多大
    try:
        rows = []
        for name in sorted(os.listdir(str(td or "."))):
            bd = os.path.join(str(td or "."), name)
            if not os.path.isdir(bd):
                continue
            mem = _memory(bd)
            keys = [k for k, v in mem.items() if isinstance(v, list)]
            total = sum(len(mem[k]) for k in keys)
            biggest = max([len(mem[k]) for k in keys], default=0)
            mp = _memory_path(bd)
            dbp = _arc_path(bd)
            lg = os.path.join(bd, "local_bot.log")
            rows.append("%s 会话%d 条%d(最多一档%d) memory.json %.0fKB messages.db %.0fKB log %.0fKB"
                        % (name, len(keys), total, biggest,
                           (os.path.getsize(mp) if os.path.exists(mp) else 0) / 1024.0,
                           (os.path.getsize(dbp) if os.path.exists(dbp) else 0) / 1024.0,
                           (os.path.getsize(lg) if os.path.exists(lg) else 0) / 1024.0))
        out.append("内存自检：各 bot 历史缓冲 %d 个" % len(rows))
        out.extend("内存自检：  " + r for r in rows)
        out.append("内存自检：拼 prompt 用最近 %d 条原文 + 一段摘要（_build_messages）"
                   % _SUMMARY_KEEP)
    except Exception as e:
        out.append("内存自检：各 bot 缓冲统计失败（%r）" % (e,))
    return "\n".join(out)
