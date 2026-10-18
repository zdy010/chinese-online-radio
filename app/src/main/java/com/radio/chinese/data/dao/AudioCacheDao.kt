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
}
