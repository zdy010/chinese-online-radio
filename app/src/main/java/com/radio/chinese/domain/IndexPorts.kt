package com.radio.chinese.domain

/**
 * 索引与浏览器提供的端口。
 *
 * 放 domain 而不是 service：实现方在 data 层（Room 适配）与 domain.browser 层
 * （浏览器工厂），消费方在 service 层（索引器）。端口若长在 service，就会变成
 * data → service 的反向依赖，与本项目 AudioBrowser(domain) / WebDavBrowser(实现)
 * 的既有分层相冲突。
 */

/** 索引器写入缓存的最小行集；字段与 audio_cache 一一对应。 */
data class IndexRow(
    val path: String,
    val parentPath: String,
    val name: String,
    val size: Long,
    val isFolder: Boolean,
)

/** 目录级缓存读写。生产实现走 AudioCacheDao，测试用内存假件（本项目无 Robolectric）。 */
interface IndexCacheWriter {
    /** 每个已爬目录的最旧 cachedAt，新鲜度判定的唯一依据。 */
    suspend fun oldestByDir(sourceId: Long): Map<String, Long>

    /** 覆盖式写入一个目录：先删旧行再落新行，天然幂等。 */
    suspend fun replaceDir(sourceId: Long, path: String, rows: List<IndexRow>)

    suspend fun countTracks(sourceId: Long): Int
}

/** 源级同步时间戳回写。只在整棵树爬完时写，见 IndexCoordinator 的收尾。 */
interface IndexSourceSync {
    suspend fun markSynced(sourceId: Long, at: Long)
}

/** 浏览器提供口：索引器只需要 WebDAV 形态的来源，非 WebDAV 返回 null。 */
interface BrowserProvider {
    fun createForIndexing(source: AudioSource): AudioBrowser?
}
