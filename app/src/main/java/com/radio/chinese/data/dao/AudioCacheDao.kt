package com.radio.chinese.data.dao

import androidx.room.*
import com.radio.chinese.data.entity.AudioCacheEntity

@Dao
interface AudioCacheDao {
    @Query("SELECT * FROM audio_cache WHERE sourceId = :sourceId AND parentPath = :parentPath ORDER BY isFolder DESC, name ASC")
    suspend fun getByParentPath(sourceId: Long, parentPath: String): List<AudioCacheEntity>

    @Query("DELETE FROM audio_cache WHERE sourceId = :sourceId AND parentPath = :parentPath")
    suspend fun deleteByParentPath(sourceId: Long, parentPath: String)

    @Query("DELETE FROM audio_cache WHERE sourceId = :sourceId")
    suspend fun deleteBySource(sourceId: Long)

    /** 清空整张缓存表（所有源的已索引曲目/目录行）。可重建缓存，不含用户收藏/最近。 */
    @Query("DELETE FROM audio_cache")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<AudioCacheEntity>)

    @Query("SELECT * FROM audio_cache WHERE sourceId = :sourceId AND name LIKE '%' || :query || '%' ORDER BY name ASC LIMIT 50")
    suspend fun searchByName(sourceId: Long, query: String): List<AudioCacheEntity>

    /**
     * 全源（sourceId 传 null）或单源曲目搜索。
     *
     * pattern 必须是 IndexQuery.likePattern 的产物，不能接裸用户输入：
     * 否则一个 `%` 就是“匹配全部”。目录行不参与搜索。
     *
     * [matchPinyin] 由调用方按查询是否纯字母数字决定：中文输入本来就能命中
     * name，不该再绕一道拼音列。拼音列存的是 `|a|b|` 形态，首尾竖线挡住
     * 跨变体拼接出的假命中，所以这里可以直接 LIKE。
     */
    @Query(
        """
        SELECT * FROM audio_cache
        WHERE isFolder = 0
          AND (:sourceId IS NULL OR sourceId = :sourceId)
          AND (
            name LIKE :pattern ESCAPE '\'
            OR (:matchPinyin AND (pyInitials LIKE :pattern ESCAPE '\' OR pyFull LIKE :pattern ESCAPE '\'))
          )
        ORDER BY name ASC
        LIMIT :limit
        """
    )
    suspend fun searchTracks(
        sourceId: Long?,
        pattern: String,
        limit: Int,
        matchPinyin: Boolean = false,
    ): List<AudioCacheEntity>

    /**
     * 每个已爬目录的最旧 cachedAt。进索引的新鲜度判定唯一依据：
     * 空目录不留行，因此它每轮都会被重爬一次，代价一个请求。
     */
    @Query(
        "SELECT parentPath, MIN(cachedAt) AS oldest FROM audio_cache " +
            "WHERE sourceId = :sourceId GROUP BY parentPath"
    )
    suspend fun dirOldestCachedAt(sourceId: Long): List<DirStamp>

    @Query("SELECT COUNT(*) FROM audio_cache WHERE sourceId = :sourceId AND isFolder = 0")
    suspend fun countTracks(sourceId: Long): Int

    /** 跨源已收录曲目数：判定“本机到底有没有索引”的唯一口径，不能用内存计数。 */
    @Query("SELECT COUNT(*) FROM audio_cache WHERE isFolder = 0")
    suspend fun countAllTracks(): Int
}

/** dirOldestCachedAt 的行投影：目录路径 + 该目录里最旧的一行时间戳。 */
data class DirStamp(val parentPath: String, val oldest: Long)
