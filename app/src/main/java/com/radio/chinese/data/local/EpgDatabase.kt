package com.radio.chinese.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.radio.chinese.data.entity.EpgFetchEntity
import com.radio.chinese.data.entity.EpgProgramEntity

/**
 * 节目单缓存独立成库，不复用 `radio_database`：那个库开着 fallbackToDestructiveMigration，
 * 每次加表都要升 version，一次写漏迁移就把用户的收藏清空。这里的数据全部可重建，
 * 用破坏性回退也不心疼。
 */
@Database(
    entities = [EpgProgramEntity::class, EpgFetchEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class EpgDatabase : RoomDatabase() {
    abstract fun epgProgramDao(): EpgProgramDao
}
