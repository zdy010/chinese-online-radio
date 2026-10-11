"""装机实播巡检：用 App 自己的播放内核逐台验证，而不是只看 HTTP 能不能下载。

为什么需要它：live.shaoxing.com.cn 的源在 PC 侧分层实测完全健康
（清单 200、分片 200、真 TS 字节、不挑 UA），但 ExoPlayer 连续 48 秒停在
BUFFERING、position 往负数漂——"能下载"不等于"能出声"。

判据用系统侧的 MediaSession 状态与 AudioService 播放器，不看界面上的
"正在播放"字样：那个文字来自 playWhenReady，缓冲不出来时也会短暂显示。
"""
import argparse
import json
import re
import sys
import time
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS))
ROOT = TOOLS.parent

for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

import ui_tap as ut

STATIONS_JSON = ROOT / "app/src/main/assets/radio_stations.json"


def playback_state():
    """从 dumpsys media_session 取本应用的播放状态字符串。"""
    out = ut.adb("shell", "dumpsys", "media_session")
    m = re.search(r"state=(PLAYING|BUFFERING|PAUSED|STOPPED|ERROR)\(\d+\)", out)
    return m.group(1) if m else "UNKNOWN"


def wait_state(want=("PLAYING",), timeout=25.0):
    """等到目标状态，或超时报最后看到的状态。"""
    deadline = time.time() + timeout
    last = "UNKNOWN"
    while time.time() < deadline:
        last = playback_state()
        if last in want:
            return last
        time.sleep(2.0)
    return last


def pick_sample(stations, new_ids, per_category):
    """每类抽 N 台新条目，按 host 分散：同一主机的多个台往往同生共死。"""
    by_cat = {}
    for s in stations:
        if s["id"] in new_ids and s["category"] in per_category:
            by_cat.setdefault(s["category"], []).append(s)
    picked = []
    for cat, n in per_category.items():
        pool = by_cat.get(cat, [])
        seen_hosts = set()
        chosen = []
        for s in pool:
            host = s["streamUrl"].split("/")[2] if "//" in s["streamUrl"] else ""
            if host in seen_hosts:
                continue
            seen_hosts.add(host)
            chosen.append(s)
            if len(chosen) >= n:
                break
        # 主机分散后仍不足，再从剩余里补
        if len(chosen) < n:
            for s in pool:
                if s not in chosen:
                    chosen.append(s)
                if len(chosen) >= n:
                    break
        picked += chosen[:n]
    return picked


def ascii_query(name):
    """从台名取一段能搜到的 ASCII 关键字（世界分类的台名多为英文）。"""
    m = re.search(r"[A-Za-z0-9][A-Za-z0-9 .+&'-]{2,18}", name)
    return (m.group(0).strip() if m else name.strip())[:18]


def drop_failed(results, stations):
    """把实播没起声音的新增条目从清单剔除。

    两道安全约束：
      1. 只算真跑到了播放这步的结果。“导航失败/没找到节点/无此分类”
         是脚本或界面问题，拿它删数据等于用工具的失败改产品数据；
      2. 不按主机连带误杀：同主机不同频道在 ExoPlayer 下表现可以完全不同。
    """
    not_tested = ("NOT-FOUND", "NAV-FAIL", "NO-CATEGORY")
    bad = {r["id"] for r in results
           if r.get("state") not in not_tested and r.get("audio_started") is False
           and "audio_started" in r}
    return [s for s in stations if s["id"] not in bad], sorted(bad)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--pkg", default="com.radio.chinese")
    p.add_argument("--per-category", default="opera=3,sports=2,tv_audio=3,world=3,music=2",
                   help="每类抽几台，形如 opera=3,sports=2")
    p.add_argument("--out", default=str(ROOT / ".qtest/playcheck.json"))
    p.add_argument("--wait", type=float, default=25.0)
    p.add_argument("--drop-only", action="store_true",
                   help="不重测，只按 --out 里已有的实播结果剔除未出声条目")
    p.add_argument("--drop-failed", action="store_true",
                   help="把本次实播未起声音的新增条目从清单剔除（会先备份）")
    args = p.parse_args()

    per = {}
    for item in args.per_category.split(","):
        k, _, v = item.partition("=")
        if k.strip():
            per[k.strip()] = int(v or 1)

    stations = json.loads(STATIONS_JSON.read_text(encoding="utf-8"))["stations"]
    if args.drop_only:
        results = json.loads(Path(args.out).read_text(encoding="utf-8"))
        kept, dropped = drop_failed(results, stations)
        print(f"已有实播结果 {len(results)} 台；未出声待剔除 {len(dropped)} 条：{dropped}")
        if dropped:
            Path(str(STATIONS_JSON) + ".preplay.bak").write_text(
                STATIONS_JSON.read_text(encoding="utf-8"), encoding="utf-8")
            STATIONS_JSON.write_text(json.dumps({"stations": kept}, ensure_ascii=False, indent=2),
                                     encoding="utf-8")
            print(f"清单现为 {len(kept)} 台（备份 radio_stations.json.preplay.bak）")
        return 0
    bak = Path(str(STATIONS_JSON) + ".bak")
    old_ids = {s["id"] for s in json.loads(bak.read_text(encoding="utf-8"))["stations"]} if bak.exists() else set()
    new_ids = {s["id"] for s in stations} - old_ids
    sample = pick_sample(stations, new_ids, per)
    print(f"抽样 {len(sample)} 台新增条目做装机实播：")
    for s in sample:
        print(f"  {s['category']:<9} {s['name'][:26]:<28} {s['streamUrl'].split('/')[2][:26]}")

    results = []
    for s in sample:
        label = s["name"]
        ut.goto_home(args.pkg)
        if not ut.tap("分类", max_scrolls=2):
            results.append({"id": s["id"], "name": label, "state": "NAV-FAIL"})
            continue
        if not ut.tap(category_label(s["category"]), max_scrolls=3):
            results.append({"id": s["id"], "name": label, "state": "NO-CATEGORY"})
            continue
        found = ut.tap(label, settle=1.0, max_scrolls=30)
        if not found:
            results.append({"id": s["id"], "name": label, "state": "NOT-FOUND"})
            print(f"  [NOT-FOUND] {label}")
            continue
        state = wait_state(("PLAYING",), timeout=args.wait)
        ok_audio, why = ut.audio_playing(args.pkg)
        tag = "OK" if state == "PLAYING" and ok_audio else "FAIL"
        print(f"  [{tag}] {label[:24]:<26} media={state} audio={'started' if ok_audio else why}")
        results.append({"id": s["id"], "name": label, "state": state,
                        "audio_started": ok_audio, "category": s["category"]})
        Path(args.out).write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")

    fails = [r for r in results if r.get("state") != "PLAYING"]
    print(f"\n实播巡检：{len(results)} 台，未进入 PLAYING 的 {len(fails)} 台")
    for r in fails:
        print(f"  {r['category']} | {r['name']} | {r.get('state')}")

    if args.drop_failed:
        noaudio = [r for r in results if not r.get("audio_started")]
        print("\n按系统侧播放器判据未出声的：" + (", ".join(r["name"] for r in noaudio) or "（无）"))
        kept, dropped = drop_failed(results, stations)
        if dropped:
            Path(str(STATIONS_JSON) + ".preplay.bak").write_text(
                STATIONS_JSON.read_text(encoding="utf-8"), encoding="utf-8")
            STATIONS_JSON.write_text(json.dumps({"stations": kept}, ensure_ascii=False, indent=2),
                                     encoding="utf-8")
            print(f"已剔除 {len(dropped)} 条：{dropped}；清单现为 {len(kept)} 台（备份 .preplay.bak）")
        else:
            print("无需剔除")
    return 0 if not fails else 1


def category_label(key):
    return {"opera": "戏曲", "sports": "体育", "tv_audio": "电视伴音",
            "world": "世界", "music": "音乐", "news": "新闻", "general": "综合",
            "traffic": "交通", "finance": "财经", "arts": "文艺"}.get(key, key)


if __name__ == "__main__":
    sys.exit(main())
