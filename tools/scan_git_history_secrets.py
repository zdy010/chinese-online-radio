"""扫 git 历史里是否含本地配置中的真实凭证（只报命中位置，绝不打印明文）。

用途：force push 重写历史前先确认"要清什么、在哪些 blob"，重写完再复扫一次作为验收。
用法:
    python tools/scan_git_history_secrets.py            # 扫全历史
    python tools/scan_git_history_secrets.py --worktree # 只扫当前工作树
"""
import os
import re
import subprocess
import sys
from pathlib import Path

# 在历史重写用的 mirror 副本里扫时，工作树里没配置文件：用环境变量指回真实仓库根
_ROOT = Path(os.environ.get("SECRET_CONFIG_ROOT", "."))
CONFIGS = [
    _ROOT / "app/src/main/java/com/radio/chinese/config/EasterEggConfig.kt",
    _ROOT / "app/src/main/java/com/radio/chinese/config/YunPanConfig.kt",
]
# 明显不是凭证的值：占位符、通用字面量、协议前缀
PLACEHOLDERS = {
    "xxx", "xxx2", "replace_me", "username", "password", "webdav", "example",
    "android", "general", "music", "news", "true", "false",
}


def collect_needles():
    """从本地配置里挑出"看起来是真实凭证"的字面量。"""
    needles = set()
    for p in CONFIGS:
        if not p.exists():
            print(f"[warn] 配置文件不可读，无法构成核对基准：{p}")
            continue
        text = p.read_text(encoding="utf-8")
        for m in re.finditer(r'"([A-Za-z0-9_\-]{6,})"', text):
            v = m.group(1)
            low = v.lower()
            if low in PLACEHOLDERS or v.startswith("http"):
                continue
            needles.add(v)
        for m in re.finditer(r'"(\d{9,})"', text):   # 手机号 / 长数字快捷码
            needles.add(m.group(1))
    return needles


def blobs_all():
    """全历史对象：产出 [(blob_sha, path)]，同一 sha 可能对应多个路径。"""
    out = subprocess.run(
        ["git", "rev-list", "--objects", "--all"],
        capture_output=True, text=True, encoding="utf-8", errors="replace"
    ).stdout
    pairs = []
    for line in out.splitlines():
        parts = line.split(" ", 1)
        if len(parts) == 2 and "/" in parts[1]:
            pairs.append((parts[0], parts[1]))
    return pairs


def main():
    needles = sorted(collect_needles())     # 必须是列表：后面按下标取用
    if not needles:
        print("没从本地配置里认出凭证字面量（可能都是占位符）")
        return 0
    print(f"待查凭证值 {len(needles)} 个（不打印明文），开始扫全历史 blob ...")

    hits = {}          # blob sha 或 工作树路径 -> 命中的 needle 下标集合
    if "--worktree" in sys.argv:
        # 只扫被跟踪的文件：build 产物与依赖目录会干扰结果且不会入库
        tracked = subprocess.run(
            ["git", "ls-files", "-z"], capture_output=True
        ).stdout.split(b"\0")
        for raw in tracked:
            if not raw:
                continue
            path = Path(raw.decode("utf-8", "replace"))
            if not path.is_file():
                continue
            data = path.read_bytes()
            for i, n in enumerate(needles):
                if n.encode("utf-8", "ignore") in data:
                    hits.setdefault(str(path), set()).add(i)
    else:
        pairs = blobs_all()
        # cat-file --batch 每行只认对象名：喂“sha 路径”会让所有对象解析失败、
        # 于是整体“没有命中”——那是假干净，必须只喂 sha。
        shas = sorted({s for s, _ in pairs})
        payload = ("\n".join(shas) + "\n").encode()
        data = subprocess.run(
            ["git", "cat-file", "--batch"], input=payload, capture_output=True
        ).stdout
        if b"missing" in data[:4096] or not data.strip():
            print("扫描器自检失败：cat-file 没返回可扫内容，不下“干净”结论。")
            return 2
        # 正控制：任何 kt blob 必含此字串，这都扫不到就是扫描链坏了
        control = b"package com.radio.chinese"
        if control not in data:
            print("扫描器自检失败：正控制字串没扫到，不下“干净”结论。")
            return 2
        # 反向自检：重写后的哨兵字串应存在（否则“没命中”可能只是没扫到东西）
        if os.environ.get("EXPECT_SCRUBBED") == "1" and b"REMOVED-PASSWORD" not in data:
            print("扫描器自检失败：未见重写哨兵 REMOVED-PASSWORD，重写可能没生效。")
            return 2
        cur_sha = None
        for line in data.split(b"\n"):
            m = re.match(rb"^([0-9a-f]{40}) blob (\d+)$", line)
            if m:
                cur_sha = m.group(1).decode()
                continue
            if cur_sha is None:
                continue
            for i, n in enumerate(needles):
                if n.encode("utf-8", "ignore") in line:
                    hits.setdefault(cur_sha, set()).add(i)

    if not hits:
        print("本次全历扫描未发现命中（正控制已过了，结果可信）。")
        return 0

    sha_to_paths = {}
    for sha, path in blobs_all():
        sha_to_paths.setdefault(sha, []).append(path)

    print(f"\n命中 {len(hits)} 个 blob：")
    for sha, idxs in list(hits.items())[:20]:
        if "--worktree" in sys.argv:
            where = sha                      # 工作树模式下 key 就是路径
        else:
            where = ", ".join(sha_to_paths.get(sha, ["<未知路径>"])[:3])
        # 只报长度与序号，不输出部分明文（8 位授权码露 4 位等于大幅降低爆破成本）
        vals = ", ".join(f"#{i}(len{len(needles[i])})" for i in sorted(idxs))
        print(f"  {sha[:10] if where != sha else 'worktree':10}  {where}  含凭证 {vals}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
