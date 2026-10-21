package com.radio.chinese.domain.model

/**
 * 播放队列里的一行。搜索结果、收藏、最近播放三个列表的元素类型各不相同，
 * 构造队列只需要这几个字段，先归一才能让这段逻辑脱离 Android 被单测覆盖。
 */
data class QueueRow(
    val sourceId: Long,
    val path: String,
    val name: String,
    val size: Long,
    /** 所在目录路径，末段作为曲集名展示 */
    val parentPath: String,
)

/**
 * 从「界面上的一列曲目」构造上一曲/下一曲要走的队列。
 *
 * 为什么需要它：从目录树点歌时队列取自 browseItems，上下曲正常；而搜索结果、
 * 收藏、最近播放这三处点歌时队列一律传了 emptyList()，`PlayerManager` 里
 * `indexOfFirst { it.fileId == file.fileId }` 找不到当前项、index 落到 -1，
 * 上一曲/下一曲于是无处可跳，按了没反应。
 *
 * 队列顺序就是传入的行序：用户在一列结果里点播放，上下曲就该在这列里走。
 * 但行序不等于「界面上的全部行」：调用方得先把跨来源的行筛掉，只把与当前项
 * 同源的行传进来，理由见 [urlOf]。
 */
object OperaQueue {

    /**
     * [urlOf] 按行解析可播放地址。
     *
     * 调用方必须保证 [rows] 与 [pick] 同属一个来源：WebDAV 的鉴权头不跟着 MediaItem 走，
     * 而是 RadioService 每次建 DataSource 时从 WebDavClient 单例现读，而 resolveUri 会把
     * 凭证写进那个单例（browseClient 对所有源返回同一实例）。跨源逐行解析，
     * 最后一次会覆盖前面所有行，整条队列拿错账号起播，并把凭证发往另一台主机。
     *
     * [pick] 不在 [rows] 里时返回只含它单项的队列，而不是空队列——空队列正是
     * 这次 bug 的形态，宁可只放出这一首，也不要 index = -1 的静默失败。
     */
    fun build(
        rows: List<QueueRow>,
        pick: QueueRow,
        urlOf: (QueueRow) -> String,
        sourceNameOf: (Long) -> String,
    ): Pair<List<OperaAudioFile>, Int> {
        val index = rows.indexOfFirst { it.sourceId == pick.sourceId && it.path == pick.path }
        if (index < 0) {
            return listOf(fileOf(pick, urlOf(pick), sourceNameOf(pick.sourceId))) to 0
        }
        val files = rows.map { fileOf(it, urlOf(it), sourceNameOf(it.sourceId)) }
        return files to index
    }

    private fun fileOf(row: QueueRow, url: String, sourceName: String) = OperaAudioFile(
        // fileId 沿用路径哈希：PlayerManager 靠它定位当前项，也用作已下载路径的键，
        // 换算法会让既有下载缓存对不上，index 也会重新落回 -1
        fileId = row.path.hashCode().toLong(),
        name = row.name,
        size = row.size,
        categoryName = sourceName,
        operaName = row.parentPath.substringAfterLast("/"),
        downloadUrl = url,
        sourcePath = row.path,
    )
}
