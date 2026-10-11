"""内置清单的标签派生、字段拆分与简介补全。

三件事，都在写盘前被 validate() 卡住：
  1. 从台名 + RadioBrowser 标签派生 StationTag 词表内的标签
  2. 把塞在 frequency 里的「国家 · 语言」还原成 country / language 字段
  3. 给全球台补一句真实介绍：真简介 > 官网自述 + 模板句 > 模板句

子命令：
  provenance   生成 tools/rb_provenance.json（派生依据入库，换机器也能重跑）
  dry-run      只出报告：标签覆盖率、0 标签名单、简介三个来源计数、问题清单
  apply        备份后写 assets；validate 不过就拒绝写盘

为什么不自动加标签层：用户已就「剧种不详的戏曲」明确决定不加中间层，
所以 `河南戏曲广播` 这类台名拿不到标签是预期结果，不靠猜把覆盖率做上去。
"""
import argparse
import concurrent.futures as cf
import html as html_mod
import json
import re
import sys
from collections import Counter
from pathlib import Path

import requests

html_unescape = html_mod.unescape

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

STATIONS_JSON = ROOT / "app/src/main/assets/radio_stations.json"
PROVENANCE = TOOLS / "rb_provenance.json"
POOL = ROOT / ".qtest/rb_pool.json"
VERIFIED = ROOT / ".qtest/rb_verified.json"
META_CACHE = ROOT / ".qtest/meta_cache.json"
BACKUP = Path(str(STATIONS_JSON) + ".pretag.bak")

MAX_TAGS = 6

# 与 StationTag.kt 逐项同步的词表（test_derive_tags 会直接读 Kotlin 源码比对，
# 顺序与内容都必须一致；改 key 先改枚举，再改这里）
TAG_KEYS = [
    "jingju", "yuju", "yueju", "yueopera", "huangmei", "pingju", "kunqu", "qinqiang",
    "chuanju", "hujv", "pingtan", "crosstalk",
    "localnews", "worldnews", "military", "health", "edu", "kids", "nighttalk",
    "storytelling", "history", "fincomment", "tech", "sportslive", "trafficnow", "weather",
    "oldies", "hits80", "rock", "folk", "jazz", "classical", "lightmusic", "blues",
    "countrymusic", "electronic", "hiphop", "rnb", "metal", "cpop", "pop", "instrumental",
    "drive", "sleep", "buddhist", "langlearn", "fitness", "chat",
]
TAG_LABEL = {
    "jingju": "京剧", "yuju": "豫剧", "yueju": "越剧", "yueopera": "粤剧", "huangmei": "黄梅戏",
    "pingju": "评剧", "kunqu": "昆曲", "qinqiang": "秦腔", "chuanju": "川剧", "hujv": "沪剧",
    "pingtan": "评弹", "crosstalk": "相声", "localnews": "本地新闻", "worldnews": "国际新闻",
    "military": "国防军事", "health": "健康医药", "edu": "教育", "kids": "少儿",
    "nighttalk": "夜间清谈", "storytelling": "评书故事", "history": "历史人文",
    "fincomment": "财经评述", "tech": "科技", "sportslive": "赛事解说", "trafficnow": "实时路况",
    "weather": "天气", "oldies": "怀旧老歌", "hits80": "八十年代", "rock": "摇滚",
    "folk": "民谣", "jazz": "爵士", "classical": "古典", "lightmusic": "轻音乐",
    "blues": "蓝调", "countrymusic": "乡村", "electronic": "电子舞曲", "hiphop": "嘻哈",
    "rnb": "节奏布鲁斯", "metal": "金属", "cpop": "华语流行", "pop": "欧美流行",
    "instrumental": "纯音乐", "drive": "行车", "sleep": "助眠放松", "buddhist": "佛教禅修",
    "langlearn": "外语学习", "fitness": "运动健身", "chat": "闲聊陪伴",
}
CATEGORY_LABEL = {
    "news": "新闻", "music": "音乐", "traffic": "交通", "arts": "文艺", "sports": "体育",
    "finance": "财经", "opera": "戏曲", "tv_audio": "电视伴音", "world": "世界",
    "general": "综合", "adult": "成人",
}
# 这些语义是 StationCategory 的活，标签里出现就是零信息量筛选
BANNED_KEYS = {"news", "sports", "traffic", "finance", "opera", "music"}
LANGUAGES = {"华语", "英语", "粤语", "闽南语", "客家语", "日语", "韩语", "德语", "法语",
             "西班牙语", "俄语", "意大利语", "葡萄牙语", "阿拉伯语"}
# RadioBrowser 的 language 取值 → 中文语言名
LANG_RAW = {
    "chinese": "华语", "mandarin": "华语", "中文": "华语", "华语": "华语",
    "cantonese": "粤语", "hokkien": "闽南语", "hakka": "客家语",
    "english": "英语", "japanese": "日语", "korean": "韩语", "german": "德语",
    "french": "法语", "spanish": "西班牙语", "russian": "俄语",
    "italian": "意大利语", "portuguese": "葡萄牙语", "arabic": "阿拉伯语",
}
PROVINCES = ("北京", "上海", "天津", "重庆", "浙江", "江苏", "广东", "广西", "湖南", "湖北",
             "河南", "河北", "山东", "山西", "陕西", "安徽", "福建", "江西", "四川", "贵州",
             "云南", "辽宁", "吉林", "黑龙江", "内蒙古", "宁夏", "新疆", "青海", "甘肃",
             "海南", "西藏")

# 台名关键词 → 标签。只认明确写出的词，不做地理推断：
# 「河南戏曲广播」不产 yuju（用户已定不加中间层，猜就是编内容）。
NAME_RULES = [
    (("豫剧",), ["yuju"]),
    (("京剧", "京戏"), ["jingju"]),
    (("越剧",), ["yueju"]),
    (("粤剧", "粤曲", "广府"), ["yueopera"]),
    (("黄梅戏", "黄梅调"), ["huangmei"]),
    (("评剧",), ["pingju"]),
    (("昆曲", "昆剧"), ["kunqu"]),
    (("秦腔",), ["qinqiang"]),
    (("川剧",), ["chuanju"]),
    (("沪剧",), ["hujv"]),
    (("评弹", "弹词", "苏州曲艺"), ["pingtan"]),
    (("相声", "笑星", "幽默"), ["crosstalk"]),
    (("评书", "书场", "故事", "小说", "连本"), ["storytelling"]),
    (("古典音乐", "classical", "classic fm", "古典"), ["classical"]),
    (("爵士", "jazz"), ["jazz"]),
    (("摇滚", "rock"), ["rock"]),
    (("民谣", "folk"), ["folk"]),
    (("蓝调", "blues"), ["blues"]),
    (("乡村音乐", "country music", "country"), ["countrymusic"]),
    (("电子", "house", "trance", "techno", "dance", "edm"), ["electronic"]),
    (("嘻哈", "hiphop", "hip hop", "rap"), ["hiphop"]),
    (("节奏布鲁斯", "r&b", "rnb"), ["rnb"]),
    (("金属", "metal", "hard rock"), ["metal"]),
    (("老歌", "oldies", "golden", "revival", "怀旧"), ["oldies"]),
    (("80s", "80 年代", "80年代", "eighties"), ["hits80"]),
    (("流行", "hits", "top 40"), ["cpop"]),
    (("纯音乐", "instrumental", "器乐"), ["instrumental"]),
    (("轻音乐", "relax", "calm", "smooth", "adult contemporary", "慵懒"), ["lightmusic"]),
    (("路况", "交通", "traffic"), ["trafficnow"]),
    (("天气", "气象", "weather"), ["weather"]),
    (("军事", "国防"), ["military"]),
    (("健康", "医药", "中医", "养生"), ["health"]),
    (("教育", "教学", "校园", "edu"), ["edu"]),
    (("少儿", "儿童", "kids", "children", "卡通"), ["kids"]),
    (("历史", "人文", "文化", "heritage"), ["history"]),
    (("财经", "金融", "经济", "business"), ["fincomment"]),
    (("科技", "it", "数码", "人工智能"), ["tech"]),
    (("体育", "赛事", "足球", "篮球", "sport"), ["sportslive"]),
    (("夜话", "情感", "深夜", "谈话", "talk"), ["nighttalk"]),
    (("佛教", "佛学", "佛法", "念经", "讲经", "法师", "禅修", "禅宗"), ["buddhist"]),
    (("助眠", "睡眠", "sleep"), ["sleep"]),
    (("外语", "英语学", "lang learn", "教学英语"), ["langlearn"]),
    (("健身", "运动", "fitness"), ["fitness"]),
    (("资讯", "新闻", "news"), ["localnews"]),
]
# RadioBrowser 英文标签 → 词表 key。映射不到的一律丢弃，不做模糊匹配
# （实测候选里出现过 waynesboro 这种美国镇名、full service 这种运营形态词）
RB_TAG_MAP = {
    "adult contemporary": "lightmusic", "hot adult contemporary": "lightmusic",
    "adult hits": "oldies", "classic hits": "oldies", "oldies": "oldies",
    "smooth jazz": "jazz", "jazz": "jazz", "classic rock": "rock", "rock": "rock",
    "album tracks": "rock", "progressive rock": "rock", "heavy metal": "metal",
    "punk": "rock", "blues": "blues", "folk": "folk", "country": "countrymusic",
    "classical": "classical", "classical music": "classical", "chillout": "lightmusic",
    "ambient": "instrumental", "dance": "electronic", "club": "electronic",
    "house": "electronic", "trance": "electronic", "techno": "electronic",
    "hiphop": "hiphop", "hip hop": "hiphop", "rap": "hiphop", "rnb": "rnb",
    "r&b": "rnb", "pop": "pop", "pop music": "pop", "hits": "pop", "80s": "hits80",
    "70s": "oldies", "90s": "oldies", "00s": "oldies", "news": "localnews",
    "local news": "localnews", "world news": "worldnews", "talk": "nighttalk",
    "talk show": "nighttalk", "information": "localnews", "weather": "weather",
    "traffic": "trafficnow", "local traffic & weather": "trafficnow",
    "sports": "sportslive", "sport": "sportslive", "kids": "kids", "children": "kids",
    "education": "edu", "educational": "edu", "health": "health",
    "religion & spirituality": "buddhist", "culture": "history",
    "storytelling": "storytelling", "literature": "storytelling", "poetry": "storytelling",
    "opra": "", "instrumental": "instrumental", "jews": "", "military": "military",
    "history": "history", "tech": "tech", "business": "fincomment",
}
# 官网自述里出现这些营销断言就当没抓到：不把宣传语当事实写进 App。
# 注意：#1 / no.1 这类符号开头的写法不能用 \b 开头——# 不是词字符，
# “The #1” 里空格到 # 之间不存在单词边界，归到主分组会让整条规则永不命中。
META_BANNED = re.compile(
    r"\b(?:award|awards|best|top|leading|premier|famous|largest|only|finest)\b"
    r"|#\s?[123]\b|\bno\.?\s?[123]\b|\b1st\b|权威|著名|领先|唯一|第一",
    re.I)
# 属性定位：不用正则吃整个 <meta> 标签，而是由下面的扫描器逐字符处理，
# 因为属性值里可以出现 '>' 和引号，正则要么提前截断要么越界拓到邻居标签。
ATTR_RE = re.compile(r'([\w:-]+)\s*=\s*(?:"([^"]*)"|\'([^\']*)\'|([^\s"\'>]+))')
META_OPEN_RE = re.compile(r"<meta\b", re.I)
TAG_STRIP = re.compile(r"<[^>]+>")
URLISH_RE = re.compile(r"https?://|www\.", re.I)
SENT_END_RE = re.compile(r"[\u3002\uff01\uff1f!?\u2026]|\.(?=\s|$)")
META_MAX = 48
# 对外广播类频率的新闻是国际新闻，不是本地新闻
INTERNATIONAL_WORDS = ("环球", "国际", "世界", "对外")


def adjust_news_scope(tags, name: str) -> list:
    """把 CRI / 环球类频率的 localnews 改成 worldnews。

    实测错法：RB 给 `华语环球广播` 打了 news 标签，无差别映射后它在界面上
    显示为“本地新闻”，而这台的对内对外定位正好相反。
    """
    if "localnews" not in tags:
        return tags
    if not any(w in (name or "") for w in INTERNATIONAL_WORDS):
        return tags
    return _sort_cut(["worldnews" if t == "localnews" else t for t in tags])


def norm_key(k: str) -> str:
    return (k or "").strip().lower()


def _sort_cut(keys) -> list:
    """按词表顺序去重截断。顺序稳定，重跑结果一致。"""
    seen = {norm_key(k) for k in keys}
    out = [k for k in TAG_KEYS if k in seen]
    return out[:MAX_TAGS]


def _name_hit(name_lower: str, kw: str) -> bool:
    """关键字匹配。

    ASCII 关键字必须整词：“it” 在 "Hit FM" 里、“top” 在 "stop" 里，
    子串匹配会把科技台挂错。中文关键字靠上面的单字约束保证区分度。
    """
    k = norm_key(kw)
    if not k:
        return False
    if re.fullmatch(r"[a-z0-9 .&#'\-]+", k):
        return re.search(r"(?<![a-z0-9])" + re.escape(k) + r"(?![a-z0-9])", name_lower) is not None
    return k in name_lower


def tags_from_name(name: str) -> list:
    n = norm_key(name or "")
    hits = []
    for kws, tags in NAME_RULES:
        if any(_name_hit(n, kw) for kw in kws):
            hits.extend(tags)
    # 地方新闻台：台名带省名才判本地新闻，不靠外部知识推断覆盖范围
    if any(p in (name or "") for p in PROVINCES) and ("新闻" in (name or "") or "资讯" in (name or "")):
        hits.append("localnews")
    return [k for k in _sort_cut(hits) if k not in BANNED_KEYS]


def map_rb_tags(tags: str) -> list:
    hits = []
    for t in (tags or "").split(","):
        t = norm_key(t)
        if not t:
            continue
        mapped = RB_TAG_MAP.get(t)
        if mapped:
            hits.append(mapped)
    return [k for k in _sort_cut(hits) if k not in BANNED_KEYS]


FREQ_MARK = re.compile(r"^\s*(fm|am|fm/AM|调频|中波)", re.I)


def split_frequency(freq: str):
    """返回 (country, language, 保留的 frequency)。

    含 FM/AM 或纯数字的一律当真实频率留着——192 个大陆台靠它认台，
    拆错了就是"副标题变空"这种用户立刻能看见的回退。
    带分隔符但语言不在白名单的，整串原样保留，不猜。
    """
    f = (freq or "").strip()
    if not f:
        return "", "", ""
    if "·" in f:
        parts = [p.strip() for p in f.split("·") if p.strip()]
        if len(parts) == 2:
            if parts[1] in LANGUAGES:
                return parts[0], parts[1], ""
            if parts[0] in LANGUAGES:
                return parts[1], parts[0], ""
        return "", "", f
    return "", "", f


PLACEHOLDER_DESC = "全球节目源"


def strip_desc(desc: str) -> str:
    """以「全球节目源」开头的是 A-1 期的占位串，不是真简介。"""
    d = (desc or "").strip()
    return "" if d.startswith(PLACEHOLDER_DESC) else d


def template_desc(country: str, language: str, category_label: str, tag_labels) -> str:
    """只用已核实字段拼一句话。

    必须有国家或语言才写：光有分类就生成句子等于把分类重说一遍
    （“交通电台，主要播实时路况”），那是噪音不是介绍。dry-run 实测发现这个坑：
    旧写法会让 465 台“全部有简介”，数字好看但没用。
    全空时返回空串，也不写「暂无介绍」这种废话。
    """
    if not (country or language):
        return ""
    head = f"{country}的" if country else ""
    mid = "".join([language or "", category_label or ""])
    sentence = f"{head}{mid or '网络'}电台"
    if tag_labels:
        sentence += "，主要播" + "、".join(list(tag_labels)[:3])
    return sentence


def _iter_meta_tags(html: str):
    """逐个吐 <meta> 标签原文。跳引号内的 '>'，也不把下一个标签扫进来。"""
    i, n = 0, len(html)
    while True:
        m = META_OPEN_RE.search(html, i)
        if not m:
            return
        j = m.end()
        quote = ""
        while j < n:
            c = html[j]
            if quote:
                if c == quote:
                    quote = ""
            elif c in "\"'":
                quote = c
            elif c == ">":
                break
            j += 1
        yield html[m.start(): min(j + 1, n)]
        i = m.end()


def _attrs(tag: str) -> dict:
    """每个属性取首个值；双引号优先，所以 UK's 里的撇号不会被当成引号结束。"""
    out = {}
    for m in ATTR_RE.finditer(tag or ""):
        key = m.group(1).lower()
        val = next((g for g in m.groups()[1:] if g is not None), "")
        out.setdefault(key, val.strip())
    return out


def adjust_genre_by_language(tags, language: str) -> list:
    """华语台的 pop 该显示为「华语流行」而不是「欧美流行」。

    实测错法：`河南音乐广播` 带 RB 标签 pop，直接归到 pop 后车机上显示为“欧美流行”，
    对一个河南本地台是错的。按语言而不是按猜测来分流。
    """
    if language != "华语" or "pop" not in tags:
        return tags
    return _sort_cut(["cpop" if t == "pop" else t for t in tags])


def clean_meta(html: str) -> str:
    """从网页取一句中性自述：og:description > description > itemprop。

    不回落到 <title>：网站标题不是介绍。抽样看到过
    「中国的华语戏曲电台 — 河南广播网」这种后半段只是站名的噪音。
    """
    html = html or ""
    og = desc = item = ""
    for tag in _iter_meta_tags(html):
        a = _attrs(tag)
        val = a.get("content", "").strip()
        if not val:
            continue
        key = (a.get("property") or a.get("name") or "").lower()
        if key == "og:description" and not og:
            og = val
        elif key == "description" and not desc:
            desc = val
        elif a.get("itemprop", "").lower() == "description" and not item:
            item = val
    text = og or desc or item
    text = TAG_STRIP.sub("", text or "")
    text = html_unescape(text)
    text = re.sub(r"\s+", " ", text).strip()
    if not text:
        return ""
    if URLISH_RE.search(text):
        return ""      # 实测抓到过自带裸 URL 的自述，不适合上界面
    if META_BANNED.search(text):
        return ""      # 宣传语当没抓到：不把“获奖/第一/最大”写进司机看到的简介里
    # 取能收住的那一句；本身就不长、又没有句号的短语（真实 og:description 大多如此）直接保留；
    # 只有“又长又收不了句”才丢弃——车机上出现半句话比没有更糟
    first = SENT_END_RE.search(text)
    if first and first.end() <= META_MAX:
        return text[:first.end()]
    if not first and len(text) <= META_MAX:
        return text
    within = text[:META_MAX]
    last = None
    for m in SENT_END_RE.finditer(within):
        last = m
    return within[:last.end()] if last else ""


def compose_desc(existing: str, meta: str, template: str) -> str:
    """真简介优先；其次「模板句 — 官网原话」；再次模板句。"""
    if existing:
        return existing
    if template and meta:
        return f"{template} — {meta}"[:60]
    return meta or template or ""


def norm_url(u: str) -> str:
    base = (u or "").split("?")[0].rstrip("/").lower()
    return re.sub(r"[^a-z0-9:/.\-]", "", base)


def load_provenance(path: Path = PROVENANCE) -> dict:
    if not path.exists():
        return {}
    rows = json.loads(path.read_text(encoding="utf-8"))
    return {norm_url(r["url"]): r for r in rows if r.get("url")}


def build_provenance(pool_path: Path, stations: list) -> list:
    """只保留真正被清单用到的字段，精简到能入库、能重跑派生。"""
    import fetch_stations as fs
    pool = json.loads(Path(pool_path).read_text(encoding="utf-8"))
    by_url = {}
    for c in pool:
        for u in (c.get("url_resolved"), c.get("url")):
            if u:
                by_url.setdefault(norm_url(u), c)
    out = []
    for st in stations:
        c = by_url.get(norm_url(st.get("streamUrl", "")))
        if c is None:
            continue
        out.append({
            "url": c.get("url_resolved") or c.get("url"),
            "name": c.get("name"),
            "tags": c.get("tags") or "",
            "cc": c.get("cc") or "",
            "country": c.get("country") or "",
            "country_zh": fs.COUNTRY_ZH.get(c.get("cc") or "", c.get("country") or ""),
            "language": c.get("language") or "",
            "homepage": c.get("homepage") or "",
        })
    return out


def derive_station(st: dict, prov: dict = None, meta_text: str = "") -> dict:
    """对单个电台做标签/字段/简介派生；流地址与 sources 一律不碰。"""
    prov = prov or {}
    out = dict(st)
    country, language, freq = split_frequency(st.get("frequency", ""))
    if not country:
        country = (prov.get("country_zh") or "").strip()
    if not language:
        for t in (prov.get("language") or "").split(","):
            if norm_key(t) in LANG_RAW:
                language = LANG_RAW[norm_key(t)]
                break
    tags = tags_from_name(st.get("name", ""))
    merged = _sort_cut(tags + map_rb_tags(prov.get("tags", "")))
    # 语言推不出来就连国家也不写：中国可能是粤语/闽南语/藏语，猜一个就是编内容，
    # 而且会破坏“有国家必有语言”这条 invariant（dry-run 实测拦下 6 条）
    if not language:
        country = ""
    # 标签语义跟着台的定位走：对外频率的 news 是国际新闻，华语台的 pop 是华语流行
    merged = adjust_genre_by_language(adjust_news_scope(merged, st.get("name", "")), language)
    # 分类是「世界」时不进模板句：那是个收录容器不是内容，写出来就是
    # “英国的英语世界电台”这种不成话的句子（实测抽样抽到）
    cat_label = "" if st.get("category") == "world" else CATEGORY_LABEL.get(st.get("category", ""), "")
    tag_labels = [TAG_LABEL.get(k, k) for k in merged]
    desc = compose_desc(strip_desc(st.get("description", "")), meta_text,
                        template_desc(country, language, cat_label, tag_labels))
    out.update({"tags": merged, "country": country, "language": language,
                "frequency": freq, "description": desc})
    return out


def validate(before: list, after: list) -> list:
    """写盘前的硬断言。返回问题列表，空才允许写。"""
    problems = []
    if len(after) < len(before):
        problems.append(f"台数缩水：{len(before)} -> {len(after)}")
    ids_b = [s["id"] for s in before]
    ids_a = [s["id"] for s in after]
    if set(ids_a) != set(ids_b):
        problems.append(f"id 集合变化：少 {sorted(set(ids_b) - set(ids_a))[:3]} "
                        f"多 {sorted(set(ids_a) - set(ids_b))[:3]}")
    before_by = {s["id"]: s for s in before}
    for s in after:
        o = before_by.get(s["id"])
        if o is None:
            continue
        sid = s["id"]
        if s.get("streamUrl") != o.get("streamUrl"):
            problems.append(f"{sid} 的 streamUrl 被改动（本任务不得动流地址）")
        if json.dumps(s.get("sources"), sort_keys=True) != json.dumps(o.get("sources"), sort_keys=True):
            problems.append(f"{sid} 的 sources 被改动")
        bad = [t for t in s.get("tags", []) if t not in TAG_KEYS]
        if bad:
            problems.append(f"{sid} 有词表外 tag：{bad}")
        if len(s.get("tags", [])) > MAX_TAGS:
            problems.append(f"{sid} 标签数 {len(s['tags'])} 超过 {MAX_TAGS}")
        if len(s.get("tags", [])) != len(set(s.get("tags", []))):
            problems.append(f"{sid} 标签重复")
        if s.get("country") and not s.get("language"):
            problems.append(f"{sid} 有国家却没语言")
        if s.get("language") and s["language"] not in LANGUAGES:
            problems.append(f"{sid} 语言不在白名单：{s['language']}")
        if (o.get("frequency") or "") and not (s.get("frequency") or ""):
            if not (o.get("country") or o.get("language") or "·" in o["frequency"]):
                problems.append(f"{sid} 原有真实频率被清空：{o['frequency']}")
    return problems


def fetch_metas(urls, workers: int = 8, timeout: float = 12.0) -> dict:
    """抓官网 meta description。带本地缓存，重跑不重复打。"""
    cache = json.loads(META_CACHE.read_text(encoding="utf-8")) if META_CACHE.exists() else {}
    todo = [u for u in urls if u and u not in cache]
    print(f"抓官网自述：{len(todo)} 个待抓（已缓存 {len(cache)}）", file=sys.stderr)

    def one(u):
        try:
            r = requests.get(u, timeout=timeout, headers={"User-Agent": "Mozilla/5.0 (Linux; Android 12)"}
                             , allow_redirects=True)
            if r.status_code != 200:
                return u, ""
            head = r.content[:200000].decode("utf-8", "replace")
            return u, clean_meta(head)
        except Exception:
            return u, ""

    with cf.ThreadPoolExecutor(max_workers=workers) as ex:
        for i, (u, text) in enumerate(ex.map(one, todo), 1):
            cache[u] = text
            if i % 40 == 0:
                print(f"  {i}/{len(todo)}", file=sys.stderr)
    META_CACHE.write_text(json.dumps(cache, ensure_ascii=False), encoding="utf-8")
    return cache


def report(stations, problems, provenance, metas) -> str:
    total = len(stations)
    tagged = [s for s in stations if s.get("tags")]
    zero = [s["name"] for s in stations if not s.get("tags")]
    quoted = sum(1 for s in stations if "—" in (s.get("description") or ""))
    with_desc = sum(1 for s in stations if s.get("description"))
    lines = [
        f"清单 {total} 台",
        f"有标签 {len(tagged)} 台（覆盖率 {len(tagged) * 100.0 / max(1, total):.1f}%）",
        f"0 标签 {len(zero)} 台，样例：{'、'.join(zero[:8])}" if zero else "0 标签 0 台",
        f"有简介 {with_desc} 台，其中带官网自述引文 {quoted} 台",
        f"能对上台外溯源 {len(provenance)} 台；抓到官网自述 {sum(1 for v in metas.values() if v)} 个站点",
        f"语言字段填充 {sum(1 for s in stations if s.get('language'))} 台；国家字段 {sum(1 for s in stations if s.get('country'))} 台",
        f"标签分布 top10：{dict(Counter(t for s in stations for t in (s.get('tags') or [])).most_common(10))}",
        f"断言问题 {len(problems)} 条" + (f"：{problems[:5]}" if problems else ""),
    ]
    return "\n".join(lines)


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("provenance", help="生成 tools/rb_provenance.json")
    d = sub.add_parser("dry-run", help="只出报告")
    d.add_argument("--no-fetch", action="store_true", help="不抓官网，只算标签与字段")
    a = sub.add_parser("apply", help="写 assets（先备份，断言不过拒写）")
    a.add_argument("--no-fetch", action="store_true")

    args = p.parse_args(argv)
    stations = json.loads(STATIONS_JSON.read_text(encoding="utf-8"))["stations"]

    if args.cmd == "provenance":
        rows = build_provenance(POOL, stations)
        PROVENANCE.write_text(json.dumps(rows, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"溯源 {len(rows)} 条 / 清单 {len(stations)} 台 -> {PROVENANCE}")
        return 0

    if args.cmd == "apply":
        # 基线只认第一次的备份。上一版写的是「每次把当前文件备份再读」，
        # 而当前文件已是上一轮的产物——于是生成的「… — 自述」被当真简介永久保留，
        # 而被清空的 frequency 再也拆不回来，数据逐轮退化。这就是自证污染。
        if not BACKUP.exists():
            BACKUP.write_text(STATIONS_JSON.read_text(encoding="utf-8"), encoding="utf-8")
            print(f"已建立基线备份 {BACKUP.name}", file=sys.stderr)
        stations = json.loads(BACKUP.read_text(encoding="utf-8"))["stations"]
        if any(s.get("tags") for s in stations):
            print("[拒写] 基线备份里已含派生标签，拒绝在它上面叠加派生。\n"
                  "        先从 git 恢复未派生的清单，再删掉 .pretag.bak 重建基线。", file=sys.stderr)
            return 1

    prov = load_provenance()
    metas = {} if args.no_fetch else fetch_metas([prov.get(norm_url(s["streamUrl"]), {}).get("homepage", "")
                                                  for s in stations])
    derived = []
    for s in stations:
        row = prov.get(norm_url(s["streamUrl"]), {})
        meta = metas.get(row.get("homepage", ""), "") if not args.no_fetch else ""
        derived.append(derive_station(s, row, meta))

    problems = validate(stations, derived)
    print(report(derived, problems, prov, metas))

    if args.cmd == "dry-run":
        print("\n[未落盘] dry-run 只出报告；确认覆盖率后加 apply")
        return 0
    if problems:
        print("\n[拒写] 断言未通过，先修脚本")
        return 1
    STATIONS_JSON.write_text(json.dumps({"stations": derived}, ensure_ascii=False, indent=2),
                             encoding="utf-8")
    print(f"\n已写入 {STATIONS_JSON.name}（基线：{BACKUP.name}）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
