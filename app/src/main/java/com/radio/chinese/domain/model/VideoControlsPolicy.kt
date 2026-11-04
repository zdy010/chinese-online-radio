package com.radio.chinese.domain.model

/** 旋转按钮要切到的目标方向。枚举而非 ActivityInfo 常量：domain 层不得 import Android。 */
enum class VideoOrientation { Landscape, Portrait }

/**
 * 视频控制层的决策，纯 Kotlin（不 import Android/media3），因此能在 JVM 测试集里跑。
 * 与 `VideoTrackGate`、`VideoMedia` 同一手法：判定集中在这里，落地是薄胶水。
 */
object VideoControlsPolicy {

    /** 旋转按钮按下后切到哪个方向：横竖互切。 */
    fun nextOrientation(currentLandscape: Boolean): VideoOrientation =
        if (currentLandscape) VideoOrientation.Portrait else VideoOrientation.Landscape

    /**
     * 旋转按钮该不该出现：只在「手机形态 + 已在全屏」时给。
     *
     * 车机恒横屏，按了不会有任何变化 —— 那是“给了控件却不响应”，本项目的反模式。
     * 手机非全屏时方向归系统与用户自己，应用不插队。
     */
    fun showsRotateButton(isCarSurface: Boolean, isFullScreen: Boolean): Boolean =
        !isCarSurface && isFullScreen

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

    /**
     * 页面细节区块（台名/频率/简介/标签/EPG/节目源/播控）要不要渲染。
     *
     * 全屏时必须不渲染，不是因为它们不好看：车机档的非全屏布局走的是 Row 左右分栏，
     * 留着台名块就吃掉一半屏宽，画面只占左半边 —— 用户点了「全屏」结果看到
     * 半屏画面 + 半屏台名，像应用坏了。
     */
    fun showsStationDetails(isFullScreen: Boolean): Boolean = !isFullScreen

    /**
     * 画面层要不要渲染。
     *
     * `isFullScreen` 优先：全屏时即使视频轨消失（直播断流、或方控切到了纯音频台），
     * 画面层也必须还在 —— 退出入口就长在这一层上。层掉了，人就锁在横屏沉浸态里，
     * 只剩知道按返回键的人才出得来。
     */
    fun showsPictureSurface(hasVideoTrack: Boolean, isFullScreen: Boolean): Boolean =
        isFullScreen || hasVideoTrack
}
