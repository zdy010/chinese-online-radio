package com.radio.chinese.data.local

import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.domain.model.StationSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * 内置清单的解析。
 *
 * 放在这里而不是留在 StationRepository 里：Repository 要 Context 才能读 assets，
 * JVM 单测拿不到 Context，于是「词表外的 tag」「有国家没语言」这类数据错误
 * 只能在运行时靠人眼发现。抽成纯函数后，测试可以直接读真 JSON 文件当构建期闸门
 * （见 StationJsonTest）。
 */
object StationJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): List<RadioStation> {
        val root = json.parseToJsonElement(text).jsonObject
        val arr = root["stations"]?.jsonArray ?: return emptyList()
        return arr.map { el ->
            val o = el.jsonObject
            RadioStation(
                id = o.string("id"),
                name = o.string("name"),
                category = o.string("category").ifBlank { "general" },
                streamUrl = o.string("streamUrl"),
                sources = o["sources"]?.asArray()?.mapNotNull { src ->
                    val x = src as? JsonObject ?: return@mapNotNull null
                    val url = x.string("url")
                    if (url.isBlank()) null
                    else StationSource(url = url, label = x.string("label"), bitrate = x.string("bitrate").toIntOrNull() ?: 0)
                } ?: emptyList(),
                logoUrl = o.string("logoUrl"),
                frequency = o.string("frequency"),
                description = o.string("description"),
                website = o.string("website"),
                tags = o["tags"]?.asArray()?.mapNotNull { (it as? JsonPrimitive)?.content }
                    ?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
                country = o.string("country"),
                language = o.string("language"),
            )
        }
    }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.content?.trim() ?: ""

    private fun Any.asArray(): JsonArray? = this as? JsonArray
}
