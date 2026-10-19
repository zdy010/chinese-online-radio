package com.radio.chinese

import com.radio.chinese.domain.model.OperaSpeed
import org.junit.Assert.assertEquals

import org.junit.Test

/**
 * 倍速档位判定。
 *
 * 作用范围是用户定过的硬约束：**只对音频库点播开放，电台直播流一律原速**
 * （时移直播流变速会变声、且会越落越远导致卡顿）。这条范围写在参数计算里，
 * 才能被单测钉住——PlayerManager 持有主线程 Handler，JVM 里造不出来。
 */
class OperaSpeedTest {
    @Test fun `档位覆盖慢放到快进且含原速`() {
        assertEquals(listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f), OperaSpeed.STEPS)
    }

    @Test fun `原速是默认档位`() {
        assertEquals(1.0f, OperaSpeed.DEFAULT, 0f)
    }

    @Test fun `未列出的速度吸附到最近档位`() {
        assertEquals(1.0f, OperaSpeed.normalize(1.02f), 0f)
        assertEquals(1.25f, OperaSpeed.normalize(1.30f), 0f)
        assertEquals(1.5f, OperaSpeed.normalize(1.40f), 0f)
    }

    @Test fun `越界速度钳在两端而不是照抄`() {
        // 直接透传会让 8x 这种值进播放器：变速算法在极端值下会静音/爆音
        assertEquals(0.75f, OperaSpeed.normalize(0.1f), 0f)
        assertEquals(2.0f, OperaSpeed.normalize(8.0f), 0f)
    }

    @Test fun `档位循环推进后回到最慢一档`() {
        assertEquals(1.25f, OperaSpeed.next(1.0f), 0f)
        assertEquals(1.0f, OperaSpeed.next(0.75f), 0f)
        assertEquals(0.75f, OperaSpeed.next(2.0f), 0f)
    }

    @Test fun `未知当前值从原速开始循环`() {
        assertEquals(1.25f, OperaSpeed.next(1.11f), 0f)
    }

    @Test fun `电台直播流一律原速`() {
        assertEquals(1.0f, OperaSpeed.parametersFor(isOperaMode = false, stored = 1.5f), 0f)
    }

    @Test fun `点播沿用已选倍速`() {
        assertEquals(1.5f, OperaSpeed.parametersFor(isOperaMode = true, stored = 1.5f), 0f)
    }

    @Test fun `标签把原速说人话其余去掉多余的零`() {
        assertEquals("原速", OperaSpeed.label(1.0f))
        assertEquals("0.75x", OperaSpeed.label(0.75f))
        assertEquals("1.25x", OperaSpeed.label(1.25f))
        assertEquals("1.5x", OperaSpeed.label(1.5f))
        assertEquals("2x", OperaSpeed.label(2.0f))
    }
}
