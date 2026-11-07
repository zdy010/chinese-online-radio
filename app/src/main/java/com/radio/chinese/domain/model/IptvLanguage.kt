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
}
