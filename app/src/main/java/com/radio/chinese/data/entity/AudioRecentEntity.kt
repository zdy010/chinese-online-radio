package com.radio.chinese.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "audio_recent", indices = [Index("trackPath", unique = true)])
data class AudioRecentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val trackPath: String,
    val trackName: String,
    val sourceName: String,
    val playedAt: Long = System.currentTimeMillis(),
    val positionMs: Long = 0
)
