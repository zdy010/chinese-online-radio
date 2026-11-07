package com.radio.chinese

import com.radio.chinese.domain.model.PlayabilityLevel
import com.radio.chinese.domain.model.PlayabilityScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 可用性评分的三个关键语义，任何一条改坏都会让"告警"骗人：
 * 1) 成功中失败一次必须直接归 -1；
 * 2) 失败中成功一次必须回到 +1；
 * 3) 上下界截断，不能无限长。
 */
class PlayabilityScoreTest {

    @Test
    fun `成功中失败一次直接归负一，不是从高分递减`() {
        assertEquals(-1, PlayabilityScore.onFailure(50))
        assertEquals(-1, PlayabilityScore.onFailure(1))
        assertEquals(-1, PlayabilityScore.onFailure(0))
        // 已经是连续失败才继续往负走
        assertEquals(-2, PlayabilityScore.onFailure(-1))
    }

    @Test
    fun `失败中成功一次回到正一`() {
        assertEquals(1, PlayabilityScore.onSuccess(-9))
        assertEquals(1, PlayabilityScore.onSuccess(-1))
        assertEquals(1, PlayabilityScore.onSuccess(0))
        // 连续成功才累加
        assertEquals(2, PlayabilityScore.onSuccess(1))
    }

    @Test
    fun `上下界截断在正负九`() {
        assertEquals(9, PlayabilityScore.onSuccess(9))
        assertEquals(9, PlayabilityScore.onSuccess(99))
        assertEquals(-9, PlayabilityScore.onFailure(-9))
        assertEquals(-9, PlayabilityScore.onFailure(-99))
    }

    @Test
    fun `连续成功与连续失败都可以累加`() {
        var score = 0
        repeat(3) { score = PlayabilityScore.onSuccess(score) }
        assertEquals(3, score)
        repeat(20) { score = PlayabilityScore.onFailure(score) }
        assertEquals(-9, score)
    }

    @Test
    fun `隐藏阈值按连续失败次数判定，默认不隐藏`() {
        assertFalse(PlayabilityScore.isHidden(-9, 0))
        assertFalse(PlayabilityScore.isHidden(-2, 3))
        assertTrue(PlayabilityScore.isHidden(-3, 3))
        assertTrue(PlayabilityScore.isHidden(-9, 3))
        assertFalse(PlayabilityScore.isHidden(5, 3))
    }

    @Test
    fun `档位映射覆盖告警与良好两端`() {
        assertEquals(PlayabilityLevel.BAD_SEVERE, PlayabilityScore.levelOf(-9))
        assertEquals(PlayabilityLevel.BAD, PlayabilityScore.levelOf(-4))
        assertEquals(PlayabilityLevel.WARN, PlayabilityScore.levelOf(-1))
        assertEquals(PlayabilityLevel.UNKNOWN, PlayabilityScore.levelOf(0))
        assertEquals(PlayabilityLevel.GOOD, PlayabilityScore.levelOf(2))
        assertEquals(PlayabilityLevel.GOOD_STRONG, PlayabilityScore.levelOf(4))
        assertEquals(PlayabilityLevel.GOOD_BEST, PlayabilityScore.levelOf(9))
    }
}
