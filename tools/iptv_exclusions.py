"""IPTV「确证不可用」的持久排除名单：剔除留痕，且下次同步不许再加回来。

为什么需要一份入仓的名单而不只是改一次 assets：
app/src/main/assets/iptv_channels.json 是 tools/iptv_sync.ps1 从 iptv-org 全量生成的，
手工删掉的台/源，下一次重跑同步会原样写回。所以剔除必须同时做三件事：
  1. 把每个被剔台和它的每一条流、每一个判定原因记进 tools/iptv_exclusions.json（入仓）；
  2. 让 tools/iptv_sync.ps1 读这份名单：整台按 id 拦、坏源按 url 逐条剔除，并断言零残留；
  3. verify 子命令校验当前 assets 与名单零交集，作为防回归闸门。

两类准入，都不能放宽：
  A. all-sources-gone：所有源都已探测且全部 GONE（HTTP 404/410）。
  B. unreachable-both-network-modes：源在「代理开启」时判 UNKNOWN（超时/连不上/空响应），
     换到「代理关闭直连」用**同一套判定参数**复测仍拿不到数据（UNKNOWN 或 GONE）。
     只要直连能收到数据就判 LIVE 保留，绝不因「代理下不通」就剔。
  403/429、5xx、错误页、宽松口径下的假 LIVE 一律不算「不可用」，绝不进这份名单。
  宁可少剔，不可误杀。

名单分两层，不能混：
  entries[]          整台排除（按 id 拦），每条带逐源证据；
  streamExclusions[] 只剔一条坏源、台本身还有好源（按 url 拦）。
  若把后者塞进 entries，同步脚本会把那些仍可播的台整台拦掉 —— 那是误杀。

用法：
    python tools/iptv_exclusions.py verify
    python tools/iptv_exclusions.py build
    python tools/iptv_exclusions.py apply --dry-run
    python tools/iptv_exclusions.py apply
    python tools/iptv_exclusions.py apply --recheck .superpowers/tmp/iptv/probe/direct_result.json

apply 幂等：名单按 id / url 合并而不是覆盖，二次执行不会把已剔除项抹掉。
替换 assets 前跑一遍与 IptvAssetsGateTest 对齐的数据闸门，任一不通过就拒绝写入。
原始清单备份到 .superpowers/tmp/iptv/probe/assets_backup_<sha12>.json（已 gitignore）。
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

try:
    for _stream in (sys.stdout, sys.stderr):
        _stream.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

import iptv_probe as ip

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_ASSETS = ROOT / "app/src/main/assets/iptv_channels.json"
DEFAULT_STATE = ROOT / ".superpowers/tmp/iptv/probe/probe_result.json"
DEFAULT_EXCLUSIONS = Path(__file__).resolve().parent / "iptv_exclusions.json"
BACKUP_DIR = ROOT / ".superpowers/tmp/iptv/probe"

SCHEMA_VERSION = 1  # 结构未变，只是新增 reason 枚举值与 streamExclusions 字段
REASON_ALL_GONE = "all-sources-gone"
REASON_BOTH_UNREACHABLE = "unreachable-both-network-modes"
REASON_BOTH_GONE = "both-network-modes-gone"
ALLOWED_REASONS = {REASON_ALL_GONE, REASON_BOTH_UNREACHABLE, REASON_BOTH_GONE}
SYNTHETIC_WHY = "both-network-modes-unreachable"
SYNTHETIC_GONE_WHY = "both-network-modes-gone"

MIN_STATIONS = 10_000  # 与 IptvAssetsGateTest、iptv_sync.ps1 的下限保持一致
MIN_ALIASES = 100      # 与 IptvAssetsGateTest「带别名的条目 > 100」一致
MIN_HEADERS = 50       # 与 IptvAssetsGateTest「带 header 的流 >= 50」一致
MIN_BYTES = 1_000_000


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def load_exclusions(path: Path) -> dict:
    if not path.exists():
        return {"schemaVersion": SCHEMA_VERSION, "entries": [], "streamExclusions": []}
    doc = json.loads(path.read_text(encoding="utf-8"))
    if doc.get("schemaVersion") != SCHEMA_VERSION:
        raise SystemExit(f"排除名单 schemaVersion={doc.get('schemaVersion')}，只认 {SCHEMA_VERSION}")
    if not isinstance(doc.get("entries"), list):
        raise SystemExit("排除名单缺 entries 数组，拒绝继续")
    doc.setdefault("streamExclusions", [])
    return doc


def write_exclusions(path: Path, doc: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    import os
    os.replace(tmp, path)


def _http_status(entry: dict):
    m = re.search(r"status=(\d{3})", entry.get("detail", "") or "")
    if m:
        return int(m.group(1))
    m = re.search(r"http-(\d{3})", entry.get("why", "") or "")
    return int(m.group(1)) if m else None


def _probe_row(network: str, e: dict) -> dict:
    return {
        "network": network,
        "verdict": e.get("verdict", ""),
        "why": e.get("why", ""),
        "httpStatus": _http_status(e),
        "mode": e.get("mode", ""),
        "detail": e.get("detail", ""),
        "elapsed": e.get("elapsed", 0),
    }


# ---------------------------------------------------------------- A 类：404/410


def build_entries(stations: list, station_keys: dict, streams: dict,
                  entries: dict, state: dict) -> list:
    """把台级 GONE 的每一台连同逐源证据展开成名单条目。

    逐源复核是刻意的：summarize 的 GONE 只说明「没有 LIVE/UNKNOWN 且全测过」，
    这里再钉一次「每条都确为 GONE」，防止上游判定口径一改就悄悄放宽准入。
    """
    summary = ip.summarize(stations, station_keys, streams, entries)
    gone_ids = set(summary["byVerdict"][ip.GONE])
    checked_at = state.get("updatedAt", "")
    out = []
    for st in stations:
        sid = st["id"]
        if sid not in gone_ids:
            continue
        keys = station_keys.get(sid, [])
        if not keys:
            continue
        rows = []
        for key in keys:
            e = entries.get(key)
            if not e or e.get("verdict") != ip.GONE:
                raise SystemExit(f"{sid} 存在未确证为 GONE 的源，拒绝写入排除名单")
            rows.append({
                "url": streams[key]["url"],
                "verdict": e.get("verdict"),
                "why": e.get("why", ""),
                "httpStatus": _http_status(e),
                "mode": e.get("mode", ""),
                "detail": e.get("detail", ""),
                "elapsed": e.get("elapsed", 0),
            })
        statuses = "、".join(
            f"HTTP {r['httpStatus']}" if r["httpStatus"] else (r["why"] or "GONE")
            for r in rows
        )
        out.append({
            "id": sid,
            "name": st.get("name", ""),
            "country": st.get("country", ""),
            "language": st.get("language", ""),
            "reason": REASON_ALL_GONE,
            "reasonDetail": (
                f"全部 {len(rows)} 个源在 {checked_at} 的实测中判定 GONE（{statuses}）；"
                "超时 / 连不上 / 403 / 429 / 5xx / 错误页一律不算 GONE，因此不进本名单。"
            ),
            "urls": [r["url"] for r in rows],
            "streams": rows,
            "excludedAt": checked_at,
        })
    return out


# ---------------------------------------------------------------- B 类：双网络环境都不可达


def recheck_dead_keys(baseline: dict, recheck: dict) -> dict:
    """返回 {key: (代理开结论, 代理关结论)}，只含两种环境都拿不到数据的流。

    三条不能省：
    - 只挑 baseline 的 UNKNOWN；已经 LIVE 的源根本不需要复测；
    - recheck 判 LIVE（直连收到数据）就保留，绝不因「代理下不通」剔掉好源；
    - recheck 里没有的 key 说明这轮没覆盖，原样留着 UNKNOWN，不动。
    """
    dead = {}
    for key, base in baseline.items():
        if base.get("verdict") != ip.UNKNOWN:
            continue
        r = recheck.get(key)
        if not r or r.get("verdict") == ip.LIVE:
            continue
        dead[key] = (base, r)
    return dead


def synthetic_entries(baseline: dict, dead: dict) -> dict:
    """把双环境不可达的流标成 GONE，交给 build_pruned 删源/删台。

    只在内存里用，绝不写回 state：原始两轮结论保留在 probes 里，不篡改证据。
    """
    out = dict(baseline)
    for key, (base, r) in dead.items():
        out[key] = {
            "verdict": ip.GONE,
            "why": SYNTHETIC_WHY,
            "detail": f"proxy-on={base.get('why', '')}; proxy-off={r.get('why', '')}",
            "mode": "recheck",
            "elapsed": 0,
        }
    return out


def _recheck_reason_detail(dead_rows: list) -> str:
    pairs = "；".join(
        f"代理开 {p['probes'][0]['why'] or p['probes'][0]['verdict']}"
        f" → 代理关直连 {p['probes'][1]['why'] or p['probes'][1]['verdict']}"
        for p in dead_rows
    )
    return (
        f"全部 {len(dead_rows)} 个源在代理开启时为 UNKNOWN，"
        f"关闭代理直连复测（同一套 --fast / 超时 / 并发参数）仍拿不到任何数据"
        f"（{pairs}）。两种网络环境均不可用，故剔除；"
        "只要直连收到数据就判 LIVE 保留，不在此列。"
    )


def _dead_rows(streams: dict, keys: list, dead: dict) -> list:
    rows = []
    for key in keys:
        base, r = dead[key]
        rows.append({
            "url": streams[key]["url"],
            # gone 是「本次处置结论」；两轮原始判定完整保留在 probes
            "verdict": ip.GONE,
            "why": SYNTHETIC_WHY,
            "httpStatus": _http_status(r) or _http_status(base),
            "probes": [_probe_row("proxy-on", base), _probe_row("proxy-off", r)],
            "excludedAt": _now(),
        })
    return rows


def build_recheck_entries(stations: list, station_keys: dict, streams: dict,
                          dead: dict) -> list:
    """整台剔除：该台**所有**源都双环境不可达。有一个源活着就不剔整台。"""
    out = []
    for st in stations:
        keys = station_keys.get(st["id"], [])
        if not keys or not all(k in dead for k in keys):
            continue
        rows = _dead_rows(streams, keys, dead)
        out.append({
            "id": st["id"],
            "name": st.get("name", ""),
            "country": st.get("country", ""),
            "language": st.get("language", ""),
            "reason": REASON_BOTH_UNREACHABLE,
            "reasonDetail": _recheck_reason_detail(rows),
            "urls": [r["url"] for r in rows],
            "streams": rows,
            "excludedAt": _now(),
        })
    return out


def build_stream_exclusions(streams: dict, dead: dict,
                            excluded_station_ids: set) -> list:
    """只剔坏源、台本身还有好源的，单独登记，防止同步脚本按 url 误杀整台。"""
    by_url = {}
    for key, (base, r) in dead.items():
        owners = sorted({s["id"] for s in streams[key]["stations"]
                         if s["id"] not in excluded_station_ids})
        if not owners:  # 所属台已被整台排除，它的 url 已在 entries[].urls 里
            continue
        url = streams[key]["url"]
        if url in by_url:
            by_url[url]["stationIds"] = sorted(set(by_url[url]["stationIds"] + owners))
            continue
        rows = _dead_rows(streams, [key], dead)
        by_url[url] = {
            "url": url,
            "stationIds": owners,
            "reason": REASON_BOTH_UNREACHABLE,
            "reasonDetail": _recheck_reason_detail(rows),
            "probes": rows[0]["probes"],
            "excludedAt": _now(),
        }
    return sorted(by_url.values(), key=lambda e: e["url"])


# ---------------------------------------------------------------- C 类：两轮严格模式都确证失效


def _resolve_probe_path(raw: str) -> Path:
    p = Path(raw)
    return p if p.is_absolute() else ROOT / p


def load_intersect(path: Path, fingerprint: str):
    """读交集结果与它引用的两轮严格探测结论，并做完整性校验。

    注意这是**一次性**操作：交集结果绑定生成时的清单指纹，而 C 类剔除本身
    会改 assets 指纹 —— 剔除后本命令必然被指纹校验拒绝。这是防旧结论套新
    清单的既定设计，不是 bug；要重新剔除得先在当前清单上重跑两轮探测，
    再用 iptv_intersect.py 生成新交集。
    """
    if not path.exists():
        raise SystemExit(f"交集结果不存在：{path}")
    doc = json.loads(path.read_text(encoding="utf-8"))
    if doc.get("assetsSha256") != fingerprint:
        raise SystemExit("交集结果的 assets 指纹与当前清单不一致，拒绝混用两版结论")
    cov = doc.get("coverage", {})
    if cov.get("bothTested", 0) < doc.get("streamTotal", 1):
        raise SystemExit("两轮没有把清单全部测完，交集是残缺的，拒绝据此剔除")
    dpath = _resolve_probe_path(doc["direct"]["path"])
    ppath = _resolve_probe_path(doc["proxy"]["path"])
    if not dpath.exists() or not ppath.exists():
        raise SystemExit(f"交集引用的探测结果缺失：{dpath} / {ppath}")
    return doc, json.loads(dpath.read_text(encoding="utf-8")), json.loads(ppath.read_text(encoding="utf-8"))


def _gone_rows(streams: dict, keys: list, dstate: dict, pstate: dict) -> list:
    rows = []
    for key in keys:
        de = dstate["entries"].get(key)
        pe = pstate["entries"].get(key)
        if not de or not pe or de.get("verdict") != ip.GONE or pe.get("verdict") != ip.GONE:
            raise SystemExit(f"{key} 并非两轮严格模式都 GONE，拒绝写入排除名单")
        rows.append({
            "url": streams[key]["url"],
            "verdict": ip.GONE,  # 处置结论；两轮原始判定在 probes
            "why": SYNTHETIC_GONE_WHY,
            "httpStatus": _http_status(pe) or _http_status(de),
            "probes": [_probe_row("proxy-on", de), _probe_row("proxy-off", pe)],
            "excludedAt": _now(),
        })
    return rows


def build_intersect_entries(stations: list, station_keys: dict, streams: dict,
                            isect: dict, dstate: dict, pstate: dict):
    """C 类：整台剔除 + 只剔坏源分层 + 供 build_pruned 使用的合成结论。"""
    gone_keys = set(isect["streamClasses"]["both-gone"])
    station_ids = set(isect["stationClasses"]["both-gone"])
    out = []
    for st in stations:
        sid = st["id"]
        if sid not in station_ids:
            continue
        keys = station_keys.get(sid, [])
        if not keys or not all(k in gone_keys for k in keys):
            raise SystemExit(f"{sid} 被标为整台失效，但不是所有源都 both-gone")
        rows = _gone_rows(streams, keys, dstate, pstate)
        out.append({
            "id": sid,
            "name": st.get("name", ""),
            "country": st.get("country", ""),
            "language": st.get("language", ""),
            "reason": REASON_BOTH_GONE,
            "reasonDetail": (
                f"全部 {len(rows)} 个源在无代理直连与有代理两轮严格模式"
                "（验 HLS 分片 / master 下钻 / DASH / 媒体魔数，参数完全一致）"
                "均确证 GONE；两种网络环境、两套判定都失效，故剔除。"
            ),
            "urls": [r["url"] for r in rows],
            "streams": rows,
            "excludedAt": _now(),
        })
    stream_entries = {}
    for key in gone_keys:
        owners = sorted({s["id"] for s in streams[key]["stations"]
                         if s["id"] not in station_ids})
        if not owners:
            continue
        url = streams[key]["url"]
        rows = _gone_rows(streams, [key], dstate, pstate)
        stream_entries[url] = {
            "url": url, "stationIds": owners,
            "reason": REASON_BOTH_GONE,
            "reasonDetail": (
                "该源在无代理直连与有代理两轮严格模式下均确证 GONE；"
                "所属频道仍有其它可用源，因此只剔此源、保留频道。"
            ),
            "probes": rows[0]["probes"],
            "excludedAt": _now(),
        }
    # 与 B 类一致按 url 排序：gone_keys 是 set，迭代序受 PYTHONHASHSEED 影响，
    # 不排序的话每次重新生成的入仓 diff 都不一样（见 iptv_sync.ps1 的排序注释）
    return out, sorted(stream_entries.values(), key=lambda e: e["url"]), gone_keys


# ---------------------------------------------------------------- 文档合并与校验


def merge_entries(existing: dict, fresh: list) -> tuple[list, int]:
    """按 id 合并而非覆盖：二次 apply 不能把已经剔除的台从名单里抹掉。"""
    by_id = {e["id"]: e for e in existing.get("entries", [])}
    added = 0
    for e in fresh:
        if e["id"] not in by_id:
            by_id[e["id"]] = e
            added += 1
    return sorted(by_id.values(), key=lambda e: e["id"]), added


def merge_stream_entries(existing: dict, fresh: list) -> tuple[list, int]:
    by_url = {e["url"]: e for e in existing.get("streamExclusions", [])}
    added = 0
    for e in fresh:
        if e["url"] not in by_url:
            by_url[e["url"]] = e
            added += 1
    return sorted(by_url.values(), key=lambda e: e["url"]), added


def make_document(entries: list, state: dict, fingerprint: str,
                  stream_entries: list | None = None,
                  recheck_state: dict | None = None,
                  recheck_path: Path | None = None,
                  intersect_path: Path | None = None) -> dict:
    stream_entries = stream_entries or []
    source = {
        "probeResult": str(DEFAULT_STATE.relative_to(ROOT)).replace("\\", "/"),
        "probeModes": state.get("modes", []),
        "assetsSha256AtProbe": state.get("assetsSha256", ""),
        "rebasedFrom": state.get("rebasedFrom", ""),
        "checkedAt": state.get("updatedAt", ""),
        "assetsSha256AtApply": fingerprint,
    }
    if recheck_state is not None:
        # 命令行常传相对路径；直接 relative_to(ROOT) 会抛 ValueError
        try:
            rel = str((recheck_path or Path(".")).resolve().relative_to(ROOT))
        except (ValueError, OSError):
            rel = str(recheck_path or "(inline)")
        source["recheckResult"] = rel.replace("\\", "/")
        source["recheckedAt"] = recheck_state.get("updatedAt", "")
        source["recheckModes"] = recheck_state.get("modes", [])
    if intersect_path is not None:
        try:
            rel = str(intersect_path.resolve().relative_to(ROOT))
        except (ValueError, OSError):
            rel = str(intersect_path)
        source["intersectResult"] = rel.replace("\\", "/")
    return {
        "schemaVersion": SCHEMA_VERSION,
        "note": (
            "确证不可播的 IPTV 排除名单。由 tools/iptv_exclusions.py 维护，"
            "tools/iptv_sync.ps1 生成清单时按 id 整台剔除、按 url 逐源剔除并断言零残留；"
            "python tools/iptv_exclusions.py verify 可随时校验。请勿手工清空本文件。"
        ),
        "generatedAt": _now(),
        "criterion": (
            "A all-sources-gone：所有源全部 HTTP 404/410；"
            "B unreachable-both-network-modes：代理开启为 UNKNOWN，关闭代理直连复测"
            "（同一套判定参数）仍拿不到数据；"
            "C both-network-modes-gone：无代理直连与有代理两轮严格模式"
            "（验分片/魔数、参数完全一致）都确证 GONE。"
            "任一环境收到数据即保留；403/429/5xx/超时单一环境结果一律不进本名单。"
        ),
        "source": source,
        "count": len(entries),
        "countStreams": len(stream_entries),
        "entries": entries,
        "streamExclusions": stream_entries,
    }


def verify_exclusions(doc: dict, stations: list) -> list:
    """零交集校验：整台名单的 id、两层名单的 url 都不许再出现在清单里。"""
    problems = []
    entries = doc.get("entries", [])
    stream_exclusions = doc.get("streamExclusions", [])
    ids = [e.get("id", "") for e in entries]
    if len(ids) != len(set(ids)):
        problems.append("排除名单里存在重复 id")
    for e in entries:
        eid = e.get("id", "")
        for field in ("id", "reason", "reasonDetail", "urls", "streams", "excludedAt"):
            if not e.get(field):
                problems.append(f"{eid} 缺字段 {field}")
        if e.get("reason") not in ALLOWED_REASONS:
            problems.append(f"{eid} 的 reason 不在准入集合：{e.get('reason')}")
        if e.get("reason") in (REASON_BOTH_UNREACHABLE, REASON_BOTH_GONE):
            for s in e.get("streams", []):
                nets = {p.get("network") for p in s.get("probes", [])}
                if nets != {"proxy-on", "proxy-off"}:
                    problems.append(f"{eid} 缺双网络环境复测证据")
        if not e.get("urls"):
            problems.append(f"{eid} 没有记录任何流地址，下次同步无法按 URL 兜底拦截")
        for s in e.get("streams", []):
            if s.get("verdict") != ip.GONE:
                problems.append(f"{eid} 的某条源不是 GONE，准入被放宽了")

    ex_ids = {e.get("id", "") for e in entries}
    ex_urls = {u for e in entries for u in e.get("urls", [])}
    ex_urls |= {e.get("url", "") for e in stream_exclusions}
    for e in stream_exclusions:
        for field in ("url", "reason", "reasonDetail", "probes", "excludedAt"):
            if not e.get(field):
                problems.append(f"流级排除项缺字段 {field}：{e.get('url', '')[:60]}")
        if e.get("reason") not in ALLOWED_REASONS:
            problems.append(f"流级排除项 reason 越界：{e.get('url', '')[:60]}")

    cur_ids = {st["id"] for st in stations}
    cur_urls = {(s.get("url") or "").strip()
                for st in stations for s in (st.get("sources") or [])}
    cur_urls |= {(st.get("streamUrl") or "").strip() for st in stations}
    cur_urls.discard("")
    leaked_ids = ex_ids & cur_ids
    leaked_urls = ex_urls & cur_urls
    if leaked_ids:
        problems.append(f"清单里仍有 {len(leaked_ids)} 个已排除频道，例：{sorted(leaked_ids)[:3]}")
    if leaked_urls:
        problems.append(f"清单里仍有 {len(leaked_urls)} 条已排除流地址，例：{sorted(leaked_urls)[:2]}")
    return problems


def validate_pruned(pruned: list, original: list, doc: dict) -> list:
    """写回 assets 前的闸门，逐条对齐 IptvAssetsGateTest.kt。"""
    problems = []
    if len(pruned) < MIN_STATIONS:
        problems.append(f"精简后只有 {len(pruned)} 台，低于下限 {MIN_STATIONS}")
    ids = [s["id"] for s in pruned]
    if len(ids) != len(set(ids)):
        problems.append("精简后 id 有重复")
    names = [s.get("name", "") for s in pruned]
    if len(names) != len(set(names)) or any(not n.strip() for n in names):
        problems.append("精简后有重名或空名")
    if any(not i.startswith("iptv:") for i in ids):
        problems.append("精简后有 id 不带 iptv: 前缀")
    if any(not (s.get("sources") or []) for s in pruned):
        problems.append("精简后有无源条目")
    if any(s.get("category") != "general" for s in pruned):
        problems.append("精简后 category 不全是 general")
    if sum(1 for s in pruned if s.get("altNames")) <= MIN_ALIASES:
        problems.append(f"精简后带别名的条目不足 {MIN_ALIASES + 1}，疑似丢字段")
    headers = sum(1 for s in pruned for x in (s.get("sources") or [])
                  if (x.get("userAgent") or "").strip() or (x.get("referrer") or "").strip())
    if headers < MIN_HEADERS:
        problems.append(f"精简后带 header 的流只有 {headers} 条，低于基线 {MIN_HEADERS}")
    for s in pruned:
        srcs = s.get("sources") or []
        if srcs and (s.get("streamUrl") or "").strip() != (srcs[0].get("url") or "").strip():
            problems.append(f"{s['id']} 的 streamUrl 与首个源不一致")
    orig_ids = {s["id"] for s in original}
    ex_ids = {e.get("id", "") for e in doc.get("entries", [])}
    expected = orig_ids - ex_ids
    if set(ids) != expected:
        problems.append(
            f"剔除集合不精确：多 {len(set(ids) - expected)} 台、少 {len(expected - set(ids))} 台"
        )
    problems.extend(verify_exclusions(doc, pruned))
    return problems


# ---------------------------------------------------------------- 子命令


def _load(args):
    data, fingerprint = ip._load_assets(args.assets)
    stations = data["stations"]
    streams, keys = ip.collect_streams(stations)
    state = ip.load_state(args.state, fingerprint, args.assets,
                          rebase_keys=set(streams) if args.rebase else None)
    return stations, streams, keys, state, fingerprint


def _load_recheck(path: Path, fingerprint: str) -> dict:
    if not path.exists():
        raise SystemExit(f"复测结果不存在：{path}")
    d = json.loads(path.read_text(encoding="utf-8"))
    if d.get("assetsSha256") != fingerprint:
        raise SystemExit("复测结果的 assets 指纹与当前清单不一致，拒绝混用两版结论")
    return d


def _plan(args, stations, streams, keys, state, fingerprint):
    """返回 (fresh台级条目, fresh流级条目, 用于 build_pruned 的 entries, 复测结果, 现有名单)。"""
    existing = load_exclusions(args.exclusions)
    if args.recheck and args.intersect:
        raise SystemExit("--recheck 与 --intersect 只能选一个，避免两种口径混算")

    if args.intersect:
        isect, dstate, pstate = load_intersect(args.intersect, fingerprint)
        fresh, stream_fresh, gone_keys = build_intersect_entries(
            stations, keys, streams, isect, dstate, pstate)
        effective = dict(state["entries"])
        for k in gone_keys:
            effective[k] = {
                "verdict": ip.GONE, "why": SYNTHETIC_GONE_WHY,
                "detail": "strict-direct=gone; strict-proxy=gone",
                "mode": "strict-intersect", "elapsed": 0,
            }
        print(f"两轮严格模式都 GONE 的流 {len(gone_keys)} 条 → "
              f"整台剔除 {len(fresh)} 台、逐源剔除 {len(stream_fresh)} 条",
              file=sys.stderr)
        return fresh, stream_fresh, effective, None, existing

    if not args.recheck:
        fresh = build_entries(stations, keys, streams, state["entries"], state)
        return fresh, [], state["entries"], None, existing

    recheck_state = _load_recheck(args.recheck, fingerprint)
    rechecked = recheck_state.get("entries", {})
    if not rechecked:
        raise SystemExit("复测结果里一条结论都没有，拒绝据此剔除")
    dead = recheck_dead_keys(state["entries"], rechecked)
    if not dead:
        print("两轮结果交叉比对：没有双环境都不可达的流，无需追加剔除", file=sys.stderr)
        return [], [], state["entries"], recheck_state, existing
    effective = synthetic_entries(state["entries"], dead)
    station_fresh = build_recheck_entries(stations, keys, streams, dead)
    excluded_ids = {e["id"] for e in station_fresh}
    stream_fresh = build_stream_exclusions(streams, dead, excluded_ids)
    print(f"双环境都不可达的流 {len(dead)} 条 → 整台剔除 {len(station_fresh)} 台、"
          f"逐源剔除 {len(stream_fresh)} 条；直连救回 "
          f"{sum(1 for k, v in rechecked.items() if v.get('verdict') == ip.LIVE)} 条",
          file=sys.stderr)
    return station_fresh, stream_fresh, effective, recheck_state, existing


def cmd_build(args) -> int:
    stations, streams, keys, state, fingerprint = _load(args)
    fresh, stream_fresh, _, recheck_state, existing = _plan(
        args, stations, streams, keys, state, fingerprint)
    merged, added = merge_entries(existing, fresh)
    merged_streams, stream_added = merge_stream_entries(existing, stream_fresh)
    doc = make_document(merged, state, fingerprint, merged_streams,
                        recheck_state, args.recheck, args.intersect)
    problems = verify_exclusions(doc, stations)
    structural = [p for p in problems if "清单里仍有" not in p]
    if structural:
        for p in structural:
            print("名单校验失败：" + p, file=sys.stderr)
        return 1
    print(f"本次新检出 {added} 台 / {stream_added} 条坏源，"
          f"名单合计 {len(merged)} 台 / {len(merged_streams)} 条源", file=sys.stderr)
    write_exclusions(args.exclusions, doc)
    print(f"已写 {args.exclusions}", file=sys.stderr)
    return 0


def cmd_apply(args) -> int:
    stations, streams, keys, state, fingerprint = _load(args)
    fresh, stream_fresh, effective, recheck_state, existing = _plan(
        args, stations, streams, keys, state, fingerprint)
    merged, added = merge_entries(existing, fresh)
    merged_streams, stream_added = merge_stream_entries(existing, stream_fresh)
    doc = make_document(merged, state, fingerprint, merged_streams,
                        recheck_state, args.recheck, args.intersect)
    # 只删不改顺序：宽松口径的 LIVE 可能是 403 错误页，不配被提到主源
    pruned, stats = ip.build_pruned(stations, keys, effective, promote_live=False)
    problems = validate_pruned(pruned, stations, doc)
    if problems:
        for p in problems:
            print("校验失败：" + p, file=sys.stderr)
        raise SystemExit("拒绝写入 assets：先把上面的问题修掉")
    payload = json.dumps({"stations": pruned}, ensure_ascii=False, separators=(",", ":"))
    if len(payload.encode("utf-8")) < MIN_BYTES:
        raise SystemExit("精简清单不足 1MB，疑似残缺，拒绝写入")

    print(f"排除名单：新增 {added} 台 / {stream_added} 条坏源，"
          f"合计 {len(merged)} 台 / {len(merged_streams)} 条源", file=sys.stderr)
    print(f"清单：{len(stations)} → {len(pruned)} 台；"
          f"删台 {stats['droppedStations']}、删坏源 {stats['droppedSources']}", file=sys.stderr)
    if args.dry_run:
        print("--dry-run：全部校验通过，未写任何文件", file=sys.stderr)
        return 0

    write_exclusions(args.exclusions, doc)
    backup = BACKUP_DIR / f"assets_backup_{fingerprint[:12]}.json"
    backup.parent.mkdir(parents=True, exist_ok=True)
    backup.write_bytes(args.assets.read_bytes())
    ip.atomic_write_json(args.assets, {"stations": pruned})

    # 结果文件跟着新清单对齐：丢掉已不在清单里的流的结论、换上新指纹；
    # 直连复测救回的源把 UNKNOWN 更新成 LIVE，免得下次报告还说它「未表态」。
    new_streams, _ = ip.collect_streams(pruned)
    rechecked = (recheck_state or {}).get("entries", {})
    cleaned = {}
    for k, v in state["entries"].items():
        if k not in new_streams:
            continue
        if v.get("verdict") == ip.UNKNOWN and rechecked.get(k, {}).get("verdict") == ip.LIVE:
            cleaned[k] = dict(rechecked[k], mode="fast-direct")
        else:
            cleaned[k] = v
    state["entries"] = cleaned
    state["assetsSha256"] = ip.sha256_file(args.assets)
    if recheck_state is not None:
        modes = set(state.get("modes") or []) | {"fast-direct"}
        state["modes"] = sorted(modes)
    state["appliedExclusions"] = {
        "at": _now(), "fromSha256": fingerprint,
        "excludedStations": len(merged), "excludedStreams": len(merged_streams),
        "removedThisRun": stats["droppedStations"], "droppedSourcesThisRun": stats["droppedSources"],
    }
    ip.save_state(args.state, state)
    print(f"已写 {args.exclusions} 与 {args.assets}；原清单备份 {backup}", file=sys.stderr)
    print("下一步：python tools/iptv_exclusions.py verify，并跑 IptvAssetsGateTest",
          file=sys.stderr)
    return 0


def cmd_verify(args) -> int:
    doc = load_exclusions(args.exclusions)
    data, _ = ip._load_assets(args.assets)
    problems = verify_exclusions(doc, data["stations"])
    if problems:
        for p in problems:
            print("防回归校验失败：" + p, file=sys.stderr)
        return 1
    print(f"通过：清单 {len(data['stations'])} 台，排除 {doc.get('count', 0)} 台 / "
          f"{doc.get('countStreams', 0)} 条源，零交集")
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="IPTV 确证不可用的持久排除名单")
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name, help_text, func in (
        ("build", "只生成/追加排除名单，不动 assets", cmd_build),
        ("apply", "校验 + 备份 + 写名单 + 原子替换 assets", cmd_apply),
        ("verify", "防回归校验：清单与名单零交集", cmd_verify),
    ):
        p = sub.add_parser(name, help=help_text)
        p.add_argument("--assets", type=Path, default=DEFAULT_ASSETS)
        p.add_argument("--state", type=Path, default=DEFAULT_STATE)
        p.add_argument("--exclusions", type=Path, default=DEFAULT_EXCLUSIONS)
        p.add_argument("--rebase", action="store_true",
                       help="assets 指纹变了但流集合一致时对齐结果文件")
        p.add_argument("--recheck", type=Path, default=None,
                       help="直连复测结果文件；与代理开的结果交叉比对，"
                            "只有两种环境都拿不到数据才追加剔除")
        p.add_argument("--intersect", type=Path, default=None,
                       help="两轮严格探测的交集结果；据此剔除两轮都确证 GONE 的台与源")
        if name == "apply":
            p.add_argument("--dry-run", action="store_true",
                           help="只跑全部校验，不写名单、不备份、不替换 assets")
        p.set_defaults(func=func)
    args = ap.parse_args(list(sys.argv[1:] if argv is None else argv))
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
