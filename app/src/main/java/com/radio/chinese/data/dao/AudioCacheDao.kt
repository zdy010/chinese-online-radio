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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<AudioCacheEntity>)

    @Query("SELECT * FROM audio_cache WHERE sourceId = :sourceId AND name LIKE '%' || :query || '%' ORDER BY name ASC LIMIT 50")
    suspend fun searchByName(sourceId: Long, query: String): List<AudioCacheEntity>

    /**
     * 全源（sourceId 传 null）或单源曲目搜索。
     *
     * pattern 必须是 IndexQuery.likePattern 的产物，不能接裸用户输入：
     * 否则一个 `%` 就是“匹配全部”。目录行不参与搜索。
     */
    @Query(
        """
        SELECT * FROM audio_cache
        WHERE isFolder = 0
          AND (:sourceId IS NULL OR sourceId = :sourceId)
          AND name LIKE :pattern ESCAPE '\'
        ORDER BY name ASC
        LIMIT :limit
        """
    )
    suspend fun searchTracks(sourceId: Long?, pattern: String, limit: Int): List<AudioCacheEntity>

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
}

/** dirOldestCachedAt 的行投影：目录路径 + 该目录里最旧的一行时间戳。 */
data class DirStamp(val parentPath: String, val oldest: Long)
