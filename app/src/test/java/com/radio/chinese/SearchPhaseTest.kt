package com.radio.chinese

import com.radio.chinese.domain.IndexState
import com.radio.chinese.domain.SearchPhase
import com.radio.chinese.domain.model.IndexQuery
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 搜索阶段判定：用户已改为**绝不主动建索引**，所以"要不要弹确认、要不要挡结果"
 * 全押在这个函数上。规则散进 Compose 就再也验不到了，因此集中成纯函数。
 *
 * 顺序有意：已有索引优先于开关与询问状态——关掉"允许建立索引"不能让已建好的搜索失效。
 */
class SearchPhaseTest {
    private val idle = IndexState.Idle
    private val running = IndexState.Running(com.radio.chinese.domain.IndexedProgress(5, 3, 0))
    private val done = IndexState.Done(3469)

    private fun phase(
        state: IndexState,
        count: Int,
        asked: Boolean = false,
        allowed: Boolean = true,
        stale: Boolean = false,
        webdavSources: Int = 1,
    ) = IndexQuery.searchPhase(state, count, asked, allowed, stale, webdavSources)

    @Test fun `已有索引且新鲜则直接出结果`() {
        assertEquals(SearchPhase.Ready, phase(done, 3469))
        assertEquals(SearchPhase.Ready, phase(idle, 3469))
    }

    @Test fun `已有索引但过期给可更新态`() {
        assertEquals(SearchPhase.ReadyStale, phase(done, 3469, stale = true))
    }

    @Test fun `进行中一律挡结果，即使已经落了一部分`() {
        // 用户要的是"正在建立索引，请稍后搜索"，不是半截结果
        assertEquals(SearchPhase.Indexing, phase(running, 0))
        assertEquals(SearchPhase.Indexing, phase(running, 1203))
    }

    @Test fun `从未建立且未询问过则请用户确认`() {
        assertEquals(SearchPhase.NotIndexed, phase(idle, 0))
    }

    // 用户实测问题 3：一个网络源都没添加时，不应该弹“建立什么索引”
    @Test fun `没有网络源时不请求建立索引`() {
        assertEquals(SearchPhase.NoWebdavSource, phase(idle, 0, webdavSources = 0))
    }

    @Test fun `没有网络源时即使开关开启也不变回 NotIndexed`() {
        // 询问过与否都不该跳过 NoWebdavSource 去弹确认
        assertEquals(SearchPhase.NoWebdavSource, phase(idle, 0, asked = true, webdavSources = 0))
    }

    @Test fun `已有索引时无论有无网络源都照常出结果`() {
        assertEquals(SearchPhase.Ready, phase(done, 3469, webdavSources = 0))
    }

    @Test fun `本次会话已否认过则不再弹，只留手动入口`() {
        assertEquals(SearchPhase.Deferred, phase(idle, 0, asked = true))
    }

    @Test fun `开关关闭且无索引时提示已关闭`() {
        assertEquals(SearchPhase.Disabled, phase(idle, 0, allowed = false))
    }

    @Test fun `开关关闭但已有索引时搜索照常可用`() {
        assertEquals(SearchPhase.Ready, phase(done, 3469, allowed = false))
    }

    @Test fun `已在跑的任务不因开关关闭而改口`() {
        assertEquals(SearchPhase.Indexing, phase(running, 800, allowed = false))
    }

    @Test fun `询问状态不影响进行中的判定顺序`() {
        // 用户点完"建立"后 asked 也为 true，此时必须显示进行中而不是"以后再说"
        assertEquals(SearchPhase.Indexing, phase(running, 0, asked = true))
    }
}
