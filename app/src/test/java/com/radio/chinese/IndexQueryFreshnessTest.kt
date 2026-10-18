package com.radio.chinese

import com.radio.chinese.domain.model.IndexQuery
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 目录新鲜度判定：断点续爬的实现方式不持久化队列，而是靠“新鲜目录跳过”。
 *
 * 计划里原本还有一个 pickDirsToCrawl，但 Task 5 的 BFS 是边发现边判，
 * 用不上它——留着就是死代码，所以这里只钉 isFresh / shouldCrawl 两个真正被消费的函数。
 */
class IndexQueryFreshnessTest {
    private val now = 1_000_000_000_000L
    private val day = 86_400_000L

    @Test fun `TTL 内的目录算新鲜`() {
        assertEquals(true, IndexQuery.isFresh(now - day + 1, day * 7, now))
    }

    @Test fun `恰好等于 TTL 的目录算过期`() {
        // 边界上判过期只会多爬一次，判新鲜则可能永远不再刷新
        assertEquals(false, IndexQuery.isFresh(now - day, day, now))
    }

    @Test fun `未来时间戳不视为新鲜`() {
        // 用户或车机把时钟改回去时，不能让整棵树永远不再刷新
        assertEquals(false, IndexQuery.isFresh(now + day, day, now))
    }

    @Test fun `没有戳的目录一定要爬`() {
        assertEquals(true, IndexQuery.shouldCrawl(stamp = null, ttlMs = day, now = now))
    }

    @Test fun `过期目录要重爬`() {
        assertEquals(true, IndexQuery.shouldCrawl(stamp = now - day * 30, ttlMs = day * 7, now = now))
    }

    @Test fun `新鲜目录跳过`() {
        assertEquals(false, IndexQuery.shouldCrawl(stamp = now - day, ttlMs = day * 7, now = now))
    }

    @Test fun `TTL 为零或负数时全量重爬`() {
        assertEquals(true, IndexQuery.shouldCrawl(stamp = now, ttlMs = 0, now = now))
        assertEquals(true, IndexQuery.shouldCrawl(stamp = now, ttlMs = -1, now = now))
    }
}
