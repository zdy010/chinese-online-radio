package com.radio.chinese

import com.radio.chinese.domain.model.StreamHeaders
import com.radio.chinese.service.RequestHeaders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 请求头是安全边界：WebDAV 的 Basic 凭据只能发给云盘。
 *
 * 这些断言钉的是"iptv 恒不带 Authorization"。生产侧判据是
 * `streamHeaderStore.isIptv`，不是 `current == null` —— 实测 17,319/18,584 条
 * iptv 流没有自定义头，拿 null 反推会把凭据发给所有裸 IP 流。
 */
class RequestHeadersTest {

    @Test
    fun `iptv 路即使配了 WebDAV 也不带 Authorization`() {
        val props = RequestHeaders.build(
            headers = StreamHeaders(userAgent = "UA", referrer = "http://ref.example"),
            isIptv = true,
            webDavUser = "user",
            webDavAuth = "Basic dXNlcjpwYXNz",
        )
        assertFalse("iptv 路不得携带 WebDAV 凭据", "Authorization" in props)
        assertEquals("UA", props["User-Agent"])
        assertEquals("http://ref.example", props["Referer"])
    }

    @Test
    fun `iptv 无自定义头时什么都不带`() {
        val props = RequestHeaders.build(
            headers = null,
            isIptv = true,
            webDavUser = "user",
            webDavAuth = "Basic xxx",
        )
        assertTrue("没有 UA/Referer 也不该补别的头", props.isEmpty())
    }

    @Test
    fun `非 iptv 路才带 Authorization`() {
        val props = RequestHeaders.build(
            headers = null,
            isIptv = false,
            webDavUser = "user",
            webDavAuth = "Basic xxx",
        )
        assertEquals("Basic xxx", props["Authorization"])
    }

    @Test
    fun `没有 WebDAV 账号时不带 Authorization`() {
        val props = RequestHeaders.build(null, false, "", "")
        assertTrue(props.isEmpty())
    }
}
