package com.radio.chinese.domain.model

/**
 * 成人内容的唯一判据。
 *
 * 取 `nsfw` 标记与 `xxx` 分类的**并集**：iptv-org 用两个独立机制表达同一件事
 * （频道上有 is_nsfw 标记，分类表里另有一个 xxx），只认一边会漏。
 *
 * 放在 domain 是因为过滤发生在两处（ViewModel 的可见集合、分类计数），
 * 两处必须同一个结论；写在页面里就会出现"列表里没有但计数还在"。
 */
object IptvAdult {

    /** iptv-org 分类表里成人项的 id。与 IptvCategory.ADULT 必须是同一个字。 */
    const val adultCategoryKey: String = "xxx"

    fun isAdult(station: RadioStation): Boolean =
        station.nsfw || station.tags.contains(adultCategoryKey)
}
