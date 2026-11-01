package com.radio.chinese.domain.model

/**
 * 视频轨闸门：要不要让播放器解码视频。
 *
 * 全应用只认这一个判据，且刻意不 import media3 —— 保持纯 Kotlin 才能在本项目
 * 没有 Robolectric 的 JVM 测试集里跑起来（与 `OperaSpeed` 同一手法：判定集中在纯函数，
 * 落地是薄胶水）。
 *
 * 默认关。画面没挂上就绝不解码视频，以此保证「只听声」的现有行为、起播耗时与功耗
 * 和改造前完全一致（spec §5.2）。
 */
object VideoTrackGate {

    /** 出厂默认：没有画面挂载时视频轨必须处于禁用态。 */
    const val DEFAULT_VIDEO_TRACK_DISABLED: Boolean = true

    /** 唯一的闸门决策。`stageMounted = false` 时恒为 false —— 这条是回归守卫的对象。 */
    fun shouldDecodeVideo(stageMounted: Boolean): Boolean = stageMounted
}
