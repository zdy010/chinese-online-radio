"""IPTV「只有 VPN/代理才能访问」频道标记：生成名单、写入 assets、防回归校验。

判定口径（台级，比流级 proxy-only 更贴合「这个节目只有 VPN 才能看」）：
  该台**所有**源在无代理直连的严格探测中都没通过，
  且**至少一个**源在有代理的严格探测中通过。
  两轮用完全相同的 --workers/--timeout/--host-cap/判定参数，只换网络通道。

为什么不是直接拿 intersect_result.json 的 proxy-only 流：
  一台可能有多个源，其中一条只在代理下活、另一条两边都活 —— 这台不算「需 VPN」。
  按台聚合才不会出现「明明直连能看却挂了需 VPN 标记」。

注意：两轮探测是在剔除 145 台之前的清单上做的，assets 指纹与当前不同。
因此不用指纹相等做校验，改用「当前清单每条流都被两轮覆盖」——
被剔除台的流不在当前清单里，自然被忽略；少一条覆盖就拒绝生成。

用法：
    python tools/iptv_vpn_only.py build     # 生成 tools/iptv_vpn_only.json
    python tools/iptv_vpn_only.py apply     # 给 assets 写 vpnOnly 字段（备份 + 原子写）
    python tools/iptv_vpn_only.py verify    # 防回归：assets 标记与名单完全一致

tools/iptv_sync.ps1 会消费这份名单，下次同步自动给频道重新打标，不会被覆盖掉。
"""
# pyright: basic
# 本目录脚本统一用裸 dict/list 注解与同目录 import（见 iptv_exclusions.py、
# recheck_stations.py），严格类型标注的告警不适用于这套既有的数据脚本风格。
from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

try:
    for _stream in (sys.stdout, sys.stderr):
        _stream.reconfigure(encoding="utf-8", errors="replace")  # pyright: ignore[reportAttributeAccessIssue]
except Exception:
    pass

import iptv_probe as ip

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_ASSETS = ROOT / "app/src/main/assets/iptv_channels.json"
PROBE_DIR = ROOT / ".superpowers/tmp/iptv/probe"
DEFAULT_DIRECT = PROBE_DIR / "strict_direct.json"
DEFAULT_PROXY = PROBE_DIR / "strict_proxy.json"
DEFAULT_LIST = ROOT / "tools/iptv_vpn_only.json"
BACKUP_DIR = PROBE_DIR

SCHEMA_VERSION = 1


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def load_states(direct_path: Path, proxy_path: Path):
    for p, label in ((direct_path, "直连轮"), (proxy_path, "代理轮")):
        if not p.exists():
            raise SystemExit(f"{label}结果不存在：{p}")
    d = json.loads(direct_path.read_text(encoding="utf-8"))
    p = json.loads(proxy_path.read_text(encoding="utf-8"))
    for doc, label in ((d, "直连轮"), (p, "代理轮")):
        modes = set(doc.get("modes") or [])
        if "strict" not in modes or "fast" in modes:
            raise SystemExit(f"{label}不是纯严格模式（modes={sorted(modes)}），不能用来判定可用性")
    if d.get("assetsSha256") != p.get("assetsSha256"):
        raise SystemExit("两轮的 assets 指纹不同，不是同一批清单，拒绝混用")
    return d, p


def assert_covered(streams: dict, d: dict, p: dict) -> None:
    keys = set(streams)
    missing_d = keys - set(d.get("entries", {}))
    missing_p = keys - set(p.get("entries", {}))
    if missing_d or missing_p:
        raise SystemExit(
            f"当前清单有 {len(missing_d | missing_p)} 条流没被两轮都测过，"
            "拒绝生成不完整的标记"
        )


def build_ids(stations: list, station_keys: dict, d: dict, p: dict) -> list:
    de, pe = d["entries"], p["entries"]
    out = []
    for st in stations:
        keys = station_keys.get(st["id"], [])
        if not keys:
            continue
        direct_live = any(de.get(k, {}).get("verdict") == ip.LIVE for k in keys)
        proxy_live = any(pe.get(k, {}).get("verdict") == ip.LIVE for k in keys)
        if not direct_live and proxy_live:
            out.append(st["id"])
    return sorted(out)


def make_document(ids: list, d: dict, p: dict, fingerprint: str,
                  direct_path: Path, proxy_path: Path) -> dict:
    return {
        "schemaVersion": SCHEMA_VERSION,
        "note": (
            "只有 VPN/代理才能访问的 IPTV 频道。由 tools/iptv_vpn_only.py 维护，"
            "tools/iptv_sync.ps1 生成清单时据此写 vpnOnly 字段。请勿手工清空。"
        ),
        "generatedAt": _now(),
        "criterion": (
            "所有源在直连严格探测都未通过、至少一个源在代理严格探测通过；"
            "两轮参数完全一致，只换网络通道。UNKNOWN 不算通过。"
        ),
        "source": {
            "directResult": str(direct_path), "proxyResult": str(proxy_path),
            "probeAssetsSha256": d.get("assetsSha256", ""),
            "probeModes": sorted({*(d.get("modes") or []), *(p.get("modes") or [])}),
            "assetsSha256AtBuild": fingerprint,
        },
        "count": len(ids),
        "stationIds": ids,
    }


def load_list(path: Path) -> dict:
    if not path.exists():
        raise SystemExit(f"标记名单不存在：{path}")
    doc = json.loads(path.read_text(encoding="utf-8"))
    if doc.get("schemaVersion") != SCHEMA_VERSION:
        raise SystemExit(f"标记名单 schemaVersion={doc.get('schemaVersion')}，只认 {SCHEMA_VERSION}")
    ids = doc.get("stationIds")
    if not isinstance(ids, list) or len(ids) != len(set(ids)):
        raise SystemExit("标记名单 stationIds 缺失或有重复")
    return doc


def verify_doc(doc: dict, stations: list) -> list:
    problems = []
    wanted = set(doc["stationIds"])
    actual = {s["id"] for s in stations if s.get("vpnOnly") is True}
    cur_ids = {s["id"] for s in stations}
    missing = wanted - cur_ids
    if missing:
        problems.append(f"名单里 {len(missing)} 个频道已不在清单中，例：{sorted(missing)[:3]}")
    if actual != wanted - missing:
        problems.append(
            f"assets 的 vpnOnly 标记与名单不一致：多 {len(actual - wanted)} 个、"
            f"少 {len((wanted - missing) - actual)} 个"
        )
    return problems


def atomic_write_json(path: Path, obj) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(json.dumps(obj, ensure_ascii=False, separators=(",", ":")),
                   encoding="utf-8")
    os.replace(tmp, path)


def cmd_build(args) -> int:
    data, fingerprint = ip._load_assets(args.assets)
    stations = data["stations"]
    streams, keys = ip.collect_streams(stations)
    d, p = load_states(args.direct, args.proxy)
    assert_covered(streams, d, p)
    ids = build_ids(stations, keys, d, p)
    doc = make_document(ids, d, p, fingerprint, args.direct.resolve(), args.proxy.resolve())
    atomic_write_json(args.list, doc)
    print(f"需 VPN 频道 {len(ids)} / {len(stations)} 台；已写 {args.list}", file=sys.stderr)
    return 0


def cmd_apply(args) -> int:
    data, fingerprint = ip._load_assets(args.assets)
    stations = data["stations"]
    doc = load_list(args.list)
    wanted = set(doc["stationIds"])
    cur_ids = {s["id"] for s in stations}
    missing = wanted - cur_ids
    if missing:
        raise SystemExit(f"名单里 {len(missing)} 个频道已不在清单中，先重跑 build")
    out = []
    for st in stations:
        row = dict(st)
        if st["id"] in wanted:
            row["vpnOnly"] = True
        else:
            row.pop("vpnOnly", None)  # 只给命中的写 true，其余缺字段回落 false
        out.append(row)
    problems = verify_doc(doc, out)
    if problems:
        for x in problems:
            print("校验失败：" + x, file=sys.stderr)
        return 1
    if args.dry_run:
        print(f"--dry-run：{len(wanted)} 台待标记，未写文件", file=sys.stderr)
        return 0
    backup = BACKUP_DIR / f"assets_before_vpn_mark_{fingerprint[:12]}.json"
    backup.parent.mkdir(parents=True, exist_ok=True)
    backup.write_bytes(args.assets.read_bytes())
    atomic_write_json(args.assets, {"stations": out})
    print(f"已给 {len(wanted)} 台写入 vpnOnly=true；原清单备份 {backup}", file=sys.stderr)
    return 0


def cmd_verify(args) -> int:
    doc = load_list(args.list)
    data, _ = ip._load_assets(args.assets)
    problems = verify_doc(doc, data["stations"])
    if problems:
        for x in problems:
            print("防回归校验失败：" + x, file=sys.stderr)
        return 1
    marked = sum(1 for s in data["stations"] if s.get("vpnOnly") is True)
    print(f"通过：清单 {len(data['stations'])} 台，标记 {marked} 台，与名单一致")
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="IPTV 需 VPN 频道标记")
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name, help_text, func in (
        ("build", "从两轮严格探测结果生成标记名单", cmd_build),
        ("apply", "备份并把 vpnOnly 写入 assets", cmd_apply),
        ("verify", "防回归：assets 标记与名单一致", cmd_verify),
    ):
        p = sub.add_parser(name, help=help_text)
        p.add_argument("--assets", type=Path, default=DEFAULT_ASSETS)
        p.add_argument("--list", type=Path, default=DEFAULT_LIST)
        p.add_argument("--direct", type=Path, default=DEFAULT_DIRECT)
        p.add_argument("--proxy", type=Path, default=DEFAULT_PROXY)
        if name == "apply":
            p.add_argument("--dry-run", action="store_true")
        p.set_defaults(func=func)
    args = ap.parse_args(list(sys.argv[1:] if argv is None else argv))
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
