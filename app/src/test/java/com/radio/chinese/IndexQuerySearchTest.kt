package com.radio.chinese

import com.radio.chinese.domain.IndexState
import com.radio.chinese.domain.IndexedProgress
import com.radio.chinese.domain.TrackHit
import com.radio.chinese.domain.model.IndexQuery
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * LIKE 转义：通配符必须失效，且最终形态是 %…% 包裹的子串匹配。
 *
 * 不转义的话用户输入一个 `%` 就等价于“匹配全部”，清空半个字屏就刷屏；
 * 转义顺序尤其关键，反斜杠必须最先处理，否则会把刚补出来的 `\%` 再吃掉一层。
 */
class IndexQuerySearchTest {
    @Test fun `普通中文按子串包裹`() {
        assertEquals("%花木兰%", IndexQuery.likePattern("花木兰"))
    }

    @Test fun `百分号被转义`() {
        assertEquals("%100\\%现场%", IndexQuery.likePattern("100%现场"))
    }

    @Test fun `下划线被转义`() {
        assertEquals("%a\\_b%", IndexQuery.likePattern("a_b"))
    }

    @Test fun `反斜杠自身先转义避免二次转义`() {
        assertEquals("%a\\\\b%", IndexQuery.likePattern("a\\b"))
    }

    @Test fun `空白输入产出空串交由调用方短路`() {
        assertEquals("", IndexQuery.likePattern("   "))
    }

    @Test fun `首尾空格被裁掉`() {
        assertEquals("%贵妃醉酒%", IndexQuery.likePattern("  贵妃醉酒 "))
    }

    // ── 跨源归并：实测两个豫剧源同为 3468 首，同一批内容的两种分类视图 ──

    @Test fun `同名同大小跨源归并为一条`() {
        val hits = listOf(
            TrackHit(1, "花木兰 选段", "/豫剧/花木兰.mp3", "/豫剧", 8_000_000),
            TrackHit(2, "花木兰 选段", "/常香玉/花木兰.mp3", "/常香玉", 8_000_000),
        )
        val merged = IndexQuery.mergeHits(hits)
        assertEquals(1, merged.size)
        assertEquals(listOf(1L, 2L), merged[0].sources.map { it.sourceId })
    }

    @Test fun `同名不同大小不合并`() {
        val hits = listOf(
            TrackHit(1, "花木兰", "/a.mp3", "/", 1_000),
            TrackHit(2, "花木兰", "/b.mp3", "/", 9_000),
        )
        assertEquals(2, IndexQuery.mergeHits(hits).size)
    }

    @Test fun `名称差异仅首尾空格仍算同一条`() {
        val hits = listOf(
            TrackHit(1, " 花木兰 ", "/a.mp3", "/", 1_000),
            TrackHit(2, "花木兰", "/b.mp3", "/", 1_000),
        )
        assertEquals(1, IndexQuery.mergeHits(hits).size)
    }

    @Test fun `归并结果按名称码位升序与 SQL 排序一致`() {
        val hits = listOf(
            TrackHit(1, "穆桂英", "/m.mp3", "/", 1),
            TrackHit(1, "花木兰", "/h.mp3", "/", 1),
        )
        // 穆 U+7A46 排在 花 U+82B1 之前：码位序，与 SQLite `ORDER BY name ASC` 同一套规则
        assertEquals(listOf("穆桂英", "花木兰"), IndexQuery.mergeHits(hits).map { it.name })
    }

    // ── 面包屑：结果行要让用户看出这首在哪个目录 ──

    @Test fun `面包屑去掉源根空段`() {
        assertEquals("豫剧 / 常香玉", IndexQuery.breadcrumb("/豫剧/常香玉", "123云盘·豫剧"))
    }

    @Test fun `面包屑超长时保留末三段并加省略号`() {
        assertEquals("… / 乙 / 丙 / 丁", IndexQuery.breadcrumb("/甲/乙/丙/丁", "源", maxSegments = 3))
    }

    @Test fun `根目录面包屑回退到源名`() {
        assertEquals("123云盘·豫剧", IndexQuery.breadcrumb("/", "123云盘·豫剧"))
    }

    // 设备实测：索引落库的 parentPath 是 href 形态（带 DAV 挂载前缀且百分号编码），
    // 不解码不剔前缀的话，结果行会显示成 %E7%8E%8B%E9%9C%9E 这种乱码。
    @Test fun `面包屑解码百分号编码的目录名`() {
        assertEquals("王霞", IndexQuery.breadcrumb("/webdav/%E7%8E%8B%E9%9C%9E/", "123云盘·豫剧"))
    }

    @Test fun `面包屑去掉 DAV 挂载前缀段`() {
        assertEquals("豫剧 / 常香玉", IndexQuery.breadcrumb("/webdav/豫剧/常香玉", "源"))
    }

    @Test fun `加号不被解码成空格`() {
        assertEquals("白莲花+豫剧", IndexQuery.breadcrumb("/webdav/%E7%99%BD%E8%8E%B2%E8%8A%B1+%E8%B1%AB%E5%89%A7", "源"))
    }

    @Test fun `无法解码的片段原样保留而不抛异常`() {
        assertEquals("%ZZ", IndexQuery.breadcrumb("/webdav/%ZZ", "源"))
    }

    // ── 索引状态跨源聚合：提示必须反映最差那个源，不能被已完成的源盖掉 ──

    @Test fun `空状态集合聚合为 Idle`() {
        assertEquals(IndexState.Idle, IndexQuery.aggregate(emptyList()))
        assertEquals(IndexState.Idle, IndexQuery.aggregate(listOf(IndexState.Idle)))
    }

    @Test fun `任一源在跑就报 Running 并合并计数`() {
        val agg = IndexQuery.aggregate(listOf(
            IndexState.Done(50),
            IndexState.Running(IndexedProgress(30, 3, 1)),
            IndexState.Running(IndexedProgress(20, 2, 0)),
        ))
        assertEquals(IndexState.Running(IndexedProgress(50, 5, 1)), agg)
    }

    @Test fun `没在跑时报 Paused 优先于 Failed 优先于 Done`() {
        assertEquals(
            IndexState.PausedNoWifi(IndexedProgress(10, 1, 0)),
            IndexQuery.aggregate(listOf(IndexState.Done(9), IndexState.PausedNoWifi(IndexedProgress(10, 1, 0))))
        )
        assertEquals(
            IndexState.PartiallyFailed(IndexedProgress(4, 0, 2)),
            IndexQuery.aggregate(listOf(IndexState.Done(9), IndexState.PartiallyFailed(IndexedProgress(4, 0, 2))))
        )
        assertEquals(IndexState.Done(140), IndexQuery.aggregate(listOf(IndexState.Done(40), IndexState.Done(100))))
    }
}
