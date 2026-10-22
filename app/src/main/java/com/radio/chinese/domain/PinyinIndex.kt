package com.radio.chinese.domain

import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType

/**
 * 把中文文本转成可做子串匹配的拼音串，供电台名与音频库曲目名共用。
 *
 * 为什么是多值而不是「取主读音」：实测探针发现 pinyin4j 的多音字候选
 * 顺序并不是常用音在前（`长`→`zhang,chang`、`乐`→`le,yue`），取第一个会把
 * 「长江」算成 `zj`、「音乐之声」算成 `ylzs`，恰好把最常用的读法全弄错。
 * 所以每个读音都展开，存全部变体。
 *
 * 纯 JVM，不依赖 Android，可直接单测。
 */
object PinyinIndex {

    /**
     * 变体数上限。5 个双读音字就是 32 种组合，而真实曲名里最多的样本
     * （`白蛇传`）只有 4 种。16 覆盖到 4 个双读音字，再往上截断只丢生僻读法，
     * 不会让常用输入搜不到。
     */
    private const val MAX_VARIANTS = 16

    private val FORMAT = HanyuPinyinOutputFormat().apply {
        caseType = HanyuPinyinCaseType.LOWERCASE
        toneType = HanyuPinyinToneType.WITHOUT_TONE
        vCharType = HanyuPinyinVCharType.WITH_V
    }

    /** 全拼变体，如 `王菲` → `["wangfei"]`、`重庆` → `["zhongqing","chongqing"]`。 */
    fun variants(text: String): List<String> = expand(text) { it }

    /** 首字母变体，如 `王菲` → `["wf"]`、`重庆` → `["zq","cq"]`。 */
    fun initialsVariants(text: String): List<String> = expand(text) { it.take(1) }

    /**
     * 落库形态：`|a|b|`。首尾的竖线让跨变体边界的假阳性必须包含 `|`，
     * 而 `|` 不会是用户输入，所以 `LIKE '%q%'` 不会从两个变体的接缝里拼出命中。
     *
     * 没有可索引内容时返回空串而不是 `||`：空串不会被任何非空查询命中。
     */
    fun column(text: String): String = wrap(variants(text))

    fun initialsColumn(text: String): String = wrap(initialsVariants(text))

    /**
     * 查询是否该走拼音列。
     *
     * 含中文（或空格、标点）的查询维持原有的「只匹配原名」路径：中文输入
     * 本来就能命中，多绕一道拼音列只会引入回退风险。数字放行是为兼容
     * `bt360` 这类型号名，纯数字匹不上拼音列，等价于原行为。
     */
    fun isAsciiQuery(query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return false
        return q.all { it in 'a'..'z' || it in '0'..'9' }
    }

    private fun wrap(list: List<String>): String =
        if (list.isEmpty()) "" else list.joinToString(separator = "", prefix = "|", postfix = "|")

    /**
     * 逐字展开成笛卡尔积。每层扩展后排序并截断到 [MAX_VARIANTS]：
     * 变体长度相同，字典序只取决于各位置，所以任何排不进前缀前 N 的组合
     * 也排不进最终前 N，逐层截断不会改变结果。
     */
    private inline fun expand(text: String, transform: (String) -> String): List<String> {
        val perChar = text.map { syllablesOf(it).map(transform).distinct() }
            .filter { it.isNotEmpty() }
        if (perChar.isEmpty()) return emptyList()

        var acc = listOf("")
        for (options in perChar) {
            acc = acc.asSequence()
                .flatMap { prefix -> options.asSequence().map { prefix + it } }
                .sorted()
                .take(MAX_VARIANTS)
                .toList()
        }
        return acc
    }

    /**
     * 单个字符的音节候选。ASCII 字母数字原样保留，其它不可转换的字符
     * （标点、空格、假名）返回空列表，即整个字符被跳过——这样
     * 「黄梅戏-天仙配」与「黄梅戏天仙配」得到同一个索引。
     */
    private fun syllablesOf(ch: Char): List<String> {
        if (ch.code < 128 && ch.isLetterOrDigit()) return listOf(ch.lowercaseChar().toString())
        val readings = runCatching { PinyinHelper.toHanyuPinyinStringArray(ch, FORMAT) }
            .getOrNull()
        // 非汉字返回空数组而非 null，两种都要挡
        if (readings.isNullOrEmpty()) return emptyList()
        return readings.toList().distinct()
    }
}
