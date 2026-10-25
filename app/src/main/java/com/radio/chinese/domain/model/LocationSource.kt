package com.radio.chinese.domain.model

/**
 * 坐标值对象与定位来源接口。
 * 放在 domain 使 data 层可合规依赖（之前 import service 是违例）。
 */
data class Coords(val lat: Double, val lng: Double)

interface LocationSource {
    suspend fun currentCoords(): Coords?
}
