package com.radio.chinese.domain

/**
 * 索引与浏览器提供的端口。
 *
 * 放 domain 而不是 service：实现方在 data 层（Room 适配）与 domain.browser 层
 * （浏览器工厂），消费方在 service 层（索引器）。端口若长在 service，就会变成
 * data → service 的反向依赖，与本项目 AudioBrowser(domain) / WebDavBrowser(实现)
 * 的既有分层相冲突。
 */

/** 索引进度快照。`pendingDirs` 是队列里还剩的目录数，不是整棵树的总数。 */
data class IndexedProgress(
    val indexedFiles: Int,
    val pendingDirs: Int,
    val skippedDirs: Int,
)

/** 索引状态。UI 侧只读，据此决定提示文案与是否显示“结果可能不全”。 */
sealed interface IndexState {
    data object Idle : IndexState
    data class Running(val p: IndexedProgress) : IndexState
    data class PartiallyFailed(val p: IndexedProgress) : IndexState
    data class Done(val trackCount: Int) : IndexState
}

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

/**
 * 音频库“全源搜索”当前的可用阶段（仅手机档会进入）。
 *
 * UI 只渲染这个枚举，不自己判断“该不该弹确认 / 该不该挡结果”——
 * 那些规则一旦写进 Compose 就再也验不到了。
 */
enum class SearchPhase {
    /** 用户关闭了“允许建立索引”且库里无索引 */
    Disabled,

    /** 一个网络源也没添加：此时“建立索引”无意义，应该引导去添加来源 */
    NoWebdavSource,

    /** 从未建立索引且本次会话未问过 → 应弹“是否现在建立” */
    NotIndexed,

    /** 本次会话已选“以后再说” → 不弹，仅提示行留手动入口 */
    Deferred,

    /** 建立中 → 不给结果 */
    Indexing,

    /** 有索引且未过期 → 正常出结果 */
    Ready,

    /** 有索引但可能过期 → 出结果 + 一行“点这里更新” */
    ReadyStale,
}

/** 浏览器提供口：索引器只需要 WebDAV 形态的来源，非 WebDAV 返回 null。 */
interface BrowserProvider {
    fun createForIndexing(source: AudioSource): AudioBrowser?
}
