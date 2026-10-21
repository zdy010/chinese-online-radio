package com.radio.chinese.domain.model

import com.radio.chinese.data.local.RegionGazetteer

/** 地区页第一级的两个大段：中国（按省，含港澳台）与国外（按国家）。 */
enum class RegionSection { CN, FOREIGN }

/**
 * 地区页的一个入口：中央台 / 本地 / 某个省 / 某个国家 / 未归类。
 * 只承载分组结果，界面文案由 [label] 提供。
 */
data class RegionBucket(
    /** 稳定 key（central / local / other / country:? / province:<省名> / country:<国名>），Compose 列表 key 用 */
    val key: String,
    /** 界面文案：中央台 / 本地 · 浙江 / 浙江 / 美国 / 国家未标注 */
    val label: String,
    val stations: List<RadioStation>,
    /** 归属大段。第一级靠它插「中国 / 国外」分隔标题，港澳台因此不会和外国并列。 */
    val section: RegionSection,
)

/**
 * 按地域切分电台，供地区页使用。
 *
 * 为什么不给 radio_stations.json 加 province 字段：仓库里已有一套从台名/简介反推省市的
 * [RegionResolver]（「本地优先」排序在用），451 个台里 229 个能直接推出省份。
 * 再加一份手工维护的省份字段，等于给同一件事留两个真相来源，改数据要同步两处。
 *
 * 判定顺序：境外 > 中央 > 省市推断 > 未归类。
 * 境外必须排在最前：197 个 gl_ 台名字是英文，不会命中省市词表，但 country 有值。
 */
object StationRegion {
    const val CENTRAL = "central"
    const val LOCAL = "local"
    const val OTHER = "other"
    /** country 缺失、又靠 gl_ 前缀判为境外的台：没有国家可以分，单独一组不混进中国段 */
    const val UNKNOWN_COUNTRY = "country:?"

    fun provinceKey(province: String): String = "province:$province"
    fun countryKey(country: String): String = "country:$country"

    /**
     * 央广与国际台的标志。这些台推不出省市，不单独归类就会掉进「其他」，
     * 和两百个境外台、电视伴音混在一起，等于没有地区页。
     * cnr/cri 走 id 前缀，其余国际台与中央级电视伴音靠台名里的特征词。
     *
     * CCTV/CETV 已确认归进中央台，不另开一组：它们确实是国家级覆盖。
     */
    private val CENTRAL_MARKERS = listOf(
        "环球", "南海之声", "世界华声", "粤语国际",
        "CCTV", "CETV", "M-Radio", "MRadio",
    )

    fun isCentral(station: RadioStation): Boolean =
        station.id.startsWith("cnr") || station.id.startsWith("cri") ||
            CENTRAL_MARKERS.any { station.name.contains(it) }

    /**
     * 视作「国内」的国家/地区写法。港澳台的台要归到各自的地区分组，不是「境外台」。
     *
     * 得先过一遍汉化再判：`country` 字段里混着 ISO 两字母码，而下面的 [ISO2_CN]
     * 明确承认了 CN/TW/HK/MO 这几个写法。若只比原文，`country="CN"` 会被判成国外，
     * 在外段里生成一个 label 为「中国」的分组，跟同一页的「中国」段标题直接矛盾。
     *
     * 也不能放宽到「包含」语义去匹配任意写法：美国、英国这类非中文值必须继续算国外。
     */
    private val CN_SPECIAL_REGIONS = setOf("台湾", "香港", "澳门")

    private fun isDomestic(country: String): Boolean {
        val label = countryLabel(country)
        return label.startsWith("中国") || label in CN_SPECIAL_REGIONS
    }

    /**
     * 是否归「国外段」。有国家字段就以它为准，字段为空才回落到 gl_/ad_ 前缀。
     *
     * 不能反过来先看前缀：gl_ 只是「全球源」的来源标记，里面混了 40 个台湾台，
     * 先看前缀就会把它们和一百多个欧美台一起塞进国外段。
     * ad_ 同样要让位于 country：否则一个写了 country="中国" 的 ad_ 台会落进国外段。
     */
    fun isOverseas(station: RadioStation): Boolean {
        val country = station.country.trim()
        if (country.isNotEmpty()) return !isDomestic(country)
        return station.id.startsWith("gl_") || station.id.startsWith("ad_")
    }

    /**
     * ISO 3166-1 alpha-2 -> 中文国名。境外台的 country 字段不统一：
     * 绝大多数写中文（美国、英国），但确确实实混着 PK / NP / MU 这类两字母码，
     * 不翻就会把英文缩写直接糊到地区页分组名上。
     * 查不到就原样显示，至少不会把台弄丢。
     */
    private val ISO2_CN = mapOf(
        "US" to "美国", "GB" to "英国", "UK" to "英国", "IE" to "爱尔兰", "IS" to "冰岛",
        "DE" to "德国", "AT" to "奥地利", "CH" to "瑞士", "LU" to "卢森堡", "NL" to "荷兰",
        "BE" to "比利时", "FR" to "法国", "MC" to "摩纳哥", "ES" to "西班牙", "PT" to "葡萄牙",
        "IT" to "意大利", "GR" to "希腊", "MT" to "马耳他", "HU" to "匈牙利", "PL" to "波兰",
        "CZ" to "捷克", "SK" to "斯洛伐克", "RO" to "罗马尼亚", "BG" to "保加利亚",
        "RS" to "塞尔维亚", "HR" to "克罗地亚", "SI" to "斯洛文尼亚", "BA" to "波斯尼亚",
        "SE" to "瑞典", "NO" to "挪威", "DK" to "丹麦", "FI" to "芬兰",
        "UA" to "乌克兰", "BY" to "白俄罗斯", "MD" to "摩尔多瓦", "RU" to "俄罗斯",
        "AZ" to "阿塞拜疆", "GE" to "格鲁吉亚", "AM" to "亚美尼亚", "KZ" to "哈萨克斯坦",
        "UZ" to "乌兹别克斯坦", "TM" to "土库曼斯坦", "TJ" to "塔吉克斯坦", "KG" to "吉尔吉斯斯坦",
        "TR" to "土耳其", "CY" to "塞浦路斯", "IL" to "以色列", "IR" to "伊朗",
        "SA" to "沙特阿拉伯", "AE" to "阿联酋", "QA" to "卡塔尔", "KW" to "科威特",
        "JO" to "约旦", "LB" to "黎巴嫩", "IQ" to "伊拉克", "OM" to "阿曼", "YE" to "也门",
        "JP" to "日本", "KR" to "韩国", "KP" to "朝鲜", "MN" to "蒙古",
        "SG" to "新加坡", "MY" to "马来西亚", "TH" to "泰国", "VN" to "越南",
        "ID" to "印度尼西亚", "PH" to "菲律宾", "MM" to "缅甸", "KH" to "柬埔寨",
        "LA" to "老挝", "BN" to "文莱", "TL" to "东帝汶", "NP" to "尼泊尔",
        "BT" to "不丹", "LK" to "斯里兰卡", "MV" to "马尔代夫", "IN" to "印度",
        "PK" to "巴基斯坦", "BD" to "孟加拉国", "AF" to "阿富汗",
        "CA" to "加拿大", "AU" to "澳大利亚", "NZ" to "新西兰", "FJ" to "斐济",
        "MX" to "墨西哥", "CU" to "古巴", "JM" to "牙买加", "HT" to "海地",
        "TT" to "特立尼达和多巴哥", "BS" to "巴哈马", "BB" to "巴巴多斯",
        "GT" to "危地马拉", "HN" to "洪都拉斯", "SV" to "萨尔瓦多", "NI" to "尼加拉瓜",
        "CR" to "哥斯达黎加", "PA" to "巴拿马", "CO" to "哥伦比亚", "VE" to "委内瑞拉",
        "EC" to "厄瓜多尔", "PE" to "秘鲁", "BO" to "玻利维亚", "BR" to "巴西",
        "CL" to "智利", "AR" to "阿根廷", "UY" to "乌拉圭", "PY" to "巴拉圭",
        "ZA" to "南非", "MU" to "毛里求斯", "ET" to "埃塞俄比亚", "KE" to "肯尼亚",
        "NG" to "尼日利亚", "GH" to "加纳", "EG" to "埃及", "LY" to "利比亚",
        "TN" to "突尼斯", "DZ" to "阿尔及利亚", "MA" to "摩洛哥", "SD" to "苏丹",
        "SS" to "南苏丹", "CM" to "喀麦隆", "TZ" to "坦桑尼亚", "UG" to "乌干达",
        "ZM" to "赞比亚", "ZW" to "津巴布韦", "BW" to "博茨瓦纳", "NA" to "纳米比亚",
        "CN" to "中国", "TW" to "台湾", "HK" to "香港", "MO" to "澳门",
    )

    /** 把 country 里的 ISO 两字母码翻成中文；已是中文则原样返回。 */
    fun countryLabel(country: String): String {
        val c = country.trim()
        if (c.isEmpty()) return ""
        return ISO2_CN[c.uppercase()] ?: c
    }

    /**
     * 境外台归属的国家名（已汉化）。返回空串表示数据里根本没写国家。
     *
     * ad_ 前缀的台把国家码写在 id 里（ad_de / ad_us），这是它们唯一的国家线索；
     * gl_ 前缀的没有，只能归入「国家未标注」。
     */
    fun foreignCountry(station: RadioStation): String {
        val label = countryLabel(station.country)
        if (label.isNotBlank()) return label
        if (station.id.startsWith("ad_")) {
            val code = station.id.substringAfter("ad_").substringBefore("_")
            if (code.length == 2) return countryLabel(code)
        }
        return ""
    }

    /**
     * 切分。只返回非空分组；省/国家各自按台数降序（同台数按名）。
     *
     * 输出分两大段：先中国（中央台、本地、各省、其他），再国外（按国家）。
     * 港澳台落在中国的省级组里，不与外国并列。
     *
     * [userProvince] 非空且该省有台时额外产出一个「本地」组，它引用的是同一个省分组里的台，
     * 不会把该省从省份列表摘掉——用户既要点得快的快捷入口，也要能按省逐个浏览。
     */
    fun bucketize(
        stations: List<RadioStation>,
        gazetteer: RegionGazetteer.Data,
        userProvince: String = "",
    ): List<RegionBucket> {
        val central = mutableListOf<RadioStation>()
        val other = mutableListOf<RadioStation>()
        val unlisted = mutableListOf<RadioStation>()
        val byProvince = LinkedHashMap<String, MutableList<RadioStation>>()
        val byCountry = LinkedHashMap<String, MutableList<RadioStation>>()

        for (station in stations) {
            if (isOverseas(station)) {
                val country = foreignCountry(station)
                if (country.isBlank()) unlisted += station
                else byCountry.getOrPut(country) { mutableListOf() } += station
                continue
            }
            if (isCentral(station)) {
                central += station
                continue
            }
            val region = RegionResolver.infer(
                "${station.name} ${station.description}", station.country, gazetteer
            )
            if (region.province.isBlank()) other += station
            else byProvince.getOrPut(region.province) { mutableListOf() } += station
        }

        val buckets = mutableListOf<RegionBucket>()
        if (central.isNotEmpty()) {
            buckets += RegionBucket(CENTRAL, "中央台", central.sortedBy { it.name }, RegionSection.CN)
        }
        byProvince[userProvince]?.let { local ->
            if (local.isNotEmpty()) {
                buckets += RegionBucket(
                    LOCAL, "本地 · $userProvince", local.sortedBy { it.name }, RegionSection.CN
                )
            }
        }
        byProvince.entries.sortedWith(countThenName).forEach { (province, list) ->
            buckets += RegionBucket(
                provinceKey(province), province, list.sortedBy { it.name }, RegionSection.CN
            )
        }
        if (other.isNotEmpty()) {
            buckets += RegionBucket(OTHER, "其他", other.sortedBy { it.name }, RegionSection.CN)
        }

        // 国外不再打成一个「境外台」大桶：161 个台堆在一起等于没分，
        // 想听美国台的人只能从头翻。按国家切开后美国 68、英国 27，一眼就能定位。
        byCountry.entries.sortedWith(countThenName).forEach { (country, list) ->
            buckets += RegionBucket(
                countryKey(country), country, list.sortedBy { it.name }, RegionSection.FOREIGN
            )
        }
        if (unlisted.isNotEmpty()) {
            buckets += RegionBucket(
                UNKNOWN_COUNTRY, "国家未标注", unlisted.sortedBy { it.name }, RegionSection.FOREIGN
            )
        }
        return buckets
    }

    /**
     * 台数降序，同台数按名。中国省份与外国共用这一条规则，两段之间才不会各排各的。
     * 做成 Comparator 而不是成员函数：调用点是 `entries.sortedWith(countThenName)`，
     * 写成 `entries.sortedByCountThenName()` 会被当成扩展函数而解析不到。
     */
    private val countThenName: Comparator<Map.Entry<String, MutableList<RadioStation>>> =
        compareByDescending<Map.Entry<String, MutableList<RadioStation>>> { it.value.size }
            .thenBy { it.key }
}
