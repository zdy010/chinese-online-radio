package com.radio.chinese.data.dao

import androidx.room.*
import com.radio.chinese.data.entity.AudioSourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AudioSourceDao {
    @Query("SELECT * FROM audio_sources ORDER BY sortOrder ASC, createdAt ASC")
    fun getAllFlow(): Flow<List<AudioSourceEntity>>

    @Query("SELECT * FROM audio_sources ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun getAll(): List<AudioSourceEntity>

    @Query("SELECT * FROM audio_sources WHERE id = :id")
    suspend fun getById(id: Long): AudioSourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(source: AudioSourceEntity): Long

    @Update
    suspend fun update(source: AudioSourceEntity)

    @Delete
    suspend fun delete(source: AudioSourceEntity)

    @Query("DELETE FROM audio_sources WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE audio_sources SET lastSyncAt = :syncTime WHERE id = :id")
    suspend fun updateSyncTime(id: Long, syncTime: Long)
}
