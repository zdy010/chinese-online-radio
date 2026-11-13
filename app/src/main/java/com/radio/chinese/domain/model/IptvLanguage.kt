package com.radio.chinese.domain.model

/**
 * iptv 频道的语言准入：默认只留中文与英语，其余语言进设置开关。
 *
 * 数据来源有两层，必须区分：
 * - 同步工具在语言播放列表里命中 → 写 `zho` / `eng`，属于**明确标注**；
 * - 未命中 → 写 `country:<该国官方语言>`，属于**按国家推测**。
 *
 * 推测层只保留"官方语言全部是中英"的国家：美国（`country:eng,spa`）、
 * 印度（`country:eng,hin,tam`）里有大量频道实际播出西语/印地语，
 * 按"含 eng 就留"会把它们全放进来，等于没过滤。
 */
object IptvLanguage {

    private const val COUNTRY_PREFIX = "country:"

    private val ZH_CODES = setOf("zho", "chi", "cmn", "yue", "zh")
    private val EN_CODES = setOf("en", "eng")

    /** 以 ISO 639-3 为主，兼容 zh/en 两字母写法与中文的几种常见码 */
    private val PREFERRED = ZH_CODES + EN_CODES

    /**
     * 列表副标题用的语言名。
     *
     * `country:` 前缀是过滤用的内部标记，**不能**漏到界面上（会显示成 "日本 · country:jpn"）；
     * 非中英语言默认已被隐藏，打开后显示代码也没有意义，所以只翻中英，其余留空。
     * 电台清单里的 "普通话"/"粤语" 这类中文标签原样返回。
     */
    fun displayName(language: String): String {
        val raw = language.trim()
        if (raw.isEmpty() || raw.startsWith(COUNTRY_PREFIX)) return ""
        val codes = raw.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        return when {
            codes.any { it in ZH_CODES } -> "中文"
            codes.any { it in EN_CODES } -> "英语"
            // 含非 ASCII 的（电台侧中文标签）原样保留
            raw.any { it.code > 127 } -> raw
            else -> ""
        }
    }

    fun isPreferred(language: String): Boolean {
        val raw = language.trim()
        // 上游既没有语言标注也没有国家：无法判断。保留 —— 误伤看不见的英语台比漏放更难解释
        if (raw.isEmpty()) return true
        val isGuess = raw.startsWith(COUNTRY_PREFIX)
        val codes = (if (isGuess) raw.removePrefix(COUNTRY_PREFIX) else raw)
            .split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
        if (codes.isEmpty()) return true
        // 推测层要求官方语言全部是中英；明确标注只要有一个命中就算
        return if (isGuess) codes.all { it in PREFERRED } else codes.any { it in PREFERRED }
    }

    // ========== 分类页的语言多选过滤 ==========
    // 与 isPreferred 是两套口径：那套是「默认只留中英」的粗过滤，这套是用户主动多选的精确过滤。
    // 数据里的 language 有两种形状：明确标注 zho/eng，或 country:<官方语言逗号串> 的推测值。
    // codesOf 把两者归一成 OPTIONS 的 key，推测串里的方言码（cat/eus/glg 等）不单列选项，
    // 但只要串里有一个已知码就归到那个码，不会被误丢进「其他」。

    const val OTHER = "other"

    val OPTIONS: List<Pair<String, String>> = listOf(
        "zho" to "中文",
        "eng" to "英语",
        "jpn" to "日语",
        "kor" to "韩语",
        "spa" to "西班牙语",
        "fra" to "法语",
        "deu" to "德语",
        "rus" to "俄语",
        "por" to "葡萄牙语",
        "ita" to "意大利语",
        "ara" to "阿拉伯语",
        "hin" to "印地语",
        OTHER to "其他/未标注",
    )

    private val OPTION_KEYS: Set<String> = OPTIONS.map { it.first }.toSet() - OTHER

    /** 选项的中文名；未识别的 key 原样回显，不猜。 */
    fun labelOf(code: String): String = OPTIONS.firstOrNull { it.first == code }?.second ?: code

    private fun normalize(code: String): String = when (code) {
        in ZH_CODES -> "zho"
        in EN_CODES -> "eng"
        else -> code
    }

    fun codesOf(language: String): Set<String> {
        val raw = language.trim()
        if (raw.isEmpty()) return setOf(OTHER)
        // 电台侧的「普通话」这类中文标签不是 ISO 码，归中文；iptv 侧基本不会出现
        if (raw.any { it.code > 127 }) return setOf("zho")
        val body = if (raw.startsWith(COUNTRY_PREFIX)) raw.removePrefix(COUNTRY_PREFIX) else raw
        val codes = body.split(',')
            .map { normalize(it.trim().lowercase()) }
            .filter { it.isNotEmpty() }
        val known = codes.filter { it in OPTION_KEYS }.toSet()
        return if (known.isEmpty()) setOf(OTHER) else known
    }

    /** 空选集 = 不过滤（保留 isPreferred / allLanguages 的既有行为）。 */
    fun matches(language: String, selected: Set<String>): Boolean {
        if (selected.isEmpty()) return true
        return codesOf(language).any { it in selected }
    }
}
