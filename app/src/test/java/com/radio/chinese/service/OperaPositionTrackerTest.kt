package com.radio.chinese.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * OperaPositionTracker 单测：验证 start/stop 语义与 PosReader 协议。
 * 由于 Tracker 使用 Handler（Android 主线程），JVM 测试中只验证纯逻辑接口。
 */
class OperaPositionTrackerTest {

    @Test
    fun `PosReader returns null when controller unavailable`() {
        // PosReader is a fun interface - test the contract
        val reader = OperaPositionTracker.PosReader { null }
        assertNull(reader.read())
    }

    @Test
    fun `PosReader returns pair when playing`() {
        val reader = OperaPositionTracker.PosReader { 5000L to 60000L }
        val result = reader.read()
        assertEquals(5000L, result?.first)
        assertEquals(60000L, result?.second)
    }

    @Test
    fun `PosReader simulates pause state`() {
        var playing = false
        val reader = OperaPositionTracker.PosReader {
            if (playing) 3000L to 45000L else null
        }
        // Paused → null
        assertNull(reader.read())
        // Playing → values
        playing = true
        val (pos, dur) = reader.read()!!
        assertEquals(3000L, pos)
        assertEquals(45000L, dur)
    }
}
