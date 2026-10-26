package com.radio.chinese.domain.model

/**
 * 电台分类的唯一定义（key + 中文名 + 展示顺序）。
 *
 * 此前分类表在 StationRepository.getCategories()、CategoryScreen、RadioRecentTab、
 * ManageScreen 四处各写一遍且取值不一致（ManageScreen 那份漏了 tv_audio，
 * RadioRecentTab 对未知分类直接显示原始 key）。新增分类只改这里。
 */
enum class StationCategory(val key: String, val label: String) {
    NEWS("news", "新闻"),
    MUSIC("music", "音乐"),
    TRAFFIC("traffic", "交通"),
    ARTS("arts", "文艺"),
    SPORTS("sports", "体育"),
    FINANCE("finance", "财经"),
    OPERA("opera", "戏曲"),
    TV_AUDIO("tv_audio", "电视伴音"),
    WORLD("world", "世界"),
    GENERAL("general", "综合");

    // 成人分类已按用户要求整体下线：清单里的 4 台（ad_de/ad_md/ad_us/ad_nl）已从
    // radio_stations.json 删除，枚举与图标映射一并移除，不留“点进去空列表”的入口。
    // 用户自建的 category=adult 电台会经 fromKey 回落成 GENERAL，不会解析失败。

    companion object {
        fun fromKey(key: String?): StationCategory = entries.firstOrNull { it.key == key } ?: GENERAL

        /** 未知分类也回显中文名清单里没有的原值，不再暴露内部 key。 */
        fun labelOf(key: String?): String =
            entries.firstOrNull { it.key == key }?.label ?: (key ?: "")

        /** 供下拉与筛选条使用，顺序即展示顺序。 */
        fun ordered(): List<Pair<String, String>> = entries.map { it.key to it.label }

        /**
         * 浏览视图（首页 chips 与分类网格）的顺序：电视伴音置顶。
         *
         * 它是「内容形态」而不是又一个节目类型，近六十台混在类型串里很难直达；
         * 其余类的相对顺序保持不变，免得置顶顺手打乱了用户记住的格子位置。
         * 不动 [ordered]：那份是管理页/添加电台下拉的取值来源。
         */
        fun orderedForBrowse(): List<Pair<String, String>> {
            val all = ordered()
            val tv = all.first { it.first == TV_AUDIO.key }
            return listOf(tv) + all.filterNot { it.first == TV_AUDIO.key }
        }
    }
}
