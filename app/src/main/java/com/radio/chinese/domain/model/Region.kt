package com.radio.chinese.domain.model

/**
 * 电台或用户所处的省/市级地区。空省市用 NONE 表示“未知/不参与本地加分”。
 * 只做数据承载，不含推断或排序逻辑（那些是 domain 里的纯函数）。
 */
data class Region(val province: String = "", val city: String = "") {
    val isEmpty: Boolean get() = province.isBlank() && city.isBlank()

    companion object {
        val NONE = Region()

        /** 解析存储串 "省|市"；空串/空白视为无地区。 */
        fun parse(text: String?): Region {
            if (text.isNullOrBlank()) return NONE
            val parts = text.split("|")
            return Region(
                province = parts.getOrElse(0) { "" }.trim(),
                city = parts.getOrElse(1) { "" }.trim(),
            )
        }
    }
}

/**
 * 地区优先级：手动 > 自动缓存 > 无（纯函数，供 RegionRepository 的响应式 Flow 与单测共用）。
 * 设置页改选地区后，首页靠观察该结果的变化即时重排，不再需要重启 App。
 */
fun resolveRegion(manual: String?, cached: String?): Region {
    val m = Region.parse(manual)
    if (!m.isEmpty) return m
    val c = Region.parse(cached)
    if (!c.isEmpty) return c
    return Region.NONE
}
