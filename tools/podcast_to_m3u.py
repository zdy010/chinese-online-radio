"""播客 RSS → 音频库能直接浏览的 .m3u 清单（可选下载成目录树）。

为什么需要这个工具：app 侧只有 LOCAL/WEBDAV/M3U/HTTP 四种来源（domain/AudioModels.kt），
`M3uBrowser` 认的是「每行一条直链 + #EXTINF 标题」的文本清单，**不解析播客 RSS**。
所以播客节目要先在 PC 上摊平成 m3u（挂到 WebDAV/HTTP 上即可浏览），
或者下载成「节目 → 集数」目录树（这样才有索引、拼音搜索、倍速、断点续播）。

收录边界沿用电台清单那条红线：只收内容提供方自我公开发布的免费流；
平台转载的 UGC 合集默认拒绝，必须显式 --allow-ugc 才放行。
"""
import argparse
import re
import sys
from pathlib import Path
from urllib.parse import urlparse
from xml.etree import ElementTree as ET

AUDIO_EXTS = (".mp3", ".m4a", ".aac", ".ogg", ".opus", ".flac", ".wav", ".m3u8")
ITUNES_NS = "http://www.itunes.com/dtds/podcast-1.0.dtd"

# 平台转载源：这些站上的条目绝大多数是别人搬运的他人内容，
# 无法从 feed 本身确认“内容提供方 = 发布方”，沿用电台收录红线默认拒绝。
# 创作者自己上传的站（如自有域名、SoundCloud 官方账号）不列在这里。
UGC_HOSTS = ("ximalaya.com", "xmcdn.com", "qingting.fm", "lizhi.fm", "lrts.me", "imalbum.com")


class FeedRejected(Exception):
    """feed 不可信（带 DTD/外部实体）或根本不是可解析的 XML。"""


def _parse_root(xml_text: str):
    text = xml_text or ""
    head = text[:4096].lower()
    # 默认 XML 解析器不抗实体攻击：声明了 DTD/ENTITY 的 feed 一律不碰
    if "<!doctype" in head or "<!entity" in head:
        raise FeedRejected("feed 含 DTD/实体声明，拒绝解析")
    try:
        return ET.fromstring(text.encode("utf-8"))
    except ET.ParseError as e:
        raise FeedRejected(f"不是可解析的 XML：{e}")


def feed_title(xml_text: str) -> str:
    """频道名：当下载目录名用，取不到就用默认名，不返空。"""
    title = (_parse_root(xml_text).findtext("./channel/title") or "").strip()
    return title or "播客节目"


def parse_feed(xml_text: str) -> list:
    """取 feed 里的音频条目，按 feed 原顺序，同一地址只留第一条。"""
    root = _parse_root(xml_text)

    out, seen = [], set()
    for item in root.iter("item"):
        for en in item.iter("enclosure"):
            url = (en.get("url") or "").strip()
            ctype = (en.get("type") or "").strip().lower()
            if not url.startswith(("http://", "https://")):
                continue
            # 自架播客普遍不写 type：没 type 时按扩展名判，有 type 时只认 audio/。
            # 两边都命不中就不收——宁漏一条不可播的，不要把 pdf/封面写成曲目。
            if ctype.startswith("audio/"):
                audio = True
            elif ctype:
                continue
            else:
                audio = any(urlparse(url).path.lower().endswith(e) for e in AUDIO_EXTS)
            if not audio:
                continue
            if url in seen:
                break
            seen.add(url)
            out.append({
                "title": (item.findtext("title") or "").strip(),
                "url": url,
                "size": int(re.sub(r"\D", "", en.get("length") or "") or 0),
                "duration": (item.findtext(f"{{{ITUNES_NS}}}duration")
                             or item.findtext("duration") or "").strip(),
            })
            break
    return out


def parse_duration(raw) -> int:
    """`mm:ss` / `h:mm:ss` / 裸秒 → 总秒数；猜不出来一律 0。

    写 0 而不是拍一个“常见时长”进去：EXTINF 的秒数会直接变成车机进度条的总长，
    编一个数字就是让界面说谎。
    """
    s = str(raw or "").strip()
    if not s:
        return 0
    parts = s.split(":")
    if not all(p.isdigit() for p in parts):
        return 0
    total = 0
    for p in parts:
        total = total * 60 + int(p)
    return total


def render_m3u(tracks) -> str:
    """输出 `M3uBrowser` 能浏览的清单：#EXTINF:<秒>,<标题> + 下一行原样直链。"""
    lines = ["#EXTM3U"]
    for t in tracks:
        title = re.sub(r"\s+", " ", t.get("title") or "").strip()
        lines.append(f"#EXTINF:{parse_duration(t.get('duration'))},{title}")
        lines.append(t["url"])           # 不转义不剥 query：token 参数掉了就 403
    return "\n".join(lines) + "\n"


def is_ugc_host(url: str) -> bool:
    host = urlparse(url or "").netloc.lower()
    return any(p in host for p in UGC_HOSTS)


def partition_by_license(tracks, allow_ugc: bool = False):
    """返回 (可用条目, 平台转载条目)。

    放行时第二个返回值仍然列出被放行的高风险条目：否则今天收了哪些搬运内容，
    以后没人能重查。
    """
    flagged = [t for t in tracks if is_ugc_host(t.get("url", ""))]
    if allow_ugc:
        return list(tracks), flagged
    kept = [t for t in tracks if not is_ugc_host(t.get("url", ""))]
    return kept, flagged


def safe_filename(name, max_len: int = 120) -> str:
    """文件系统安全名：非法字符删除，空白压缩，去尾部点；空了就叫“未命名”。"""
    s = re.sub(r'[\\/:*?"<>|\x00-\x1f]', "", str(name or ""))
    s = re.sub(r"\s+", " ", s).strip().rstrip(".").strip()
    return (s[:max_len].rstrip() or "未命名")


def audio_ext_of(url: str) -> str:
    path = (urlparse(url or "").path or "").lower()
    for ext in AUDIO_EXTS:
        if path.endswith(ext):
            return ext
    return ""


def episode_filename(track, index: int):
    """`0001 标题.mp3`；拿不到音频扩展名就返回 None，绝不编一个后缀。

    无后缀文件落到 WebDAV 后，app 侧“无扩展名一律保留”的过滤规则会把它当曲目列出来，
    点开却放不出，还洗不掉——宁可不下载。
    """
    ext = audio_ext_of(track.get("url", ""))
    if not ext:
        return None
    return f"{int(index):04d} {safe_filename(track.get('title'))}{ext}"


def plan_downloads(tracks, root_dir):
    """把曲目排成下载计划：返回 ([(url, 本地路径)], 跳过数)。"""
    plan, skipped = [], 0
    for i, t in enumerate(tracks, 1):
        name = episode_filename(t, i)
        if not name:
            skipped += 1
            continue
        plan.append((t["url"], str(Path(root_dir) / name)))
    return plan, skipped


def read_feed(source: str, fetch=None) -> str:
    """feed 文本的四个入口：注入的 fetch（单测/调用方）> stdin > 本地文件 > http。

    真取数只在传了 http 地址时才发生；单测永远用注入假件，不拿网络当断言。
    """
    if fetch is not None:
        return fetch()
    if source == "-":
        return sys.stdin.read()
    if source.lower().startswith(("http://", "https://")):
        return http_get(source)
    return Path(source).read_text(encoding="utf-8", errors="replace")


def http_get(url: str, timeout: float = 25.0) -> str:
    import requests

    r = requests.get(url, timeout=timeout,
                     headers={"User-Agent": "Mozilla/5.0 (Linux; Android 12) RSS-Reader"})
    r.raise_for_status()
    return r.text


def download_tracks(plan, log=print, get=None) -> int:
    """逐条落文件；失败不中断整批，但必须报数。

    先写 `.part` 再改名：掉线留下的半截文件如果直接落在目标路径上，
    下次“已存在且非空”的判定会把它当成已下完，WebDAV 里就是一首永远放不完的曲目。
    """
    if get is None:
        import requests

        def get(url, **kw):
            return requests.get(url, stream=True,
                                headers={"User-Agent": "Mozilla/5.0 (Linux; Android 12)"}, **kw)

    ok = 0
    for url, dest in plan:
        p = Path(dest)
        if p.exists() and p.stat().st_size > 0:
            ok += 1
            continue
        tmp = p.with_name(p.name + ".part")
        try:
            with get(url, timeout=60) as r:
                r.raise_for_status()
                p.parent.mkdir(parents=True, exist_ok=True)
                with tmp.open("wb") as f:
                    for chunk in r.iter_content(1 << 16):
                        f.write(chunk)
            tmp.replace(p)
            ok += 1
        except Exception as e:  # noqa: BLE001
            try:
                tmp.unlink()      # 残缺文件不能留在目录里冒充曲目
            except OSError:
                pass
            log(f"  [下载失败] {p.name} : {type(e).__name__} {str(e)[:60]}")
    return ok


def main(argv=None, fetch=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--feed", required=True,
                    help="播客 RSS：http(s) 地址 / 本地 xml 路径 / - 表示从标准输入读")
    ap.add_argument("--out", required=True, help="写出的 .m3u 路径")
    ap.add_argument("--limit", type=int, default=0, help="只取前 N 集（0=全要）")
    ap.add_argument("--allow-ugc", action="store_true",
                    help="放行平台转载源（默认拒绝：发布方不是内容提供方）")
    ap.add_argument("--download", default="", help="把曲目下成目录树（挂 WebDAV 才有索引/倍速）")
    args = ap.parse_args(argv)

    try:
        text = read_feed(args.feed, fetch)
        tracks = parse_feed(text)
    except FeedRejected as e:
        print(f"feed 不可用：{e}", file=sys.stderr)
        return 3

    kept, flagged = partition_by_license(tracks, allow_ugc=args.allow_ugc)
    if args.limit:
        kept = kept[:args.limit]
    if not kept:
        print(f"没有可收的条目：feed 共 {len(tracks)} 条，其中 {len(flagged)} 条来自平台转载。"
              f"确认这些是发布方自我公开的，再加 --allow-ugc", file=sys.stderr)
        return 2

    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text(render_m3u(kept), encoding="utf-8")
    tail = f"（其中 {len(flagged)} 条来自平台转载，已显式放行）" if flagged else ""
    print(f"已写出 {args.out}：{len(kept)} 集{tail}")

    if args.download:
        folder = Path(args.download) / safe_filename(feed_title(text))
        plan, skipped = plan_downloads(kept, folder)
        print(f"下载计划 {len(plan)} 个文件"
              + (f"，跳过 {skipped} 个拿不到音频后缀的条目" if skipped else ""))
        print(f"实际落盘 {download_tracks(plan)} 个 -> {folder}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
