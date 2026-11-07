package com.radio.chinese

import com.radio.chinese.data.local.RadioPreferences
import com.radio.chinese.data.repository.PlayabilityStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 钉住 store 的读写接口，而不只是 [com.radio.chinese.domain.model.PlayabilityScore] 的纯函数。
 *
 * 上一轮 QA 的教训：纯函数测得再全，生产调用点被删掉测试照样绿。
 * 这里直接走 reportSuccess/reportFailure，删掉其中任何一次转移都会红。
 */
class PlayabilityStoreTest {

    private fun newStore(): PlayabilityStore {
        val prefs = mockk<RadioPreferences>(relaxed = true)
        every { prefs.playabilityJson } returns flowOf("")
        return PlayabilityStore(prefs)
    }

    @Test
    fun `成功与失败按约定转移`() {
        val store = newStore()
        store.reportSuccess("a")
        assertEquals(1, store.scoreOf("a"))
        store.reportSuccess("a")
        assertEquals(2, store.scoreOf("a"))
        // 成功中失败一次直接归 -1，不是 2 -> 1
        store.reportFailure("a")
        assertEquals(-1, store.scoreOf("a"))
        store.reportFailure("a")
        assertEquals(-2, store.scoreOf("a"))
        // 失败中成功一次回到 +1
        store.reportSuccess("a")
        assertEquals(1, store.scoreOf("a"))
    }

    @Test
    fun `没播过的台默认零`() {
        assertEquals(0, newStore().scoreOf("never-played"))
    }

    @Test
    fun `连续失败截断在负九`() {
        val store = newStore()
        repeat(20) { store.reportFailure("x") }
        assertEquals(-9, store.scoreOf("x"))
    }

    @Test
    fun `不同台的分数互不影响`() {
        val store = newStore()
        store.reportSuccess("a")
        store.reportFailure("b")
        assertEquals(1, store.scoreOf("a"))
        assertEquals(-1, store.scoreOf("b"))
    }
}
