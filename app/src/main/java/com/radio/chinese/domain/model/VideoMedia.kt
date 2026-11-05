package com.radio.chinese.domain.model

/**
 * 「这个文件名像不像视频」的正向判定。
 *
 * 只用于列表徽标，不参与能不能播的判断：能不能播仍由 `PlayableTrack` 的黑名单决定，
 * 有没有真画面由播放器运行时 `currentTracks` 决定（见 spec §2.3、§5）。
 *
 * 刻意不含 `m3u8`：云盘里的 m3u8 可能是歌单或电台清单，标成视频会误导用户。
 * 用白名单而不是黑名单（与 `PlayableTrack` 相反）：这里是「多标一个」的代价，
 * 判错只是徽标不准，不会像 `PlayableTrack` 那样悄悄删掉能播的条目。
 */
object VideoMedia {

    /**
     * 明确按视频对待的扩展名（不含点、已小写）。
     *
     * 分两段记清来历：上一行是初版就有的常见容器；下面几个是核对 media3-extractor 清单时
     * 补的录像机/老 rip 常见容器（asf、ogv、3g2、f4v、m2v、mts、divx）。
     * 刻意不含 `mka`（Matroska 音频）与 `mks`（Matroska 字幕），也不含 `m3u8`：
     * 云盘里的 m3u8 可能是歌单或电台清单，标成视频会误导用户。
     */
    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "mkv", "mov", "webm", "flv", "avi", "mpg", "mpeg", "wmv",
        "m4v", "rm", "rmvb", "3gp", "vob", "ts", "m2ts",
        "asf", "ogv", "ogm", "3g2", "f4v", "m2v", "mts", "divx",
    )

    /**
     * [fileNameOrHref] 可以是显示名，也可以是带路径的 PROPFIND href；
     * 只按最后一段的扩展名判断，与 `PlayableTrack.isPlayable` 同一派生法。
     */
    fun looksLikeVideo(fileNameOrHref: String): Boolean {
        val trimmed = fileNameOrHref.trim()
        if (trimmed.isEmpty()) return false
        val base = trimmed.substringAfterLast('/')
        val ext = base.substringAfterLast('.', "").lowercase()
        return ext in VIDEO_EXTENSIONS
    }
}
