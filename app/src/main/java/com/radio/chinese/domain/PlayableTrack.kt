package com.radio.chinese.domain

/**
 * 判断一个文件名是不是可播放的曲目。
 *
 * 起因：云盘目录里除了音频还散着歌词与封面，而列目录与索引的判断是
 * 「不是文件夹就算曲目」，于是 `花木兰.lrc` 也进了播放列表，点一下必然放不出声。
 *
 * 用黑名单而不是「只放行已知音频扩展名」的白名单：白名单会误杀 wav/flac/m3u8
 * 以及用户自有的非常规扩展名，等于悄悄删掉能用的功能。副产物清单反而稳定，
 * 且与 sidecar 读取（`BrowserSidecarSource`）找歌词封面用的那批同源——
 * 把它们从列表里剔掉不会弄丢歌词功能，因为歌词是按曲目名派生路径去取的，
 * 本来就不需要它们出现在列表中。
 */
object PlayableTrack {

    /** 歌词、封面、字幕、说明与元数据这几类附属文件的扩展名（不含点、已小写）。 */
    private val SIDECAR_EXTENSIONS = setOf(
        "lrc", "jpg", "jpeg", "png", "gif", "webp", "bmp",
        "txt", "md", "srt", "ass", "ssa", "vtt", "nfo", "json", "xml",
    )

    /**
     * [fileNameOrHref] 可以是显示名，也可以是带路径的 PROPFIND href；
     * 只按最后一段的扩展名判断，所以两种都能喂。
     *
     * 无扩展名一律保留：判成非曲目等于让用户少一批能听的，而误放的代价只是一行点不响的条目。
     */
    fun isPlayable(fileNameOrHref: String): Boolean {
        val trimmed = fileNameOrHref.trim()
        if (trimmed.isEmpty()) return false
        val base = trimmed.substringAfterLast('/')
        val ext = base.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return true
        return ext !in SIDECAR_EXTENSIONS
    }
}
