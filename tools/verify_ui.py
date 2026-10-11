"""uiautomator dump 的读取器：把界面 XML 变成可断言的文本清单。

为什么不用 inline python：PowerShell 对嵌套引号的处理会把 `python -c "..."`
卡在续行提示符里（实测踩过），而这段解析逻辑每次验收都要用。
"""
import argparse
import re
import sys

for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

TEXT_RE = re.compile(r'text="([^"]*)"')
CONTENT_RE = re.compile(r'content-desc="([^"]*)"')
NODE_RE = re.compile(r'<node\b[^>]*>', re.S)
BOUNDS_RE = re.compile(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')


def texts(xml: str) -> list:
    """界面上所有非空文本节点，按出现顺序去重。"""
    out = []
    for m in TEXT_RE.finditer(xml):
        t = m.group(1).strip()
        if t and t not in out:
            out.append(t)
    return out


def root_size(xml: str):
    m = BOUNDS_RE.search(xml)
    if not m:
        return None
    x1, y1, x2, y2 = (int(v) for v in m.groups())
    return x2 - x1, y2 - y1


def find_bad_state(xml: str) -> list:
    """界面不该出现的状态：崩溃弹窗、加载失败、空白。"""
    bad = []
    joined = " ".join(texts(xml))
    for kw in ("已停止运行", "无响应", "找不到应用"):
        if kw in joined:
            bad.append(kw)
    for kw in ("正在加载电台…", "正在加载分类…"):
        if kw in joined:
            bad.append("卡在加载: " + kw)
    return bad


def expect_counts(xml: str, pairs) -> list:
    """校验分类计数。

    页面上“戏曲”与“12 个电台”是两个独立文本节点，不是在同一个节点里，
    所以取分类名节点后的第一个“N 个电台”作为它的计数。
    """
    problems = []
    nodes = texts(xml)
    for label, minimum in pairs:
        hit = None
        for i, t in enumerate(nodes):
            if t.strip() != label.strip():
                continue
            for nxt in nodes[i + 1:i + 4]:
                m = re.search(r"(\d+)\s*个电台", nxt)
                if m:
                    hit = int(m.group(1))
                    break
            break
        if hit is None:
            problems.append(f"页面上找不到分类「{label}」的计数")
        elif hit < minimum:
            problems.append(f"「{label}」计数 {hit} < 期望 {minimum}")
        else:
            print(f"  [OK] {label} = {hit}（期望≥{minimum}）")
    return problems


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("xml")
    p.add_argument("--expect", action="append", default=[],
                   help="期望分类计数，形如 戏曲=12；可重复")
    args = p.parse_args()

    xml = open(args.xml, encoding="utf-8").read()
    size = root_size(xml)
    print(f"视口: {size[0]}x{size[1]} px  横屏={size[0] > size[1]}")
    print("可见文本:", " | ".join(texts(xml)[:40]))

    bad = find_bad_state(xml)
    for b in bad:
        print("  [FAIL]", b)

    pairs = []
    for e in args.expect:
        label, _, num = e.partition("=")
        pairs.append((label, int(num)))
    problems = expect_counts(xml, pairs) if pairs else []
    for pr in problems:
        print("  [FAIL]", pr)

    ok = not bad and not problems
    print("界面校验通过" if ok else "界面校验未通过")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
