package com.radio.chinese

import com.radio.chinese.domain.model.PlayCommitGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 起播提交的世代号规则。
 *
 * 对应 Task 6 审查 C-1：iptv 起播等 header 索引期间，旧协程恢复后不得再提交，
 * 也不得把旧台的 UA/Referer 写回全局 store。
 */
class PlayCommitGateTest {

    @Test
    fun `只有最新一次起播允许提交`() {
        val gate = PlayCommitGate()
        val first = gate.next()
        val latest = gate.next()
        assertFalse("先发起的起播已被取代，却仍允许提交", gate.isCurrent(first))
        assertTrue(gate.isCurrent(latest))
    }

    @Test
    fun `多源回退连推两次只认最后一次`() {
        val gate = PlayCommitGate()
        val tokens = (1..3).map { gate.next() }
        assertFalse(gate.isCurrent(tokens[0]))
        assertFalse(gate.isCurrent(tokens[1]))
        assertTrue(gate.isCurrent(tokens[2]))
    }

    @Test
    fun `停止与切戏曲让挂起的提交失效`() {
        val gate = PlayCommitGate()
        val pending = gate.next()
        gate.invalidate()
        assertFalse("stop 后旧协程仍会提交，等于停止后自动开播", gate.isCurrent(pending))
    }

    @Test
    fun `没有新意图时挂起的提交仍然有效`() {
        val gate = PlayCommitGate()
        val token = gate.next()
        assertTrue(gate.isCurrent(token))
    }
}
