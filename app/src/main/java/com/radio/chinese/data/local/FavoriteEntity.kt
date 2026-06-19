package com.radio.chinese.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey
    val stationId: String,
    val addedTime: Long = System.currentTimeMillis()
)
