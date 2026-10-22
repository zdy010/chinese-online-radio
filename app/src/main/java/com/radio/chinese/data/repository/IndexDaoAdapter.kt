package com.radio.chinese.data.repository

import com.radio.chinese.data.dao.AudioCacheDao
import com.radio.chinese.data.dao.AudioSourceDao
import com.radio.chinese.data.entity.AudioCacheEntity
import com.radio.chinese.domain.IndexCacheWriter
import com.radio.chinese.domain.IndexRow
import com.radio.chinese.domain.IndexSourceSync
import com.radio.chinese.domain.PinyinIndex
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 索引器的 Room 落地块。
 *
 * 写行语义与 AudioLibraryRepository.listChildren 保持一致（先删该目录旧行再落新行），
 * 这样浏览路径能直接命中索引写入的行，不需要索引器与浏览各维护一套缓存规则。
 */
@Singleton
class IndexDaoAdapter @Inject constructor(
    private val cacheDao: AudioCacheDao,
    private val sourceDao: AudioSourceDao,
) : IndexCacheWriter, IndexSourceSync {

    override suspend fun oldestByDir(sourceId: Long): Map<String, Long> =
        cacheDao.dirOldestCachedAt(sourceId).associate { it.parentPath to it.oldest }

    override suspend fun replaceDir(sourceId: Long, path: String, rows: List<IndexRow>) {
        cacheDao.deleteByParentPath(sourceId, path)
        if (rows.isEmpty()) return   // 空目录无行可落：下轮会重爬它一次，接受
        cacheDao.insertAll(rows.map {
            AudioCacheEntity(
                sourceId = sourceId, path = it.path, parentPath = it.parentPath,
                name = it.name, isFolder = it.isFolder, size = it.size,
                // 目录行不参与曲目搜索，不给它们算变体（一首歌名都长于目录名，白算）
                pyFull = if (it.isFolder) "" else PinyinIndex.column(it.name),
                pyInitials = if (it.isFolder) "" else PinyinIndex.initialsColumn(it.name),
            )
        })
    }

    override suspend fun countTracks(sourceId: Long): Int = cacheDao.countTracks(sourceId)

    override suspend fun markSynced(sourceId: Long, at: Long) = sourceDao.updateSyncTime(sourceId, at)
}
