package com.radio.chinese.domain.model

/**
 * 标签分组。顺序即展示顺序：先最具体的戏曲曲种，再内容，再音乐风格，最后场景。
 */
enum class TagGroup(val label: String) {
    Opera("戏曲曲艺"),
    Content("内容"),
    Music("音乐风格"),
    Scene("场景"),
}

/**
 * 节目标签词表：全项目唯一来源。
 *
 * 与 [StationCategory] 同一手法，是因为分类表当年在四处各写一份、取值还不一致
 * （ManageScreen 那份漏了 tv_audio）。标签比分类更容易漂：多写一份，
 * 半年后就会出现「越剧 / 粤剧」这类互相打架的叫法。
 *
 * 两条纪律，加标签前先读：
 *  1. 不与分类重复。分类已有新闻 / 交通 / 体育 / 财经 / 戏曲，这里只打细一层
 *     （localnews、worldnews、sportslive…）。打同名词等于零信息量的筛选。
 *  2. 语言与地区不是标签。它们是 RadioStation.country / .language 字段，
 *     由副标题负责显示，避免出现两处真源。
 *
 * 命名上规避了两处真实冲突：越剧与粤剧拼音同为 yueju，故粤剧用 yueopera；
 * 乡村音乐的 key 不能叫 country（与字段同名），故用 countrymusic。
 * key 一旦写进 assets JSON 就很难改，改名要重跑 tools/derive_tags.py 的迁移。
 */
enum class StationTag(val key: String, val label: String, val group: TagGroup) {
    // 戏曲曲艺
    JINGJU("jingju", "京剧", TagGroup.Opera),
    YUJU("yuju", "豫剧", TagGroup.Opera),
    YUEJU("yueju", "越剧", TagGroup.Opera),
    YUEOPERA("yueopera", "粤剧", TagGroup.Opera),
    HUANGMEI("huangmei", "黄梅戏", TagGroup.Opera),
    PINGJU("pingju", "评剧", TagGroup.Opera),
    KUNQU("kunqu", "昆曲", TagGroup.Opera),
    QINQIANG("qinqiang", "秦腔", TagGroup.Opera),
    CHUANJU("chuanju", "川剧", TagGroup.Opera),
    HUJV("hujv", "沪剧", TagGroup.Opera),
    PINGTAN("pingtan", "评弹", TagGroup.Opera),
    CROSSTALK("crosstalk", "相声", TagGroup.Opera),

    // 内容
    LOCALNEWS("localnews", "本地新闻", TagGroup.Content),
    WORLDNEWS("worldnews", "国际新闻", TagGroup.Content),
    MILITARY("military", "国防军事", TagGroup.Content),
    HEALTH("health", "健康医药", TagGroup.Content),
    EDU("edu", "教育", TagGroup.Content),
    KIDS("kids", "少儿", TagGroup.Content),
    NIGHTTALK("nighttalk", "夜间清谈", TagGroup.Content),
    STORYTELLING("storytelling", "评书故事", TagGroup.Content),
    HISTORY("history", "历史人文", TagGroup.Content),
    FINCOMMENT("fincomment", "财经评述", TagGroup.Content),
    TECH("tech", "科技", TagGroup.Content),
    SPORTSLIVE("sportslive", "赛事解说", TagGroup.Content),
    TRAFFICNOW("trafficnow", "实时路况", TagGroup.Content),
    WEATHER("weather", "天气", TagGroup.Content),

    // 音乐风格
    OLDIES("oldies", "怀旧老歌", TagGroup.Music),
    HITS80("hits80", "八十年代", TagGroup.Music),
    ROCK("rock", "摇滚", TagGroup.Music),
    FOLK("folk", "民谣", TagGroup.Music),
    JAZZ("jazz", "爵士", TagGroup.Music),
    CLASSICAL("classical", "古典", TagGroup.Music),
    LIGHTMUSIC("lightmusic", "轻音乐", TagGroup.Music),
    BLUES("blues", "蓝调", TagGroup.Music),
    COUNTRYMUSIC("countrymusic", "乡村", TagGroup.Music),
    ELECTRONIC("electronic", "电子舞曲", TagGroup.Music),
    HIPHOP("hiphop", "嘻哈", TagGroup.Music),
    RNB("rnb", "节奏布鲁斯", TagGroup.Music),
    METAL("metal", "金属", TagGroup.Music),
    CPOP("cpop", "华语流行", TagGroup.Music),
    POP("pop", "欧美流行", TagGroup.Music),
    INSTRUMENTAL("instrumental", "纯音乐", TagGroup.Music),

    // 场景
    DRIVE("drive", "行车", TagGroup.Scene),
    SLEEP("sleep", "助眠放松", TagGroup.Scene),
    BUDDHIST("buddhist", "佛教禅修", TagGroup.Scene),
    LANGLEARN("langlearn", "外语学习", TagGroup.Scene),
    FITNESS("fitness", "运动健身", TagGroup.Scene),
    CHAT("chat", "闲聊陪伴", TagGroup.Scene),
    ;

    companion object {
        private val index: Map<String, StationTag> = entries.associateBy { it.key }

        /** 顺序即分组声明顺序。派生脚本与详情页 chips 都按这个优先级截断。 */
        val keyOrder: List<String> = entries.map { it.key }

        fun fromKey(key: String): StationTag? = index[key]

        /** 未知 key 回落显示原值：宁显示英文 key，也不给一片空白（同 StationCategory.labelOf）。 */
        fun labelOf(key: String): String = index[key]?.label ?: key

        fun isValidKey(key: String): Boolean = index.containsKey(key)

        fun orderedByGroup(): List<Pair<TagGroup, List<StationTag>>> =
            TagGroup.entries.map { g -> g to entries.filter { it.group == g } }

        /** 按词表顺序取前 limit 个；词表外的 key 直接丢弃，不往界面上倒垃圾。 */
        fun byKeyOrder(keys: List<String>, limit: Int): List<StationTag> =
            keys.mapNotNull { index[it] }.distinct().sortedBy { keyOrder.indexOf(it.key) }.take(limit)
    }
}
