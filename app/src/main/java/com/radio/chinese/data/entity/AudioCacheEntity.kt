package com.radio.chinese.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "audio_cache",
    indices = [Index("sourceId"), Index("parentPath")],
    foreignKeys = [ForeignKey(
        entity = AudioSourceEntity::class,
        parentColumns = ["id"],
        childColumns = ["sourceId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class AudioCacheEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val path: String,
    val parentPath: String,
    val name: String,
    val isFolder: Boolean = false,
    val size: Long = 0,
    val mimeType: String = "",
    val cachedAt: Long = System.currentTimeMillis()
)
