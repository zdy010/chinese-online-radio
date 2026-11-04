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
     * 全屏入口该不该出现。
     *
     * 只看“有没有画面”与“是不是已在全屏”，**不再看形态**：上一版加了 `!isCarSurface`，
     * 而「界面模式=车机」「sw≥600 且横屏」「车机恒横屏」都会判成 Car，
     * 于是入口在手机上也会凭空消失。车机上「全屏」的含义是画面独占整块面板。
     *
     * `isFullScreen` 优先：已在全屏就必须给退出入口 —— 大屏手机点全屏后强制横屏，
     * 形态判定会当场翻转，若条件里有任何形态判断，按钮会在用户眼前消失。
     */
    fun showsFullScreenButton(hasVideoTrack: Boolean, isFullScreen: Boolean): Boolean =
        isFullScreen || hasVideoTrack

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
