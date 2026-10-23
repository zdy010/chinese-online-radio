package com.radio.chinese.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.radio.chinese.data.entity.EpgFetchEntity
import com.radio.chinese.data.entity.EpgProgramEntity

@Dao
interface EpgProgramDao {

    @Query("SELECT * FROM epg_program WHERE stationId = :stationId AND date = :date ORDER BY startMin ASC")
    suspend fun programsFor(stationId: String, date: String): List<EpgProgramEntity>

    /** 节目搜索只搜本地已缓存的节目单，按天取一次。 */
    @Query("SELECT * FROM epg_program WHERE date = :date ORDER BY stationId ASC, startMin ASC")
    suspend fun allProgramsFor(date: String): List<EpgProgramEntity>

    @Query("SELECT * FROM epg_fetch WHERE stationId = :stationId AND date = :date")
    suspend fun fetchStamp(stationId: String, date: String): EpgFetchEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPrograms(rows: List<EpgProgramEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFetch(row: EpgFetchEntity)

    @Query("DELETE FROM epg_program WHERE stationId = :stationId AND date = :date")
    suspend fun deletePrograms(stationId: String, date: String)

    @Query("DELETE FROM epg_program WHERE date < :before")
    suspend fun deleteProgramsOlderThan(before: String)

    @Query("DELETE FROM epg_fetch WHERE date < :before")
    suspend fun deleteFetchOlderThan(before: String)
}
