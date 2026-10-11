"""station_probe 的离线单元测试。

为什么先写这些：A-1 最大的风险不是取不到台，而是"把没验证过的源当活的写进清单"。
项目历史上已经踩过两类假通过（旧产物冒充本次结果、宽匹配全说 yes），
所以这里把三态语义钉死成断言：超时/5xx/连接重置只能判 UNKNOWN，
既不许算活、也不许算死，必须由调用方重试后再表态。
"""
import unittest

import station_probe as sp


class TestResolveRef(unittest.TestCase):
    """变体地址解析：卫星流的变体既可能是相对路径，也可能是带 token 的绝对域名。"""

    def test_relative_ref_joins_base_dir(self):
        base = "https://satellitepull.cnr.cn/live/wxahxqgb/playlist.m3u8"
        got = sp.resolve_ref(base, "1791694075.ts?wsSession=abc")
        self.assertEqual(got, "https://satellitepull.cnr.cn/live/wxahxqgb/1791694075.ts?wsSession=abc")

    def test_absolute_url_passes_through_with_query(self):
        base = "https://satellitepull.cnr.cn/live/wxahxqgb/playlist.m3u8"
        absolute = "https://tokenhost.100ycdn.com/satellitepull.cnr.cn/live/wxahxqgb/playlist.m3u8?wsSession=x&wsBindIP=2"
        self.assertEqual(sp.resolve_ref(base, absolute), absolute)

    def test_root_relative_ref(self):
        base = "https://host/a/b/playlist.m3u8"
        self.assertEqual(sp.resolve_ref(base, "/c/d.ts"), "https://host/c/d.ts")

    def test_blank_ref_is_not_invented(self):
        """空引用必须回空串：宁可不判活，也不许拼出一个不存在的地址去"验证"。"""
        self.assertEqual(sp.resolve_ref("https://host/live/x/playlist.m3u8", ""), "")
        self.assertEqual(sp.resolve_ref("", "seg.ts"), "")


class TestPlaylistParsing(unittest.TestCase):
    MASTER = (
        "#EXTM3U\n"
        "#EXT-X-STREAM-INF:PROGRAM-ID=1, BANDWIDTH=225000\n"
        "https://tok.100ycdn.com/satellitepull.cnr.cn/live/wxahxqgb/playlist.m3u8?wsSession=1\n"
    )
    # 安徽戏曲广播的真实形状：媒体列表用 #EXTINF 后跟带 token 的分片名
    MEDIA = (
        "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-ALLOW-CACHE:NO\n#EXT-X-TARGETDURATION:3\n"
        "#EXT-X-MEDIA-SEQUENCE:1791694076\n\n"
        "#EXTINF:2.987,\n1791694075.ts?wsSession=1\n"
        "#EXTINF:2.987,\n1791694076.ts?wsSession=1\n"
    )
    # 服务器返回 200 但是错误页/占位页：内容不是播放列表，绝不能判活
    HTML = "<html><body>404 not found</body></html>"

    def test_master_variant_extraction(self):
        variants = sp.parse_master_variants(self.MASTER)
        self.assertEqual(len(variants), 1)
        self.assertTrue(variants[0].startswith("https://tok.100ycdn.com/"))

    def test_master_rejects_html_disguised_as_200(self):
        self.assertEqual(sp.parse_master_variants(self.HTML), [])

    def test_media_sequence_and_segment_count(self):
        self.assertTrue(sp.has_media_sequence(self.MEDIA))
        self.assertEqual(sp.count_segments(self.MEDIA), 2)

    def test_empty_media_playlist_has_zero_segments(self):
        text = "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:1\n"
        self.assertEqual(sp.count_segments(text), 0)

    def test_segment_count_ignores_blank_and_comment_lines(self):
        text = "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:9\n\n\n#EXTINF:2.0,\nreal.ts\n"
        self.assertEqual(sp.count_segments(text), 1)

    def test_media_sequence_absent_is_false_not_none(self):
        self.assertFalse(sp.has_media_sequence("#EXTM3U\n#EXTINF:2.0,\na.ts\n"))


class TestDirectStreamVerdict(unittest.TestCase):
    """MP3 / Icecast 直链：靠内容类型与首字节判定，不靠状态码单独说话。"""

    def test_audio_content_type_is_live(self):
        v = sp.classify_direct(200, "audio/mpeg", b"ID3\x03\x00" + b"\x00" * 64)
        self.assertEqual(v, sp.LIVE)

    def test_octet_stream_with_audio_magic_is_live(self):
        """很多 Icecast 源只报 application/octet-stream，得认 MP3 帧头。"""
        v = sp.classify_direct(200, "application/octet-stream", b"\xff\xfb\x90\x64" + b"\x00" * 64)
        self.assertEqual(v, sp.LIVE)

    def test_html_body_is_gone_even_with_200(self):
        v = sp.classify_direct(200, "text/html", b"<!DOCTYP")
        self.assertEqual(v, sp.GONE)

    def test_tiny_body_is_unknown_not_live(self):
        """只拿到几个字节说明没真取到音频，不许判活。"""
        v = sp.classify_direct(200, "audio/mpeg", b"ab")
        self.assertEqual(v, sp.UNKNOWN)

    def test_404_is_gone(self):
        self.assertEqual(sp.classify_direct(404, "", b""), sp.GONE)

    def test_403_and_429_are_unknown(self):
        """限流与鉴权拒绝都不代表源不存在，必须走重试而不是当死源剔除。"""
        self.assertEqual(sp.classify_direct(403, "", b""), sp.UNKNOWN)
        self.assertEqual(sp.classify_direct(429, "", b""), sp.UNKNOWN)


class TestVerdictVocabulary(unittest.TestCase):
    def test_three_states_are_distinct_strings(self):
        self.assertEqual({sp.LIVE, sp.GONE, sp.UNKNOWN}, {"live", "gone", "unknown"})

    def test_hls_verdict_requires_segments(self):
        self.assertEqual(sp.hls_verdict(1, 0), sp.GONE)
        self.assertEqual(sp.hls_verdict(0, 2), sp.GONE)
        self.assertEqual(sp.hls_verdict(1, 1), sp.LIVE)

    def test_hls_verdict_accepts_single_level_playlist(self):
        """单层列表（既有序号又有分片）也算活：不是所有官方流都分主/媒体两级。"""
        self.assertEqual(sp.hls_verdict(1, 3, master_variants=0, single_level=True), sp.LIVE)


class TestAppErrorBody(unittest.TestCase):
    """HTTP 200 + JSON 错误体：实测 live.xmcdn.com 对已下架的台就这么回。

    它是服务端取流失败，不是"这个地址不存在"的证据，所以既不能判活也不能判死，
    只能归 UNKNOWN；重试后仍是错体则单独报成"未表态"，由人决定去留。
    """

    XMCDN_DEAD = '{"msg":"电台流获取失败，请稍后再试","ret":2011}'

    def test_json_error_body_is_app_error(self):
        self.assertEqual(sp.hls_manifest_evidence(self.XMCDN_DEAD), "app-error")

    def test_json_error_body_is_unknown_not_live(self):
        """最关键的一条：状态 200 绝不能单独推出可用。"""
        self.assertEqual(sp.body_verdict(200, self.XMCDN_DEAD), sp.UNKNOWN)

    def test_html_error_page_is_gone(self):
        self.assertEqual(
            sp.body_verdict(200, "<html><body>Channel Not Found</body></html>"), sp.GONE
        )

    def test_real_playlist_evidence_is_recognised(self):
        self.assertEqual(sp.hls_manifest_evidence("#EXTM3U\n#EXT-X-TARGETDURATION:3\n"), "playlist")

    def test_empty_body_is_not_live(self):
        self.assertEqual(sp.body_verdict(200, ""), sp.GONE)


class TestCandidateRules(unittest.TestCase):
    """候选归类：关键词命中才收，命不中就 general，禁止硬塞进薄分类。"""

    def test_opera_keywords(self):
        for name in ["安徽戏曲广播", "河南戏曲之声", "北京戏曲曲艺", "苏州评弹广播"]:
            self.assertEqual(sp.classify_by_name(name), "opera", name)

    def test_sports_keywords(self):
        for name in ["山东体育休闲广播", "北京体育广播", "CCTV-5体育伴音"]:
            self.assertEqual(sp.classify_by_name(name), "sports", name)

    def test_tv_audio_keywords(self):
        self.assertEqual(sp.classify_by_name("湖南卫视"), "tv_audio")

    def test_unmatched_is_general_not_forced(self):
        self.assertEqual(sp.classify_by_name("某未知综合频率"), "general")

    def test_music_keywords(self):
        self.assertEqual(sp.classify_by_name("Classic FM 古典音乐"), "music")

    def test_dialect_genres_map_to_opera(self):
        """地方剧种名也得落到戏曲：候选池里真实出现过秦腔/川剧/沪剧这类台名。"""
        for name in ["陕西秦腔广播", "四川川剧广播", "上海越剧频道", "河北梆子戏广播"]:
            self.assertEqual(sp.classify_by_name(name), "opera", name)

    def test_crosstalk_and_storytelling_go_to_arts_not_opera(self):
        """相声/评书归文艺，不能混进戏曲分类里。"""
        self.assertEqual(sp.classify_by_name("天津相声广播"), "arts")
        self.assertEqual(sp.classify_by_name("评书频道"), "arts")


if __name__ == "__main__":
    unittest.main()
