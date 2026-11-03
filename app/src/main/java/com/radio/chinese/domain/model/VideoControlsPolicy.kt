package com.radio.chinese.domain.model

/**
 * 视频控制层的决策，纯 Kotlin（不 import Android/media3），因此能在 JVM 测试集里跑。
 * 与 `VideoTrackGate`、`VideoMedia` 同一手法：判定集中在这里，落地是薄胶水。
 */
object VideoControlsPolicy {

    /**
     * 全屏按钮该不该出现。
     *
     * `isFullScreen` 优先：已在全屏就必须给退出入口。这条不是凑数分支 ——
     * 大屏手机点全屏后强制横屏，形态判定会跳成车机档，若按「车机档不给按钮」
     * 处理，按钮会自己消失，用户被锁在全屏里。
     * 所以「车机档不做全屏」只约束**进入**，不约束**退出**。
     */
    fun showsFullScreenButton(
        isCarSurface: Boolean,
        hasVideoTrack: Boolean,
        isFullScreen: Boolean,
    ): Boolean = isFullScreen || (!isCarSurface && hasVideoTrack)

    /** 控制层自动收起延时（毫秒）。 */
    const val CONTROLS_AUTO_HIDE_MS: Long = 3000L
}
