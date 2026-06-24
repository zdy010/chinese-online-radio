package com.radio.chinese.data.dao

import androidx.room.*
import com.radio.chinese.data.entity.AudioFavoriteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AudioFavoriteDao {
    @Query("SELECT * FROM audio_favorites ORDER BY favoritedAt DESC")
    fun getAllFlow(): Flow<List<AudioFavoriteEntity>>

    @Query("SELECT * FROM audio_favorites WHERE trackPath = :trackPath LIMIT 1")
    suspend fun getByPath(trackPath: String): AudioFavoriteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(fav: AudioFavoriteEntity)

    @Delete
    suspend fun delete(fav: AudioFavoriteEntity)

    @Query("DELETE FROM audio_favorites WHERE trackPath = :trackPath")
    suspend fun deleteByPath(trackPath: String)

    @Query("SELECT EXISTS(SELECT 1 FROM audio_favorites WHERE trackPath = :trackPath)")
    suspend fun isFavorited(trackPath: String): Boolean
}
