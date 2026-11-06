package com.radio.chinese.service

import com.radio.chinese.domain.model.StreamHeaders
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 当前这一路播放需要的 HTTP 请求头。
 *
 * 为什么用单例而不是随 MediaItem 传：media3 的 MediaItem 不支持携带 per-item header，
 * 而 `DataSource.Factory.createDataSource()` 拿不到 URI（URI 要到 `open(DataSpec)` 才知道）。
 * 项目里 WebDAV 的动态 Auth 用的就是同一个手法 —— 工厂 lambda 里读单例当前值，
 * 所以这不是新发明，是沿用既有形状（见 RadioService.initializePlayer 的 dataSourceFactory）。
 *
 * **已知代价**：这是全局可变状态，与项目踩过的"WebDAV 单例凭证跨源串写"同一类。
 * 边界是本应用单播放器、起播串行，写入紧贴 setMediaItem 之前、stop 时清空，因此不并发。
 * **将来引入画中画或第二路播放时，这里必须改成随 MediaItem 传递（用 MetadataEntry 携带），
 * 不能继续加 volatile 补丁。**
 */
@Singleton
class StreamHeaderStore @Inject constructor() {
    @Volatile var current: StreamHeaders? = null
}
