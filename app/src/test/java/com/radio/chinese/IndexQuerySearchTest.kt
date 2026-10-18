package com.radio.chinese

import com.radio.chinese.domain.model.IndexQuery
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * LIKE 转义：通配符必须失效，且最终形态是 %…% 包裹的子串匹配。
 *
 * 不转义的话用户输入一个 `%` 就等价于“匹配全部”，清空半个字屏就刷屏；
 * 转义顺序尤其关键，反斜杠必须最先处理，否则会把刚补出来的 `\%` 再吃掉一层。
 */
class IndexQuerySearchTest {
    @Test fun `普通中文按子串包裹`() {
        assertEquals("%花木兰%", IndexQuery.likePattern("花木兰"))
    }

    @Test fun `百分号被转义`() {
        assertEquals("%100\\%现场%", IndexQuery.likePattern("100%现场"))
    }

    @Test fun `下划线被转义`() {
        assertEquals("%a\\_b%", IndexQuery.likePattern("a_b"))
    }

    @Test fun `反斜杠自身先转义避免二次转义`() {
        assertEquals("%a\\\\b%", IndexQuery.likePattern("a\\b"))
    }

    @Test fun `空白输入产出空串交由调用方短路`() {
        assertEquals("", IndexQuery.likePattern("   "))
    }

    @Test fun `首尾空格被裁掉`() {
        assertEquals("%贵妃醉酒%", IndexQuery.likePattern("  贵妃醉酒 "))
    }
}
