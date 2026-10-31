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
_llm = {"key": "", "api": "", "model": "", "vision_model": ""}


def set_llm(key, api, model, vision_model=""):
    """由 BotHost.startAll 在 start_all 之前调用，把 Key 放进本进程内存。

    vision_model 可以留空：留空 = 识图沿用主模型。
    """
    _llm["key"] = str(key or "").strip()
    _llm["api"] = str(api or "").strip()
    _llm["model"] = str(model or "").strip()
    _llm["vision_model"] = str(vision_model or "").strip()
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
    """{key, api, model, vision_model, reasoning_effort}。

    api / model / vision_model / reasoning_effort 都可能来自落盘的 llm.json；key 只在内存里
    （BotHost 推过来的）。vision_model 空串 = 识图沿用主模型（不复制 model，调用时再回退）。
    reasoning_effort 空串 = 请求体里不带这个字段（用平台默认），见 _req_body。
    """
    c = _load_json(os.path.join(base_dir, "llm.json"), {}) or {}
    key = str(_llm.get("key") or c.get("key") or "").strip()
    api = str(_llm.get("api") or c.get("api") or "").strip()
    model = str(_llm.get("model") or c.get("model") or "").strip() or "deepseek-chat"
    vision_model = str(_llm.get("vision_model") or c.get("vision_model") or "").strip()
    effort = str(c.get("reasoning_effort") or "").strip()
    if not api or not key:
        return None
    if not api.endswith("/chat/completions"):
        api = api.rstrip("/") + "/chat/completions"
    return {"key": key, "api": api, "model": model, "vision_model": vision_model,
            "reasoning_effort": effort}


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
    return _extract_text(d["choices"][0]["message"])


# ── 回复判定（非 @ 消息要不要插话）──────────────────────────
#
# 设置页那一行：模型判断（llm）/ 随机（random，按「闲聊概率」掷骰子）。
# 只影响「没 @ 本 bot 的群消息」，@ 的照旧必回。

_judge = {"mode": "llm", "prob": 0.3}


async def _should_reply(conf, text):
    """这条没 @ 的消息要不要接一句。"""
    if _judge["mode"] != "llm":
        import random as _random
        hit = _random.random() <= _judge["prob"]
        log("判定（随机 %.2f）：%s" % (_judge["prob"], "接" if hit else "不接"))
        return hit
    if not conf:
        log("判定跳过：模型配置没填（llm.json 缺 key / 地址）")
        return False
    prompt = (
        "群里有人说：%s\n\n"
        "如果你觉得这句话该由你接一句，只回 1；不该插话只回 0。\n"
        "只回一个字符，不要解释。" % str(text or "")[:200]
    )
    try:
        raw = await asyncio.to_thread(
            _call_llm_sync, conf, [{"role": "user", "content": prompt}], 200, 0.2,
        )
        raw = (raw or "").strip()
        # 推理模型的正文可能只有「1」落在开头或结尾，两头都看一眼
        hit = ("1" in raw[:4]) or (raw[-1:] == "1") or (raw[:1] == "1")
        log("判定（模型 %s）：%s → %s" % (conf["model"], (raw[:20] or "(空)").replace(chr(10), " "),
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


def _build_messages(botdir, key, who, text, first=False):
    """persona → 称呼规则 → 群聊硬规则 → 最近 12 条历史。

    first 参数只为兼容老调用点保留：首句不再注入问候语（问候改成时段随机主动私聊）。
    """
    persona = _persona(botdir)
    sys = persona or "你是一个 QQ 群里的机器人，说话自然简短，像真人聊天。"
    sys = sys + _words_rules(botdir, key)
    sys = sys + chr(10) + chr(10) + "现在是 %s。" % _now_text()
    sys = sys + _CHAT_RULES
    msgs = [{"role": "system", "content": sys}]
    for r in (_memory(botdir).get(key) or [])[-12:]:
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
_autochat = {"on": False}  # 设置页「群里自动接话（默认关）」


def set_group_autochat(on):
    """Kotlin 侧 BotHost.startAll 在 start_all 之前推过来；默认关。"""
    _autochat["on"] = bool(on)
    log("群里自动接话：%s" % ("开" if _autochat["on"] else "关（默认只回 @）"))
    return "OK"


def group_autochat():
    return bool(_autochat["on"])


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
            if not _autochat["on"]:
                log("跳过：未 @ 本 bot（%s），且「群里自动接话」关闭 → 默认只回 @（%s）"
                    % (why_at, brief), appid)
                return
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

    log("%s：%s" % (tag, text[:60]), appid)
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
            log("本 bot 的 @ 判定 id 集合：%s｜own_ids.json：%s｜群里自动接话：%s" % (
                (",".join(sorted(_self_id_set(appid))[:4]) or "(空)")[:60],
                (",".join(sorted(_own_ids(botdir))[:4]) or "(空，等可信 @ 事件学习)"),
                "开" if _autochat["on"] else "关（默认只回 @）"), appid)
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
            text = (getattr(message, "content", "") or "").strip()
            images = _attachments_of(message)
            if images:
                log("私聊收到 %d 张图片附件" % len(images), appid)
            log("私聊：%s" % text[:60], appid)
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
    if images and not incoming:
        incoming = "（发了张图）"
    conf = _llm_conf(_base_dir) if _base_dir else None
    if images:
        if conf is None:
            log("识图跳过：模型配置没填（llm.json 缺 key / 地址），这条按纯文本处理", appid)
        else:
            extra = await _vision_suffix(appid, conf, images)
            if extra:
                incoming = incoming + extra
    # 「第一句」要在丢进记忆之前判：_remember 之后 key 下就已经有这条了
    first = not (_memory(botdir).get(key) or [])
    if incoming:
        _remember(botdir, key, "user", ("%s：%s" % (who, incoming)) if who else incoming, who)
    if conf is None:
        return "（本机模式连通测试）收到：%s" % (incoming or "")[:40]
    try:
        msgs = _build_messages(botdir, key, who, incoming, first=first)
        text = await asyncio.to_thread(_call_llm_sync, conf, msgs)
        if not text:
            text = "（模型没返回内容）"
        raw_len = len(text)
        text = _clean_reply(text)
        log("回复清洗：原 %d 字 → 现 %d 字" % (raw_len, len(text)), appid)
        _remember(botdir, key, "assistant", text)
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

