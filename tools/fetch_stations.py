"""电台清单取数 / 实测 / 入库脚本（A-1 的交付物之一）。

三个子命令，各干一件能单独重跑的事：
  mine   从 RadioBrowser 拉候选池（华语台 + 大陆台 + 官方卫星流码发现）
  probe  对候选池逐条分层实测，三态分箱，结果可续跑
  merge  把实测通过的候选写进 radio_stations.json（默认只出报告，--apply 才落盘）

为什么候选靠 RadioBrowser 挖而不是按拼音猜频道码：实测中猜出来的码大量表现为
"连接超时"而非 404（同一码 curl 回 404、requests 回超时），三态里 UNKNOWN 占比过高，
批量枚举无法收敛；而从真实数据里挖到的码（wxnxws / wxahxqgb 这类）一次就通。

分类的单一来源在 Kotlin 侧，这里运行时读 StationCategory.kt，不抄第二份表。
"""
import argparse
import concurrent.futures as cf
import hashlib
import json
import re
import sys
import threading
import time
import unicodedata
from pathlib import Path

# 控制台先改成 UTF-8：Windows 默认 GBK 代码页，台名里的 · 与特殊符号会直接把报告打印炸掉
# （实测：UnicodeEncodeError: 'gbk' codec can't encode character '\u2022'）
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

from urllib.parse import urlparse

import requests
import station_probe as sp

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
STATION_KT = ROOT / "app/src/main/java/com/radio/chinese/domain/model/StationCategory.kt"
STATIONS_JSON = ROOT / "app/src/main/assets/radio_stations.json"
WORK = ROOT / ".qtest"

RB_SERVERS = [
    "https://de1.api.radio-browser.info",
    "https://de2.api.radio-browser.info",
    "https://nl1.api.radio-browser.info",
    "https://api.radio-browser.info",
]
RB_UA = "chinese-online-radio-sync/1.0 (+https://github.com/zdy010/chinese-online-radio)"

# 盗版转播/聚合站特征串：实测候选池里出现过 vip1.fhbf9.com + 空白台名 + 带日期路径的 m3u8。
# 这类内容不是发布方自我公开，不进来也不实测。
# kuwo（酷我）：它把央广等电台频率挂在自己的 antiserver 上转发，转发方不是内容方，
# 连“镜像源”都不该加（实测候选：CNR-3 音乐之声 @ antiserver.kuwo.cn）。
PIRATE_HOSTS = ("fhbf9", "ipipgo", "hykt", "190ip", "tvbus", "8555555", "ddtt9", "kuwo")

# 人工裁决名单：逐条看过台名与来源后判定不收的候选。
# 写成名单而不是手删 JSON——手删的结果下一轮 mine 会被同一批候选又加回来。
# 口径：台名（归一后）以首项开头 **且** host 含第二项才剔，免得误杀同名正经台。
MANUAL_DROP = (
    ("電台測試2", "125.227.87.206"),            # 台名自己写着“测试”，不是正经电台
    ("休息音乐", "mediacp.eu"),                  # emoji 台名、发布方不可考
    ("新闻联播", "infomaniak.ch"),               # 央视栏目名挂在 RFI 转发流上，内容标识混乱
    ("中央人民廣播電台 經濟之聲", "cnr.cn"),      # 与既有 cnr2 经济之声同内容（繁体写法没并上键）
    ("神州之聲", "satellitepull.cnr.cn"),        # 与既有 cnr_szzs 神州之声同内容（繁体写法）
    ("郫都综合广播亚洲音乐965", "asiafm.vip"),     # 台名自称区县台、来源却是另一品牌，发布方无法确认
    ("CNR-7 粵港澳大灣區之聲", "satellitepull.cnr.cn"),  # 被候选源标成“中国澳门”+繁体台名，走人工精选入库
)


def _pirate(host: str) -> bool:
    return any(p in host for p in PIRATE_HOSTS)

# 国家码 → 中文名：只覆盖华语台/公共流实际会出现的国家，取不到就回落英文原名
COUNTRY_ZH = {
    "CN": "中国", "TW": "台湾", "HK": "中国香港", "MO": "中国澳门", "SG": "新加坡",
    "MY": "马来西亚", "US": "美国", "CA": "加拿大", "GB": "英国", "AU": "澳大利亚",
    "NZ": "新西兰", "JP": "日本", "KR": "韩国", "DE": "德国", "FR": "法国",
    "NL": "荷兰", "TH": "泰国", "VN": "越南", "PH": "菲律宾", "ID": "印度尼西亚",
    "IN": "印度", "BR": "巴西", "MX": "墨西哥", "AR": "阿根廷", "ES": "西班牙",
    "IT": "意大利", "PT": "葡萄牙", "SE": "瑞典", "NO": "挪威", "DK": "丹麦",
    "FI": "芬兰", "PL": "波兰", "CZ": "捷克", "AT": "奥地利", "CH": "瑞士",
    "BE": "比利时", "IE": "爱尔兰", "RU": "俄罗斯", "UA": "乌克兰", "TR": "土耳其",
    "ZA": "南非", "EG": "埃及", "SA": "沙特阿拉伯", "AE": "阿联酋", "IL": "以色列",
    "MX": "墨西哥", "CL": "智利", "CO": "哥伦比亚", "PE": "秘鲁", "VE": "委内瑞拉",
    "KZ": "哈萨克斯坦", "UZ": "乌兹别克斯坦", "MN": "蒙古", "PK": "巴基斯坦",
    "BD": "孟加拉国", "NP": "尼泊尔", "LK": "斯里兰卡", "MM": "缅甸", "KH": "柬埔寨",
    "LA": "老挝", "KR": "韩国", "IS": "冰岛", "EE": "爱沙尼亚", "LV": "拉脱维亚",
    "LT": "立陶宛", "GR": "希腊", "HU": "匈牙利", "RO": "罗马尼亚", "BG": "保加利亚",
    "HR": "克罗地亚", "SI": "斯洛文尼亚", "SK": "斯洛伐克", "RS": "塞尔维亚",
    "MD": "摩尔多瓦", "GE": "格鲁吉亚", "AM": "亚美尼亚", "AZ": "阿塞拜疆",
}

_GENRE_ZH = {
    "music": "音乐", "news": "新闻", "talk": "谈话", "sports": "体育",
    "traffic": "交通", "culture": "文化", "oldies": "老歌", "pop": "流行",
    "rock": "摇滚", "jazz": "爵士", "classical": "古典", "relax": "轻音乐",
    "entertainment": "娱乐", "information": "综合资讯", "economics": "财经",
    "lifestyle": "生活", "literature": "文学", "storytelling": "评书",
    "public radio": "公共广播", "hits": "热歌",
}

# •‧⋅・ 这几个“点”都得当标点：实测候选里同时出现了“青岛音乐·体育广播”和
# “青岛音乐•体育广播”（U+00B7 与 U+2022），不归一就会当成两个台写入两条。
_PUNCT = "·．.，,、＿_-—（）()【】[]{}「」『』\"'“”‘’:：;；/\\|～~＊*＆&＋+•‧⋅・"
_CHINESE_NAME_HINT = re.compile(r"chinese|mandarin|cantonese|中文|华语|國語|国语", re.I)
_CJK_SUFFIXES = ("人民广播电台", "广播电视", "广播电台", "广播", "电台", "频率", "频道")


def _has_chinese_evidence(name: str) -> bool:
    """华语台的第二证据：language 字段在实测里不可全信。"""
    return bool(re.search(r"[\u4e00-\u9fff]", name or "")) or bool(_CHINESE_NAME_HINT.search(name or ""))


# ---------- 分类单一来源：从 Kotlin 枚举里读，不抄第二份 ----------

def load_station_category_keys(path: Path = STATION_KT) -> set:
    """解析 StationCategory.kt 里的 KEY("key", "中文名") 形式。"""
    if not path.exists():
        raise FileNotFoundError(f"找不到分类定义文件：{path}")
    text = path.read_text(encoding="utf-8")
    keys = set(re.findall(r'^\s*[A-Z_0-9]+\("([a-z_0-9]+)",', text, re.M))
    if not keys:
        raise ValueError(f"{path} 里没解析出任何分类 key，检查枚举格式")
    return keys


STATION_CATEGORY_KEYS = load_station_category_keys()
# 本脚本允许赋的分类（adult 由内容边界规则单独处理，不自动赋）
CATEGORY_KEYS = {
    "news", "music", "traffic", "arts", "sports", "finance",
    "opera", "tv_audio", "world", "general",
}


def assert_category(category: str) -> str:
    if category not in STATION_CATEGORY_KEYS:
        raise ValueError(f"分类 {category!r} 不在 StationCategory 枚举里，禁止入库")
    return category


# ---------- 台名归一 ----------

def norm_name(s: str) -> str:
    """小写 + 去空白标点，用于比较；不改写字面内容。"""
    out = []
    for ch in unicodedata.normalize("NFKC", s or ""):
        if ch.isspace() or ch in _PUNCT:
            continue
        out.append(ch.lower())
    return "".join(out)


def name_ok(name: str) -> bool:
    """台名质检：至少两个字符且含中日韩或字母数字。

    mine 阶段与入库判定共用这一把尺，否则一边筛了另一边没筛，
    纯符号台名会变成 id 为 st_!!! 的有东西。
    """
    n = (name or "").strip()
    return len(n) >= 2 and bool(re.search(r"[\u4e00-\u9fffA-Za-z0-9]", n))


def _strip_freq_tail(key: str) -> str:
    """剥掉尾部的频率与音质标记：AM1197 FM97.2 / 104.9 / 320kbps / MP3 / HQ。

    不剥就会出现“上海戏剧曲艺广播”和“上海戏剧曲艺广播 AM1197 FM97.2”两条；
    实测里英语台同一情况更严重：“Techno.FM (320 kbit)”与“Techno.FM”占了两个条目。
    只剥尾部，不剥头部：CETV-1/CETV-2 这类头部编号是频道身份，剥了会把四个台合一个。
    """
    prev = None
    while prev != key:
        prev = key
        key = re.sub(r"(am|fm)?[0-9]+(kbps|kbit|kbps|kb|k|bit|mp3|aac|hq|mq|lq)*$", "", key)
        key = re.sub(r"(hq|mq|lq|mp3|aac)$", "", key)
    return key


def match_key(name: str) -> str:
    """镜像匹配键：归一标点后，剥频率后缀与 广播/电台 这类通配后缀。

    两条实测碰出来的规则：
      1. 分隔符只在“后面那截含数字”时才截断。无条件截断会把
         “青岛音乐·体育广播”砍成“青岛音乐”（丢流派），而保留截断是为了
         “安徽戏曲广播·动听995”能等于“安徽戏曲广播”。
      2. 先剥频率尾巴再剥中文后缀，顺序反了就会得到
         “上海戏剧曲艺广播”≠“上海戏剧曲艺”（因为带频率的那条结尾不是“广播”）。
    必须保证"河南戏曲广播"≠"河北戏曲广播"，否则会把不同台归成同一个源。
    """
    n = unicodedata.normalize("NFKC", name or "")
    # 先去掉“纯码率/数字括号说明”：“Techno.FM (320 kbit)”去掉括号后才能与“Techno.FM”归一。
    # 不带数字的括号（如 “(UK)”）不动，它是频道身份的一部分。
    n = re.sub(r"\([^()]*\d[^()]*\)", "", n)
    for sep in ("·", "•", "‧", "・", "⋅", ".", "，", ",", "（", "("):
        if sep in n:
            head, tail = n.split(sep, 1)
            if re.search(r"\d", tail):
                n = head
            break
    n = norm_name(n)
    # 头部“CNR-<n>”这类台标编号不是频道身份（CETV-1/CETV-2 那种才是），不剥就会
    # 把“CNR-2 经济之声”与已有的“经济之声”写成两台。
    n = re.sub(r"^cnr\d+", "", n)
    n = _strip_freq_tail(n)
    for suf in _CJK_SUFFIXES:
        s = norm_name(suf)
        if n.endswith(s) and len(n) > len(s):
            n = n[: -len(s)]
            break
    return _strip_freq_tail(n)


def language_label(tags) -> str:
    t = {str(x).strip().lower() for x in (tags or []) if str(x).strip()}
    if t & {"chinese", "mandarin", "cantonese", "zh", "中文", "华语"}:
        return "华语"
    if "english" in t:
        return "英语"
    return "综合"


def genre_zh(tags) -> str:
    for x in (tags or []):
        k = str(x).strip().lower()
        if k in _GENRE_ZH:
            return _GENRE_ZH[k]
    return "综合"


def make_id(name: str, cc: str, taken) -> str:
    """沿用既有命名约定：大陆 st_<中文名>，海外 gl_<国家码小写>_<ASCII缩写>。"""
    base_name = (name or "").strip()
    if (cc or "").upper() == "CN":
        base = f"st_{base_name}"
    else:
        ascii_part = re.sub(r"[^a-z0-9]+", "", base_name.lower())
        slug = ascii_part[:16] if ascii_part else "c" + hashlib.md5(
            base_name.encode("utf-8")).hexdigest()[:8]
        base = f"gl_{(cc or 'xx').lower()}_{slug}"
    cid, n = base, 2
    while cid in taken:
        cid = f"{base}_{n}"
        n += 1
    return cid


# 海外候选的语言信号：按 RadioBrowser 的 language 字段筛，不按台名用什么文字。
_CHINESE_TOKENS = {"chinese", "mandarin", "cantonese", "hakka", "hokkien", "zh",
                   "中文", "华语", "国語", "国语"}
# 纯英语台的最低票数：一个中文应用里的英语台得有人真听，不收无名小站
ENGLISH_MIN_VOTES = 20
# 英语台只取英语母语国家：实测证明按 language=english 拉会出来
# “Hits Of Lata Mangeshkar”（印度）、“Kantipur FM”（尼泊尔）这类只带 english 标签的台，
# 那不是中文用户想听的英语台
ENGLISH_NATIVE_CC = ("GB", "US", "AU", "CA", "NZ", "IE")


def overseas_lang_kind(cand: dict):
    """返回 'chinese' / 'english' / None。None 即不收。"""
    lang = (cand.get("language") or "").lower()
    toks = {t.strip() for t in re.split(r"[,;]", lang) if t.strip()}
    name = cand.get("name", "")
    if not toks:
        # language 没填时只能回落到台名：含汉字当华语，否则信息不足不收
        return "chinese" if re.search(r"[\u4e00-\u9fff]", name) else None
    if toks & _CHINESE_TOKENS:
        # language 字段在实测里会骗人：候选池出现过“RADIO CLUB DJ”标成波兰·华语、
        # “TBS eFM 101.3”标成韩国·华语。没任何中文证据就不按华语收。
        return "chinese" if _has_chinese_evidence(name) else None
    # 必须是精确 token：“new english”、“creole”不能当成英语服务
    if "english" in toks:
        if (cand.get("cc") or "").upper() not in ENGLISH_NATIVE_CC:
            return None
        if int(cand.get("votes") or 0) < ENGLISH_MIN_VOTES:
            return None
        return "english"
    return None


def _lang_label(kind: str) -> str:
    return "英语" if kind == "english" else "华语"


def build_entry(name, category, url, cc, country_zh, bitrate, homepage, taken,
                lang_kind="chinese", tags="") -> dict:
    """按大陆/海外两种字段语义构造条目（约定见模块 docstring）。

    adult 分类走海外式写法（它们几乎全在境外），但 category 保持 adult 而不是 world，
    免得用户在“世界”标签下撞见不该随便出现的内容。
    """
    url = store_url(url)
    overseas = (cc or "").upper() != "CN"
    cat = category or "general"
    if overseas or cat in ("world", "adult"):
        zh = country_zh or (cc or "").upper()
        frequency = f"{zh} · {_lang_label(lang_kind)}"
        description = f"全球节目源 · {zh} · {genre_zh(tags)}"
        sources = [{"url": url, "label": "网络源", "bitrate": int(bitrate or 0)}]
        cat = "adult" if cat == "adult" else "world"
    else:
        cat = category or "general"
        frequency = ""
        description = ""
        sources = []
    assert_category(cat)
    return {
        "id": make_id(name, cc, taken),
        "name": name,
        "category": cat,
        "streamUrl": url,
        "logoUrl": "",
        "frequency": frequency,
        "description": description,
        "website": homepage or "",
        "sources": sources,
    }


def entry_urls(entry) -> list:
    """条目实际持有的所有流地址（streamUrl 与 sources 的并集，保持顺序）。"""
    out = []
    for u in [entry.get("streamUrl", "")] + [s.get("url", "") for s in (entry.get("sources") or [])]:
        if u and u not in out:
            out.append(u)
    return out


def apply_mirror(stations, mirrors: dict, max_sources: int = 3) -> list:
    """把同一台的第二个可用官方入口并入 sources[]。

    这条规则是让"多源可用性评分/自动切源"第一次真正拿到数据：此前全库
    sources>1 的台数为 0，切源逻辑无源可切。
    每台最多 max_sources 个源：堆到十几个不会更可靠，只会拉低整体质量。

    两条硬约束（实测中两者都违反过）：
      1. 已被任何电台持有的地址不能再挂给第二个电台，否则 audit 会报“同流共用”；
      2. 一个 match_key 只挂到第一条：存量里有“SomaFM Groove Salad”这种本来就重两条的，
         不限制就会把同一个镜像同时发给两条。
    """
    owned = {}                     # 全局地址归属：norm_url -> 持有它的电台下标
    for i, st in enumerate(stations):
        for u in dict.fromkeys(entry_urls(st)):
            owned.setdefault(norm_url(u), i)
    by_key = {}
    for i, st in enumerate(stations):
        by_key.setdefault(match_key(st.get("name", "")), i)

    for name, urls in (mirrors or {}).items():
        idx = by_key.get(match_key(name))
        if idx is None:
            continue
        st = stations[idx]
        have = {norm_url(u) for u in entry_urls(st)}
        sources = st.get("sources") or []
        added_any = False
        for u in urls or []:
            if not u or len(sources) >= max_sources:
                continue
            nu = norm_url(u)
            if nu in have:
                continue
            holder = owned.get(nu)
            if holder is not None and holder != idx:
                continue      # 已被另一个电台持有，不能共用
            if not sources and st.get("streamUrl"):
                sources = [{"url": st["streamUrl"], "label": "主源", "bitrate": 0}]
            sources.append({"url": store_url(u), "label": "镜像源", "bitrate": 0})
            have.add(nu)
            owned[nu] = idx
            added_any = True
        # 只在真加了镜像时才回写：否则会把大陆台既有的“sources 缺失、由 streamUrl 兜底”
        # 约定改成一条假源，sources>1 看起来有备源实际一死全死
        if added_any:
            st["sources"] = sources
    return stations


# 视频流特征：实测中 cztv 的 channel007/360p.m3u8 通过了分层实测（它确实有分片），
# 但它是视频不是声音，当作收音机备源就是错的。
VIDEO_URL_HINT = re.compile(r"/\d{3,4}p(?![a-z0-9])|/video/|\.mp4($|\?)|/dash/", re.I)
# 广播音频很少超过 320kbps；标称码率异常高基本是视频通道
AUDIO_MAX_BITRATE = 400


# 真成人标记：按 token 精确判，绝不用子串。
# 实测教训：“adult contemporary / adult hits / hot adult contemporary”是音乐格式，
# 子串匹配曾误杀 Heart UK（3.4万票）等 7 个主流台，而一条真成人内容也没拦住。
ADULT_TOKENS = {"porn", "xxx", "18+", "21+", "sex", "erotic", "nsfw", "adult only"}


def tag_tokens(cand: dict) -> set:
    return {t.strip().lower() for t in (cand.get("tags") or "").split(",") if t.strip()}


def is_adult_tag(cand: dict) -> bool:
    """是不是成人内容。按项目规定全球源不过滤成人，所以这只用于归类，不用于排除。"""
    return bool(tag_tokens(cand) & ADULT_TOKENS)


PLAY_FAILED = TOOLS / "play_failed.json"


def load_play_failed(path=None) -> set:
    """读装机实播确认失败的流地址。

    不持久化就会在重跑流水线时把已验证放不出声音的台又加回来。
    名单读失败不阻断取数，但必须说明原因：静默当成空名单会把坏源又收进来。
    """
    p = Path(path or PLAY_FAILED)
    if not p.exists():
        print(f"[warn] 实播失败名单不存在：{p}（本次不跳过任何地址）", file=sys.stderr)
        return set()
    try:
        data = json.loads(p.read_text(encoding="utf-8"))
    except Exception as e:
        print(f"[warn] 实播失败名单解析失败：{e}（本次不跳过任何地址）", file=sys.stderr)
        return set()
    return {norm_url(u) for u in (data.get("urls") or []) if u}


def _manual_drop(cand: dict, host: str) -> bool:
    """人工裁决名单命中判定：台名与 host 两个条件缺一不可。"""
    nn = norm_name(cand.get("name") or "")
    for key, host_sub in MANUAL_DROP:
        k = norm_name(key)
        if k and nn.startswith(k) and host_sub.lower() in host:
            return True
    return False


# 拼盘型台名：在聚合平台上出现基本等于“某人把别人的作品拼了一条流”。
# 官方域名上不适用（官方自己编排的选集是正当内容）。
# “500首华语经典”这类带量的写法与“合集”同义，实测漏过一批。
REBLOG_NAME = re.compile(r"\d+\s*首|合集|大全|合辑|拼盘|精选")


def drop_reason(cand: dict):
    """候选淘汰原因。必须可解释：静默消失的条目以后没人能复查。

    曾经过滤但现在不再排除的：
      裸 IP    —— 实测挡掉了 Radio Caroline、台北 Hit FM、台中廣播等 47 个可用台，
                  小站长用 IP 托管 Shoutcast 是常态，可用就收
      成人标记 —— 项目规定全球源不过滤成人，改为归入 StationCategory 的 adult
    仍然排除的：盗版转播 CDN（版权边界）、人工裁决名单、聚合平台拼盘名、
    下划线开头的排序花活台名、视频流、码率异常非音频。
    """
    url = (cand.get("url") or "").strip()
    if not url:
        return "no-url"
    if not url.lower().startswith(("http://", "https://")):
        return "not-http"
    if (cand.get("name") or "").strip().startswith("_"):
        return "junk-name"
    host = (cand.get("host") or urlparse(url).netloc).lower()
    if _pirate(host):
        return "pirate-cdn"
    if _manual_drop(cand, host):
        return "manual-drop"
    # 聚合平台 + 拼盘名：内容提供方没在这里发布，平台也只是别人传的列表
    if is_aggregator(host) and REBLOG_NAME.search(cand.get("name") or ""):
        return "aggregator-reblog"
    if VIDEO_URL_HINT.search(url):
        return "likely-video"
    try:
        if int(cand.get("bitrate") or 0) > AUDIO_MAX_BITRATE:
            return "bitrate-too-high-for-audio"
    except (TypeError, ValueError):
        pass
    return None


# ---------- mine ----------

def rb_get(path: str, params: dict = None, attempts: int = 4):
    last = []
    for i in range(attempts):
        server = RB_SERVERS[i % len(RB_SERVERS)]
        try:
            r = requests.get(server + path, params=params or {}, timeout=60,
                             headers={"User-Agent": RB_UA})
            if r.status_code == 200:
                return r.json()
            last.append(f"{server} HTTP {r.status_code}")
        except Exception as e:
            last.append(f"{server} {type(e).__name__}")
        time.sleep(1.0 + i)
    # 逐次尝试全列出：只留最后一条会把“首轮超时+次轮 503”这种真实原因说好成单一的 404
    raise RuntimeError("RadioBrowser 取数失败：" + " | ".join(last))


def cmd_mine(args) -> int:
    WORK.mkdir(parents=True, exist_ok=True)
    pools = [
        ("/json/stations/search", {"language": "chinese", "hidebroken": "true",
                                    "order": "votes", "reverse": "true", "limit": args.pool_limit}),
        ("/json/stations/search", {"countrycode": "CN", "hidebroken": "true",
                                    "order": "votes", "reverse": "true", "limit": args.pool_limit}),
        # 英语台：按英语母语国家拉，不按 language=english 拉（后者拉到一堆只带该标签的非英语台）
    ] + [
        ("/json/stations/search", {"countrycode": cc, "hidebroken": "true", "codec": "MP3",
                                    "order": "votes", "reverse": "true", "limit": args.en_limit})
        for cc in ENGLISH_NATIVE_CC
    ]
    seen, pool = set(), []
    for path, params in pools:
        rows = rb_get(path, params)
        for x in rows:
            uuid = x.get("stationuuid") or ""
            url = (x.get("url") or "").strip()
            name = (x.get("name") or "").strip()
            if not url or uuid in seen:
                continue
            # 台名质检：实测中按 votes 排在前面的中文候选大量是空白名/纯符号，
            # 这些条目即使流可用也不能入库——车机上只会看到一排空列表项
            if not name_ok(name):
                continue
            seen.add(uuid)
            pool.append({
                "name": name,
                "url": url,
                "host": urlparse(url).netloc.lower(),
                "url_resolved": (x.get("url_resolved") or url).strip(),
                "tags": x.get("tags") or "",
                "votes": int(x.get("votes") or 0),
                "cc": (x.get("countrycode") or "").upper(),
                "country": x.get("country") or "",
                "codec": (x.get("codec") or "").upper(),
                "bitrate": int(x.get("bitrate") or 0),
                "homepage": x.get("homepage") or "",
                "language": x.get("language") or "",
                "lastcheckok": int(x.get("lastcheckok") or 0),
            })
        print(f"  pulled {path}?{params.get('language') or params.get('countrycode')} -> total pool {len(pool)}",
              file=sys.stderr)
    out = Path(args.out)
    out.write_text(json.dumps(pool, ensure_ascii=False), encoding="utf-8")
    # 主机分布：官方域名单单凭直觉写不出来，拿实际数据定白名单
    tally = {}
    for c in pool:
        tally[c["host"]] = tally.get(c["host"], 0) + 1
    top = sorted(tally.items(), key=lambda x: -x[1])[:args.top_hosts]
    print(f"候选池 {len(pool)} 条 -> {out}")
    print("TOP HOSTS（条数）：")
    for h, n in top:
        print(f"  {n:5d}  {h}")
    return 0


# ---------- probe ----------

def _probe_one(cand: dict, timeout: float, attempts: int):
    url = cand["url_resolved"] or cand["url"]
    r = sp.probe_with_retry(url, attempts=attempts, pause=2.0, timeout=timeout)
    r["url"] = url
    return r


def cmd_probe(args) -> int:
    pool = json.loads(Path(args.pool).read_text(encoding="utf-8"))
    out_path = Path(args.out)
    done = {}
    if out_path.exists():
        try:
            for x in json.loads(out_path.read_text(encoding="utf-8")):
                done[x["url"]] = x
        except Exception:
            done = {}
    # 同 URL 只测一次；按 votes 从高到低，够目标数量就停
    todo, seen = [], set()
    for c in sorted(pool, key=lambda x: -int(x.get("votes") or 0)):
        u = (c.get("url_resolved") or c.get("url") or "").strip()
        if not u or u in seen:
            continue
        seen.add(u)
        if u in done:
            continue
        if args.max and len(todo) >= args.max:
            break
        todo.append(c)
    print(f"待测 {len(todo)} 条（已缓存 {len(done)} 条），workers={args.workers}", file=sys.stderr)

    lock = threading.Lock()
    results = dict(done)
    n_live = n_gone = n_unknown = 0
    with cf.ThreadPoolExecutor(max_workers=args.workers) as ex:
        for i, r in enumerate(ex.map(lambda c: _probe_one(c, args.timeout, args.attempts), todo), 1):
            with lock:
                results[r["url"]] = {"url": r["url"], "verdict": r["verdict"],
                                     "why": r["why"], "detail": r.get("detail", "")}
                n_live += r["verdict"] == sp.LIVE
                n_gone += r["verdict"] == sp.GONE
                n_unknown += r["verdict"] == sp.UNKNOWN
            if i % 25 == 0:
                print(f"  {i}/{len(todo)} live={n_live} gone={n_gone} unknown={n_unknown}",
                      file=sys.stderr)
                out_path.parent.mkdir(parents=True, exist_ok=True)
                out_path.write_text(json.dumps(list(results.values()), ensure_ascii=False),
                                    encoding="utf-8")
    out_path.write_text(json.dumps(list(results.values()), ensure_ascii=False), encoding="utf-8")
    total_live = sum(1 for v in results.values() if v["verdict"] == sp.LIVE)
    print(f"实测完成：累计 live={total_live} gone={sum(1 for v in results.values() if v['verdict']==sp.GONE)} "
          f"unknown={sum(1 for v in results.values() if v['verdict']==sp.UNKNOWN)} -> {out_path}")
    return 0


# ---------- merge ----------

def load_stations(path: Path) -> list:
    data = json.loads(path.read_text(encoding="utf-8"))
    return data["stations"] if isinstance(data, dict) and "stations" in data else data


def cmd_merge(args) -> int:
    pool = json.loads(Path(args.pool).read_text(encoding="utf-8"))
    verified = {v["url"]: v for v in json.loads(Path(args.verified).read_text(encoding="utf-8"))}
    stations = load_stations(Path(args.json))

    alive = [c for c in pool
             if verified.get(c.get("url_resolved") or c.get("url"), {}).get("verdict") == sp.LIVE
             and drop_reason(c) is None]
    # 实播确认失败的地址不再收：PC 侧“能下载分片”不等于 ExoPlayer 能出声
    play_failed = load_play_failed()
    if play_failed:
        before = len(alive)
        alive = [c for c in alive
                 if norm_url(c.get("url_resolved") or c.get("url") or "") not in play_failed]
        print(f"跳过实播失败地址命中的候选 {before - len(alive)} 条（名单共 {len(play_failed)} 个地址）",
              file=sys.stderr)
    # 淘汰原因全量报出来：只说“收了多少”而不说“丢了什么、为什么丢”，以后无法复查
    tally = {}
    for c in pool:
        if verified.get(c.get("url_resolved") or c.get("url"), {}).get("verdict") != sp.LIVE:
            continue
        r = drop_reason(c)
        if r:
            tally[r] = tally.get(r, 0) + 1
    print("实测通过但被规则淘汰："
          + (", ".join(f"{k}={v}" for k, v in sorted(tally.items(), key=lambda x: -x[1])) or "（无）"),
          file=sys.stderr)
    print(f"实测通过且未被淘汰的候选：{len(alive)} 条", file=sys.stderr)

    # 同 match_key 分组后判定新台/镜像：台名相同、地址不同就是镜像，不能另开一条
    new_entries, mirrors, skipped = build_selection(alive, stations, COUNTRY_ZH, args.max_new)
    if skipped:
        print(f"台名归一后为空而跳过 {skipped} 条", file=sys.stderr)

    report = summarize(stations, new_entries, mirrors, verified, alive)
    print(report)

    # 先组装再出预览：只报计数的话，没人能在写盘前看出“选进来的是垃圾台名”
    verified_map = {norm_url(u): v.get("verdict") for u, v in verified.items()}
    guarded = guard(stations, new_entries, args.max_new, verified_map)
    apply_mirror(guarded, mirrors)
    if args.preview:
        Path(args.preview).write_text(
            json.dumps({"stations": guarded}, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"预览已写出（未动正式清单）：{args.preview}")

    if not args.apply:
        print("\n[未落盘] 以上只是报告；确认无误后加 --apply 才会写入 " + str(args.json))
        return 0

    Path(str(args.json) + ".bak").write_text(Path(args.json).read_text(encoding="utf-8"), encoding="utf-8")
    Path(args.json).write_text(
        json.dumps({"stations": guarded}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"已写入 {args.json}（备份 {str(args.json)+'.bak'}）")
    return 0


def norm_url(u: str) -> str:
    """URL 归一比较：丢掉会话 token，否则同一源的每次结果都被当成不同地址。"""
    base = (u or "").split("?")[0].rstrip("/").lower()
    return re.sub(r"[^a-z0-9:/.\-]", "", base)


# 只对单次会话生效的 CDN 参数（实测 satellitepull.cnr.cn 会返回这种变体地址）
SESSION_PARAMS = ("wssession", "wsipsercert", "wsbindip", "wshash")


def store_url(url: str) -> str:
    """落库地址清洗：剔掉会话参数，其余 query 原样保留。

    wsSession 是 CDN 签给那一次拉流的，写进清单就是埋一颗会过期的雷（实测带/不带
    会话参数的 playlist 都能播出分片，所以剔掉不损失可用性）。
    不认识的参数一律留下：猜它们没用就会把真正需要的 region/token 剔掉。
    """
    u = (url or "").strip()
    if "?" not in u:
        return u
    base, query = u.split("?", 1)
    keep = [kv for kv in query.split("&")
            if kv and kv.split("=", 1)[0].lower() not in SESSION_PARAMS]
    return f"{base}?{'&'.join(keep)}" if keep else base


# 聚合平台域名：流可用，但不是播出机构自己的源，地址生命周期与平台策略绑定，优先级低于官方域名
AGGREGATOR_HOSTS = ("qingting.fm", "qtfm.cn", "ximalaya.com", "xmcdn.com",
                    "zeno.fm", "streamtheworld.com", "revma.com", "listenoutloud")


def is_aggregator(host: str) -> bool:
    h = (host or "").lower()
    return any(p in h for p in AGGREGATOR_HOSTS)


# 分类优先级分层：薄分类必须硬压过其他分类。
# 上一版只给 +60/+25，实测发现有英语台靠“官方域名+高码率+高票”总分超过蜻蜓上的戏曲台，
# 把用户明确要优先补的 opera/sports 从 10/7 顶到 7/2，那与需求相反。
CATEGORY_TIER = {
    "opera": 200, "sports": 200,
    "tv_audio": 150,
    "world": 100,
    "news": 60, "traffic": 60, "finance": 60, "music": 60, "arts": 60,
    "general": 30,
    "adult": 20,      # 不排除，但也不能挤占薄分类与华语/英语台的名额
}

# 每类限额：电视伴音候选最多（ CCTV 全频道），不限额就会吃掉大半名额。
# 世界限额 45 实测不够：候选 401 组，线到 138 分时会把台中廣播(132)、
# 全澳華語廣播華廈之聲(135) 这类华语台挤出去，进来的全是英国 Heart/Pumpkin FM 这类小台。
CATEGORY_CAP = {"opera": 999, "sports": 999, "tv_audio": 40, "world": 55, "adult": 4}
CATEGORY_CAP_DEFAULT = 25


def score_candidate(name, cc, category, host, bitrate, votes, lang_kind="") -> int:
    """名额排序：分类分层 > 华语优先 > 官方域名 > 码率/票数。

    华语优先是实测定出来的：世界候选里英语台数量远多于华语台，
    不给华语加分时名额会被码率标注高的英国小台占满。
    """
    s = CATEGORY_TIER.get(category, CATEGORY_TIER["general"])
    if category in ("world", "adult") and lang_kind == "chinese":
        s += 25
    if not is_aggregator(host):
        s += 20
    s += min(15, int(bitrate or 0) // 20)
    v = int(votes or 0)
    s += 12 if v >= 100 else (8 if v >= 20 else (4 if v > 0 else 0))
    return s


def pick_indices(ranked, max_new: int, cat_of=None) -> set:
    """返回被选中条目在 ranked 里的下标集。

    用下标而不是用 id(tuple) 做入选标记：临时元组的 id 永远不在旧集合里，
    而且 CPython 会复用已释放对象的 id，拿它做成员判定会静默地选不中任何东西。
    """
    cat_of = cat_of or (lambda it: it[4] if len(it) > 4 else "general")
    if not max_new:
        return set(range(len(ranked)))
    chosen, used, round2 = set(), {}, []
    for i, it in enumerate(ranked):
        cat = cat_of(it)
        if used.get(cat, 0) >= CATEGORY_CAP.get(cat, CATEGORY_CAP_DEFAULT):
            round2.append(i)
            continue
        if len(chosen) >= max_new:
            round2.append(i)
            continue
        used[cat] = used.get(cat, 0) + 1
        chosen.add(i)
    for i in round2:
        if len(chosen) >= max_new:
            break
        chosen.add(i)
    return chosen


def build_selection(alive, stations, country_map=None, max_new: int = 2000):
    """把实测通过的候选分成两摊：新台 / 现有台的镜像源。

    判定顺序不能错：“台名相同但地址不同”正是多源镜像的定义，必须进 mirrors；
    把它当成新台就会让同一个台在清单里出现两遍，而且永远集不到第二个源。
    返回 (new_entries, mirrors, skipped)。
    """
    country_map = country_map or COUNTRY_ZH
    owned = {}                       # norm_url -> id，已有地址归属
    for st in stations:
        for u in entry_urls(st):
            owned.setdefault(norm_url(u), st.get("id", ""))
    by_key = {}                      # match_key -> 现有台
    for st in stations:
        by_key.setdefault(match_key(st.get("name", "")), st)

    groups = {}
    for c in alive:
        groups.setdefault(match_key(c.get("name", "")), []).append(c)

    taken = {st["id"] for st in stations}
    taken_urls = {norm_url(u) for st in stations for u in entry_urls(st)}
    new_entries, mirrors, skipped, dup_skipped = [], {}, 0, 0

    # 先把分组算好分数、再按分数从高到低落位：
    # 同一个台的两种台名写法（CCTV-13新闻伴音 / CCTV-13 新闻（伴音））指向同一个地址时，
    # 先高分的那条拿到地址，后一条因地址已被占而丢弃，不会在清单里出现两个同流台
    ranked = []
    for key, rows in groups.items():
        # 在这里拦而不是在分组前拦：拦下了要能计数，否则多少条被丢没人知道
        first = rows[0] if rows else {}
        fname = first.get("name", "")
        if not key or not rows or not name_ok(fname):
            skipped += 1
            continue
        cc = first.get("cc", "")
        lang_kind = "chinese"
        if cc != "CN":
            # 按 language 字段筛华语/英语台，不按台名文字：上一版用“必须含汉字”
            # 筛选，把 language=chinese,portuguese 这类合法双语台误杀了一批
            lang_kind = overseas_lang_kind(first)
            if lang_kind is None:
                skipped += 1
                continue
        category = "world" if cc != "CN" else sp.classify_by_name(fname)
        if is_adult_tag(first):
            # 按项目规定不过滤成人内容，但它们应落在已有的 adult 分类，
            # 而不是混进 world 里让用户在“世界”标签下撞见
            category = "adult"
        score = score_candidate(fname, cc, category,
                                first.get("host", ""), first.get("bitrate", 0),
                                first.get("votes", 0), lang_kind)
        urls = []
        for c in rows:
            u = c.get("url_resolved") or c.get("url")
            if u and norm_url(u) not in {norm_url(x) for x in urls}:
                urls.append(u)
        ranked.append((score, key, first, cc, category, urls, lang_kind))
    ranked.sort(key=lambda t: -t[0])
    # 名额只按“新台组”计算：镜像组不新增条目，混在同一个 ranked 里算 max_new
    # 会白白吃掉名额（实测因此漏掉了 台中廣播 等 18 个合格新台）。
    new_ranked = [t for t in ranked if by_key.get(t[1]) is None]
    mirror_ranked = [t for t in ranked if by_key.get(t[1]) is not None]
    chosen = pick_indices(new_ranked, max_new)
    for pos, (score, key, first, cc, category, urls, lang_kind) in enumerate(new_ranked):
        if pos not in chosen:
            continue
        fresh = [u for u in urls if norm_url(u) not in taken_urls]
        if not fresh:
            dup_skipped += 1
            continue
        entry = build_entry(
            name=first.get("name", ""), category=category, url=fresh[0], cc=cc,
            country_zh=country_map.get(cc, first.get("country", "")),
            bitrate=first.get("bitrate", 0), homepage=first.get("homepage", ""), taken=taken,
            lang_kind=lang_kind, tags=first.get("tags", ""),
        )
        # 大陆台新条目：同一台多个官方入口直接写成 sources[]，一入库就能用上多源切源
        extra = list(fresh[1:4])
        if cc == "CN" and extra:
            entry["sources"] = [{"url": store_url(u), "label": ("主源" if i == 0 else "镜像源"), "bitrate": 0}
                                for i, u in enumerate([fresh[0]] + extra)]
        for u in [fresh[0]] + extra:
            taken_urls.add(norm_url(u))
        taken.add(entry["id"])
        entry["_score"] = score
        new_entries.append(entry)

    # 镜像不受名额限制，但在新台认领地址之后再算一轮：
    # 避免同一个流既是新台主源又当成旧台的镜像源
    for score, key, first, cc, category, urls, lang_kind in mirror_ranked:
        exist = by_key.get(key)
        if exist is None:
            continue
        fresh = [u for u in urls
                 if norm_url(u) not in {norm_url(x) for x in entry_urls(exist)}
                 and norm_url(u) not in taken_urls]
        if fresh:
            mirrors.setdefault(exist["name"], []).extend(fresh)

    new_entries.sort(key=lambda e: -e.get("_score", 0))
    for e in new_entries:
        e.pop("_score", None)
    return new_entries, mirrors, skipped + dup_skipped


def summarize(stations, new_entries, mirrors, verified, alive) -> str:
    cats = {}
    for e in new_entries:
        cats[e["category"]] = cats.get(e["category"], 0) + 1
    mirrored = sum(1 for k, v in mirrors.items() if v)
    lines = [
        f"现有条目 {len(stations)} 台",
        f"实测通过候选 {len(alive)} 条 → 可新增 {len(new_entries)} 台",
        f"可补多源镜像的现有台 {mirrored} 个",
        "新增分类分布：" + (", ".join(f"{k}={v}" for k, v in sorted(cats.items(), key=lambda x: -x[1])) or "（无）"),
    ]
    for c in ("opera", "sports", "tv_audio"):
        have = sum(1 for s in stations if s.get("category") == c)
        add = cats.get(c, 0)
        lines.append(f"  薄分类 {c}: 现有 {have} → 新增 {add} → 合计 {have + add}")
    return "\n".join(lines)


def guard(stations, new_entries, max_new: int, verified_map=None) -> list:
    """入库前的硬断言：任何一条不满足就不写，宁可少交付也不带病入库。

    最核心的一条是 verified_map：新条目的每一个地址都必须在本次实测里拿到 live，
    这一条拦住的是“把目录里写着可用但未经验证的地址写进清单”。
    """
    assert max_new > 0, "max_new 必须为正数"
    chosen = new_entries[:max_new]
    verified_map = verified_map or {}

    seen_ids = set()
    owned = {}
    for st in stations:
        for u in entry_urls(st):
            owned.setdefault(norm_url(u), st.get("id", ""))

    for st in stations:
        sid = st.get("id", "")
        assert sid not in seen_ids, f"存量数据重复 id：{sid}"
        seen_ids.add(sid)

    for e in chosen:
        assert e["id"] not in seen_ids, f"新条目 id 与存量冲突：{e['id']}"
        seen_ids.add(e["id"])
        assert_category(e.get("category", ""))
        urls = entry_urls(e)
        assert urls, f"{e['id']} 没有任何流地址"
        for u in urls:
            nu = norm_url(u)
            verdict = verified_map.get(nu)
            assert verdict == sp.LIVE, f"未实测通过（verdict={verdict!r}）的地址禁止入库：{u}"
            clash = owned.get(nu)
            assert clash is None, f"地址已被 {clash!r} 占用，不能再挂给 {e['id']}：{u}"
            owned[nu] = e["id"]
    return list(stations) + chosen


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)

    m = sub.add_parser("mine", help="拉候选池")
    m.add_argument("--pool-limit", type=int, default=1500)
    m.add_argument("--en-limit", type=int, default=120, help="每个英语国家拉多少条候选（按 votes）")
    m.add_argument("--top-hosts", type=int, default=40)
    m.add_argument("--out", default=str(WORK / "rb_pool.json"))

    q = sub.add_parser("probe", help="分层实测候选池")
    q.add_argument("--pool", default=str(WORK / "rb_pool.json"))
    q.add_argument("--out", default=str(WORK / "rb_verified.json"))
    q.add_argument("--workers", type=int, default=6)
    q.add_argument("--timeout", type=float, default=10.0)
    q.add_argument("--attempts", type=int, default=3, help="UNKNOWN 重试次数")
    q.add_argument("--max", type=int, default=0, help="本轮最多测多少条（0=全部）")

    g = sub.add_parser("merge", help="生成入库报告，--apply 才写文件")
    g.add_argument("--pool", default=str(WORK / "rb_pool.json"))
    g.add_argument("--verified", default=str(WORK / "rb_verified.json"))
    g.add_argument("--json", default=str(STATIONS_JSON))
    g.add_argument("--max-new", type=int, default=120)
    g.add_argument("--preview", default="", help="把将要写入的完整清单导出到这里，不动正式文件")
    g.add_argument("--apply", action="store_true")

    args = p.parse_args(argv)
    if args.cmd == "mine":
        return cmd_mine(args)
    if args.cmd == "probe":
        return cmd_probe(args)
    return cmd_merge(args)


if __name__ == "__main__":
    sys.exit(main())
