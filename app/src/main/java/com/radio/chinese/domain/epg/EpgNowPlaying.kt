package com.radio.chinese.domain.epg

import com.radio.chinese.domain.model.EpgProgram

/**
 * 列表页「正在播出」那一行的取数规则。
 *
 * 只关心此刻真在播的节目名；电台是否接入节目单（列表里那个标记）由
 * [com.radio.chinese.data.repository.EpgRepository.hasEpg] 单独判断，与时间无关，
 * 两者数据源不同，不在这里混为一谈。
 *
 * 数据只来自本地缓存：不为列表批量打接口，缺缓存的台这里给不出标题，
 * 列表行自然不渲染。[programsByStation] 用 `cachedPrograms(date).groupBy { it.stationId }` 拼。
 *
 * 结果按台 id 排序并用 LinkedHashMap 保住顺序：map 的迭代顺序不属语义，
 * 交给它会让同一份数据在两次重组里给出先后不同的列表行。
 */
fun nowPlayingTitles(
    programsByStation: Map<String, List<EpgProgram>>,
    date: String,
    minute: Int,
    epgStationIds: Set<String>,
): Map<String, String> {
    if (epgStationIds.isEmpty()) return emptyMap()
    val sorted = LinkedHashMap<String, String>()
    epgStationIds.asSequence()
        .sorted()
        .forEach { id ->
            val dayPrograms = programsByStation[id]?.filter { it.date == date }.orEmpty()
            val title = EpgQuery.currentAt(dayPrograms, minute)?.title
            if (title != null) sorted[id] = title
        }
    return sorted
}
