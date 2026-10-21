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

/**
 * 改选省份时算出新的存储串「省|市」。
 *
 * 省没变就保留已选城市：地区页的省份弹窗只提供省，若一律写成「省|」，
 * 用户在设置页选过的城市会被顺手抹掉，`StationSorter.tierOf` 的同市档（tier3）
 * 随之退化成同省（tier2），首页顺序当场变化——而弹窗就是用当前省高亮的，
 * 用户很容易把它当「确认」再点一次。
 * 换省时旧城市属于上一个省，必须清空。
 */
fun manualRegionAfterProvinceChange(province: String, current: Region): String =
    "$province|" + (if (current.province == province) current.city else "")
