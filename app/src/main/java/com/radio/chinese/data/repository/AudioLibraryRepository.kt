package com.radio.chinese.data.repository

import com.radio.chinese.data.CredentialManager
import com.radio.chinese.data.dao.*
import com.radio.chinese.data.entity.*
import com.radio.chinese.domain.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioLibraryRepository @Inject constructor(
    private val sourceDao: AudioSourceDao,
    private val cacheDao: AudioCacheDao,
    private val favoriteDao: AudioFavoriteDao,
    private val recentDao: AudioRecentDao,
    private val credentialManager: CredentialManager
) {
    // ========== 音频源 ==========

    val allSources: Flow<List<AudioSource>> = sourceDao.getAllFlow().map { entities ->
        entities.map { it.toDomain() }
    }

    suspend fun getSources(): List<AudioSource> = sourceDao.getAll().map { it.toDomain() }

    suspend fun getSource(id: Long): AudioSource? = sourceDao.getById(id)?.toDomain()

    suspend fun saveSource(name: String, type: SourceType, url: String, username: String, password: String): Long {
        val id = sourceDao.insert(
            AudioSourceEntity(name = name, type = type.name, url = url, username = username)
        )
        if (password.isNotBlank()) {
            credentialManager.savePassword(id, password)
        }
        return id
    }

    suspend fun deleteSource(id: Long) {
        credentialManager.deletePassword(id)
        cacheDao.deleteBySource(id)
        sourceDao.deleteById(id)
    }

    // ========== 浏览 ==========

    suspend fun listChildren(source: AudioSource, path: String, browser: AudioBrowser): Result<List<AudioTrack>> {
        val entity = sourceDao.getById(source.id)
        val now = System.currentTimeMillis()
        val isCacheValid = entity != null && (now - entity.lastSyncAt) < entity.cacheTtlMs

        if (isCacheValid) {
            val cached = cacheDao.getByParentPath(source.id, path)
            if (cached.isNotEmpty()) {
                return Result.success(cached.map { it.toDomain(source.id) })
            }
        }

        val result = browser.listChildren(path)
        result.onSuccess { items ->
            cacheDao.deleteByParentPath(source.id, path)
            cacheDao.insertAll(items.map { it.toEntity(source.id) })
            sourceDao.updateSyncTime(source.id, now)
        }
        return result
    }

    suspend fun refreshSource(source: AudioSource, path: String, browser: AudioBrowser): Result<List<AudioTrack>> {
        cacheDao.deleteBySource(source.id)
        sourceDao.updateSyncTime(source.id, 0)
        return listChildren(source, path, browser)
    }

    // ========== 收藏 ==========

    val favorites: Flow<List<AudioFavoriteEntity>> = favoriteDao.getAllFlow()

    suspend fun toggleFavorite(sourceId: Long, trackPath: String, trackName: String, sourceName: String): Boolean {
        val existing = favoriteDao.getByPath(trackPath)
        return if (existing != null) {
            favoriteDao.delete(existing)
            false
        } else {
            favoriteDao.insert(
                AudioFavoriteEntity(sourceId = sourceId, trackPath = trackPath, trackName = trackName, sourceName = sourceName)
            )
            true
        }
    }

    suspend fun isFavorited(trackPath: String): Boolean = favoriteDao.isFavorited(trackPath)

    // ========== 最近播放 ==========

    val recentPlays: Flow<List<AudioRecentEntity>> = recentDao.getAllFlow()

    suspend fun recordPlay(sourceId: Long, trackPath: String, trackName: String, sourceName: String, positionMs: Long = 0) {
        recentDao.insert(
            AudioRecentEntity(
                sourceId = sourceId, trackPath = trackPath, trackName = trackName,
                sourceName = sourceName, positionMs = positionMs
            )
        )
        recentDao.trimTo100()
    }

    suspend fun getPlayPosition(trackPath: String): Long = recentDao.getByPath(trackPath)?.positionMs ?: 0

    // ========== 映射 ==========

    private fun AudioSourceEntity.toDomain(): AudioSource = AudioSource(
        id = id, name = name, type = SourceType.valueOf(type),
        url = url, username = username,
        password = credentialManager.getPassword(id) ?: ""
    )

    private fun AudioCacheEntity.toDomain(sourceId: Long) = AudioTrack(
        id = path.hashCode().toString(), name = name, path = path,
        size = size, sourceId = sourceId, isFolder = isFolder, parentPath = parentPath
    )

    private fun AudioTrack.toEntity(sourceId: Long) = AudioCacheEntity(
        sourceId = sourceId, path = path, parentPath = parentPath,
        name = name, isFolder = isFolder, size = size
    )
}
