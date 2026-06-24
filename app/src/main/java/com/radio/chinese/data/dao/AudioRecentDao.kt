package com.radio.chinese.data.dao

import androidx.room.*
import com.radio.chinese.data.entity.AudioRecentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AudioRecentDao {
    @Query("SELECT * FROM audio_recent ORDER BY playedAt DESC LIMIT 20")
    fun getAllFlow(): Flow<List<AudioRecentEntity>>

    @Query("SELECT * FROM audio_recent WHERE trackPath = :trackPath LIMIT 1")
    suspend fun getByPath(trackPath: String): AudioRecentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: AudioRecentEntity)

    @Query("DELETE FROM audio_recent WHERE trackPath = :trackPath")
    suspend fun deleteByPath(trackPath: String)

    @Query("DELETE FROM audio_recent WHERE id NOT IN (SELECT id FROM audio_recent ORDER BY playedAt DESC LIMIT 100)")
    suspend fun trimTo100()
}
