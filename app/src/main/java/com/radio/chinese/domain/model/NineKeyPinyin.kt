package com.radio.chinese.domain.model

/**
 * 遥控器九键拼音首字母搜索。
 *
 * TV 上没有实体键盘，软键盘用方向键一个个选字母极慢。九键（2=ABC … 9=WXYZ）
 * 按数字序列直接匹配台名拼音首字母的前缀：输入 `2-2-8-8` 命中「CCTV」（c/c/t/v），
 * 输入 `6-4-4-7` 命中「中国之声」（z/g/z/s）。
 *
 * 纯函数，JVM 可测；拼音列由调用方用 `PinyinIndex.initialsColumn` 取好传进来。
 */
object NineKeyPinyin {

    /** 按键 → 字母组（老式 T9 布局，7/9 是四个字母） */
    private val KEY_LETTERS: Map<Char, String> = mapOf(
        '2' to "abc",
        '3' to "def",
        '4' to "ghi",
        '5' to "jkl",
        '6' to "mno",
        '7' to "pqrs",
        '8' to "tuv",
        '9' to "wxyz",
    )

    /**
     * 数字序列能否作为首字母串的前缀命中。
     *
     * [initialsColumn] 是多音字变体列，形如 `|zggs|zghs|`，任一变体命中即可。
     * 空输入返回 true（不过滤），由调用方决定要不要显示全量。
     */
    fun matches(digits: String, initialsColumn: String): Boolean {
        if (digits.isEmpty()) return true
        return initialsColumn.split('|')
            .filter { it.isNotEmpty() }
            .any { prefixMatches(digits, it) }
    }

    private fun prefixMatches(digits: String, initials: String): Boolean {
        if (initials.length < digits.length) return false
        return digits.indices.all { i ->
            KEY_LETTERS[digits[i]]?.contains(initials[i].lowercaseChar()) == true
        }
    }
}
