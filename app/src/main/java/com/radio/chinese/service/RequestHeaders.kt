package com.radio.chinese.service

import com.radio.chinese.domain.model.StreamHeaders

/**
 * 这一路 HTTP 请求该带哪些头。
 *
 * 抽成纯函数是因为它是**安全边界**：WebDAV 的 Basic 凭据只能发给云盘，
 * 绝不能跟着 iptv 的第三方主机走。iptv 里绝大多数流没有自定义头
 * （实测 17,319/18,584 条无 header），所以"header 为空"不能反推"不是 iptv"——
 * 早先用 `streamHeaderStore.current == null` 当判据，等于把凭据发给了所有裸 IP 流。
 */
object RequestHeaders {

    fun build(
        headers: StreamHeaders?,
        isIptv: Boolean,
        webDavUser: String,
        webDavAuth: String,
    ): Map<String, String> {
        val props = linkedMapOf<String, String>()
        // 凭据只给非 iptv 路；iptv 清单里有大量第三方主机与裸 IP
        if (!isIptv && webDavUser.isNotEmpty()) props["Authorization"] = webDavAuth
        if (headers != null) {
            if (headers.userAgent.isNotBlank()) props["User-Agent"] = headers.userAgent
            if (headers.referrer.isNotBlank()) props["Referer"] = headers.referrer
        }
        return props
    }
}
