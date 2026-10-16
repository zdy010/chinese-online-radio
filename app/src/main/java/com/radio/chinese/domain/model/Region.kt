package com.radio.chinese.domain.model

/**
 * 电台或用户所处的省/市级地区。空省市用 NONE 表示“未知/不参与本地加分”。
 * 只做数据承载，不含推断或排序逻辑（那些是 domain 里的纯函数）。
 */
data class Region(val province: String = "", val city: String = "") {
    val isEmpty: Boolean get() = province.isBlank() && city.isBlank()

    companion object {
        val NONE = Region()
    }
}
