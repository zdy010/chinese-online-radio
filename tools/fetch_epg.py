"""生成 / 校验 `app/src/main/assets/epg_stations.json`：云听台 ↔ 本项目台库。

映射表是节目单功能的唯一入口。接错一个台，就会把 A 台的节目挂到 B 台的播放页上，
比没有节目单更糟——用户看不出哪里错了。所以这里只认「规范化后台名精确相等」，
模糊命中一律进人工裁决清单，绝不自动接单。

    python tools/fetch_epg.py              # 用已有扫描缓存出报告，不写文件
    python tools/fetch_epg.py --rescan     # 重新打接口扫描（约 900 次请求，别常跑）
    python tools/fetch_epg.py --write      # 闸门全过才写映射表

报告一律落文件：Windows 控制台是 GBK，中文台名直接 print 会抛 UnicodeEncodeError，
那种崩法还会让人以为脚本本身坏了。stdout 只走 ASCII 摘要。
"""
import argparse
import hashlib
import io
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

API_KEY = "f0fc4c668392f9f9a447e48584c214ee"   # 云听网页源码里的公开常量，不是密钥
BASE = "https://ytmsout.radio.cn"
USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120 Safari/537.36"
)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOCAL_STATIONS = os.path.join("app", "src", "main", "assets", "radio_stations.json")
MAPPING = os.path.join("app", "src", "main", "assets", "epg_stations.json")
SCAN_CACHE = os.path.join("tools", "epg_scan.json")
REPORT = os.path.join("tools", "epg_match_report.txt")
SCHEMA_VERSION = 1


def norm(name):
    """台名规范化：去首尾空白、内部连续空白压成一个空格。

    云听实测有「 经济之声」这种带前导空格的台名，不处理就会漏匹配。
    """
    return re.sub(r"\s+", " ", (name or "").strip())


def sign(params, timestamp_ms, key=API_KEY):
    """与云听前端一致的签名：参数按 key 字典序拼接，再挂 timestamp 与 key，取 MD5 大写。"""
    joined = "&".join("{0}={1}".format(k, params[k]) for k in sorted(params))
    text = (joined + "&" if joined else "") + "timestamp={0}&key={1}".format(timestamp_ms, key)
    return hashlib.md5(text.encode("utf-8")).hexdigest().upper()


def http_fetch(path, params):
    """真打接口。单测里换成假件，所以调用方只把这个函数当依赖传进去。"""
    ts = str(int(time.time() * 1000))
    query = urllib.parse.urlencode(params)
    url = BASE + path + ("?" + query if query else "")
    request = urllib.request.Request(url, headers={
        "User-Agent": USER_AGENT,
        "Content-Type": "application/json",
        "equipmentId": "0000",
        "platformCode": "WEB",
        "timestamp": ts,
        "sign": sign(params, int(ts)),
    })
    with urllib.request.urlopen(request, timeout=20) as response:
        return json.loads(response.read().decode("utf-8"))


def scan(fetch, today, pause=0.05, sleep=time.sleep, log=lambda *_: None):
    """扫全站，标出哪些台有节目单。

    每个台一次 `listByDate`——896 个台就是约 900 次请求，所以扫完必须缓存，
    重建映射表默认读缓存而不是重扫。
    """
    provinces = (fetch("/web/appProvince/list/all", {}).get("data") or [])
    seen = set()
    stations = []
    for province in provinces:
        name = norm(province.get("provinceName")).replace(" ", "")
        try:
            rows = fetch("/web/appBroadcast/list",
                         {"provinceCode": str(province.get("provinceCode"))}).get("data") or []
        except Exception as error:                      # 单个省失败不影响整体
            log("list ERR", name, error)
            continue
        for row in rows:
            bid = row.get("contentId")
            if bid is None or bid in seen:
                continue
            seen.add(bid)
            stations.append({
                "broadcastId": bid,
                "title": norm(row.get("title")),
                "province": name,
            })

    with_epg = []
    for index, station in enumerate(stations):
        try:
            programs = fetch("/web/appProgram/listByDate", {
                "broadcastId": str(station["broadcastId"]), "date": today,
            }).get("data") or []
        except Exception:
            programs = []
        station["programCount"] = len(programs)
        if programs:
            station["firstProgram"] = norm(programs[0].get("programName"))
            with_epg.append(station)
        if (index + 1) % 50 == 0:
            log("checked", index + 1, "with_epg", len(with_epg))
        if pause:
            sleep(pause)

    return {"checkedAt": today, "stations": stations, "withEpg": with_epg}


def match(with_epg, local):
    """按规范化台名精确配对。

    返回 matched / ambiguous / unmatched 三段。ambiguous 是「一个云听台名对上多个本地台」
    或「一个本地台对上多个云听 bid」——两种都必须人工裁决，脚本没能力判断哪个是真的那个。
    """
    by_name = {}
    for station in local:
        by_name.setdefault(norm(station.get("name")), []).append(station)

    matched, ambiguous, unmatched = [], [], []
    for entry in with_epg:
        key = norm(entry.get("title"))
        hits = by_name.get(key) or []
        if len(hits) == 1:
            matched.append({
                "stationId": hits[0]["id"],
                "broadcastId": str(entry["broadcastId"]),
                "name": key,
            })
        elif len(hits) > 1:
            ambiguous.append((key, str(entry["broadcastId"]), [h["id"] for h in hits]))
        else:
            unmatched.append((key, str(entry["broadcastId"]), entry.get("province") or ""))
    # 这里不去重：同一个台对上多个 bid 是「必须人工看」面而非「可以顺手清」面，
    # 交给调用方用 dedupe_by_station 分开处理
    return {"matched": matched, "ambiguous": ambiguous, "unmatched": unmatched}


def dedupe_by_station(rows):
    """同一个本地台只留一条映射；多条时整组剔到冲突清单里等人工看。

    这里不「选第一个」：一个台对上两个云听 bid 时，脚本没有依据判断哪个是对的，
    猜错就是静默挂错节目单。
    """
    buckets = {}
    for row in rows:
        buckets.setdefault(row["stationId"], []).append(row)
    kept, collisions = [], []
    for group in buckets.values():
        if len(group) == 1:
            kept.append(group[0])
        else:
            collisions.append(group)
    return kept, collisions


def validate(rows, local):
    """写文件前的闸门。返回问题清单，空清单才允许落盘。"""
    by_id = {station["id"]: norm(station.get("name")) for station in local}
    problems = []
    seen_station, seen_bid = set(), set()
    for row in rows:
        station_id = row.get("stationId") or ""
        bid = row.get("broadcastId") or ""
        if not station_id or not bid:
            problems.append("空字段：{0}".format(row))
            continue
        if station_id not in by_id:
            problems.append("映射表里的 {0} 不在台库".format(station_id))
        elif by_id[station_id] != norm(row.get("name")):
            problems.append("{0} 台名不一致：映射 {1!r} / 台库 {2!r}".format(
                station_id, row.get("name"), by_id[station_id]))
        if not bid.isdigit():
            problems.append("broadcastId 不是数字：{0} -> {1}".format(station_id, bid))
        if station_id in seen_station:
            problems.append("stationId 重复：{0}".format(station_id))
        if bid in seen_bid:
            problems.append("broadcastId 重复：{0}".format(bid))
        seen_station.add(station_id)
        seen_bid.add(bid)
    return problems


def build_payload(rows, generated_at):
    return {
        "schemaVersion": SCHEMA_VERSION,
        "generatedAt": generated_at,
        "stations": sorted(
            [{"stationId": r["stationId"], "broadcastId": r["broadcastId"], "name": r["name"]}
             for r in rows],
            key=lambda r: r["stationId"],
        ),
    }


def render_report(scan_result, result, problems):
    lines = [
        "扫描于 {0}：云听台 {1}，其中有节目单 {2}".format(
            scan_result.get("checkedAt"), len(scan_result.get("stations") or []),
            len(scan_result.get("withEpg") or [])),
        "本地台库参与匹配，精确匹配 {0}，同名多台 {1}，未匹配 {2}".format(
            len(result["matched"]), len(result["ambiguous"]), len(result["unmatched"])),
        "",
        "=== 同名多台（需人工裁决）===",
    ]
    lines += ["  {0}  bid={1}  本地候选={2}".format(*item) for item in result["ambiguous"]]
    lines.append("")
    lines.append("=== 一个本地台对上多个云听 bid（需人工裁决）===")
    lines += ["  {0}".format(group) for group in result["collisions"]]
    lines.append("")
    lines.append("=== 闸门未过 ===")
    lines += ["  {0}".format(p) for p in problems]
    lines.append("")
    lines.append("=== 云听有节目单但台库里没有（B/C 阶段素材）===")
    lines += ["  {0}  bid={1}  {2}".format(*item) for item in result["unmatched"]]
    return "\n".join(lines) + "\n"


def load_json(path):
    with io.open(path, encoding="utf-8") as handle:
        return json.load(handle)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--rescan", action="store_true",
                        help="重新打云听接口扫描（约 900 次请求），默认读 tools/epg_scan.json")
    parser.add_argument("--write", action="store_true",
                        help="写 assets/epg_stations.json；闸门不过时拒绝写")
    parser.add_argument("--scan-cache", default=SCAN_CACHE)
    parser.add_argument("--report", default=REPORT)
    args = parser.parse_args(argv)

    os.chdir(ROOT)

    if args.rescan:
        if not os.path.exists(args.scan_cache):
            print("rescanning ->", args.scan_cache)
        data = scan(http_fetch, time.strftime("%Y-%m-%d"), log=print)
        with io.open(args.scan_cache, "w", encoding="utf-8", newline="\n") as handle:
            json.dump(data, handle, ensure_ascii=False, indent=1)
    elif os.path.exists(args.scan_cache):
        data = load_json(args.scan_cache)
    else:
        print("no scan cache at {0}; run with --rescan first".format(args.scan_cache))
        return 2

    local = load_json(LOCAL_STATIONS)["stations"]
    result = match(data.get("withEpg") or [], local)
    kept, collisions = dedupe_by_station(result["matched"])
    result["matched"], result["collisions"] = kept, collisions

    problems = validate(result["matched"], local)
    with io.open(args.report, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(render_report(data, result, problems))

    print("matched={0} ambiguous={1} collisions={2} unmatched={3} problems={4}".format(
        len(result["matched"]), len(result["ambiguous"]),
        len(result["collisions"]), len(result["unmatched"]), len(problems)))
    print("report ->", args.report)

    if args.write:
        if problems:
            print("refusing to write: gate failed")
            return 1
        payload = build_payload(result["matched"], time.strftime("%Y-%m-%d"))
        with io.open(MAPPING, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(json.dumps(payload, ensure_ascii=False, indent=2) + "\n")
        print("wrote", MAPPING, len(payload["stations"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
