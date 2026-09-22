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
    GENERAL("general", "综合"),

    /** 成人内容：排最后，不影响其他分类在网格里的既有位置。 */
    ADULT("adult", "成人");

    companion object {
        fun fromKey(key: String?): StationCategory = entries.firstOrNull { it.key == key } ?: GENERAL

        /** 未知分类也回显中文名清单里没有的原值，不再暴露内部 key。 */
        fun labelOf(key: String?): String =
            entries.firstOrNull { it.key == key }?.label ?: (key ?: "")

        /** 供下拉与筛选条使用，顺序即展示顺序。 */
        fun ordered(): List<Pair<String, String>> = entries.map { it.key to it.label }
    }
}
