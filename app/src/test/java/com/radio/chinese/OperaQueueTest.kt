package com.radio.chinese

import com.radio.chinese.domain.model.OperaQueue
import com.radio.chinese.domain.model.QueueRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上一曲/下一曲要走的队列怎么从「界面上的一列曲目」构造。
 *
 * 起因：搜索结果、收藏、最近播放三处点歌时队列都传了 emptyList()，
 * PlayerManager 定位不到当前项（index = -1），上下曲按钮按了没反应。
 * 目录树里点歌是正常的，因为它把 browseItems 当成了队列。
 */
class OperaQueueTest {

    private fun row(sourceId: Long, path: String, name: String = path) =
        QueueRow(sourceId = sourceId, path = path, name = name, size = 100L, parentPath = "/dir/$name")

    /** 地址解析按行注入，方便断言「每行用的是它自己那个源」。 */
    private fun build(rows: List<QueueRow>, pick: QueueRow) = OperaQueue.build(
        rows = rows,
        pick = pick,
        urlOf = { "url:${it.sourceId}:${it.path}" },
        sourceNameOf = { "src$it" },
    )

    @Test
    fun `队列顺序就是界面行序`() {
        val rows = listOf(row(1, "/a.mp3"), row(1, "/b.mp3"), row(1, "/c.mp3"))
        val (files, index) = build(rows, pick = rows[1])
        assertEquals(listOf("/a.mp3", "/b.mp3", "/c.mp3"), files.map { it.sourcePath })
        assertEquals(1, index)
    }

    @Test
    fun `按行调用注入回调，不把某一行的值复用给整列`() {
        // build 是纯函数，认行不认源；源一致性由调用方保证。
        // （AudioLibraryViewModel.playFromRows 会把队列限制在 pick 所属源：
        // WebDAV 鉴权头由 RadioService 从单例现读，跨源逐行 resolve 会让最后一行
        // 覆盖前面所有行，整条队列拿错账号起播。）
        // 这里故意喂不同 sourceId，只为钉住「逐行各调一次回调」而不是取首行套全部。
        val rows = listOf(row(1, "/x.mp3"), row(2, "/y.mp3"), row(3, "/z.mp3"))
        val (files, _) = build(rows, pick = rows[0])
        assertEquals(listOf("src1", "src2", "src3"), files.map { it.categoryName })
        assertEquals(
            listOf("url:1:/x.mp3", "url:2:/y.mp3", "url:3:/z.mp3"),
            files.map { it.downloadUrl },
        )
    }

    @Test
    fun `被点项按源与路径定位，不受同名不同源干扰`() {
        val rows = listOf(row(1, "/song.mp3", "曲名"), row(2, "/song.mp3", "曲名"))
        val (_, firstIdx) = build(rows, pick = rows[0])
        val (_, secondIdx) = build(rows, pick = rows[1])
        assertEquals(0, firstIdx)
        assertEquals(1, secondIdx)
    }

    @Test
    fun `被点项不在列表里时退化为单项队列而不是空队列`() {
        // 返回空队列会让 PlayerManager 把 index 定成 -1，正是这次 bug 的形态；
        // 至少要让当前这一项能放出来
        val rows = listOf(row(1, "/a.mp3"))
        val orphan = row(1, "/gone.mp3")
        val (files, index) = build(rows, pick = orphan)
        assertEquals(1, files.size)
        assertEquals("/gone.mp3", files.single().sourcePath)
        assertEquals(0, index)
    }

    @Test
    fun `空列表加孤立项仍然可用`() {
        val (files, index) = build(emptyList(), pick = row(7, "/only.mp3"))
        assertEquals(1, files.size)
        assertEquals(0, index)
    }

    @Test
    fun `fileId 由路径哈希得到，与 PlayerManager 定位当前项的口径一致`() {
        val r = row(1, "/same.mp3")
        val (files, index) = build(listOf(row(1, "/p.mp3"), r), pick = r)
        // PlayerManager 用 playlist.indexOfFirst { it.fileId == file.fileId } 找当前项，
        // 这里必须给出同一个算法，否则 index 会重新落到 -1
        assertEquals(r.path.hashCode().toLong(), files[1].fileId)
        assertEquals(1, index)
        // 两侧口径真对得上：拿返回的 index 取出那一项，再按 PlayerManager 的算法回查同一个下标
        val picked = files[index]
        assertEquals(index, files.indexOfFirst { it.fileId == picked.fileId })
    }

    @Test
    fun `曲目标题与父目录名分别落到 name 与 operaName`() {
        val r = QueueRow(1, "/dir/唱段.mp3", "唱段", 2048L, "/dir")
        val (files, _) = build(listOf(r), pick = r)
        val f = files.single()
        assertEquals("唱段", f.name)
        assertEquals("dir", f.operaName)
        assertEquals(2048L, f.size)
        assertTrue(f.sourcePath == "/dir/唱段.mp3")
    }
}
