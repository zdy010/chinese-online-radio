package com.radio.chinese.data.repository

import com.radio.chinese.data.CredentialManager
import com.radio.chinese.data.dao.*
import com.radio.chinese.data.entity.*
import com.radio.chinese.domain.*
import com.radio.chinese.domain.model.IndexQuery
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

    /**
     * 只重取一个目录：先删该目录旧行再落新行。
     *
     * 这里继不沿用旧 refreshSource 的 `deleteBySource`：那会抹掉后台索引
     * 整整建立的几千行，用户点一下“刷新”就要重跑一轮全量遍历。
     */
    suspend fun refreshDir(source: AudioSource, path: String, browser: AudioBrowser): Result<List<AudioTrack>> {
        cacheDao.deleteByParentPath(source.id, path)
        val result = browser.listChildren(path)
        result.onSuccess { items ->
            cacheDao.insertAll(items.map { it.toEntity(source.id) })
        }
        return result
    }

    // ========== 搜索 ==========

    /**
     * 全源曲目搜索。空/空白查询直接返回空列表——不把它送进 LIKE，
     * 否则模式退化成 `%+%`，等于“匹配全部”。
     *
     * 拼音只对纯字母数字的查询生效，与电台侧的`StationFilter`同一套判据：
     * 用户在电台页与音频库不需要记两套规则。
     */
    suspend fun searchTracks(sourceId: Long?, query: String, limit: Int = 200): List<TrackHit> {
        val pattern = IndexQuery.likePattern(query)
        if (pattern.isEmpty()) return emptyList()
        return cacheDao.searchTracks(sourceId, pattern, limit, PinyinIndex.isAsciiQuery(query)).map {
            TrackHit(
                sourceId = it.sourceId, name = it.name, path = it.path,
                parentPath = it.parentPath, size = it.size
            )
        }
    }

    // ========== 索引状态 ==========

    /** 跨源已收录曲目数；0 表示本机从未成功建立过索引。 */
    suspend fun indexedTrackCount(): Int = cacheDao.countAllTracks()

    /** 可建索引的来源：只有 WebDAV 有目录树可递归。 */
    suspend fun webdavSources(): List<AudioSource> =
        getSources().filter { it.type == SourceType.WEBDAV }

    /**
     * 索引是否可能过期。
     *
     * 两种情况都算过期：lastSyncAt<=0（从未整树跑完，含中途有目录失败），
     * 以及超过源级 TTL。索引残缺时提示「点这里更新」比假装完整更诚实。
     */
    suspend fun isIndexStale(now: Long = System.currentTimeMillis()): Boolean {
        for (s in getSources()) {
            if (s.type != SourceType.WEBDAV) continue
            val e = sourceDao.getById(s.id) ?: return true
            if (e.lastSyncAt <= 0L) return true
            if (now - e.lastSyncAt > e.cacheTtlMs) return true
        }
        return false      // 没有任何 WebDAV 源，或所有源都在 TTL 内
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
        name = name, isFolder = isFolder, size = size,
        // 浏览路径与后台索引写的是同一张表，两边的拼音列必须都填，
        // 否则用户刚点过的目录反而搜不到
        pyFull = if (isFolder) "" else PinyinIndex.column(name),
        pyInitials = if (isFolder) "" else PinyinIndex.initialsColumn(name),
    )
}
