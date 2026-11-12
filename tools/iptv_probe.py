"""IPTV 内置清单的批量可用性体检：把 12k 频道 / 18k 条流逐条验到「能不能播出画面」。

为什么不能直接用 tools/station_probe.py：那份探针是给电台写的，直链只认 MP3/FLAC/OGG
魔数、三态结论围绕「有没有声音」。IPTV 是 HLS/DASH/TS/MP4 视频流，且清单里 1,119 条源
带 userAgent / referrer —— 不带这两个头去测，会把大量「必须鉴 Referer 才给流」的好源
整批判死。所以这里复用 station_probe 的三态语义与 HLS 纯解析函数，视频侧判定另写。

三条不能破的口径（都是本项目踩过的坑）：
1. 三态：LIVE = 真拿到播放列表分片 / 媒体魔数；GONE = 404/410/HTML 错页/空列表；
   UNKNOWN = 超时、连不上、403/429/5xx、鉴权错体、认不出的媒体字节。
   UNKNOWN 既不算活也不算死，报告与精简清单都不得把它当死源剔除。
2. 同一条流只测一次，缓存键是 (url, userAgent, referrer) 三元组而非 url 本身：
   同 url 配不同 header 是两条不同的流；反过来共享 url 的多台不该被打第二遍。
3. 结果文件绑定 assets 的 SHA-256。清单换了却沿用旧结果，等于拿上一版的体检报告
   冒充这一版（历史上出过这类假通过），默认直接拒绝，要重跑得显式 --force-new。

--fast 是另一套口径，默认不启用：单次请求、只读 4KB、不下钻 master，
连上并收到任意数据就算 LIVE（只有 404/410 与空 body 例外）。它最快，但
200 + HTML 错页 / 403 页 / 5xx 页都会被算成有效，所以 fast 的 LIVE 只能读作
「主机有响应」，不能当作白名单证据。每条结论的 mode 字段标明来源，报告会单列
错误页数量；黑名单（404/410）在两种口径下都可用。

用法（默认产物落在 .superpowers/tmp/iptv/probe/，该目录已在 .gitignore）：

    # 最快粗筛：高并行 + 宽松口径，分批跑（重复执行会自动接着未测条目跑）
    python tools/iptv_probe.py probe --fast --workers 48 --timeout 5 --limit 500 \
        --proxy http://127.0.0.1:7897

    # 先小样本验证网络/代理是否通，20 条
    python tools/iptv_probe.py probe --limit 20 --proxy http://127.0.0.1:7897

    # 只测中文台（ASCII 参数，避开 PowerShell 传中文的编码坑）
    python tools/iptv_probe.py probe --only-language zho --workers 16

    # 全量；随时可 Ctrl-C，重跑会自动跳过已表态的条目
    python tools/iptv_probe.py probe --workers 24 --timeout 8

    # 第二轮：只重跑 UNKNOWN（建议降并发、加超时，避开同站限流假阳性）
    python tools/iptv_probe.py probe --retry-unknown --workers 4 --timeout 20

    # 出黑白名单与人读报告 / 生成精简清单（绝不写回 assets）
    python tools/iptv_probe.py report
    python tools/iptv_probe.py prune --out .superpowers/tmp/iptv/probe/iptv_channels_pruned.json

不带子命令直接跟参数时默认走 probe（方便 `python tools/iptv_probe.py --limit 20`）。
本脚本只读 app/src/main/assets/iptv_channels.json，永远不修改它。
"""
from __future__ import annotations

import argparse
import concurrent.futures as cf
import hashlib
import json
import os
import re
import sys
import threading
import time
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlparse

try:
    for _stream in (sys.stdout, sys.stderr):
        _stream.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

import requests

import station_probe as sp

LIVE = sp.LIVE
GONE = sp.GONE
UNKNOWN = sp.UNKNOWN

SCHEMA_VERSION = 1
DEFAULT_UA = "Mozilla/5.0 (Linux; Android 12) ExoPlayer/1.5.1"

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_ASSETS = ROOT / "app/src/main/assets/iptv_channels.json"
DEFAULT_WORKDIR = ROOT / ".superpowers/tmp/iptv/probe"
DEFAULT_STATE = DEFAULT_WORKDIR / "probe_result.json"
DEFAULT_REPORT = DEFAULT_WORKDIR / "iptv_probe_report.txt"
DEFAULT_WHITELIST = DEFAULT_WORKDIR / "iptv_whitelist.json"
DEFAULT_BLACKLIST = DEFAULT_WORKDIR / "iptv_blacklist.json"

# 视频/音频容器首字节。TS 只验一个 0x47 太松（任意二进制都可能撞上），
# 再验第 189 字节（下一个 TS 包起点）也是同步字节，双包刚好 188*2 = 376。
_TS_PACKET = 188
_MEDIA_CONTENT_TYPES = ("video/", "audio/", "mpegurl", "dash+xml", "application/octet-stream")


# ---------------------------------------------------------------- 清单展开


def stream_key(url: str, user_agent: str = "", referrer: str = "") -> str:
    """同一 url 配不同 header 必须是不同的键；取短哈希避免把 18k 条 URL 当 JSON key。"""
    raw = "\n".join((url, user_agent or "", referrer or ""))
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:16]


def collect_streams(stations: list) -> tuple[dict, dict]:
    """展开成 {key: 流} 与 {stationId: [key]}。

    只读 sources 不读 streamUrl：生成脚本保证 streamUrl == sources[0].url，
    但 sources 才带 userAgent / referrer，从 streamUrl 取会静默丢掉鉴权头。
    """
    streams: dict = {}
    station_keys: dict = {}
    for st in stations:
        sid = st["id"]
        # 每台都留一个（可能为空的）列表：下游据此判「无源可测」，绝不把空列表当死台
        station_keys[sid] = []
        srcs = st.get("sources") or [{"url": st.get("streamUrl", "")}]
        meta = {
            "id": sid,
            "name": st.get("name", ""),
            "country": st.get("country", ""),
            "language": st.get("language", ""),
            "isDirect": sid.startswith("iptv:direct:"),
        }
        seen = set()
        for src in srcs:
            url = (src.get("url") or "").strip()
            if not url.lower().startswith(("http://", "https://")):
                continue
            ua = (src.get("userAgent") or "").strip()
            ref = (src.get("referrer") or "").strip()
            key = stream_key(url, ua, ref)
            if key in seen:
                continue
            seen.add(key)
            station_keys[sid].append(key)
            row = streams.get(key)
            if row is None:
                row = streams[key] = {
                    "key": key, "url": url, "userAgent": ua, "referrer": ref,
                    "stations": [], "seenIds": set(),
                }
            if sid not in row["seenIds"]:
                row["seenIds"].add(sid)
                row["stations"].append(meta)
    for row in streams.values():
        del row["seenIds"]
    return streams, station_keys


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


# ---------------------------------------------------------------- 单条探测


@dataclass
class ProbeOptions:
    timeout: float = 8.0
    proxy: str | None = None
    trust_env: bool = True
    head_limit: int = 65536
    max_depth: int = 2
    session: object | None = None  # 单测注入，避免真联网
    fast: bool = False  # 宽松口径：收到数据即 LIVE，不验分片/魔数、不下钻


_tls = threading.local()


def _session(opts: ProbeOptions):
    if opts.session is not None:
        return opts.session
    s = getattr(_tls, "session", None)
    if s is None:
        s = requests.Session()
        s.trust_env = opts.trust_env
        if opts.proxy:
            s.proxies = {"http": opts.proxy, "https": opts.proxy}
        _tls.session = s
    return s


def build_headers(user_agent: str = "", referrer: str = "") -> dict:
    h = {
        "User-Agent": user_agent or DEFAULT_UA,
        "Accept": "*/*",
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
    }
    if referrer:
        h["Referer"] = referrer
    return h


def _read_head(resp, limit: int) -> bytes:
    try:
        return resp.raw.read(limit, decode_content=True) or b""
    except Exception:
        return b""


def _fetch(session, url: str, headers: dict, opts: ProbeOptions) -> dict:
    """取一段 body 就断开：直播流是无限的，绝不能等它结束。

    直链只读 4KB；若前几字节像播放列表 / XML / HTML 再续读到 head_limit。
    """
    r = session.get(url, headers=headers, timeout=opts.timeout,
                    stream=True, allow_redirects=True)
    with r:
        status = r.status_code
        ct = r.headers.get("Content-Type", "")
        body = _read_head(r, 4096)
        probe = body[:512].decode("utf-8", errors="replace").lstrip().lower()
        if not opts.fast and any(tag in probe for tag in
                                 ("#extm3u", "<?xml", "<mpd", "<html", "<!doctype")):
            body += _read_head(r, max(0, opts.head_limit - len(body)))
        return {"status": status, "contentType": ct, "body": body}


def _has_media_magic(body: bytes) -> bool:
    if not body:
        return False
    if body[:4] in (b"FLV\x01", b"\x1a\x45\xdf\xa3", b"ID3\x03", b"fLaC", b"OggS", b"RIFF", b"FORM"):
        return True
    if body[4:8] == b"ftyp":  # MP4 / fMP4
        return True
    if body[0] == 0x47 and len(body) >= 2 * _TS_PACKET and body[_TS_PACKET] == 0x47:
        return True  # 连续两个 MPEG-TS 同步字节
    if len(body) >= 2 and body[0] == 0xFF and (body[1] & 0xE0) == 0xE0:
        return True  # 裸 MP3 帧头
    return False


def _dash_verdict(text: str) -> dict:
    """MPD 只要有 Period / Representation / SegmentTemplate 就认定结构可用。

    不去二次请求分片：DASH 的分片地址常由 SegmentTemplate 按时间拼出，
    PC 侧拼出来的地址未必是播放器实际取的那个，验它只会制造假阴性。
    """
    if re.search(r"<(Period|Representation|SegmentTemplate|AdaptationSet)\b", text, re.I):
        return {"verdict": LIVE, "why": "dash-mpd", "detail": "mpd has representation"}
    return {"verdict": GONE, "why": "dash-empty", "detail": "mpd has no representation"}


def classify_response(status: int, content_type: str, body: bytes, base_url: str = "") -> dict:
    """状态码 + body 证据一起出结论；状态码永远不能单独决定判活。

    返回 {verdict, why, detail}；master 播放列表额外带 drill（待下钻的绝对地址）。
    """
    ct = (content_type or "").lower()
    if status in (404, 410):
        return {"verdict": GONE, "why": f"http-{status}", "detail": ""}
    if status in (401, 403, 429) or status >= 500:
        return {"verdict": UNKNOWN, "why": f"http-{status}", "detail": ""}
    if status not in (200, 206):
        return {"verdict": UNKNOWN, "why": f"http-{status}", "detail": ""}

    text = body.decode("utf-8", errors="replace")
    ev = sp.hls_manifest_evidence(text)
    if ev == "playlist":
        variants = sp.parse_master_variants(text)
        if variants:
            child = sp.resolve_ref(base_url, variants[0])
            if not child:
                return {"verdict": UNKNOWN, "why": "unresolvable-variant",
                        "detail": variants[0][:120]}
            return {"verdict": UNKNOWN, "why": "master-drill", "detail": "",
                    "drill": child}
        segs = sp.count_segments(text)
        if segs >= 1:
            # 单层列表不强制 #EXT-X-MEDIA-SEQUENCE：IPTV 上游有大量无序号的直播列表，
            # 硬要求它会把好源判死；但「一个分片都没有」必须判死。
            return {"verdict": LIVE, "why": "media-playlist", "detail": f"segments={segs}"}
        return {"verdict": GONE, "why": "empty-playlist", "detail": f"len={len(text)}"}
    if ev == "app-error":
        return {"verdict": UNKNOWN, "why": "app-error-body", "detail": text[:90].strip()}
    if ev == "html":
        return {"verdict": GONE, "why": "html-body", "detail": ""}
    if "<mpd" in text[:4096].lower():
        return _dash_verdict(text)
    if ev == "empty":
        return {"verdict": GONE, "why": "empty-body", "detail": ""}
    if "html" in ct or body[:1] == b"<":
        return {"verdict": GONE, "why": "html-body", "detail": ""}
    if _has_media_magic(body):
        return {"verdict": LIVE, "why": "media-magic", "detail": f"bytes={len(body)}"}
    if any(k in ct for k in _MEDIA_CONTENT_TYPES) and len(body) >= 128:
        # 有媒体 Content-Type 却认不出魔数：可能是加密流 / 罕见容器，不能判死
        return {"verdict": UNKNOWN, "why": "media-undetermined",
                "detail": f"ct={ct} bytes={len(body)}"}
    return {"verdict": GONE, "why": "not-media", "detail": f"ct={ct} bytes={len(body)}"}


FAST_ANY_DATA = "fast-any-data"


def classify_fast_response(status: int, content_type: str, body: bytes) -> dict:
    """宽松口径（--fast）：连上并收到任意数据就算有效，目的是最快地粗筛一遍。

    只留两个确定性例外：404/410（源确实不存在）与空 body（根本没收到数据）。
    200 + HTML 错页、403 鉴权页、5xx 错误页在这个口径下**都算 LIVE**，
    所以这里的 LIVE 只能读作「主机有响应」，不能读作「能播出画面」，
    更不能拿白名单当发版依据。要看状态分布请读报告里的「宽松口径明细」。
    """
    if status in (404, 410):
        return {"verdict": GONE, "why": f"fast-http-{status}", "detail": ""}
    if not body:
        return {"verdict": UNKNOWN, "why": "fast-no-data", "detail": ""}
    return {"verdict": LIVE, "why": FAST_ANY_DATA,
            "detail": f"status={status} ct={content_type} bytes={len(body)}"}


def probe_stream(stream: dict, opts: ProbeOptions) -> dict:
    """单条流实测，返回三态结论 + 原因 + 耗时。任何异常都只落 UNKNOWN。"""
    started = time.monotonic()
    session = _session(opts)
    headers = build_headers(stream.get("userAgent", ""), stream.get("referrer", ""))
    url = stream["url"]
    try:
        if opts.fast:
            # 单次请求、只读 4KB、不下钻 master，一次网络往返就出结论
            resp = _fetch(session, url, headers, opts)
            verdict = classify_fast_response(resp["status"], resp["contentType"],
                                             resp["body"])
        else:
            for depth in range(opts.max_depth):
                resp = _fetch(session, url, headers, opts)
                verdict = classify_response(resp["status"], resp["contentType"],
                                            resp["body"], base_url=url)
                child = verdict.pop("drill", "")
                if not child:
                    break
                if depth + 1 >= opts.max_depth:
                    verdict = {"verdict": UNKNOWN, "why": "variant-depth-exceeded", "detail": ""}
                    break
                url = child
            else:
                verdict = {"verdict": UNKNOWN, "why": "variant-depth-exceeded", "detail": ""}
    except requests.Timeout:
        verdict = {"verdict": UNKNOWN, "why": "timeout", "detail": ""}
    except requests.ConnectionError:
        verdict = {"verdict": UNKNOWN, "why": "conn-error", "detail": ""}
    except Exception as e:  # 解析炸了不等于源死了
        verdict = {"verdict": UNKNOWN, "why": "exception",
                   "detail": f"{type(e).__name__}: {e}"}
    verdict["elapsed"] = round(time.monotonic() - started, 3)
    return verdict


class HostLimiter:
    """同一 host 的并发上限。

    电台体检踩过：并发下同一 CDN 会限流，把好源打成 timeout。18k 条流里
    jmp2.uk 一家占 2,343 条，不限流等于对它发起 DDoS，结果全是假 UNKNOWN。
    """

    def __init__(self, cap: int):
        self.cap = max(1, cap)
        self._sems: dict = {}
        self._guard = threading.Lock()

    def run(self, url: str, fn):
        host = urlparse(url).hostname or "?"
        with self._guard:
            sem = self._sems.setdefault(host, threading.BoundedSemaphore(self.cap))
        with sem:
            return fn()


# ---------------------------------------------------------------- 断点续跑


def new_state(fingerprint: str, assets: Path) -> dict:
    return {
        "schemaVersion": SCHEMA_VERSION,
        "assets": str(assets),
        "assetsSha256": fingerprint,
        "updatedAt": "",
        "entries": {},
    }


def atomic_write_json(path: Path, obj) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(json.dumps(obj, ensure_ascii=False, separators=(",", ":")),
                   encoding="utf-8")
    os.replace(tmp, path)


def atomic_write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(text, encoding="utf-8")
    os.replace(tmp, path)


def load_state(path: Path, fingerprint: str, assets: Path, force_new: bool = False,
               rebase_keys: set | None = None) -> dict:
    """读结果文件并校验清单指纹。

    --rebase 的判据不是「差不多就行」：state 里每条结论的 key 是
    (url, UA, Referer) 的哈希，只有当已测 key 集合与当前清单的流 key 集合
    **完全相等**时，才证明这次改动只碰了 name/nsfw 之类的元数据、没动任何流地址，
    此时沿用全部实测结果是安全的，并把旧指纹记在 rebasedFrom 里留痕。
    少一条 / 多一条都拒绝 —— 新增流没测过，拿旧结论冒充就是假通过。
    """
    if not path.exists():
        return new_state(fingerprint, assets)
    try:
        state = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        raise SystemExit(f"结果文件损坏，不猜内容：{path}（{e}）。删除它或换 --state 重跑")
    if state.get("schemaVersion") != SCHEMA_VERSION:
        raise SystemExit(f"结果文件 schemaVersion={state.get('schemaVersion')}，本工具只认 {SCHEMA_VERSION}")
    if state.get("assetsSha256") != fingerprint:
        if rebase_keys is not None and set(state.get("entries") or {}) == set(rebase_keys):
            old = state["assetsSha256"]
            state["assetsSha256"] = fingerprint
            state["rebasedFrom"] = old
            print(f"清单流集合未变（{len(rebase_keys)} 条），仅元数据改动；"
                  f"沿用已有结果并把指纹 {old[:12]} 对齐到 {fingerprint[:12]}", file=sys.stderr)
            # 对齐后的指纹要落盘，否则后续每个子命令都得再带一次 --rebase
            atomic_write_json(path, state)
            return state
        if not force_new:
            raise SystemExit(
                "清单 SHA-256 与结果文件不一致（assets 已更新或换过文件）。\n"
                "若确认只改了元数据、流集合没变，加 --rebase 沿用已有结果；\n"
                "否则 --force-new 清空重跑，或用 --state 指定旧结果。"
            )
        print("警告：--force-new 已清空旧结果，本次从零开始", file=sys.stderr)
        return new_state(fingerprint, assets)
    state.setdefault("entries", {})
    return state


def save_state(path: Path, state: dict) -> None:
    state["updatedAt"] = datetime.now(timezone.utc).isoformat(timespec="seconds")
    atomic_write_json(path, state)


# ---------------------------------------------------------------- 汇总 / 报告


def station_verdict(verdicts: list) -> str:
    """台级判定：任一源 live 即 live；只要有没测过的或 UNKNOWN 就不许判死。"""
    if not verdicts:
        return UNKNOWN
    if LIVE in verdicts:
        return LIVE
    if UNKNOWN in verdicts or None in verdicts:
        return UNKNOWN
    return GONE


def summarize(stations: list, station_keys: dict, streams: dict, entries: dict) -> dict:
    stream_counts = Counter(e.get("verdict", UNKNOWN) for e in entries.values())
    # 宽松口径的 LIVE 要单独报出来：其中有多少其实是 4xx/5xx 错误页，
    # 决定了这份「白名单」能不能用。混在 live 总数里就什么都看不出来了。
    fast_live = [e for e in entries.values() if e.get("why") == FAST_ANY_DATA]
    fast_error_page = [e for e in fast_live
                       if re.search(r"status=(4|5)\d\d", e.get("detail", ""))]
    by_verdict = {LIVE: [], GONE: [], UNKNOWN: []}
    rows = []
    host_gone: Counter = Counter()
    host_live: Counter = Counter()
    for key, e in entries.items():
        host = urlparse((streams.get(key) or {}).get("url", "")).hostname or "?"
        if e.get("verdict") == GONE:
            host_gone[host] += 1
        elif e.get("verdict") == LIVE:
            host_live[host] += 1
    dead_hosts = sorted(
        ((h, n) for h, n in host_gone.items() if n >= 5 and host_live[h] == 0),
        key=lambda x: -x[1],
    )
    for st in stations:
        keys = station_keys.get(st["id"], [])
        verdicts = [(entries[k].get("verdict") if k in entries else None) for k in keys]
        v = station_verdict(verdicts)
        by_verdict[v].append(st["id"])
        rows.append({
            "id": st["id"], "name": st.get("name", ""),
            "country": st.get("country", ""), "language": st.get("language", ""),
            "verdict": v, "untested": sum(1 for x in verdicts if x is None),
        })
    by_language = Counter(r["language"] or "(空)" for r in rows if r["verdict"] == GONE)
    by_country = Counter(r["country"] or "(空)" for r in rows if r["verdict"] == GONE)
    return {
        "streamTotal": len(streams),
        "streamTested": len(entries),
        "streamCounts": {k: stream_counts.get(k, 0) for k in (LIVE, GONE, UNKNOWN)},
        "stationTotal": len(stations),
        "stationCounts": {k: len(by_verdict[k]) for k in (LIVE, GONE, UNKNOWN)},
        "byVerdict": by_verdict,
        "rows": rows,
        "deadHosts": dead_hosts,
        "goneByLanguage": by_language.most_common(12),
        "goneByCountry": by_country.most_common(12),
        "fastCounts": {"looseLive": len(fast_live), "errorPage": len(fast_error_page)},
    }


def render_report(summary: dict, fingerprint: str) -> str:
    sc = summary["streamCounts"]
    tc = summary["stationCounts"]
    untested = summary["streamTotal"] - summary["streamTested"]
    lines = [
        "IPTV 清单体检报告",
        f"assets sha256 : {fingerprint}",
        f"流  : 共 {summary['streamTotal']} 条，已测 {summary['streamTested']} 条，未测 {untested} 条",
        f"      live {sc[LIVE]} / gone {sc[GONE]} / unknown {sc[UNKNOWN]}",
        f"频道: 共 {summary['stationTotal']} 台",
        f"      可播(live) {tc[LIVE]} / 确认全源失效(gone) {tc[GONE]} / 未表态(unknown) {tc[UNKNOWN]}",
        "",
        "口径：只有「全部源都已测且全部判死」的台才进黑名单；",
        "      只要有一个源未测或 UNKNOWN，该台一律留在未表态，不剔除（超时≠源已死）。",
        "",
    ]
    fl = summary.get("fastCounts", {})
    if fl.get("looseLive"):
        loose, errs = fl["looseLive"], fl["errorPage"]
        lines += [
            f"【宽松口径】本次有 {loose} 条流按 --fast「收到数据即有效」判为 live，",
            f"            其中 {errs} 条其实是 4xx/5xx 错误页。",
            "            live 只能读作「主机有响应」，不等于能播出画面；",
            "            白名单不可作为发版依据，黑名单（404/410）仍可用。",
            "",
        ]
    if untested:
        lines.append(f"注意：还有 {untested} 条流没测过，本次黑名单是保守下界。继续跑 probe 补齐。")
        lines.append("")
    if sc[UNKNOWN] + untested > (summary["streamTotal"] or 1) * 0.5:
        lines.append("注意：UNKNOWN/未测占比过半，先确认 --proxy 与网络是否通；"
                     "本机走不出去时不要据此清理清单。")
        lines.append("")
    if summary["deadHosts"]:
        lines.append("整站判死的主机 TOP 20（gone>=5 且无 live）：")
        for host, n in summary["deadHosts"][:20]:
            lines.append(f"   {n:5d}  {host}")
        lines.append("")
    if summary["goneByCountry"]:
        lines.append("确认失效台按国家 TOP 12：" + "，".join(
            f"{k} {v}" for k, v in summary["goneByCountry"]))
        lines.append("")
    if summary["goneByLanguage"]:
        lines.append("确认失效台按语言 TOP 12：" + "，".join(
            f"{k} {v}" for k, v in summary["goneByLanguage"]))
        lines.append("")
    gone_rows = [r for r in summary["rows"] if r["verdict"] == GONE]
    if gone_rows:
        lines.append(f"黑名单候选（全源确认失效）{len(gone_rows)} 台，前 50：")
        for r in gone_rows[:50]:
            lines.append(f"   {r['name'][:36]:36} [{r['country'] or '-'}] {r['id']}")
        lines.append("")
    unk_rows = [r for r in summary["rows"] if r["verdict"] == UNKNOWN and r["untested"] == 0]
    if unk_rows:
        lines.append(f"两轮仍未表态（需真机复测，不进黑名单）{len(unk_rows)} 台，前 20：")
        for r in unk_rows[:20]:
            lines.append(f"   {r['name'][:36]:36} {r['id']}")
    return "\n".join(lines) + "\n"


def build_pruned(stations: list, station_keys: dict, entries: dict,
                 promote_live: bool = True) -> tuple[list, dict]:
    """按体检结果生成精简清单：只删全源 GONE 的台，并逐台去掉 GONE 的源。

    绝不删 UNKNOWN/未测的台；绝不把 assets 当输出目标（由 cmd_prune 再断言一次）。
    promote_live=False 用于真正写回 assets 的场景：宽松口径下的 LIVE 可能是 4xx/5xx
    错误页，把它提到主源只会让播放器先撞错页，所以剔除动作只删不改顺序。
    """
    out = []
    stats = {"kept": 0, "droppedStations": 0, "droppedSources": 0, "promoted": 0}
    for st in stations:
        keys = station_keys.get(st["id"], [])
        verdicts = [(entries[k].get("verdict") if k in entries else None) for k in keys]
        if station_verdict(verdicts) == GONE:
            stats["droppedStations"] += 1
            continue
        srcs = list(st.get("sources") or [])
        kept = []
        for src in srcs:
            u = (src.get("url") or "").strip()
            key = stream_key(u, (src.get("userAgent") or "").strip(),
                             (src.get("referrer") or "").strip())
            if key in entries and entries[key].get("verdict") == GONE:
                stats["droppedSources"] += 1
                continue
            kept.append(src)
        new_st = dict(st)
        if kept:
            if promote_live:
                # 把首个确认 live 的源提到主源，避免播放器每次都先撞一个没测过的地址
                live_idx = next((i for i, s in enumerate(kept)
                                 if entries.get(stream_key(
                                     (s.get("url") or "").strip(),
                                     (s.get("userAgent") or "").strip(),
                                     (s.get("referrer") or "").strip()), {})
                                     .get("verdict") == LIVE), -1)
                if live_idx > 0:
                    kept.insert(0, kept.pop(live_idx))
                    stats["promoted"] += 1
            new_st["sources"] = kept
            new_st["streamUrl"] = kept[0]["url"]
        out.append(new_st)
        stats["kept"] += 1
    return out, stats


# ---------------------------------------------------------------- 子命令


def _load_assets(path: Path):
    if not path.exists():
        raise SystemExit(f"清单不存在：{path}")
    data = json.loads(path.read_text(encoding="utf-8"))
    if "stations" not in data or not isinstance(data["stations"], list):
        raise SystemExit("清单不是 {\"stations\":[...]} 形状，拒绝继续")
    return data, sha256_file(path)


def _select(streams: dict, args) -> list:
    rows = list(streams.values())
    if args.skip_direct:
        rows = [r for r in rows if not any(s["isDirect"] for s in r["stations"])]
    if args.only_direct:
        rows = [r for r in rows if any(s["isDirect"] for s in r["stations"])]
    if args.only_country:
        rows = [r for r in rows if any(s["country"] in args.only_country
                                       for s in r["stations"])]
    if args.only_language:
        rows = [r for r in rows if any(
            any((s["language"] or "").startswith(p) for p in args.only_language)
            for s in r["stations"])]
    if args.name_contains:
        rows = [r for r in rows if any(args.name_contains.lower() in (s["name"] or "").lower()
                                       for s in r["stations"])]
    return sorted(rows, key=lambda r: r["key"])


def cmd_probe(args) -> int:
    data, fingerprint = _load_assets(args.assets)
    streams, _ = collect_streams(data["stations"])
    state = load_state(args.state, fingerprint, args.assets, args.force_new,
                       rebase_keys=set(streams) if args.rebase else None)
    entries = state["entries"]
    mode = "fast" if args.fast else "strict"
    state["modes"] = sorted(set(state.get("modes") or []) | {mode})
    if len(state["modes"]) > 1:
        print("注意：结果文件里混有严格/宽松两种口径，逐条 mode 字段标明来源",
              file=sys.stderr)

    if args.unknown_from:
        if not args.unknown_from.exists():
            raise SystemExit(f"--unknown-from 文件不存在：{args.unknown_from}")
        src = json.loads(args.unknown_from.read_text(encoding="utf-8"))
        if src.get("assetsSha256") != fingerprint:
            raise SystemExit(
                "--unknown-from 的 assets 指纹与当前清单不一致，"
                "拒绝拿另一版清单的 UNKNOWN 集合来复测"
            )
        unknown_keys = {k for k, v in (src.get("entries") or {}).items()
                        if v.get("verdict") == UNKNOWN}
        selected = [r for r in _select(streams, args) if r["key"] in unknown_keys]
        pending = [r for r in selected if r["key"] not in entries]
        print(f"复测来源 {args.unknown_from}：UNKNOWN {len(unknown_keys)} 条", file=sys.stderr)
    elif args.retry_unknown:
        pending = [r for r in _select(streams, args)
                   if entries.get(r["key"], {}).get("verdict") == UNKNOWN]
    else:
        pending = [r for r in _select(streams, args) if r["key"] not in entries]
    if args.limit:
        pending = pending[:args.limit]
    total = len(pending)
    print(f"清单 {len(data['stations'])} 台 / {len(streams)} 条流；"
          f"口径 {mode}；已表态 {len(entries)} 条，本次待测 {total} 条", file=sys.stderr)
    if not total:
        print("没有待测条目。要重跑未表态的请加 --retry-unknown，"
              "要全部重测请 --force-new。", file=sys.stderr)
        return 0

    opts = ProbeOptions(timeout=args.timeout, proxy=args.proxy or None,
                        trust_env=not args.no_env_proxy, fast=args.fast)
    limiter = HostLimiter(args.host_cap)
    done = 0
    counts: Counter = Counter()
    interrupted = False
    # 不用 with ThreadPoolExecutor：__exit__ 是 shutdown(wait=True)，Ctrl-C 时会
    # 傻等队列里剩下的几千个任务跑完，用户以为卡死了。改为显式 cancel_futures。
    ex = cf.ThreadPoolExecutor(max_workers=args.workers)
    try:
        futs = {}
        for row in pending:
            fut = ex.submit(lambda r=row: limiter.run(
                r["url"], lambda: probe_stream(r, opts)))
            futs[fut] = row["key"]
        for fut in cf.as_completed(futs):
            key = futs[fut]
            try:
                result = fut.result()
            except Exception as e:  # 单条炸了不许中断整轮体检
                result = {"verdict": UNKNOWN, "why": "worker-exception",
                          "detail": f"{type(e).__name__}: {e}", "elapsed": 0}
            if result is None:
                result = {"verdict": UNKNOWN, "why": "host-limit", "detail": "", "elapsed": 0}
            entries[key] = {
                "verdict": result["verdict"], "why": result["why"],
                "detail": result.get("detail", ""), "elapsed": result.get("elapsed", 0),
                "mode": mode,
            }
            counts[result["verdict"]] += 1
            done += 1
            if done % args.checkpoint == 0:
                save_state(args.state, state)
                print(f"  {done}/{total} live={counts[LIVE]} gone={counts[GONE]} "
                      f"unknown={counts[UNKNOWN]}", file=sys.stderr)
    except KeyboardInterrupt:
        interrupted = True
        print("\n收到中断，正在保存已完成的条目……", file=sys.stderr)
    finally:
        ex.shutdown(wait=False, cancel_futures=True)
        save_state(args.state, state)

    print(f"完成 {done}/{total}：live={counts[LIVE]} gone={counts[GONE]} "
          f"unknown={counts[UNKNOWN]}；结果 {args.state}", file=sys.stderr)
    print(f"下一步：python tools/iptv_probe.py report --state {args.state}")
    return 130 if interrupted else 0


def cmd_report(args) -> int:
    data, fingerprint = _load_assets(args.assets)
    streams, station_keys = collect_streams(data["stations"])
    state = load_state(args.state, fingerprint, args.assets,
                       rebase_keys=set(streams) if args.rebase else None)
    summary = summarize(data["stations"], station_keys, streams, state["entries"])
    text = render_report(summary, fingerprint)
    atomic_write_text(args.report, text)
    atomic_write_json(args.whitelist, {
        "schemaVersion": SCHEMA_VERSION,
        "assetsSha256": fingerprint,
        "criterion": "station has at least one LIVE stream",
        "stationIds": summary["byVerdict"][LIVE],
    })
    atomic_write_json(args.blacklist, {
        "schemaVersion": SCHEMA_VERSION,
        "assetsSha256": fingerprint,
        "criterion": "all streams probed and all GONE; UNKNOWN/untested never included",
        "stationIds": summary["byVerdict"][GONE],
    })
    print(text)
    print(f"报告 {args.report}\n白名单 {args.whitelist}\n黑名单 {args.blacklist}",
          file=sys.stderr)
    return 0


def cmd_prune(args) -> int:
    out = args.out
    if out.resolve() == DEFAULT_ASSETS.resolve():
        raise SystemExit("拒绝写回 app/src/main/assets/iptv_channels.json：本工具只读清单")
    if out.resolve().is_relative_to(DEFAULT_ASSETS.parent.resolve()):
        raise SystemExit("拒绝写入 assets 目录：精简清单是待审阅产物，确认后再人工替换")
    data, fingerprint = _load_assets(args.assets)
    streams, station_keys = collect_streams(data["stations"])
    state = load_state(args.state, fingerprint, args.assets,
                       rebase_keys=set(streams) if args.rebase else None)
    summary = summarize(data["stations"], station_keys, streams, state["entries"])
    if summary["streamTested"] == 0:
        raise SystemExit("结果文件里一条已测流都没有，拒绝产出一份看似干净的假清单")
    untested = summary["streamTotal"] - summary["streamTested"]
    if untested:
        print(f"注意：还有 {untested}/{summary['streamTotal']} 条流未测，未测台一律保留，"
              f"本次产物不会明显瘦身；要真精简得先跑完全量 probe。", file=sys.stderr)
    stations, stats = build_pruned(data["stations"], station_keys, state["entries"])
    atomic_write_json(out, {"stations": stations})
    print(f"剔除 {stats['droppedStations']} 台全源失效频道、"
          f"{stats['droppedSources']} 条失效源；提升 {stats['promoted']} 台主源；"
          f"保留 {stats['kept']} 台（含 UNKNOWN/未测，不误杀）", file=sys.stderr)
    print(f"产物 {out}（{out.stat().st_size / 1024 / 1024:.2f} MiB）\n"
          f"确认无误后由人工替换 assets，并跑 IptvAssetsGateTest。", file=sys.stderr)
    return 0


def add_common_args(ap: argparse.ArgumentParser) -> None:
    ap.add_argument("--assets", type=Path, default=DEFAULT_ASSETS)
    ap.add_argument("--state", type=Path, default=DEFAULT_STATE)
    ap.add_argument("--rebase", action="store_true",
                    help="assets 指纹变了但流集合完全一致时，沿用已有结果并对齐指纹"
                         "（仅元数据改动可用；少/多一条流都拒绝）")


def add_probe_args(ap: argparse.ArgumentParser) -> None:
    add_common_args(ap)
    ap.add_argument("--fast", action="store_true",
                    help="宽松口径：单次请求、收到任意数据即判 live（404/410 仍判死）；"
                         "跳过分片/魔数验证与 master 下钻，最快但会有假阳性")
    ap.add_argument("--workers", type=int, default=16)
    ap.add_argument("--timeout", type=float, default=8.0)
    ap.add_argument("--host-cap", type=int, default=4,
                    help="同一 host 的最大并发，避开 CDN 限流造成的假 timeout")
    ap.add_argument("--checkpoint", type=int, default=200,
                    help="每处理多少条落一次盘")
    ap.add_argument("--proxy", default=None,
                    help="如 http://127.0.0.1:7897；不传则沿用环境变量")
    ap.add_argument("--no-env-proxy", action="store_true",
                    help="忽略 HTTPS_PROXY 等环境变量，直连")
    ap.add_argument("--retry-unknown", action="store_true",
                    help="只重跑已判 UNKNOWN 的条目（建议配合低并发 + 大超时）")
    ap.add_argument("--unknown-from", type=Path, default=None,
                    help="只测另一份结果文件里判为 UNKNOWN 的流；用于换网络环境复测，"
                         "结果写入 --state，不覆盖原结果")
    ap.add_argument("--force-new", action="store_true",
                    help="assets 指纹变化时清空旧结果重跑")
    ap.add_argument("--limit", type=int, default=0, help="本次最多测多少条，0 = 不限")
    ap.add_argument("--only-language", action="append", default=[],
                    help="只测 language 以该前缀开头的台，可重复，如 zho / eng")
    ap.add_argument("--only-country", action="append", default=[],
                    help="只测该 country 的台，可重复")
    ap.add_argument("--only-direct", action="store_true", help="只测无频道归属的 direct 流")
    ap.add_argument("--skip-direct", action="store_true", help="跳过 direct 流")
    ap.add_argument("--name-contains", default="", help="只测台名含该串的流（忽略大小写）")


def main(argv=None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    if not argv or argv[0].startswith("-"):
        argv = ["probe"] + argv
    ap = argparse.ArgumentParser(description="IPTV 内置清单批量可用性体检（只读 assets）")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p_probe = sub.add_parser("probe", help="并发探测，结果可断点续跑")
    add_probe_args(p_probe)
    p_probe.set_defaults(func=cmd_probe)

    p_report = sub.add_parser("report", help="汇总黑白名单与人读报告")
    add_common_args(p_report)
    p_report.add_argument("--report", type=Path, default=DEFAULT_REPORT)
    p_report.add_argument("--whitelist", type=Path, default=DEFAULT_WHITELIST)
    p_report.add_argument("--blacklist", type=Path, default=DEFAULT_BLACKLIST)
    p_report.set_defaults(func=cmd_report)

    p_prune = sub.add_parser("prune", help="按结果生成精简清单（不写 assets）")
    add_common_args(p_prune)
    p_prune.add_argument("--out", type=Path, required=True)
    p_prune.set_defaults(func=cmd_prune)

    args = ap.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
