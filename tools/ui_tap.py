"""按可见文字定位并点击：uiautomator dump → 找节点中心 → input tap。

车机档下控件坐标会随 density 变化，写死坐标的脚本一改布局就废，
所以统一走"按文字找再点击"。dump 每次重取，不复用上一次的文件，
避免拿旧界面当本次结论（项目里踩过这个假通过坑）。
"""
import argparse
import os
import re
import subprocess
import sys
import time

for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

NODE_RE = re.compile(r"<node\b[^>]*?/>(?:[^<]*<node)", re.S)
ADB = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe")


def adb(*args, check=True):
    cmd = [ADB] + list(args)
    # 必须显式指定 UTF-8：Windows 上 text=True 默认按 GBK 代码页解码，
    # 而 dumpsys / uiautomator 输出里常有 UTF-8 字节，会直接报
    # UnicodeDecodeError 把读取线程带崩（实测踩过）
    r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8",
                       errors="replace", timeout=60)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} 失败: {r.stderr.strip()[:200]}")
    return (r.stdout or "").strip()


def dump(tag="cur", retries=4):
    """取当前界面 XML。返回 (xml 文本, 落盘路径)。

    uiautomator dump 在界面动画中会偶发失败（实测：刚点完分类就抓），
    重试几次而不是抛异常带崩整轮巡检。
    """
    last = ""
    for i in range(retries):
        r = subprocess.run([ADB, "shell", "uiautomator", "dump", "/sdcard/ui.xml"],
                           capture_output=True, text=True, encoding="utf-8",
                           errors="replace", timeout=60)
        last = (r.stdout or "") + (r.stderr or "")
        if r.returncode == 0 and "dumped to" in last:
            break
        time.sleep(1.5 + i)
    out = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), ".qtest",
                       f"ui_{tag}.xml")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "wb") as f:
        f.write(subprocess.run([ADB, "exec-out", "cat", "/sdcard/ui.xml"],
                               capture_output=True, timeout=60).stdout)
    return open(out, encoding="utf-8", errors="replace").read(), out


def node_bounds(xml, want):
    """找 text 或 content-desc 含 want 的可点击优先节点，返回 (cx, cy, 文字, bounds)。"""
    best = None
    for m in re.finditer(r'<node\b[^>]*>', xml):
        s = m.group(0)
        text = (re.search(r'text="([^"]*)"', s) or [None, ""])[1]
        desc = (re.search(r'content-desc="([^"]*)"', s) or [None, ""])[1]
        if want not in text and want not in desc:
            continue
        bm = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s)
        if not bm:
            continue
        x1, y1, x2, y2 = (int(v) for v in bm.groups())
        if x2 <= x1 or y2 <= y1:
            continue
        clickable = 'clickable="true"' in s
        cand = (0 if clickable else 1, x1, y1, x2, y2, text or desc)
        if best is None or cand < best:
            best = cand
    if not best:
        return None
    _, x1, y1, x2, y2, label = best
    return (x1 + x2) // 2, (y1 + y2) // 2, label, (x1, y1, x2, y2)


def swipe_up():
    """向上滚一屏（手指从下往上）。用屏幕尺寸算行程，不写死坐标。"""
    m = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', dump("_scroll")[0])
    w, h = (int(m.group(3)) - int(m.group(1)), int(m.group(4)) - int(m.group(2))) if m else (1080, 1920)
    cx = w // 2
    adb("shell", "input", "swipe", str(cx), str(int(h * 0.75)), str(cx), str(int(h * 0.25)), "400")
    time.sleep(1.0)


def tap(want, settle=1.6, max_scrolls=0):
    """按文字点击；找不到时最多滚动 max_scrolls 屏再找。

    新增条目排在清单尾部（世界 190 台、电视伴音 42 台），
    不滚动就报告“找不到”会把验收卡在可见区上。
    """
    for attempt in range(max_scrolls + 1):
        xml, _ = dump("find")
        hit = node_bounds(xml, want)
        if hit:
            cx, cy, label, bounds = hit
            print(f"点击「{label}」@({cx},{cy}) bounds={bounds}" + (f"（滚 {attempt} 屏）" if attempt else ""))
            adb("shell", "input", "tap", str(cx), str(cy))
            time.sleep(settle)
            return True
        if attempt < max_scrolls:
            swipe_up()
    print(f"[FAIL] 滚动 {max_scrolls} 屏后仍找不到「{want}」")
    return False


def audio_playing(pkg):
    """系统级证据：AudioService 里本包有 state:started 的播放器。

    不看 App 自己的“正在播放”字样：那个文字来自 playWhenReady，
    流拉不到时也可能短暂显示为正在播放。
    """
    out = adb("shell", "dumpsys", "audio")
    block = re.findall(r"AudioPlaybackConfiguration.*?(?=AudioPlaybackConfiguration|$)", out, re.S)
    for b in block:
        if pkg in b and re.search(r"state:started", b):
            m = re.search(r"usage=AudioAttributes: usage=(\S+)", b)
            return True, (m.group(1) if m else "started")
    return False, "未见本包的 started 播放器"


def goto_home(pkg):
    """确定性地回到首页。

    不用 KEYCODE_BACK“退回”：实测从播放页按两次 BACK 会把 App 整个退出到桌面，
    后续所有“找不到节点”都是这个原因，不是布局问题。
    """
    adb("shell", "am", "force-stop", pkg)
    time.sleep(1.0)
    adb("shell", "am", "start", "-n", f"{pkg}/com.radio.chinese.MainActivity")
    time.sleep(9.0)


def goto_category_station(pkg, category, station):
    goto_home(pkg)
    if not tap("分类", max_scrolls=2):
        return False
    if not tap(category, max_scrolls=2):
        return False
    return tap(station, settle=1.0, max_scrolls=12)


def type_text(query: str, attempts: int = 3):
    """逐字符输入，并**确认输进去了**。

    实测：一次 `input text "Linn"` 在 Compose 输入框上只收到 "L"，另一次一个字也没进。
    不校验就会把 IME 故障当成“这个台搜不到”的数据结论，所以输完回读屏幕。
    """
    for i in range(attempts):
        for ch in query:
            adb("shell", "input", "text", "%s" if ch == " " else ch)
            time.sleep(0.2)
        time.sleep(1.0)
        xml, _ = dump("typed")
        if query.lower() in xml.lower():
            return True
        print(f"  [warn] 第 {i + 1} 次输入未生效（只到：“{query[:4]}”），重试", file=sys.stderr)
        time.sleep(0.6)
    return False


def search_and_play(pkg, query, station, wait=13.0):
    """走搜索入口播放：用于排在长列表尾部的条目。

    世界分类 190 台，新增项附在清单末尾，靠滚动定位要 20+ 屏，
    而搜索是真实用户路径，走它同也能顺带验证搜索没被数据变更弄坏。
    """
    goto_home(pkg)
    if not tap("搜索电台名称或频率", max_scrolls=1):
        return False
    if not type_text(query):
        print(f"[FAIL] 输入框拿不到关键词「{query}」：IME 问题，不是电台问题")
        return False
    time.sleep(2.5)
    if not tap(station, settle=1.0, max_scrolls=6):
        return False
    time.sleep(wait)
    xml, _ = dump("search_" + station[:6])
    labels = [t for t in re.findall(r'text="([^"]*)"', xml) if t.strip()]
    print(f"  界面状态文字: {[t for t in labels if t in ('正在播放', '缓冲中…', '已暂停')] or labels[-4:]}")
    ok, why = audio_playing(pkg)
    print(f"  系统侧播放器: {'OK' if ok else 'FAIL'} ({why})")
    return ok


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    d = sub.add_parser("dump", help="抓当前界面并报告视口")
    d.add_argument("--tag", default="cur")
    t = sub.add_parser("tap", help="按文字点击")
    t.add_argument("text")
    t.add_argument("--tag", default="tap")
    s = sub.add_parser("screenshot", help="截图留证")
    s.add_argument("--tag", default="cur")
    y = sub.add_parser("play", help="从首页进分类点台并做系统级出声断言")
    y.add_argument("category")
    y.add_argument("station")
    y.add_argument("--pkg", default="com.radio.chinese.debug")
    y.add_argument("--wait", type=float, default=13.0)
    z = sub.add_parser("searchplay", help="用搜索入口播放并做系统级出声断言")
    z.add_argument("query")
    z.add_argument("station")
    z.add_argument("--pkg", default="com.radio.chinese.debug")
    z.add_argument("--wait", type=float, default=13.0)
    args = p.parse_args()

    if args.cmd == "dump":
        xml, path = dump(args.tag)
        m = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
        x1, y1, x2, y2 = (int(v) for v in m.groups()) if m else (0, 0, 0, 0)
        print(f"已保存 {path}  视口={x2-x1}x{y2-y1} 横屏={x2-x1 > y2-y1}")
        return 0
    if args.cmd == "screenshot":
        out = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                           ".qtest", f"a1_{args.tag}.png")
        with open(out, "wb") as f:
            f.write(subprocess.run([ADB, "exec-out", "screencap", "-p"],
                                   capture_output=True, timeout=60).stdout)
        print(f"截图已保存 {out}")
        return 0
    if args.cmd == "play":
        print(f"播放验收：{args.category} / {args.station}")
        if not goto_category_station(args.pkg, args.category, args.station):
            return 1
        time.sleep(args.wait)
        xml, _ = dump("play_" + args.station[:6])
        labels = [t for t in re.findall(r'text="([^"]*)"', xml) if t.strip()]
        tail = [t for t in labels if t in ("正在播放", "缓冲中…", "已暂停")]
        print(f"  界面状态文字: {tail or labels[-4:]}")
        ok, why = audio_playing(args.pkg)
        print(f"  系统侧播放器: {'OK' if ok else 'FAIL'} ({why})")
        return 0 if ok else 1
    if args.cmd == "searchplay":
        print(f"搜索播放验收：{args.query} -> {args.station}")
        return 0 if search_and_play(args.pkg, args.query, args.station, args.wait) else 1
    xml, _ = dump(args.tag)
    return 0 if tap(args.text) else 1


if __name__ == "__main__":
    sys.exit(main())
