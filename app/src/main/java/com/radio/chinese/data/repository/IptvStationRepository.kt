package com.radio.chinese.data.repository

import android.content.Context
import com.radio.chinese.data.local.StationJson
import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.domain.model.StreamHeaders
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * iptv 清单的唯一读取入口（spec V4/V9）。
 *
 * 为什么独立于 StationRepository：12k 条不能进首页/分类/地区/EPG 的任何一条列表 ——
 * 那些页面要的是"451 个电台"，掺进来既拖慢也串语义（V5/V9 定的就是"iptv 自成一页"）。
 *
 * 解析走 [StationJson.parse]：与内置电台清单**同一个解析器、同一种 {"stations":[...]} 形状**。
 * 另起一条 kotlinx decodeFromString 路径就是第二套真相，
 * 手写解析器不认的字段（altNames/nsfw/quality/userAgent/referrer）会静默丢。
 *
 * 懒加载 + Mutex：assets 是 6.33 MB，首次解析放 IO 线程；Mutex 保证列表页与播放页
 * 并发进入时不会解两遍。解析失败退化成空表 —— iptv 页显示空态，而不是把崩溃带给用户。
 */
@Singleton
class IptvStationRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val mutex = Mutex()

    @Volatile private var loaded: List<RadioStation>? = null
    @Volatile private var headerIndex: Map<String, StreamHeaders>? = null

    suspend fun all(): List<RadioStation> = loaded ?: withContext(Dispatchers.IO) {
        mutex.withLock {
            loaded ?: runCatching {
                val raw = context.assets.open(ASSET).bufferedReader().use { it.readText() }
                StationJson.parse(raw)
            }.getOrElse {
                // 退化但不静默：这行日志是"iptv 页空白"唯一的现场线索
                android.util.Log.w(TAG, "iptv 清单读取失败，退化为空表：${it.message}")
                emptyList()
            }.also {
                loaded = it
                headerIndex = buildHeaderIndex(it)
            }
        }
    }

    /**
     * 只有 iptv 前缀才查本表：非前缀 id 每次都过 12k 条是白付的代价，
     * 而电台侧的查找走的是 StationRepository.getAllStations()，不该被拖进这里。
     */
    suspend fun byId(id: String): RadioStation? {
        if (!isIptvId(id)) return null
        return all().firstOrNull { it.id == id }
    }

    fun isIptvId(id: String): Boolean = id.startsWith(PREFIX)

    /**
     * 该流是否需要自定义请求头。
     *
     * **两个字段都为空时返回 null**，不是返回空对象：PlayerManager 只在非 null 时
     * 写 StreamHeaderStore，返回空对象会让纯音频台也被塞一组空请求头 ——
     * 那是静默的行为改变，单测与编译都抓不到。
     *
     * 只要有一个字段有值就返回非 null：上游有一批流只要 Referer 不要 UA，
     * 按"两个都要非空"筛会把它们整个跳过，表现是 403 且没人报警。
     */
    suspend fun headersFor(url: String): StreamHeaders? {
        if (url.isBlank()) return null
        all()   // 确保索引已建
        return headerIndex?.get(url)
    }

    private fun buildHeaderIndex(list: List<RadioStation>): Map<String, StreamHeaders> {
        val m = HashMap<String, StreamHeaders>(1024)
        for (s in list) {
            for (src in s.allSources()) {
                if (src.userAgent.isNotBlank() || src.referrer.isNotBlank()) {
                    m[src.url] = StreamHeaders(src.userAgent, src.referrer)
                }
            }
        }
        return m
    }

    companion object {
        const val ASSET = "iptv_channels.json"
        const val PREFIX = "iptv:"
        private const val TAG = "IptvRepo"
    }
}
