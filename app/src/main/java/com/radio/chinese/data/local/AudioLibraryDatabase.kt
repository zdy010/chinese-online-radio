package com.radio.chinese.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.radio.chinese.data.dao.*
import com.radio.chinese.data.entity.*

@Database(
    entities = [
        AudioSourceEntity::class,
        AudioCacheEntity::class,
        AudioFavoriteEntity::class,
        AudioRecentEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AudioLibraryDatabase : RoomDatabase() {
    abstract fun audioSourceDao(): AudioSourceDao
    abstract fun audioCacheDao(): AudioCacheDao
    abstract fun audioFavoriteDao(): AudioFavoriteDao
    abstract fun audioRecentDao(): AudioRecentDao
}
