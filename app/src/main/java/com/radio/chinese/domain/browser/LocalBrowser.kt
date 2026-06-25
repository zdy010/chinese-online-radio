package com.radio.chinese.domain.browser

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.radio.chinese.domain.AudioBrowser
import com.radio.chinese.domain.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalBrowser(private val context: Context) : AudioBrowser {

    override suspend fun listChildren(path: String): Result<List<AudioTrack>> = withContext(Dispatchers.IO) {
        try {
            val tracks = if (path.isEmpty()) {
                scanFolderStructure()
            } else {
                scanFilesInFolder(path)
            }
            Result.success(tracks)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun resolveUri(track: AudioTrack): String = track.path

    override suspend fun testConnection(): Result<Unit> = Result.success(Unit)

    override fun isStreamSource(): Boolean = false

    /**
     * 扫描所有音频文件，按文件夹分组，返回文件夹列表（根目录）
     */
    private fun scanFolderStructure(): List<AudioTrack> {
        val folderMap = linkedMapOf<String, MutableList<AudioTrack>>()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.RELATIVE_PATH
        )

        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val relPathCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)

            while (cursor.moveToNext()) {
                val fileId = cursor.getLong(idCol)
                val name = cursor.getString(nameCol)
                val data = cursor.getString(dataCol)
                val size = cursor.getLong(sizeCol)
                if (size <= 0 || data == null) continue

                val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, fileId)
                val relPath = cursor.getString(relPathCol) ?: ""
                // 规范化文件夹名：去掉开头和结尾的 /
                val folder = relPath.trim('/').ifEmpty { "未分类" }

                val track = AudioTrack(
                    id = data.hashCode().toString(),
                    name = name,
                    path = contentUri.toString(),
                    size = size,
                    sourceId = 0,
                    isFolder = false,
                    parentPath = folder
                )
                folderMap.getOrPut(folder) { mutableListOf() }.add(track)
            }
        }

        // 返回文件夹列表 + 根目录直接文件
        return folderMap.map { (folderName, files) ->
            AudioTrack(
                id = folderName.hashCode().toString(),
                name = "$folderName (${files.size})",
                path = folderName,
                isFolder = true
            )
        }.sortedBy { it.name }
    }

    /**
     * 列出某个文件夹内的所有文件
     */
    private fun scanFilesInFolder(folderPath: String): List<AudioTrack> {
        val tracks = mutableListOf<AudioTrack>()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.RELATIVE_PATH
        )
        val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("$folderPath/%", "$folderPath/")

        context.contentResolver.query(uri, projection, selection, args, "LOWER(${MediaStore.Audio.Media.DISPLAY_NAME}) ASC")?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)

            while (cursor.moveToNext()) {
                val fileId = cursor.getLong(idCol)
                val name = cursor.getString(nameCol)
                val size = cursor.getLong(sizeCol)
                if (size <= 0) continue

                val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, fileId)
                tracks.add(AudioTrack(
                    id = fileId.toString(),
                    name = name,
                    path = contentUri.toString(),
                    size = size,
                    sourceId = 0,
                    isFolder = false,
                    parentPath = folderPath
                ))
            }
        }
        return tracks
    }
}
