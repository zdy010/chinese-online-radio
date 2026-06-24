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
}
