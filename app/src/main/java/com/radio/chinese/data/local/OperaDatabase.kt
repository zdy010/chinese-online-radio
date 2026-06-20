package com.radio.chinese.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.radio.chinese.domain.model.DownloadedOpera

@Database(entities = [DownloadedOpera::class], version = 1, exportSchema = false)
abstract class OperaDatabase : RoomDatabase() {
    abstract fun downloadedOperaDao(): DownloadedOperaDao
}
