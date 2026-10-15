package com.radio.chinese

import com.radio.chinese.service.HeadsetHookDetector
import com.radio.chinese.service.HeadsetHookResult
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 耳机线控 HEADSETHOOK 事件判别器测试。
 * 系统只把按键原始事件送到会话：单击=暂停/播放切换，窗口内第二次=双击=下一曲。
 * Media3 默认处理把双击转成 seekToNextMediaItem，而本播放器恒为单 MediaItem，等于无事发生，
 * 所以双击必须自己判。
 */
class HeadsetHookDetectorTest {
    private val t0 = 1_000_000L

    @Test fun firstDownStartsPendingToggle() {
        val d = HeadsetHookDetector()
        assertEquals(HeadsetHookResult.PENDING, d.onKeyDown(t0))
    }

    @Test fun upAndRepeatEventsIgnored() {
        val d = HeadsetHookDetector()
        assertEquals(HeadsetHookResult.IGNORED, d.onEvent(t0, actionUp = true, repeatCount = 0))
        assertEquals(HeadsetHookResult.IGNORED, d.onEvent(t0, actionUp = false, repeatCount = 1))
    }

    @Test fun secondPressWithinWindowIsDoubleNext() {
        val d = HeadsetHookDetector()
        d.onKeyDown(t0)
        assertEquals(HeadsetHookResult.NEXT, d.onKeyDown(t0 + 400))
    }

    @Test fun pressAfterWindowIsANewSingleClick() {
        // 定时器模型：窗口到期由上层 expirePending() 补发 TOGGLE 后，
        // 下一次按下是全新单击序列（开新窗口），而不是直接 TOGGLE
        val d = HeadsetHookDetector()
        d.onKeyDown(t0)
        assertEquals(HeadsetHookResult.TOGGLE, d.expirePending(t0 + 501))
        assertEquals(HeadsetHookResult.PENDING, d.onKeyDown(t0 + 600))
    }

    @Test fun tripleClickIsToggleThenNextThenToggle() {
        val d = HeadsetHookDetector()
        assertEquals(HeadsetHookResult.PENDING, d.onKeyDown(t0))
        assertEquals(HeadsetHookResult.NEXT, d.onKeyDown(t0 + 200))
        // 双击判定后立刻再按（第三次），与第二次间隔仍在窗口内：视为新序列的单击，
        // 但为避免"双击+马上补一按"被误读成又一次双击，第三次只开新窗口
        assertEquals(HeadsetHookResult.PENDING, d.onKeyDown(t0 + 300))
    }

    @Test fun windowExpiresThenNextPressTogglesImmediately() {
        // 单击的 TOGGLE 判定发生在"窗口内没等到第二击"时：上层用超时补发。
        // 判别器职责：给出事件时刻的即时结论 + 是否需要在 500ms 后补发 TOGGLE
        val d = HeadsetHookDetector()
        d.onKeyDown(t0)
        assertEquals(true, d.hasPendingToggle(t0 + 501))
        assertEquals(HeadsetHookResult.TOGGLE, d.expirePending(t0 + 501))
        assertEquals(false, d.hasPendingToggle(t0 + 502))
    }

    @Test fun expireWithoutPendingIsNoop() {
        val d = HeadsetHookDetector()
        assertEquals(HeadsetHookResult.IGNORED, d.expirePending(t0 + 501))
    }
}
