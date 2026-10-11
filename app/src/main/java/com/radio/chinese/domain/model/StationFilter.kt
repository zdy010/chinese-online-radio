package com.radio.chinese.domain.model

/**
 * 电台列表的三个筛选维度：分类、搜索、标签。彼此正交，可任意组合。
 *
 * 放在 domain 而不是 ui：这是关于电台的规则，Repository 与页面都要用；
 * 早期版本它长在 HomeViewModel 私有方法里，于是仓库层想用就得反向依赖界面层。
 * 做成纯函数是因为 ViewModel 挂着 Hilt 与协程作用域，单测成本高，
 * 而"选了标签还能不能叠加分类"这种规则恰恰最需要被测试钉住。
 */
object StationFilter {

    /** 搜索词命中的范围：台名、频率、简介、国家、语言、标签中文名。 */
    fun matches(station: RadioStation, query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        val tagLabels = station.tags.map { StationTag.labelOf(it).lowercase() }
        return station.name.lowercase().contains(q) ||
            station.frequency.lowercase().contains(q) ||
            station.description.lowercase().contains(q) ||
            station.country.lowercase().contains(q) ||
            station.language.lowercase().contains(q) ||
            tagLabels.any { it.contains(q) || q.contains(it) }
    }

    fun apply(
        stations: List<RadioStation>,
        category: String?,
        query: String,
        tag: String? = null,
    ): List<RadioStation> = stations.filter { st ->
        (category == null || st.category == category) &&
            (tag == null || tag in st.tags) &&
            matches(st, query)
    }

    /**
     * 有电台的标签及其台数，按词表顺序。
     *
     * 只列真有电台的标签：全量词表会把 0 台的标签也摆进标签页，
     * 点进去只看到一句"这个标签下暂时没有电台"——这个教训来自分类条。
     * 词表外的脏 key 直接忽略，不给它生成入口。
     */
    fun tagsWithCount(stations: List<RadioStation>): List<Pair<StationTag, Int>> {
        val counts = stations.flatMap { it.tags }.groupingBy { it }.eachCount()
        return StationTag.entries.filter { counts.containsKey(it.key) }.map { it to counts.getValue(it.key) }
    }
}
