package com.radio.chinese.ui.iptv

import com.radio.chinese.data.repository.IptvStationRepository
import com.radio.chinese.domain.model.IptvAdult
import com.radio.chinese.domain.model.IptvCategory
import com.radio.chinese.domain.model.IptvLanguage
import com.radio.chinese.domain.model.PlayabilityScore
import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.domain.model.StationFilter

/**
 * iptv 页「哪些台能被看见」的唯一判定。
 *
 * 从 [IptvViewModel] 抽出来是因为 ViewModel 挂着 Hilt、stateIn 与 DataStore，
 * 规则写在里面就只能靠真机看；而这几条恰恰是漏了不报错、只会表现为
 * 「收藏了却找不到」「搜索里看不到、收藏页却冒出来」的那类（spec §8 / review C1）。
 * ViewModel 只负责把 Flow 的输入递进来。
 */
data class IptvLists(
    val visible: List<RadioStation>,
    val favorites: List<RadioStation>,
    val recents: List<RadioStation>,
    val categories: List<Pair<String, Int>>,
    val countries: List<Pair<String, Int>>,
    /** 省份/区域 → 频道数。仅中国频道有值，「其他」固定排最后 */
    val regions: List<Pair<String, Int>> = emptyList(),
)

object IptvViewLogic {

    fun build(
        all: List<RadioStation>,
        query: String,
        adultEnabled: Boolean,
        allLanguagesEnabled: Boolean,
        favoriteIds: List<String>,
        recentIds: List<String>,
        /** 连续失败达到该次数后隐藏；0 = 不隐藏 */
        hideThreshold: Int = 0,
        scoreOf: (String) -> Int = { PlayabilityScore.UNKNOWN },
    ): IptvLists {
        // 收藏与最近是电台 / iptv 共用的 id 集合，按前缀先把电台 id 挡在外面。
        // 绝不从 StationRepository.getAllStations() 解析：那里面没有 iptv 条目，
        // HomeViewModel 的 mapNotNull 会把它们整条丢掉，用户看到的是"收藏消失"。
        val mine = all.filter { it.id.startsWith(IptvStationRepository.PREFIX) }
        // 默认隐藏成人：列表、搜索、分类计数、收藏、最近全用同一份 allowed，
        // 免得留一条"列表里没有、收藏页却能看到"的旁路（V2）。
        val adultOk = if (adultEnabled) mine else mine.filterNot { IptvAdult.isAdult(it) }
        // 默认只留中文/英语频道（V13）。分类计数、收藏、最近都基于 allowed，不留旁路。
        // 成人频道不受语言过滤：实测两条成人台恰好是西语（country:grn,spa），
        // 这里若也按语言剔，打开成人开关后仍然一条都看不到，开关等于假的。
        var allowed = if (allLanguagesEnabled) adultOk
        else adultOk.filter { IptvAdult.isAdult(it) || IptvLanguage.isPreferred(it.language) }
        // 连续失败达到阈值就隐藏（0 = 不隐藏）。与成人/语言同一份 allowed，
        // 收藏与最近一起过滤，避免"列表里没有、收藏页却能看到"。
        if (hideThreshold > 0) {
            allowed = allowed.filterNot { PlayabilityScore.isHidden(scoreOf(it.id), hideThreshold) }
        }
        val byId = allowed.associateBy { it.id }
        val favSet = favoriteIds.toSet()

        return IptvLists(
            visible = StationFilter.apply(allowed, category = null, query = query),
            favorites = allowed.filter { it.id in favSet },
            recents = recentIds.mapNotNull { byId[it] },
            categories = allowed
                .flatMap { it.tags }
                // 先翻成中文标签再分组：上游新增两个未抄录的 key 会都落成「其他」，
                // 若先按 key 分组再翻译，Compose items(key = label) 会撞 key 直接抛
                // "Key was already used" 闪退。
                .groupingBy { IptvCategory.labelOf(it) }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .map { it.key to it.value },
            countries = allowed
                .map { it.country }
                .filter { it.isNotBlank() }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .map { it.key to it.value },
            regions = allowed
                .map { it.region }
                .filter { it.isNotBlank() }
                .groupingBy { it }
                .eachCount()
                .entries
                // 「其他」放最后：它是兜底桶，不该压在一堆具体省份前面
                .sortedWith(compareBy({ it.key == "其他" }, { -it.value }))
                .map { it.key to it.value },
        )
    }
}
