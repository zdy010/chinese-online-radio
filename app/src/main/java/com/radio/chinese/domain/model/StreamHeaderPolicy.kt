package com.radio.chinese.domain.model

/**
 * "这一路播放该带什么请求头"的唯一判定。
 *
 * 非 iptv 台一律 null：即使调用方手里还留着上一个台的 [StreamHeaders]，也必须覆盖掉，
 * 否则那台的 UA/Referer 会串到纯音频电台上（项目里 WebDAV 单例凭证踩过同一类跨源串写）。
 * iptv 台原样采用查表结果 —— 只带 Referer 不带 UA 的流是上游真实存在的一类，
 * 不能在这里"规范化"成整条丢弃。
 */
object StreamHeaderPolicy {
    fun resolve(isIptv: Boolean, headers: StreamHeaders?): StreamHeaders? =
        if (isIptv) headers else null
}
