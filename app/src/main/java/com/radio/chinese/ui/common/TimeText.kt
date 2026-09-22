package com.radio.chinese.ui.common

/**
 * 播放时间文案。直播/无时长时只显示已播时长，有时长时显示 已播 / 总时长。
 *
 * 抽出来的原因：播放条不再区分「全屏页」与「迷你条」两套私有格式化函数，
 * 避免出现 "3:20" 与 "03:20" 两种写法。
 */
fun mediaTimeText(positionMs: Long, durationMs: Long): String {
    fun clock(ms: Long): String {
        val totalSec = ms / 1000
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }
    return if (durationMs > 0) "${clock(positionMs)} / ${clock(durationMs)}" else clock(positionMs)
}

/** 码率文案，未知时返回 null 由调用方省略。 */
fun bitrateText(bitrateBps: Int): String? =
    if (bitrateBps > 0) "${bitrateBps / 1000}kbps" else null
