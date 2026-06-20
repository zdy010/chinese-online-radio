package com.radio.chinese.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.radio.chinese.domain.model.DownloadedOpera
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadedOperaDao {
    @Query("SELECT * FROM downloaded_operas ORDER BY downloadTime DESC")
    fun getAllDownloaded(): Flow<List<DownloadedOpera>>

    @Query("SELECT * FROM downloaded_operas WHERE fileId = :fileId")
    suspend fun getByFileId(fileId: Long): DownloadedOpera?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(opera: DownloadedOpera)

    @Query("DELETE FROM downloaded_operas WHERE fileId = :fileId")
    suspend fun delete(fileId: Long)

    @Query("SELECT fileId FROM downloaded_operas")
    suspend fun getAllDownloadedIds(): List<Long>
}
