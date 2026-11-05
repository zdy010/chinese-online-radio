package com.radio.chinese.domain.model

/**
 * 一条流要求的 HTTP 请求头。
 *
 * 放 domain 而不是 ui/service：RadioService（service 层）要读它，而 service 不该依赖 ui；
 * 同时查表逻辑要能在 JVM 单测里被钉住 —— "没有 header 的流必须返回 null，
 * 不能返回空对象"这条一旦破，纯音频台也会被塞一组空请求头，那是静默的行为改变。
 *
 * 两个字段都是非空串：空值由上游（仓库的查表）表达成"整个对象为 null"，
 * 而不是留一个空串进来让各处判空。
 */
data class StreamHeaders(val userAgent: String, val referrer: String)
