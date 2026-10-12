"""内置清单体检：逐条流地址跑与入库同样的分层实测，输出「每台能否播到」。

为什么需要它：入库时的 live 只对该瞬间负责。A-1 之后第一次全量回归就撞上
存量台的 mgtv 主源返回 HTML（HTTP 200 假活），说明清单会随时间腐烂，
发版前必须有一个可复跑的数字，而不是「上次测过」。

判定口径有两个容易造假的地方，本脚本按事实处理：
1. 不能只看 streamUrl。A-1 补了 115 个镜像源，「主源死 / 镜像活」的台用户照样
   能听到，把它算成死台会既误报又漏掉真正该修的切源顺序。
   → 每台测它全部去重后的地址，只要有一个 live 就算这台可播。
2. 并发 8 线程下的 timeout 常常只是同站限流（苏州一次报了 4 台 timeout，
   串行复测后主源全活）。
   → 首轮判为不可播的台，再串行重测一轮，两轮都不 live 才写进结论。

用法：
    python tools/recheck_stations.py                      # 全量
    python tools/recheck_stations.py --out .qtest/v.json  # 指定产物
产出 JSON 供 tools/apply_source_fix.py 消费，人读摘要写同名 .txt。
本脚本只读清单、不改清单。

unknown 不是可以安全发版的结论。探针分不出「本机走不出去」与「对方已下线」，
而播放器能：拿 tools/playcheck.py --names 把这些台在真机上点一遍，
未进 PLAYING 的才是该剔除的（2026-10-12 实测：北京戏曲曲艺 PC 侧只是未表态，
真机切台直接 state=ERROR）。
"""
import argparse
import concurrent.futures as cf
import json
import sys
import time
from pathlib import Path

try:
    for _s in (sys.stdout, sys.stderr):
        _s.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

import station_probe as sp

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_ASSETS = ROOT / "app/src/main/assets/radio_stations.json"


def probe_one(url: str, attempts: int = 2, pause: float = 1.5, timeout: float = 12.0) -> dict:
    """统一走这里调探针，而且必须用关键字参数。

    这不是多余的封装：station_probe.probe_with_retry 的形参序是
    (url, attempts, pause, session, timeout)，第 4 个是 session。上一版按位置传
    timeout，等于把一个 float 塞成了 requests.Session，探针内部异常被吞成
    unknown，整份体检报告 465 台全判“不可播”——一个静默的全量假阳性。
    """
    return sp.probe_with_retry(url, attempts=attempts, pause=pause, timeout=timeout)


def station_urls(station: dict) -> list:
    """该台所有去重后的地址，保留「主源/镜像源」标签，主源永远排第一。"""
    out = []
    seen = set()
    primary = (station.get("streamUrl") or "").strip()
    if primary:
        out.append({"url": primary, "label": "主源"})
        seen.add(primary)
    for s in station.get("sources") or []:
        u = (s.get("url") or "").strip()
        if u and u not in seen:
            seen.add(u)
            out.append({"url": u, "label": s.get("label") or "镜像源"})
    return out


def probe_urls(urls: list, attempts: int, timeout: float, pause: float) -> list:
    rows = []
    for item in urls:
        r = probe_one(item["url"], attempts=attempts, pause=pause, timeout=timeout)
        rows.append({"url": item["url"], "label": item["label"],
                     "verdict": r["verdict"], "why": r["why"]})
    return rows


def summarize(station: dict, rows: list, first_pass: str) -> dict:
    live = [r for r in rows if r["verdict"] == sp.LIVE]
    gone = [r for r in rows if r["verdict"] == sp.GONE]
    if live:
        verdict = sp.LIVE
    elif gone:
        # 至少有一条被明确判死（200 返 HTML / 404 / 音频魔数缺失）——这是可定性的死
        verdict = sp.GONE
    else:
        verdict = sp.UNKNOWN
    return {
        "id": station["id"],
        "name": station["name"],
        "category": station.get("category", ""),
        "verdict": verdict,
        "live_index": rows.index(live[0]) if live else -1,
        "urls": rows,
        "first_pass": first_pass,
    }


def run(assets_path: Path, out_path: Path, workers: int, timeout: float) -> int:
    data = json.loads(assets_path.read_text(encoding="utf-8"))
    stations = data["stations"]
    per_station_urls = {st["id"]: station_urls(st) for st in stations}

    # 第一轮：按地址并发，但**同一个地址只测一次**（缓存键就是地址本身）。
    # 这不只是省时间：上一轮串行复测发现 satellitepull 会被限流，
    # 同一台共用地址多打几次就会多出几个假 unknown，而假 unknown 会直接影响剔除判断。
    all_urls = []
    for st in stations:
        for item in per_station_urls[st["id"]]:
            all_urls.append((st["id"], item))
    verdict_of = {}
    with cf.ThreadPoolExecutor(max_workers=workers) as ex:
        futs = {ex.submit(probe_one, it["url"], 2, 1.5, timeout): it["url"]
                for it in {item["url"]: item for _sid, item in all_urls}.values()}
        done = 0
        for fut in cf.as_completed(futs):
            url = futs[fut]
            r = fut.result()
            verdict_of[url] = {"verdict": r["verdict"], "why": r["why"]}
            done += 1
            if done % 100 == 0:
                print("  首轮 %d/%d" % (done, len(futs)), file=sys.stderr)

    rows = []
    for st in stations:
        first = [{"url": it["url"], "label": it["label"], **verdict_of[it["url"]]}
                 for it in per_station_urls[st["id"]]]
        rows.append(summarize(st, first, "concurrent"))

    # 第二轮：首轮判不可播的，串行重测（每次间隔，避免同站限流假阳性）
    retry_ids = [r["id"] for r in rows if r["verdict"] != sp.LIVE]
    if retry_ids:
        print("  串行复测 %d 台" % len(retry_ids), file=sys.stderr)
    by_id = {st["id"]: st for st in stations}
    row_of = {r["id"]: i for i, r in enumerate(rows)}
    for i, sid in enumerate(retry_ids, 1):
        st = by_id[sid]
        serial = probe_urls(per_station_urls[sid], attempts=3, timeout=timeout + 8, pause=2.0)
        rows[row_of[sid]] = summarize(st, serial, "serial")
        print("    %d/%d %s -> %s" % (i, len(retry_ids), st["name"][:20],
                                      rows[row_of[sid]]["verdict"]),
              file=sys.stderr)
        time.sleep(0.3)

    out_path.write_text(json.dumps(rows, ensure_ascii=False, indent=1), encoding="utf-8")

    live = [r for r in rows if r["verdict"] == sp.LIVE]
    gone = [r for r in rows if r["verdict"] == sp.GONE]
    unk = [r for r in rows if r["verdict"] == sp.UNKNOWN]
    promote = [r for r in live if r["live_index"] > 0]
    lines = ["清单体检 %d 台：%s live / %s gone / %s unknown" % (
        len(rows), len(live), len(gone), len(unk)), ""]
    if promote:
        lines.append("可播但主源不是可用地址（建议提升镜像为主源）%d 台：" % len(promote))
        for r in promote:
            lines.append("   %-24s 主源=%s(%s) 第%d个源 live" % (
                r["name"][:24], r["urls"][0]["verdict"], r["urls"][0]["why"], r["live_index"] + 1))
        lines.append("")
    if gone:
        lines.append("所有源都确认不可播（候选剔除）%d 台：" % len(gone))
        for r in gone:
            lines.append("   %-24s [%s]" % (r["name"][:24], r["category"]))
            for u in r["urls"]:
                lines.append("        %-8s %-18s %s" % (u["verdict"], u["label"], u["why"]))
        lines.append("")
    if unk:
        lines.append("两轮都未表态（超时/连不上，不定性为死）%d 台：" % len(unk))
        for r in unk:
            lines.append("   %-24s %s" % (r["name"][:24], r["urls"][0]["why"]))

    text = "\n".join(lines)
    out_path.with_suffix(".txt").write_text(text, encoding="utf-8")
    print(text)
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="内置电台清单全量体检（只读）")
    ap.add_argument("--assets", type=Path, default=DEFAULT_ASSETS)
    ap.add_argument("--out", type=Path, default=Path(__file__).resolve().parent / "recheck_report.json")
    ap.add_argument("--workers", type=int, default=8)
    ap.add_argument("--timeout", type=float, default=12.0)
    args = ap.parse_args(argv)
    return run(args.assets, args.out, args.workers, args.timeout)


if __name__ == "__main__":
    raise SystemExit(main())
