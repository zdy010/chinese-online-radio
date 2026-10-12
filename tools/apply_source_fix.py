"""按体检报告修内置清单：把实测活着的地址提为主源，剔除实测判死的台。

为什么需要一个工具而不是手改 JSON：这份清单 465 台、27 万字符，手改既无法证明
「只改了该改的」，也无法重跑。发版数据必须能被另一个人复现。

三种动作，破坏性从低到高：
1. promote（默认）——某台有 live 地址但不是第一位：把 live 地址提到 streamUrl 与
   sources[0]，顺手把实测已死的地址从队列里摘掉。这不减少任何台，只改顺序。
   为什么不靠 App 的自动切源兜：切源要先把死源失败一遍（用户端是几秒卡顿 +
   一条报错），而我已经知道它死了，没理由让用户付这个代价。
2. replace——所有源都死但找到了新地址：新地址必须当场实测 live 才写入。
3. drop——所有源都死且无替代：从清单剔除。硬闸见 drop_stations：报告判 live
   或没参加体检的台一律拒绝，超过单次上限必须 --force。

备份只认第一次（.fix.bak 存在就不覆盖）：A-2 吃过教训，把上一轮的产物当基线，
第二轮起会把工具自己生成的东西当真数据永久保留下来。
"""
import argparse
import json
import sys
from pathlib import Path

try:
    for _s in (sys.stdout, sys.stderr):
        _s.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

LIVE = "live"
GONE = "gone"
UNKNOWN = "unknown"

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_ASSETS = ROOT / "app/src/main/assets/radio_stations.json"
DEFAULT_REPORT = Path(__file__).resolve().parent / "recheck_report.json"


def index_report(report: list) -> dict:
    return {r["id"]: r for r in report}


def serialize(stations: list) -> str:
    """排版与出厂文件一致：indent=2、中文不转义、无结尾换行。

    不一致的话 git diff 会显示整文件重写，真实改动就再也 review 不动了。
    """
    return json.dumps({"stations": stations}, ensure_ascii=False, indent=2)


def _meta_for(station: dict, url: str):
    """沿用该地址原有的 label/bitrate，别在搬动时把元数据抹平。"""
    for s in station.get("sources") or []:
        if s.get("url") == url:
            return (s.get("label") or ""), int(s.get("bitrate") or 0)
    if url == station.get("streamUrl"):
        return "主源", 0
    return "", 0


def rebuild_sources(station: dict, ordered: list) -> list:
    """ordered: [(url, verdict)]，第一个即新主源；重复地址合并。"""
    out = []
    seen = set()
    for url, _verdict in ordered:
        if not url or url in seen:
            continue
        seen.add(url)
        label, bitrate = _meta_for(station, url)
        out.append({"url": url, "label": label, "bitrate": bitrate})
    if not out:
        return out
    out[0]["label"] = "主源"
    if out[0]["bitrate"] == 0:
        # 码率在 RadioBrowser 里是台级声明，不是逐地址给的：A-1 把它记在了当时的主源上，
        # 而那个主源现在往往就是被删掉的死地址。不继承的话，换个地址就把 128 抹成 0。
        for s in station.get("sources") or []:
            b = int(s.get("bitrate") or 0)
            if b > 0:
                out[0]["bitrate"] = b
                break
    for item in out[1:]:
        # 被挤下去的原主源不许继续顶着「主源」这个名字，播放器会照它显示
        if item["label"] in ("", "主源"):
            item["label"] = "镜像源"
    return out


def promote_station(station: dict, report_row) -> dict:
    """把实测 live 的地址提到第一位；无需改动时返回 None。

    按 URL 匹配而不是按报告里的下标：报告可能是在另一版清单上跑的，
    下标会指到别的地址上去。当前主源已经 live 就不动手，这也顺带保证了重跑零差异。
    """
    if not report_row:
        return None
    urls = report_row.get("urls") or []
    live = [x["url"] for x in urls if x.get("verdict") == LIVE]
    unknown = [x["url"] for x in urls if x.get("verdict") == UNKNOWN]
    if not live:
        return None
    current = (station.get("streamUrl") or "").strip()
    if current in live:
        return None
    primary = live[0]
    ordered = [(primary, LIVE)]
    for u in live[1:]:
        ordered.append((u, LIVE))
    for u in unknown:
        if u != primary:
            ordered.append((u, UNKNOWN))
    # 判死的地址不进队列：留着它，重装/清数据后评分归零时又会踩一次同样的坑

    out = dict(station)          # dict() 保键序，不能让 tags/country 换位
    out["streamUrl"] = primary
    out["sources"] = rebuild_sources(station, ordered)
    return out


def apply_promotions(stations: list, report: list) -> tuple:
    idx = index_report(report)
    out = []
    changed = []
    for st in stations:
        new = promote_station(st, idx.get(st["id"]))
        if new is None:
            out.append(st)
        else:
            out.append(new)
            changed.append(st["id"])
    return out, changed


def drop_stations(stations: list, ids: set, report: list,
                  cap: int = 10, force: bool = False, allow_unknown: bool = False) -> tuple:
    """按 id 剔除，但必须拿报告当许可证。返回 (kept, dropped, refused)。"""
    idx = index_report(report)
    accepted = []
    refused = []
    for st in stations:
        if st["id"] not in ids:
            continue
        row = idx.get(st["id"])
        if row is None:
            refused.append((st["id"], "未参加体检"))
            continue
        verdict = row.get("verdict")
        if verdict == LIVE:
            refused.append((st["id"], "报告判定 live，不许剔除"))
            continue
        if verdict == UNKNOWN and not allow_unknown:
            refused.append((st["id"], "两轮均未表态，默认保留（要删加 --allow-unknown）"))
            continue
        accepted.append(st["id"])

    if len(accepted) > cap and not force:
        return list(stations), [], [(sid, "超过单次上限 %d，确认无误后加 --force" % cap)
                                    for sid in accepted]

    drop_set = set(accepted)
    kept = [s for s in stations if s["id"] not in drop_set]
    return kept, accepted, refused


def apply_replacements(stations: list, replacements: list, probe) -> tuple:
    """probe(url) -> 'live'|'gone'|'unknown'；只有实测 live 的新地址才写入。"""
    by_id = {s["id"]: s for s in stations}
    applied = []
    refused = []
    resolved = {}
    for item in replacements or []:
        sid = item.get("id")
        url = (item.get("url") or "").strip()
        if sid not in by_id:
            refused.append((sid, "清单里没有这个台"))
            continue
        if not url:
            refused.append((sid, "替代地址为空"))
            continue
        if probe(url) != LIVE:
            refused.append((sid, "替代地址实测不为 live"))
            continue
        resolved[sid] = item
        applied.append(sid)

    out = []
    for st in stations:
        if st["id"] not in resolved:
            out.append(st)
            continue
        item = resolved[st["id"]]
        url = item["url"].strip()
        ordered = [(url, LIVE)]
        old = list(dict.fromkeys(
            [s.get("url", "") for s in (st.get("sources") or [])] + [st.get("streamUrl", "")]))
        for u in old:
            if not u or u == url:
                continue
            v = probe(u)
            if v == LIVE:
                ordered.append((u, LIVE))
            elif v == UNKNOWN:
                ordered.append((u, UNKNOWN))
            # gone 丢弃
        new = dict(st)
        new["streamUrl"] = url
        new["sources"] = rebuild_sources(st, ordered)
        if item.get("label"):
            # 溯源信息写进 label，方便下次有人问「这个地址哪来的」
            new["sources"][0]["label"] = item["label"]
        out.append(new)
    return out, applied, refused


def parse_id_lines(text: str) -> set:
    """读剔除名单：整行 # 是注释，行尾 # 是理由。

    剔除理由必须能写在名单里，否则过几个月没人知道为什么少了十台；
    而 id 本身永不含 #，所以按 # 切分不会误伤。
    """
    out = set()
    for line in text.splitlines():
        head = line.split("#", 1)[0].strip()
        if head:
            out.add(head)
    return out


def load_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def ensure_baseline_backup(assets_path: Path) -> Path:
    """基线备份只认第一次：已存在就原样保留，绝不覆盖。"""
    bak = Path(str(assets_path) + ".fix.bak")
    if not bak.exists():
        bak.write_text(assets_path.read_text(encoding="utf-8"), encoding="utf-8")
    return bak


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="按体检报告修内置电台清单")
    ap.add_argument("--assets", type=Path, default=DEFAULT_ASSETS)
    ap.add_argument("--report", type=Path, default=DEFAULT_REPORT)
    ap.add_argument("--drop-file", type=Path, default=None, help="每行一个待剔除 id，# 开头为注释")
    ap.add_argument("--replacements", type=Path, default=None, help="JSON: [{id,url,label}]")
    ap.add_argument("--cap", type=int, default=10)
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--allow-unknown", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args(argv)

    stations = load_json(args.assets)["stations"]
    report = load_json(args.report) if args.report.exists() else []
    before = len(stations)

    stations, promoted = apply_promotions(stations, report)

    dropped = []
    refused_drop = []
    if args.drop_file:
        ids = parse_id_lines(args.drop_file.read_text(encoding="utf-8"))
        stations, dropped, refused_drop = drop_stations(
            stations, ids, report, cap=args.cap, force=args.force, allow_unknown=args.allow_unknown)

    applied_repl = []
    refused_repl = []
    if args.replacements:
        import station_probe as sp

        def probe(u):
            return sp.probe_with_retry(u, attempts=3, pause=1.5, timeout=15)["verdict"]

        stations, applied_repl, refused_repl = apply_replacements(stations, load_json(args.replacements), probe)

    print("体检报告 %d 条；提升主源 %d 台；替代 %d 台；剔除 %d 台（原 %d → 现 %d）" % (
        len(report), len(promoted), len(applied_repl), len(dropped), before, len(stations)))
    for label, rows in (("剔除被拒", refused_drop), ("替代被拒", refused_repl)):
        if rows:
            print("%s %d 条：" % (label, len(rows)))
            for sid, why in rows:
                print("   %-28s %s" % (sid, why))

    if args.dry_run:
        print("\n[未落盘] dry-run")
        return 0
    if not (promoted or dropped or applied_repl):
        print("没有需要改动的项，文件不动。")
        return 0

    bak = ensure_baseline_backup(args.assets)
    args.assets.write_text(serialize(stations), encoding="utf-8")
    print("已写入（基线备份：%s）" % bak.name)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
