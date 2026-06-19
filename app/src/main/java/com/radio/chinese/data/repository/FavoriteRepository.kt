package com.radio.chinese.data.repository

import com.radio.chinese.data.local.FavoriteDao
import com.radio.chinese.data.local.FavoriteEntity
import com.radio.chinese.domain.model.FavoriteStation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FavoriteRepository @Inject constructor(
    private val favoriteDao: FavoriteDao
) {
    fun getAllFavorites(): Flow<List<FavoriteStation>> =
        favoriteDao.getAllFavorites().map { entities ->
            entities.map { it.toDomain() }
        }

    fun getFavoriteIds(): Flow<List<String>> = favoriteDao.getFavoriteIds()

    fun isFavorite(stationId: String): Flow<Boolean> = favoriteDao.isFavorite(stationId)

    suspend fun addFavorite(stationId: String) {
        favoriteDao.addFavorite(FavoriteEntity(stationId = stationId))
    }

    suspend fun removeFavorite(stationId: String) {
        favoriteDao.removeFavorite(stationId)
    }

    suspend fun toggleFavorite(stationId: String, isCurrentlyFavorite: Boolean) {
        if (isCurrentlyFavorite) {
            removeFavorite(stationId)
        } else {
            addFavorite(stationId)
        }
    }
}

private fun FavoriteEntity.toDomain() = FavoriteStation(
    stationId = stationId,
    addedTime = addedTime
)
