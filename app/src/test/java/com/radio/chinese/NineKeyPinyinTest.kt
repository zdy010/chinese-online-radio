package com.radio.chinese

import com.radio.chinese.domain.model.NineKeyPinyin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 九键拼音首字母搜索的判定。
 *
 * TV 上这是主要搜索方式（没有软键盘），错一位就整页搜不到，必须有守卫。
 * 变体列格式 `|zggs|zghs|` 与 `PinyinIndex.initialsColumn` 一致。
 */
class NineKeyPinyinTest {

    @Test
    fun `cctv 用 2288 能命中`() {
        assertTrue(NineKeyPinyin.matches("2288", "|cctv|"))
    }

    @Test
    fun `七和九对应四个字母`() {
        // 7 = pqrs，9 = wxyz
        assertTrue(NineKeyPinyin.matches("79", "|pz|"))
        assertTrue(NineKeyPinyin.matches("79", "|sw|"))
    }

    @Test
    fun `数字不对应首字母时不命中`() {
        // 2 = abc，`z` 不在里面
        assertFalse(NineKeyPinyin.matches("2", "|zggs|"))
    }

    @Test
    fun `输入比首字母长时不命中`() {
        assertFalse(NineKeyPinyin.matches("2288", "|cc|"))
    }

    @Test
    fun `多音字变体里任一变体命中即可`() {
        // 中国之声 zggs / zghs：z=9 g=4 g=4 s=7，两个变体都落在 9447
        assertTrue(NineKeyPinyin.matches("9447", "|zggs|zghs|"))
        // 第四位换成 8（tuv），s 不在 tuv 里
        assertFalse(NineKeyPinyin.matches("9448", "|zggs|zghs|"))
    }

    @Test
    fun `空输入不过滤`() {
        assertTrue(NineKeyPinyin.matches("", "|cctv|"))
    }
}
