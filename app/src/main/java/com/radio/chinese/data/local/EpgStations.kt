package com.radio.chinese.data.local

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `assets/epg_stations.json`：云听 broadcastId 与本项目 stationId 的唯一映射来源。
 *
 * 解析成纯函数放在这里，是为了让 JVM 单测能直接读仓库里那份真实资源跑闸门
 * （见 EpgStationsTest），不必依赖 Android 的 AssetManager。
 */
object EpgStations {

    data class Entry(
        val stationId: String,
        val broadcastId: String,
        /** 匹配时的规范化台名，仅用于构建期核对，不进运行时逻辑 */
        val name: String,
    )

    private val JSON = Json { ignoreUnknownKeys = true }

    fun parse(json: String): List<Entry> {
        val payload = decode(json) ?: return emptyList()
        return payload.stations.mapNotNull {
            val id = it.stationId.trim()
            val bid = it.broadcastId.trim()
            if (id.isEmpty() || bid.isEmpty()) null
            else Entry(stationId = id, broadcastId = bid, name = it.name)
        }
    }

    fun schemaVersion(json: String): Int = decode(json)?.schemaVersion ?: 0

    private fun decode(json: String): Payload? = runCatching {
        JSON.decodeFromString<Payload>(json)
    }.getOrNull()

    @Serializable
    private data class Payload(
        val schemaVersion: Int = 0,
        val stations: List<Row> = emptyList(),
    )

    @Serializable
    private data class Row(
        val stationId: String = "",
        val broadcastId: String = "",
        val name: String = "",
    )
}
