package com.radio.chinese.domain.model

/**
 * 「中国」页的频道顺序：CCTV 系列打头，再按省份 / 中文台名排。
 *
 * 上游清单按 id 字母序，直接过滤出中国频道会从 Ando TV、安徽卫视开始，
 * 用户进来第一眼看不到中央台。CCTV 内部还要按台号自然序（1、2、10，而不是 1、10、2）。
 */
object IptvChinaOrder {

    /**
     * 只做两件事：CCTV 组整体提前、组内按台号自然序。其余频道**保持清单原顺序**
     * （sortedWith 是稳定排序）。
     *
     * 不给中文字段加 thenBy：String 的天然顺序是 Unicode 码点，既不是拼音序也不是笔画序，
     * 「北京」会排在「安徽」前面，看起来像乱序。用户只要求「CCTV 开始」，不要求重排全部。
     */
    fun sorted(stations: List<RadioStation>): List<RadioStation> =
        stations.sortedWith(
            compareBy<RadioStation> { if (isCctv(it)) 0 else 1 }
                .thenBy { cctvNumber(it) }
        )

    private fun isCctv(station: RadioStation): Boolean =
        station.name.contains("CCTV", ignoreCase = true) ||
            station.id.contains("CCTV", ignoreCase = true) ||
            station.altNames.any { it.contains("CCTV", ignoreCase = true) }

    /** 非 CCTV 或取不到台号时给 MAX，让它落到普通频道那一组 */
    private fun cctvNumber(station: RadioStation): Int =
        CCTV_NUMBER.find(station.name)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE

    private val CCTV_NUMBER = Regex("""CCTV[- ]?(\d+)""", RegexOption.IGNORE_CASE)
}
