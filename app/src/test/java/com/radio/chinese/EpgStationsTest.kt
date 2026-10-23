package com.radio.chinese

import com.radio.chinese.data.local.EpgStations
import com.radio.chinese.data.local.StationJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 映射表闸门。`epg_stations.json` 是云听 broadcastId 与本项目 stationId 之间的唯一真相，
 * 只要它漂了一条，某个台的播放页就会挂上另一个台的节目单——这种错静默、难发现、
 * 而且是用户会当真的错。所以放在构建期拦。
 */
class EpgStationsTest {

    private fun assets(name: String): String {
        val candidates = listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name"))
        val file = candidates.firstOrNull { it.exists() }
            ?: throw AssertionError("找不到 $name，尝试过: $candidates")
        return file.readText(Charsets.UTF_8)
    }

    private val stations by lazy { StationJson.parse(assets("radio_stations.json")) }
    private val mapping by lazy { EpgStations.parse(assets("epg_stations.json")) }

    @Test
    fun `schemaVersion 为 1`() {
        assertEquals(1, EpgStations.schemaVersion(assets("epg_stations.json")))
    }

    @Test
    fun `A 阶段至少接入 65 个台`() {
        // 交接文档的 A 阶段口径：56 个已匹配台 + 9 个新补央广台。
        // 台库扩容后匹配数会继续涨，这里只钉下限。
        assertTrue("实际 ${mapping.size} 条", mapping.size >= 65)
    }

    @Test
    fun `每个 stationId 都能在台库里查到`() {
        val known = stations.map { it.id }.toSet()
        val bad = mapping.filter { it.stationId !in known }
        assertTrue("台库里没有的 stationId: ${bad.map { it.stationId }}", bad.isEmpty())
    }

    @Test
    fun `stationId 与 broadcastId 都唯一`() {
        val dupeStation = mapping.groupBy { it.stationId }.filterValues { it.size > 1 }.keys
        val dupeBid = mapping.groupBy { it.broadcastId }.filterValues { it.size > 1 }.keys
        assertTrue("重复 stationId: $dupeStation", dupeStation.isEmpty())
        assertTrue("重复 broadcastId: $dupeBid", dupeBid.isEmpty())
    }

    @Test
    fun `broadcastId 全为非空数字`() {
        val bad = mapping.filter { it.broadcastId.isBlank() || it.broadcastId.any { c -> !c.isDigit() } }
        assertTrue("非法 broadcastId: ${bad.map { it.broadcastId }}", bad.isEmpty())
    }

    @Test
    fun `映射里的台名与台库完全一致`() {
        // 台名是生成映射表时的匹配依据。两边不一致说明数据被手改过：
        // 要么映射表挂错了台，要么台库里改了名而映射没跟着重生成。
        val byId = stations.associateBy { it.id }
        val bad = mapping.filter { byId[it.stationId]?.name != it.name }
            .map { "${it.stationId}: 映射=${it.name} 台库=${byId[it.stationId]?.name}" }
        assertTrue("台名不一致: $bad", bad.isEmpty())
    }

    @Test
    fun `坏 JSON 解析成空表而不是抛异常`() {
        // 资源被截断时宁可让节目单功能整体消失，也不能让 App 起不来
        assertEquals(emptyList<EpgStations.Entry>(), EpgStations.parse("<html>404</html>"))
    }
}
