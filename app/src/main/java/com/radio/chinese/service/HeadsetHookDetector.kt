package com.radio.chinese.service

enum class HeadsetHookResult { PENDING, NEXT, TOGGLE, IGNORED }

/**
 * 耳机线控 HEADSETHOOK 单击/双击判别器（纯逻辑，无计时器依赖——
 * 窗口到期由上层 Handler 调 expirePending() 补发）。
 *
 * 单击=暂停/播放切换；窗口(500ms)内第二击=双击=下一曲/下一段。
 * 必须自己判：Media3 默认处理把双击转成 seekToNextMediaItem，而本播放器恒为
 * 单 MediaItem（电台直播流/戏曲逐段起播），默认转换等于无事发生——
 * 这就是线控双击在 1.0.75→1.0.123 之间失效的根因。
 */
class HeadsetHookDetector(private val doubleWindowMs: Long = 500L) {
    private var pendingAt = -1L

    /** 一次物理按下（调用方须先过滤 UP 与 repeat）。 */
    fun onKeyDown(nowMs: Long): HeadsetHookResult {
        if (pendingAt >= 0 && nowMs - pendingAt <= doubleWindowMs) {
            pendingAt = -1L
            return HeadsetHookResult.NEXT
        }
        pendingAt = nowMs
        return HeadsetHookResult.PENDING
    }

    fun onEvent(nowMs: Long, actionUp: Boolean, repeatCount: Int): HeadsetHookResult {
        if (actionUp || repeatCount > 0) return HeadsetHookResult.IGNORED
        return onKeyDown(nowMs)
    }

    fun hasPendingToggle(nowMs: Long): Boolean =
        pendingAt >= 0 && nowMs - pendingAt > doubleWindowMs

    /** 窗口到期仍没有第二击 → 判定为单击。无挂起时返回 IGNORED（双击已判定过）。 */
    fun expirePending(nowMs: Long): HeadsetHookResult {
        if (!hasPendingToggle(nowMs)) return HeadsetHookResult.IGNORED
        pendingAt = -1L
        return HeadsetHookResult.TOGGLE
    }
}
