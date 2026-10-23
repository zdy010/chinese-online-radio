package com.radio.chinese.data.repository

import androidx.room.withTransaction
import com.radio.chinese.data.entity.EpgFetchEntity
import com.radio.chinese.data.entity.EpgProgramEntity
import com.radio.chinese.data.local.EpgDatabase
import com.radio.chinese.data.local.EpgProgramDao
import com.radio.chinese.domain.epg.EpgStore
import com.radio.chinese.domain.model.EpgProgram
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomEpgStore @Inject constructor(
    private val database: EpgDatabase,
    private val dao: EpgProgramDao,
) : EpgStore {

    override suspend fun programs(stationId: String, date: String): List<EpgProgram> =
        dao.programsFor(stationId, date).map { it.toDomain() }

    override suspend fun programsOn(date: String): List<EpgProgram> =
        dao.allProgramsFor(date).map { it.toDomain() }

    override suspend fun fetchedAt(stationId: String, date: String): Long? =
        dao.fetchStamp(stationId, date)?.fetchedAt

    /**
     * 删旧、插新、打时间戳必须在同一个事务里。中途失败会留下「旧行已删、
     * 新行没插、时间戳却是新的」——这台台会在 6 小时内一直显示空节目单。
     */
    override suspend fun replaceDay(
        stationId: String,
        date: String,
        programs: List<EpgProgram>,
        fetchedAt: Long,
    ) {
        database.withTransaction {
            dao.deletePrograms(stationId, date)
            if (programs.isNotEmpty()) {
                dao.insertPrograms(programs.map { EpgProgramEntity.from(it) })
            }
            dao.upsertFetch(EpgFetchEntity(stationId = stationId, date = date, fetchedAt = fetchedAt))
        }
    }

    override suspend fun deleteBefore(date: String) {
        database.withTransaction {
            dao.deleteProgramsOlderThan(date)
            dao.deleteFetchOlderThan(date)
        }
    }
}
