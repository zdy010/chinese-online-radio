package com.radio.chinese.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "audio_sources")
data class AudioSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,                    // "LOCAL" | "WEBDAV" | "M3U" | "HTTP"
    val url: String,
    val username: String = "",
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val lastSyncAt: Long = 0,
    val cacheTtlMs: Long = 86_400_000   // 24h
)
