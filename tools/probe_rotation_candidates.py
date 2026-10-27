"""为「多源轮转」回归挑候选台：第一个源确定已死、后面还有活源的台。

为什么要专门挑：轮转的触发条件是首源报错，随便点一个台多半第一源就播通了，
测不到推进分支；而清单里 144 个多源台哪些是这种状态，光看数据看不出来。

判定一律走 station_probe 的三态（LIVE / GONE / UNKNOWN），不把 UNKNOWN 当死源：
超时/403 当 GONE 会挑出一个其实能播的台，那样测出来的「轮转成功」是假的。

用法：python tools/probe_rotation_candidates.py [扫描台数] [需要的候选数]
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import station_probe as sp  # noqa: E402  同目录的工具模块

ROOT = Path(__file__).resolve().parent.parent


def main(scan: int = 80, need: int = 4) -> int:
    stations = json.loads(
        (ROOT / "app/src/main/assets/radio_stations.json").read_text(encoding="utf-8")
    )["stations"]
    targets = [s for s in stations if len(s.get("sources") or []) >= 2]
    print(f"多源台 {len(targets)} 个，按清单顺序探测前 {scan} 个…")

    hits = 0
    for st in targets[:scan]:
        urls = [x["url"] for x in st["sources"]]
        verdicts = [sp.probe_with_retry(u, attempts=2, timeout=8.0)["verdict"] for u in urls]
        first, rest = verdicts[0], verdicts[1:]
        if first != sp.GONE or sp.LIVE not in rest:
            continue
        hits += 1
        print(f"\n候选 {hits}: {st['id']}  {st['name']}")
        for url, verdict in zip(urls, verdicts):
            print(f"    {verdict:<8} {url[:86]}")
        if hits >= need:
            break

    if hits == 0:
        print("没找到「首源 GONE 且有活源」的台：可放宽扫描数，或改用全死台验证耗尽分支")
        return 1
    print(f"\n提示：这些台的可用性分数会随播放结果变化，回归前先清一次应用数据，"
          f"否则 getSortedSources 可能已经把活源排到第一位。")
    return 0


if __name__ == "__main__":
    args = sys.argv[1:]
    raise SystemExit(main(scan=int(args[0]) if args else 80, need=int(args[1]) if len(args) > 1 else 4))
