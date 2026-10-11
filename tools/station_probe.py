"""电台流可达性探针：把"这个源到底能不能播出声音"变成一个可信的三态判定。

三态语义（不可混淆，这是本模块存在的理由）：
  LIVE    —— 真取到音频内容：HLS 验到媒体播放列表里的分片名，直链验到音频字节
  GONE    —— 确定性地死：404/410，或返回 HTML 错误页，或播放列表里一个分片都没有
  UNKNOWN —— 不确定：超时、连接重置、403/429、5xx。既不算活也不算死，必须重试后再表态

为什么不"只看 HTTP 200"：实测中 satellitepull.cnr.cn 的 playlist.m3u8 返回 200 时，
body 是一个带 wsSession/wsIPSercert/wsBindIP 的 token 变体地址——清单活着不代表有声音。
反过来，故意编造的频道码里，有的回 404（确实没这个台），有的直接连接超时，
把超时当"源已死"会误杀好源，把超时当"可用"会写进一堆放不出来的台。

注意：不跨请求二次直连 .ts 分片。token CDN 的会话绑请求上下文，用上一次清单里的
session 再去取分会假失败（实测 http=000）。判定到"新鲜会话内拿到分片名"为止。
"""
from urllib.parse import urlparse, urlunparse

import requests

LIVE = "live"
GONE = "gone"
UNKNOWN = "unknown"

# 首字节指纹：不少 Icecast 源把 Content-Type 报成 application/octet-stream，只能认魔数
_AUDIO_MAGICS = (
    b"ID3",            # MP3 with tag
    b"fLaC",           # FLAC
    b"OggS",           # OGG/Opus
    b"RIFF",           # WAV
    b"FORM",           # AIFF
    b"\x00\x00\x01\xba",  # MPEG audio
    b"\x00\x00\x01\xb3",
)

_MIN_AUDIO_BYTES = 128


def resolve_ref(base: str, ref: str) -> str:
    """把播放列表里的引用解析成绝对地址；拿不准就回空串，绝不拼一个臆造地址。"""
    if not ref:
        return ""
    ref = ref.strip()
    if not base:
        return ""
    if ref.startswith("//"):
        scheme = urlparse(base).scheme
        return f"{scheme}:{ref}"
    p = urlparse(ref)
    if p.scheme in ("http", "https") and p.netloc:
        return ref
    b = urlparse(base)
    if not b.netloc:
        return ""
    # 引用自身可能带 ?wsSession=... 这类会话参数：路径只能取 p.path，
    # 把整个 ref 当路径拼再补一次 query，就会造出 ...ts?a=1?a=1 这种放不出来的地址
    if ref.startswith("/"):
        path = p.path
    else:
        parent = b.path.rsplit("/", 1)[0] if "/" in b.path else ""
        path = f"{parent}/{p.path}"
    return urlunparse((b.scheme, b.netloc, path, "", p.query, ""))


def parse_master_variants(text: str) -> list:
    """取主播放列表里的变体地址（#EXT-X-STREAM-INF 的下一行非注释行）。"""
    if not text or "<html" in text[:400].lower():
        return []
    lines = text.splitlines()
    out = []
    for i, line in enumerate(lines):
        if line.strip().upper().startswith("#EXT-X-STREAM-INF"):
            for nxt in lines[i + 1:]:
                nxt = nxt.strip()
                if not nxt or nxt.startswith("#"):
                    continue
                out.append(nxt)
                break
    return out


def has_media_sequence(text: str) -> bool:
    return any(l.strip().upper().startswith("#EXT-X-MEDIA-SEQUENCE") for l in (text or "").splitlines())


def count_segments(text: str) -> int:
    """数 #EXTINF 之后真正跟着的 URI 行；空行与注释行不算分片。"""
    if not text:
        return 0
    lines = (text or "").splitlines()
    n, pending = 0, False
    for line in lines:
        s = line.strip()
        if s.startswith("#EXTINF"):
            pending = True
            continue
        if not s:
            continue
        if s.startswith("#"):
            continue
        if pending:
            n += 1
            pending = False
    return n


def hls_verdict(sequence_ok: bool, segment_count: int, master_variants: int = 0, single_level: bool = False) -> str:
    """HLS 判活：必须既有媒体序号又有分片名。只有主列表时下钻一层再判。"""
    if segment_count >= 1 and (sequence_ok or single_level):
        return LIVE
    if master_variants >= 1:
        return UNKNOWN  # 主列表活着不等于有声音，交由链式验证下钻
    return GONE


def _has_audio_magic(head: bytes) -> bool:
    """只看魔数，不管长度：ID3 + 几十字节的头就足以确认是音频。"""
    if not head:
        return False
    if any(head.startswith(m) for m in _AUDIO_MAGICS):
        return True
    # 裸 MP3 帧头：0xFF + 0xE0/0xF0 掩码
    if len(head) >= 2 and head[0] == 0xFF and (head[1] & 0xE0) == 0xE0:
        return True
    return False


def _is_audio_content_type(ct: str) -> bool:
    return any(k in ct for k in ("audio", "mpeg", "mp3", "aac", "ogg", "opus", "flac"))


def hls_manifest_evidence(text: str) -> str:
    """先把“这堆 body 到底是什么”说清楚，再谈判活判死。

    返回：playlist / app-error / html / empty / other
    app-error 必须单独拎出来：live.xmcdn.com 对已下架的台回 HTTP 200 +
    {"msg":"电台流获取失败","ret":2011}，当成 200 可用会写入大量放不出声的台。
    """
    t = (text or "").lstrip()
    head = t[:400].lower()
    if not t:
        return "empty"
    if t.startswith("{") or '"ret"' in head or '"msg"' in head:
        return "app-error"
    if "<html" in head or "<!doctype" in head:
        return "html"
    if "#extm3u" in head:
        return "playlist"
    return "other"


def body_verdict(status: int, text: str) -> str:
    """状态码 + body 证据一起出结论；状态码永远不能单独决定判活。"""
    if status in (404, 410):
        return GONE
    if status not in (200, 206):
        return UNKNOWN
    ev = hls_manifest_evidence(text)
    if ev == "app-error":
        return UNKNOWN  # 服务端错体不等于台不存在
    if ev != "playlist":
        return GONE
    if parse_master_variants(text):
        return UNKNOWN  # 主列表活着不等于有声音，要下钻
    return hls_verdict(has_media_sequence(text), count_segments(text), single_level=True)


def classify_direct(status: int, content_type: str, head: bytes) -> str:
    """直链（MP3/Icecast/aac）判定：状态码 + 内容类型 + 首字节三者一起说话。"""
    ct = (content_type or "").lower()
    if status in (404, 410):
        return GONE
    if status in (401, 403, 429) or status >= 500:
        return UNKNOWN
    if status in (301, 302, 303, 307, 308):
        return UNKNOWN  # 跳板没跟到底，不能算结论
    if "html" in ct or (head[:200].lstrip()[:1] == b"<"):
        return GONE
    if status == 200 or status == 206:
        # 判活顺序：魔数 > (音频类型 + 足量字节) > 证据不足 UNKNOWN > 确实非音频 GONE。
        # 音频 Content-Type 单独不算数——错页挂 audio/mpeg 的服务器真实存在。
        if _has_audio_magic(head):
            return LIVE
        if _is_audio_content_type(ct) and len(head) >= _MIN_AUDIO_BYTES:
            return LIVE
        if len(head) < _MIN_AUDIO_BYTES:
            return UNKNOWN
        return GONE
    return UNKNOWN


def _head_bytes(resp, limit=4096) -> bytes:
    try:
        return resp.raw.read(limit, decode_content=True) or b""
    except Exception:
        return b""


def probe(url: str, timeout: float = 12.0, session: requests.Session = None) -> dict:
    """单条 URL 的分层实测。返回 {verdict, why, detail}。"""
    s = session or requests.Session()
    headers = {"User-Agent": "Mozilla/5.0 (Linux; Android 12) ExoPlayer/1.3.1"}
    u = (url or "").strip()
    if not u.lower().startswith(("http://", "https://")):
        return {"verdict": GONE, "why": "not-http", "detail": u}
    try:
        if u.lower().split("?")[0].endswith(".m3u8"):
            return _probe_hls(u, s, headers, timeout)
        return _probe_direct(u, s, headers, timeout)
    except requests.Timeout:
        return {"verdict": UNKNOWN, "why": "timeout", "detail": u}
    except requests.ConnectionError:
        return {"verdict": UNKNOWN, "why": "conn-error", "detail": u}
    except Exception as e:  # 解析炸了不等于源死了
        return {"verdict": UNKNOWN, "why": "exception", "detail": f"{type(e).__name__}: {e}"}


def _probe_hls(url: str, s, headers, timeout) -> dict:
    r = s.get(url, headers=headers, timeout=timeout, stream=True)
    with r:
        if r.status_code in (404, 410):
            return {"verdict": GONE, "why": "manifest-404", "detail": str(r.status_code)}
        if r.status_code not in (200, 206):
            return {"verdict": UNKNOWN, "why": f"manifest-{r.status_code}", "detail": ""}
        text = _head_text(r, 64 * 1024)
    ev = hls_manifest_evidence(text)
    if ev == "app-error":
        return {"verdict": UNKNOWN, "why": "app-error-body", "detail": text[:90].strip()}
    if ev != "playlist":
        return {"verdict": GONE, "why": f"body-{ev}", "detail": f"len={len(text)}"}
    variants = parse_master_variants(text)
    seq = has_media_sequence(text)
    segs = count_segments(text)
    if not variants:
        if seq and segs >= 1:
            return {"verdict": LIVE, "why": "single-level-playlist", "detail": f"segments={segs}"}
        return {"verdict": GONE, "why": "empty-playlist", "detail": f"len={len(text)}"}
    child = resolve_ref(url, variants[0])
    if not child:
        return {"verdict": UNKNOWN, "why": "unresolvable-variant", "detail": variants[0][:120]}
    r2 = s.get(child, headers=headers, timeout=timeout, stream=True)
    with r2:
        if r2.status_code in (404, 410):
            return {"verdict": GONE, "why": "variant-404", "detail": ""}
        if r2.status_code not in (200, 206):
            return {"verdict": UNKNOWN, "why": f"variant-{r2.status_code}", "detail": ""}
        text2 = _head_text(r2, 64 * 1024)
    ev2 = hls_manifest_evidence(text2)
    if ev2 != "playlist":
        # token 变体指向的二级地址给了非列表内容（错体/页）：本次会话不可用
        return {"verdict": UNKNOWN if ev2 == "app-error" else GONE,
                "why": f"variant-body-{ev2}", "detail": text2[:90].strip()}
    segs2 = count_segments(text2)
    if segs2 >= 1:
        return {"verdict": LIVE, "why": "variant-chain", "detail": f"segments={segs2}"}
    return {"verdict": GONE, "why": "variant-no-segments", "detail": f"len={len(text2)}"}


def _probe_direct(url: str, s, headers, timeout) -> dict:
    h = dict(headers)
    h["Range"] = "bytes=0-4095"
    r = s.get(url, headers=h, timeout=timeout, stream=True, allow_redirects=True)
    with r:
        head = _head_bytes(r, 4096)
        return {
            "verdict": classify_direct(r.status_code, r.headers.get("Content-Type", ""), head),
            "why": "direct",
            "detail": f"status={r.status_code} ct={r.headers.get('Content-Type', '')} bytes={len(head)}",
        }


def _head_text(resp, limit) -> str:
    raw = _head_bytes(resp, limit)
    return raw.decode("utf-8", errors="replace")


def classify_by_name(name: str) -> str:
    """候选归类：命中关键词才进薄分类，命不中回落 general，禁止硬塞。

    返回的是 StationCategory 的 key；分类的唯一定义在 Kotlin 侧
    domain/model/StationCategory.kt，这里只是取数阶段的候选粗分，
    入库前仍会用那份清单做校验（见 fetch_stations.py 的断言）。
    """
    n = name or ""
    if any(k in n for k in (
        "戏曲", "豫剧", "京剧", "越剧", "黄梅戏", "黄梅", "评剧", "昆曲", "粤剧", "曲剧",
        "评弹", "曲艺", "京胡", "梨园", "秦腔", "川剧", "晋剧", "沪剧", "闽剧", "潮剧",
        "琼剧", "汉剧", "梆子", "吕剧", "二人转", "藏戏", "京戏", "鼓曲", "弹词", "平剧",
    )):
        return "opera"
    if any(k in n for k in ("体育", "运动", "赛事", "足球")):
        return "sports"
    if "卫视" in n or n.endswith("电视") or "伴音" in n:
        return "tv_audio"
    if any(k in n for k in ("交通", "路况")):
        return "traffic"
    if any(k in n for k in ("财经", "经济", "金融")):
        return "finance"
    if any(k in n for k in ("新闻", "资讯", " News", "news")):
        return "news"
    if any(k in n for k in ("音乐", "music", "Music", "FM 古典", "古典", "经典")):
        return "music"
    if any(k in n for k in ("文艺", "文化", "故事", "文学", "阅读", "评书", "相声", "有声")):
        return "arts"
    return "general"


def probe_with_retry(url: str, attempts: int = 3, pause: float = 1.5, session=None, timeout: float = 12.0) -> dict:
    """UNKNOWN 必须重试到能表态为止；重试耗尽仍是 UNKNOWN 就原样上报，不猜。"""
    import time
    last = {"verdict": UNKNOWN, "why": "no-attempt", "detail": ""}
    for i in range(attempts):
        last = probe(url, timeout=timeout, session=session)
        if last["verdict"] in (LIVE, GONE):
            last["attempts"] = i + 1
            return last
        time.sleep(pause * (i + 1))
    last["attempts"] = attempts
    return last
