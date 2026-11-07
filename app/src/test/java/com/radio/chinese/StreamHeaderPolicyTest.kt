package com.radio.chinese

import com.radio.chinese.domain.model.StreamHeaderPolicy
import com.radio.chinese.domain.model.StreamHeaders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Task 6 审查 I-2 / 计划 Review Focus #6 的"零回退"守卫：
 * 非 iptv 台一律写 null，这条规则以前只活在 PlayerManager 的注释里，删掉没人会发现。
 */
class StreamHeaderPolicyTest {

    private val residual = StreamHeaders(userAgent = "UA-of-previous-iptv", referrer = "https://ref.example")

    @Test
    fun `非 iptv 台即使有残留 header 也必须清空`() {
        assertNull(StreamHeaderPolicy.resolve(isIptv = false, headers = residual))
    }

    @Test
    fun `iptv 台原样采用查表结果`() {
        assertSame(residual, StreamHeaderPolicy.resolve(isIptv = true, headers = residual))
    }

    @Test
    fun `只带 referer 的流不被规范化掉`() {
        val h = StreamHeaders(userAgent = "", referrer = "https://only-ref.example")
        val r = StreamHeaderPolicy.resolve(isIptv = true, headers = h)
        assertEquals("", r!!.userAgent)
        assertEquals("https://only-ref.example", r.referrer)
    }

    @Test
    fun `iptv 台没有命中 header 时返回 null，不塞空对象`() {
        assertNull(StreamHeaderPolicy.resolve(isIptv = true, headers = null))
    }
}
