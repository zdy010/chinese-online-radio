package com.radio.chinese.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class RadioStation(
    val id: String,
    val name: String,
    val category: String,
    val streamUrl: String,
    val logoUrl: String = "",
    val frequency: String = "",
    val description: String = "",
    val website: String = ""
)

@Serializable
data class Category(
    val id: String,
    val name: String,
    val icon: String = ""
)

@Serializable
data class ProgramInfo(
    val stationId: String,
    val title: String,
    val host: String = "",
    val startTime: String = "",
    val endTime: String = ""
)

data class FavoriteStation(
    val stationId: String,
    val addedTime: Long
)
