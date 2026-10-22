package com.radio.chinese.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.radio.chinese.data.dao.*
import com.radio.chinese.data.entity.*

@Database(
    entities = [
        AudioSourceEntity::class,
        AudioCacheEntity::class,
        AudioFavoriteEntity::class,
        AudioRecentEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AudioLibraryDatabase : RoomDatabase() {
    abstract fun audioSourceDao(): AudioSourceDao
    abstract fun audioCacheDao(): AudioCacheDao
    abstract fun audioFavoriteDao(): AudioFavoriteDao
    abstract fun audioRecentDao(): AudioRecentDao

    companion object {
        /**
         * v1 → v2：audio_cache 加两列拼音。
         *
         * 这个库开着 fallbackToDestructiveMigration，而它同时装着用户的音频库来源
         * （含凭证）、收藏、最近播放。不写显式迁移的话，Room 会静默走破坏性回退，
         * 把这三样一起清掉——这比搜不到曲目严重得多。
         *
         * 迁移里只清空 audio_cache：它是不含用户数据的可重建缓存。留着没有
         * 拼音值的老行，用户会在不知情的情况下搜不到东西，不如让他明确重建一次。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE audio_cache ADD COLUMN pyFull TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE audio_cache ADD COLUMN pyInitials TEXT NOT NULL DEFAULT ''")
                db.execSQL("DELETE FROM audio_cache")
            }
        }
    }
}
