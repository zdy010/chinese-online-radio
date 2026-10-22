package com.radio.chinese.data.entity

import androidx.room.ColumnInfo
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
    val cachedAt: Long = System.currentTimeMillis(),
    /**
     * 曲名全拼变体，`|a|b|` 形态（见 PinyinIndex）。目录行与无可索引内容时为空串。
     *
     * 存多值而不是单个读音：pinyin4j 的多音字候选顺序不是常用音在前，
     * 取第一个会把「重庆」算成 zq。
     */
    @ColumnInfo(defaultValue = "''")
    val pyFull: String = "",
    /** 曲名首字母变体，`|a|b|` 形态。 */
    @ColumnInfo(defaultValue = "''")
    val pyInitials: String = "",
)
