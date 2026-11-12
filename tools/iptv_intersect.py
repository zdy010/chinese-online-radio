"""两轮严格探测的交叉比对：取「有代理 / 无代理都验到内容」的交集。

为什么需要它：单看一轮结论分不清「源本身死了」和「本机网络到不了」。
让同一批流在两种网络环境下用**完全相同的判定参数**各跑一次严格模式
（--workers / --timeout / --host-cap 必须一致，只换代理通道），
两轮都验到分片/魔数的才算高置信可用，这就是过滤准确度的来源。

红线（与 station_probe / recheck_stations 一脉相承）：
  UNKNOWN（超时 / 403 / 5xx / 鉴权 / 未测）**既不算活也不算死**。
  只有两轮都明确 GONE 才进「确证不可用」；把 UNKNOWN 当死源会把
  「被墙」「同站限流」误杀成坏源。单边 LIVE 的归「环境相关」，
  由产品决定目标用户是否带代理，本工具不擅自剔除。

输入是两份独立的 probe 结果文件，输出交集报告；**不修改 assets**。

用法：
    # 直连轮（当前）
    python tools/iptv_probe.py probe --state .superpowers/tmp/iptv/probe/strict_direct.json \
        --workers 24 --timeout 10 --host-cap 4 --no-env-proxy

    # 代理轮（用户打开代理后，参数保持一致，只换通道）
    python tools/iptv_probe.py probe --state .superpowers/tmp/iptv/probe/strict_proxy.json \
        --workers 24 --timeout 10 --host-cap 4 --no-env-proxy --proxy http://127.0.0.1:7897

    # 交叉比对
    python tools/iptv_intersect.py
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path

try:
    for _stream in (sys.stdout, sys.stderr):
        _stream.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

import iptv_probe as ip

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_ASSETS = ROOT / "app/src/main/assets/iptv_channels.json"
PROBE_DIR = ROOT / ".superpowers/tmp/iptv/probe"
DEFAULT_DIRECT = PROBE_DIR / "strict_direct.json"
DEFAULT_PROXY = PROBE_DIR / "strict_proxy.json"
DEFAULT_OUT = PROBE_DIR / "intersect_result.json"
DEFAULT_REPORT = PROBE_DIR / "intersect_report.txt"

SCHEMA_VERSION = 1
BOTH_LIVE = "both-live"
DIRECT_ONLY = "direct-only"
PROXY_ONLY = "proxy-only"
BOTH_GONE = "both-gone"
UNDETERMINED = "undetermined"
STREAM_CLASSES = (BOTH_LIVE, DIRECT_ONLY, PROXY_ONLY, BOTH_GONE, UNDETERMINED)

SOLID = "solid"          # 所有源两轮都通过
USABLE = "usable"        # 至少一个源两轮都通过
ENV_ONLY = "environment-only"  # 只在某一个网络环境下通过
STATION_CLASSES = (SOLID, USABLE, ENV_ONLY, BOTH_GONE, UNDETERMINED)


def load_probe(path: Path, label: str, fingerprint: str) -> dict:
    if not path.exists():
        raise SystemExit(f"{label}轮结果不存在：{path}")
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("assetsSha256") != fingerprint:
        raise SystemExit(f"{label}轮的 assets 指纹与当前清单不一致，拒绝混用两版结论")
    modes = set(data.get("modes") or [])
    if "strict" not in modes or "fast" in modes:
        raise SystemExit(
            f"{label}轮不是纯严格模式（modes={sorted(modes)}）。"
            "宽松口径只认「收到数据就算活」，不能拿来做交集"
        )
    return data


def classify_stream(direct: dict | None, proxy: dict | None) -> str:
    # 有一轮没测过就别下象限结论：把「代理未测」标成「仅直连通过」，
    # 会让人误以为代理环境下验证过且不可用。
    if not direct or not proxy:
        return UNDETERMINED
    dv = direct.get("verdict")
    pv = proxy.get("verdict")
    if dv == ip.LIVE and pv == ip.LIVE:
        return BOTH_LIVE
    if dv == ip.LIVE:
        return DIRECT_ONLY
    if pv == ip.LIVE:
        return PROXY_ONLY
    # 只有两轮都确证 GONE 才判死；UNKNOWN / 未测 / GONE+UNKNOWN 混合一律不打标签
    if dv == ip.GONE and pv == ip.GONE:
        return BOTH_GONE
    return UNDETERMINED


def classify_station(verdicts: list) -> str:
    if not verdicts:
        return UNDETERMINED
    if all(v == BOTH_LIVE for v in verdicts):
        return SOLID
    if any(v == BOTH_LIVE for v in verdicts):
        return USABLE
    if all(v == BOTH_GONE for v in verdicts):
        return BOTH_GONE
    if any(v in (DIRECT_ONLY, PROXY_ONLY) for v in verdicts):
        return ENV_ONLY
    return UNDETERMINED


def intersect(stations: list, stream_keys: dict, stream_meta: dict,
              direct: dict, proxy: dict, direct_path: Path, proxy_path: Path,
              fingerprint: str) -> dict:
    de, pe = direct.get("entries", {}), proxy.get("entries", {})
    stream_classes = {
        key: classify_stream(de.get(key), pe.get(key))
        for keys in stream_keys.values() for key in keys
    }
    stream_counts = Counter(stream_classes.values())
    station_classes = {
        st["id"]: classify_station(
            [stream_classes[k] for k in stream_keys.get(st["id"], [])])
        for st in stations
    }
    station_counts = Counter(station_classes.values())
    return {
        "schemaVersion": SCHEMA_VERSION,
        "assetsSha256": fingerprint,
        "criterion": (
            "两轮严格模式（验 HLS 分片 / master 下钻 / DASH / 媒体魔数）都判 LIVE 才算高置信可用；"
            "只有两轮都 GONE 才算确证不可用；UNKNOWN 与单边通过均不自动剔除。"
        ),
        "direct": {
            "path": str(direct_path), "modes": direct.get("modes", []),
            "tested": len(de), "counts": dict(Counter(
                e.get("verdict") for e in de.values())),
        },
        "proxy": {
            "path": str(proxy_path), "modes": proxy.get("modes", []),
            "tested": len(pe), "counts": dict(Counter(
                e.get("verdict") for e in pe.values())),
        },
        "streamTotal": len(stream_classes),
        "streamCounts": {c: stream_counts.get(c, 0) for c in STREAM_CLASSES},
        "stationTotal": len(stations),
        "stationCounts": {c: station_counts.get(c, 0) for c in STATION_CLASSES},
        "coverage": {
            "directTested": sum(1 for k in stream_classes if k in de),
            "proxyTested": sum(1 for k in stream_classes if k in pe),
            "bothTested": sum(1 for k in stream_classes if k in de and k in pe),
        },
        "streamClasses": {
            c: sorted(k for k, v in stream_classes.items() if v == c)
            for c in STREAM_CLASSES
        },
        "stationClasses": {
            c: sorted(i for i, v in station_classes.items() if v == c)
            for c in STATION_CLASSES
        },
        "streamUrls": {k: v["url"] for k, v in stream_meta.items()},
    }


def render_report(result: dict) -> str:
    sc, tc, cov = result["streamCounts"], result["stationCounts"], result["coverage"]
    urls = result["streamUrls"]
    lines = [
        "IPTV 双网络环境严格探测交叉比对",
        f"assets sha256 : {result['assetsSha256']}",
        f"直连轮: 已测 {result['direct']['tested']} 条，modes={result['direct']['modes']}",
        f"代理轮: 已测 {result['proxy']['tested']} 条，modes={result['proxy']['modes']}",
        f"当前清单 {result['streamTotal']} 条流 / {result['stationTotal']} 台；"
        f"两轮都测过 {cov['bothTested']} 条",
        "",
        "流级：",
        f"  两轮都验到内容 (both-live) {sc[BOTH_LIVE]}",
        f"  仅直连通过      (direct-only) {sc[DIRECT_ONLY]}",
        f"  仅代理通过      (proxy-only)  {sc[PROXY_ONLY]}",
        f"  两轮都确证失效  (both-gone)  {sc[BOTH_GONE]}",
        f"  未表态          (undetermined) {sc[UNDETERMINED]}",
        "",
        "台级：",
        f"  所有源都双通过  (solid)       {tc[SOLID]}",
        f"  至少一源双通过  (usable)      {tc[USABLE]}",
        f"  只在单边环境可用 (environment-only) {tc[ENV_ONLY]}",
        f"  全部源两轮确证失效 (both-gone) {tc[BOTH_GONE]}",
        f"  未表态          (undetermined) {tc[UNDETERMINED]}",
        "",
        "口径：UNKNOWN（超时 / 403 / 5xx / 鉴权 / 未测）既不算活也不算死；",
        "      只有两轮都明确 GONE 才进确证不可用，避免把「被墙」「同站限流」误杀。",
        "      单边通过的台属于产品决策（目标用户是否带代理），本工具不擅自剔除。",
        "",
    ]
    if cov["bothTested"] < result["streamTotal"]:
        lines.append(f"注意：还有 {result['streamTotal'] - cov['bothTested']} 条流没被两轮都测过，"
                     "交集是保守下界；补齐后再下结论。")
        lines.append("")
    for label, cls in (("两轮都确证失效的流", BOTH_GONE),
                       ("仅直连通过（代理下反而不可用）", DIRECT_ONLY)):
        keys = result["streamClasses"][cls]
        if keys:
            lines.append(f"{label} {len(keys)} 条，前 10：")
            for k in keys[:10]:
                lines.append(f"   {urls.get(k, k)}")
            lines.append("")
    return "\n".join(lines) + "\n"


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="两轮严格探测结果的双环境交集比对（只读 assets）")
    ap.add_argument("--assets", type=Path, default=DEFAULT_ASSETS)
    ap.add_argument("--direct", type=Path, default=DEFAULT_DIRECT)
    ap.add_argument("--proxy", type=Path, default=DEFAULT_PROXY)
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    ap.add_argument("--report", type=Path, default=DEFAULT_REPORT)
    args = ap.parse_args(list(sys.argv[1:] if argv is None else argv))

    data, fingerprint = ip._load_assets(args.assets)
    stations = data["stations"]
    stream_meta, stream_keys = ip.collect_streams(stations)
    direct = load_probe(args.direct, "直连", fingerprint)
    proxy = load_probe(args.proxy, "代理", fingerprint)
    result = intersect(stations, stream_keys, stream_meta, direct, proxy,
                       args.direct, args.proxy, fingerprint)
    ip.atomic_write_json(args.out, result)
    ip.atomic_write_text(args.report, render_report(result))
    print(render_report(result))
    print(f"结果 {args.out}\n报告 {args.report}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
