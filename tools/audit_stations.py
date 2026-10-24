"""入库后的独立复核：静态完整性检查 + 可选的 live 复测。

为什么要和 merge 分开写：merge 里的 guard() 是"写入时的自证"，
再由同一份代码报告"写对了"没有意义。这个脚本独立读最终文件，
按外部事实（Kotlin 枚举、本次实测记录、备份文件）逐项核对。

用法：
  python tools/audit_stations.py                 # 静态审计
  python tools/audit_stations.py --recheck 20    # 再随机抽 20 台真打一遍流
"""
import argparse
import collections
import json
import random
import re
import sys
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

STATIONS_JSON = ROOT / "app/src/main/assets/radio_stations.json"
STATION_KT = ROOT / "app/src/main/java/com/radio/chinese/domain/model/StationCategory.kt"
VERIFIED = ROOT / ".qtest/rb_verified.json"

# 落库地址与实测地址常常只差一组会话参数（wsSession/wsIPSercert），
# 不先归一就会把“刚洗过地址的台”误报成“无实测记录”。
from fetch_stations import store_url  # noqa: E402

FIELDS_REQUIRED = {"id", "name", "category", "streamUrl", "sources"}


def station_category_keys(path=STATION_KT):
    """分类的单一来源在 Kotlin 枚举里，这里从它反读，不抄第二份表。"""
    text = path.read_text(encoding="utf-8")
    return set(re.findall(r'^\s*[A-Z_0-9]+\("([a-z_0-9]+)",', text, re.M))


def entry_urls(entry):
    out = []
    if entry.get("streamUrl"):
        out.append(entry["streamUrl"])
    for s in entry.get("sources") or []:
        if s.get("url"):
            out.append(s["url"])
    return out


def audit(stations, live_urls=None, prev_ids=None, baseline=None):
    """返回 [(检查名, 是否通过, 说明)]。任何一项不通过，退出码非 0。

    重要区分：“新增条目”与“历史存量”用不同强度。
    存量大陆台本来就没 sources 字段，把它们当本次失败会导致
    真正的新增问题被噪声淹没。
    """
    checks = []
    keys = station_category_keys()
    new_ids = None if prev_ids is None else {s["id"] for s in stations if s["id"] not in prev_ids}

    bad_cat = [s["id"] for s in stations if s.get("category") not in keys]
    checks.append(("分类都在 StationCategory 枚举内", not bad_cat,
                   f"越界 {len(bad_cat)} 条 {bad_cat[:3]}" if bad_cat else f"枚举 {len(keys)} 项"))

    ids = [s.get("id", "") for s in stations]
    dup_id = [i for i, c in collections.Counter(ids).items() if c > 1]
    checks.append(("id 全局唯一", not dup_id, f"重复 {dup_id[:3]}" if dup_id else f"{len(ids)} 条"))

    # 每台内部先去重：既有约定就是 streamUrl 与 sources[0] 同址，直接跨台比会自己撞自己
    shared_now = _shared_urls(stations)
    # 回归型判定：存量本来就有几条共用地址（如 st_粤语国际/st_世界华声 共用 sk.cri.cn），
    # 只要本次没新增共用就应该通过，同时候选量报出来不藏问题
    shared_before = _shared_urls(baseline) if baseline is not None else set()
    introduced = sorted(shared_now - shared_before)
    checks.append(("本次未新增共用流地址", not introduced,
                   f"新增 {len(introduced)} 个 {introduced[:2]}｜存量本来就有 {len(shared_before & shared_now)} 个"
                   if introduced else f"当前共用 {len(shared_now)} 个，均为存量遗留"))

    no_stream = [s["id"] for s in stations if not s.get("streamUrl")]
    checks.append(("每条都有主 streamUrl", not no_stream, f"缺失 {no_stream[:3]}" if no_stream else ""))

    http_only = [s["id"] for s in stations
                 if not str(s.get("streamUrl", "")).lower().startswith(("http://", "https://"))]
    checks.append(("主地址都是 http(s)", not http_only, f"异常 {http_only[:3]}" if http_only else ""))

    if new_ids is None:
        miss_field = [s.get("id", "?") for s in stations if not FIELDS_REQUIRED <= set(s.keys())]
        checks.append(("必需字段齐全", not miss_field, f"缺字段 {miss_field[:3]}" if miss_field else ""))
    else:
        miss_new = [s["id"] for s in stations if s["id"] in new_ids and not FIELDS_REQUIRED <= set(s.keys())]
        miss_old = sum(1 for s in stations if s["id"] not in new_ids and "sources" not in s)
        checks.append(("新增条目字段齐全", not miss_new,
                       f"缺字段 {miss_new[:3]}" if miss_new else f"存量无 sources 字段 {miss_old} 条（大陆台既有约定，不算本次问题）"))

    bad_new = [i for i in _bad_host(stations) if new_ids is None or i in new_ids]
    bad_all = _bad_host(stations)
    checks.append(("新增主地址无盗版CDN", not bad_new,
                   f"{bad_new[:3]}" if bad_new else f"盗版特征 {len(bad_all)} 条"))
    # 裸 IP 不再是排除理由（实测它挡掉了 Radio Caroline、台北 Hit FM 等可用台），
    # 但数量还是报出来：IP 托管的流生命周期短，日后排查用得上
    ip_cnt = sum(1 for s in stations if re.match(
        r"^\d+\.\d+\.\d+\.\d+", str(s.get("streamUrl", "").split("//", 1)[-1])))
    print(f"  [info] 裸 IP 主地址共 {ip_cnt} 条（允许存在，生命周期短）")

    multi = [s for s in stations if len(s.get("sources") or []) > 1]
    checks.append(("多源台数大于 0（切源功能有数据可用）", bool(multi),
                   f"{len(multi)} 台有多源"))

    if live_urls is not None and prev_ids is not None:
        new = [s for s in stations if s["id"] not in prev_ids]
        measured = {store_url(u) for u in live_urls}
        unverified = sorted({s["id"] for s in new
                             for u in entry_urls(s) if store_url(u) not in measured})
        checks.append((f"新增 {len(new)} 台的每个地址都有本次 live 实测", not unverified,
                       f"无实测记录 {unverified[:3]}" if unverified else "全部可追溯"))
    return checks


def _shared_urls(stations):
    """被两个及以上电台持有的地址集合（每台内部先按自身去重）。"""
    owner = {}
    for s in stations or []:
        for u in dict.fromkeys(entry_urls(s)):
            owner.setdefault(u, set()).add(s.get("id", ""))
    return {u for u, owners in owner.items() if len(owners) > 1}


def _bad_host(stations):
    """只查盗版转播 CDN 特征。裸 IP 已不视为排除理由（小站长用 IP 托管常见）。"""
    out = []
    for s in stations:
        host = str(s.get("streamUrl", "")).split("/")[2] if "//" in str(s.get("streamUrl", "")) else ""
        if "fhbf9" in host:
            out.append(s.get("id", "?"))
    return out


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--json", default=str(STATIONS_JSON))
    p.add_argument("--recheck", type=int, default=0, help="随机抽 N 台真打一遍流")
    p.add_argument("--seed", type=int, default=20261011)
    args = p.parse_args()

    stations = json.loads(Path(args.json).read_text(encoding="utf-8"))["stations"]
    dist = collections.Counter(s["category"] for s in stations)
    print(f"清单条目 {len(stations)} 台；分类分布：")
    for c, n in sorted(dist.items(), key=lambda t: -t[1]):
        print(f"  {c:<10} {n}")

    live_urls = prev_ids = None
    if VERIFIED.exists():
        live_urls = {v["url"] for v in json.loads(VERIFIED.read_text(encoding="utf-8"))
                     if v["verdict"] == "live"}
    bak = Path(str(args.json) + ".bak")
    baseline = None
    if bak.exists():
        baseline = json.loads(bak.read_text(encoding="utf-8"))["stations"]
        prev_ids = {s["id"] for s in baseline}

    failed = False
    for name, ok, detail in audit(stations, live_urls, prev_ids, baseline):
        print(("  [OK]   " if ok else "  [FAIL] ") + name + (f"  <- {detail}" if detail else ""))
        failed = failed or not ok

    if args.recheck:
        import station_probe as sp
        random.seed(args.seed)
        sample = random.sample(stations, min(args.recheck, len(stations)))
        live = gone = unknown = 0
        print(f"\n随机抽 {len(sample)} 台复测：")
        for s in sample:
            r = sp.probe_with_retry(s["streamUrl"], attempts=2, pause=1.5, timeout=10)
            if r["verdict"] == sp.LIVE:
                live += 1
            elif r["verdict"] == sp.GONE:
                gone += 1
                print(f"  [GONE]   {s['name'][:22]:<24} {r['why']}")
            else:
                unknown += 1
                print(f"  [UNKNOW] {s['name'][:22]:<24} {r['why']}")
        print(f"复测结果 live={live} gone={gone} unknown={unknown}")

    print("\n审计通过" if not failed else "\n审计未通过")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
