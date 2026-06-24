package com.radio.chinese.domain.browser

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.radio.chinese.domain.AudioBrowser
import com.radio.chinese.domain.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalBrowser(private val context: Context) : AudioBrowser {

    override suspend fun listChildren(path: String): Result<List<AudioTrack>> = withContext(Dispatchers.IO) {
        try {
            val tracks = mutableListOf<AudioTrack>()
            val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.RELATIVE_PATH
            )
            context.contentResolver.query(uri, projection, null, null, "LOWER(${MediaStore.Audio.Media.DISPLAY_NAME}) ASC")?.use { cursor ->
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)

                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol)
                    val data = cursor.getString(dataCol)
                    val size = cursor.getLong(sizeCol)
                    if (size > 0 && data != null) {
                        tracks.add(AudioTrack(
                            id = data.hashCode().toString(),
                            name = name,
                            path = data,
                            size = size,
                            sourceId = 0,
                            isFolder = false
                        ))
                    }
                }
            }
            Result.success(tracks)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun resolveUri(track: AudioTrack): String = Uri.fromFile(java.io.File(track.path)).toString()

    override suspend fun testConnection(): Result<Unit> = Result.success(Unit)

    override fun isStreamSource(): Boolean = false
}
