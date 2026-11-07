package com.radio.chinese.domain.model

/**
 * 「中国」页的频道顺序：CCTV 系列打头，再按省份 / 中文台名排。
 *
 * 上游清单按 id 字母序，直接过滤出中国频道会从 Ando TV、安徽卫视开始，
 * 用户进来第一眼看不到中央台。CCTV 内部还要按台号自然序（1、2、10，而不是 1、10、2）。
 */
object IptvChinaOrder {

    /**
     * 三档：纯数字主频道（CCTV-1 → CCTV-2 → … → CCTV-11，台号自然序）→ 其它 CCTV 变体
     * （CCTV-4 America / CCTV-4K / CCTV-5+ / CCTV-Billiards…）→ 非 CCTV 台。
     *
     * 变体必须整体后置：它们也带台号，混进主序列会把 3 后面接成 4 America、4 Asia、
     * 4 Europe、4K，用户要的「1→2→3→…→10→11」就被打断了。
     * 非 CCTV 台不给中文字段排序：String 天然顺序是 Unicode 码点，既非拼音也非笔画，
     * 「北京」会排在「安徽」前面。这一档保持清单原顺序（sortedWith 是稳定排序）。
     */
    fun sorted(stations: List<RadioStation>): List<RadioStation> =
        stations.map { station ->
            val r = rank(station)
            station to SortKey(
                rank = r,
                number = if (r == RANK_OTHER) Int.MAX_VALUE else cctvNumber(station),
                variantName = if (r == RANK_CCTV_VARIANT) station.name.lowercase() else "",
            )
        }
            .sortedWith(
                compareBy<Pair<RadioStation, SortKey>> { it.second.rank }
                    .thenBy { it.second.number }
                    .thenBy { it.second.variantName }
            )
            .map { it.first }

    private data class SortKey(val rank: Int, val number: Int, val variantName: String)

    private const val RANK_CCTV_MAIN = 0
    private const val RANK_CCTV_VARIANT = 1
    private const val RANK_OTHER = 2

    private fun rank(station: RadioStation): Int = when {
        !isCctv(station) -> RANK_OTHER
        PURE_CCTV_NUMBER.matches(station.name) -> RANK_CCTV_MAIN
        else -> RANK_CCTV_VARIANT
    }

    private fun isCctv(station: RadioStation): Boolean =
        station.name.contains("CCTV", ignoreCase = true) ||
            station.id.contains("CCTV", ignoreCase = true) ||
            station.altNames.any { it.contains("CCTV", ignoreCase = true) }

    /** 非 CCTV 或取不到台号时给 MAX，让它落到普通频道那一组 */
    private fun cctvNumber(station: RadioStation): Int =
        CCTV_NUMBER.find(station.name)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE

    private val CCTV_NUMBER = Regex("""CCTV[- ]?(\d+)""", RegexOption.IGNORE_CASE)
    /** 整串就是「CCTV-数字」才算主频道；带后缀（America / K / + / Billiards）的不算 */
    private val PURE_CCTV_NUMBER = Regex("""CCTV[- ]?\d+""", RegexOption.IGNORE_CASE)
}
