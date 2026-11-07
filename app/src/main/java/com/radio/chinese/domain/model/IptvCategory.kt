package com.radio.chinese.domain.model

/**
 * iptv 自己的分类集（spec V8）。
 *
 * 为什么不动 StationCategory：那份的 `ordered()` 是节目源管理页与"添加电台"下拉的取值来源，
 * 往里塞 movies/series/kids/weather 会让那些页面长出与"电台"语义不符的格子，
 * 影响面远超本需求。iptv 的分类入口只服务 iptv 页。
 *
 * key 必须逐字对齐 iptv-org 的 categories.json。下面 30 个条目是 2026-11-06 实测抄录的
 * （初版我漏了 `family`、还把 `shop` 编成一个不存在的 FINANCE_LIKE），
 * 由 IptvCategoryTest 两条互为对照的用例钉住：一条对抄录清单，一条直接读真实 assets。
 *
 * **与 StationTag 词表无关**（review I-4）：这些 key 存在 `RadioStation.tags` 里，
 * 而 `tags` 对电台台是 StationTag 词表、对 iptv 台是本表。`kids` 与 `weather` 两个 key
 * 在两套词表里重名且含义相近，**靠 id 前缀区分，不得混用**：
 * StationFilter / TagScreen / HomeViewModel / ManageViewModel.sanitizeTags 四处都按
 * StationTag 把关并丢弃词表外的 key，iptv 的 tags 流进那些路径会被静默清空。
 *
 * **覆盖边界（实测）**：清单里 3,557 条记录 tags 为空（含全部 2,096 条 direct 记录）。
 * 按 tags 聚合的分类入口永远到不了它们，它们只能靠搜索 / 国家 / 最近 / 收藏到达。
 * 这是数据形状决定的，不是 bug，但也不能当成“分类能逛完整个库”。
 */
enum class IptvCategory(val key: String, val label: String) {
    AUTO("auto", "汽车"),
    ANIMATION("animation", "动画"),
    BUSINESS("business", "商业"),
    CLASSIC("classic", "经典"),
    COMEDY("comedy", "喜剧"),
    COOKING("cooking", "美食"),
    CULTURE("culture", "人文"),
    DOCUMENTARY("documentary", "纪录"),
    EDUCATION("education", "教育"),
    ENTERTAINMENT("entertainment", "娱乐"),
    FAMILY("family", "家庭"),
    GENERAL("general", "综合"),
    INTERACTIVE("interactive", "互动"),
    KIDS("kids", "少儿"),
    LEGISLATIVE("legislative", "议会"),
    LIFESTYLE("lifestyle", "生活"),
    MOVIES("movies", "电影"),
    MUSIC("music", "音乐"),
    NEWS("news", "新闻"),
    OUTDOOR("outdoor", "户外"),
    PUBLIC("public", "公共"),
    RELAX("relax", "休闲"),
    RELIGIOUS("religious", "宗教"),
    SCIENCE("science", "科学"),
    SERIES("series", "剧集"),
    SHOP("shop", "购物"),
    SPORTS("sports", "体育"),
    TRAVEL("travel", "旅行"),
    WEATHER("weather", "天气"),
    // 常量只有一个出处：这里不复写一份字面量，否则改了 IptvAdult 这边不会跟着变，
    // 而成人过滤会半边失效（默认关着时 xxx 频道直接露出）
    ADULT(IptvAdult.adultCategoryKey, "成人");

    companion object {
        const val otherLabel: String = "其他"

        /**
         * 这个 key 在表里吗。
         *
         * 需要它而不是“labelOf 返不返 otherLabel”：后者把「其他」这个中文串当成了
         * 哨兵值。一旦上游新增两个未抄录的分类，两个格子都会显示「其他」，
         * 而列表按标签做 key 时会直接抛 "Key was already used" 闪退。
         */
        fun isKnown(key: String): Boolean = entries.any { it.key == key }

        /**
         * 未知 key 回落「其他」，不抛异常。
         * 上游随时可能新增分类；抛异常的后果是整个 iptv 页打不开，
         * 而那只是多了一个没中文的格子——代价不对等。
         */
        fun labelOf(key: String): String =
            entries.firstOrNull { it.key == key }?.label ?: otherLabel

        /**
         * 展示顺序即声明顺序。
         *
         * 注意：分类入口实际按“条数降序 + 只取前 12”展示，不读本函数。
         * 想要格子位置稳定，得在 Task 8 里改成以本表为顺序源，而不是指望注释。
         */
        fun ordered(): List<Pair<String, String>> = entries.map { it.key to it.label }
    }
}
