"""podcast_to_m3u 纯逻辑单测（不碰网络）。

约定依据两条，都不是凭空定的：
1. app 侧 M3uBrowser 的解析方式——`#EXTINF:<秒>,<标题>` 取**第一个逗号之后整串**当标题，
   下一条非注释行是直链（见 app/src/main/java/com/radio/chinese/domain/browser/M3uBrowser.kt）。
   所以标题里的逗号无害，换行才致命；直链必须原样可 GET。
2. 收录红线：只收内容提供方自我公开发布的免费流，平台转载的 UGC 合集默认拒绝。
"""
import os
import unittest

import podcast_to_m3u as pm

FEED = """<?xml version="1.0" encoding="UTF-8"?>
<rss xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" version="2.0">
<channel>
  <title>测试节目</title>
  <item>
    <title>第 2 期·贵妃醉酒</title>
    <enclosure url="https://cdn.example.org/a.mp3?token=xyz" type="audio/mpeg" length="1024"/>
    <itunes:duration>12:34</itunes:duration>
  </item>
  <item>
    <title>封面不是声音</title>
    <enclosure url="https://cdn.example.org/cover.jpg" type="image/jpeg" length="99"/>
  </item>
  <item>
    <title>第 1 期·空城计</title>
    <enclosure url="https://cdn.example.org/b.m4a" type="audio/mp4" length="2048"/>
    <itunes:duration>1:02:03</itunes:duration>
  </item>
  <item>
    <title>第 2 期·贵妃醉酒（同址重复条目）</title>
    <enclosure url="https://cdn.example.org/a.mp3?token=xyz" type="audio/mpeg" length="1024"/>
  </item>
</channel>
</rss>"""


class TestParseFeed(unittest.TestCase):
    def test_keeps_audio_enclosure_and_drops_image(self):
        """图片 enclosure 进了清单就是车机上一条点开没声音的假曲目。"""
        tracks = pm.parse_feed(FEED)
        self.assertEqual(2, len(tracks), [t["url"] for t in tracks])
        self.assertEqual({t["url"] for t in tracks},
                         {"https://cdn.example.org/a.mp3?token=xyz",
                          "https://cdn.example.org/b.m4a"})

    def test_preserves_query_string_on_enclosure_url(self):
        """带 token 的直链剥掉 query 就 403；原样保留是唯一正确解。"""
        tracks = pm.parse_feed(FEED)
        self.assertEqual("https://cdn.example.org/a.mp3?token=xyz", tracks[0]["url"])

    def test_dedupes_same_url_and_keeps_feed_order(self):
        """同一地址写两行 = 曲库里两个假条目；顺序按 feed 原样，不做时间重排。"""
        tracks = pm.parse_feed(FEED)
        self.assertEqual(["第 2 期·贵妃醉酒", "第 1 期·空城计"], [t["title"] for t in tracks])

    def test_records_size_from_length_attribute(self):
        """size 是全源搜索的归并依据之一（曲名+大小），丢了就并不掉重复。"""
        tracks = pm.parse_feed(FEED)
        self.assertEqual(1024, tracks[0]["size"])

    def test_rejects_feed_with_doctype_or_entity(self):
        """外部实体/DOCTYPE 的 feed 直接拒绝：默认 XML 解析器不抗实体攻击。"""
        evil = '<!DOCTYPE rss [<!ENTITY x SYSTEM "file:///etc/passwd">]><rss><channel/></rss>'
        with self.assertRaises(pm.FeedRejected):
            pm.parse_feed(evil)

    def test_exposes_raw_duration_string(self):
        """时长从 itunes:duration 取原始字符串，换算留给 parse_duration（不在解析层臆造秒数）。"""
        tracks = pm.parse_feed(FEED)
        self.assertEqual("12:34", tracks[0]["duration"])


class TestParseDuration(unittest.TestCase):
    def test_mmss(self):
        self.assertEqual(754, pm.parse_duration("12:34"))

    def test_hhmmss(self):
        self.assertEqual(3723, pm.parse_duration("1:02:03"))

    def test_bare_seconds(self):
        self.assertEqual(89, pm.parse_duration("89"))

    def test_unparseable_falls_back_to_zero(self):
        """猜不出来就写 0：编一个时长会让车机上的进度条说谎。"""
        for raw in ("", "unknown", "12:xx", None, "--:--"):
            self.assertEqual(0, pm.parse_duration(raw), repr(raw))


class TestRenderM3u(unittest.TestCase):
    TRACKS = [
        {"title": "第 2 期, 贵妃\n醉酒", "url": "https://cdn.example.org/a.mp3?token=xyz",
         "duration": "12:34", "size": 1024},
        {"title": "第 1 期·空城计", "url": "https://cdn.example.org/b.m4a",
         "duration": "", "size": 2048},
    ]

    def test_header_and_paired_lines(self):
        """首行 #EXTM3U，每条恰好两行：#EXTINF:<秒>,<标题> + 直链。"""
        lines = pm.render_m3u(self.TRACKS).splitlines()
        self.assertEqual("#EXTM3U", lines[0])
        self.assertEqual("#EXTINF:754,第 2 期, 贵妃 醉酒", lines[1])
        self.assertEqual("https://cdn.example.org/a.mp3?token=xyz", lines[2])
        self.assertEqual("#EXTINF:0,第 1 期·空城计", lines[3])
        self.assertEqual("https://cdn.example.org/b.m4a", lines[4])

    def test_title_newline_is_flattened_but_comma_kept(self):
        """换行会把一行裂成两行（致命）；逗号无害——M3uBrowser 取第一个逗号后的整串当标题。"""
        lines = pm.render_m3u(self.TRACKS).splitlines()
        self.assertNotIn("醉酒", [l for l in lines if not l.startswith("#")])
        self.assertIn(",", lines[1])

    def test_empty_list_still_yields_a_valid_playlist(self):
        """空清单也要是个合法 m3u，不能让 app 解析到一个文件头都没有的东西。"""
        self.assertEqual(["#EXTM3U"], pm.render_m3u([]).splitlines())


class TestNoTypeEnclosure(unittest.TestCase):
    FEED = """<rss><channel>
      <item><title>无 type 但能播</title>
        <enclosure url="https://self.example.org/ep1.mp3?sign=abc" length="10"/></item>
      <item><title>无 type 又不是音频</title>
        <enclosure url="https://self.example.org/ep2.pdf" length="10"/></item>
    </channel></rss>"""

    def test_falls_back_to_url_extension_when_type_missing(self):
        """自架播客普遍不写 type。只看 type 会把好源全漏掉；但也不能照单全收。"""
        tracks = pm.parse_feed(self.FEED)
        self.assertEqual(["无 type 但能播"], [t["title"] for t in tracks])


class TestLicenseGate(unittest.TestCase):
    """收录红线：只收内容提供方自我公开发布的免费流。平台转载的 UGC 合集默认拒绝。"""

    TRACKS = [
        {"title": "院团官方专辑", "url": "https://cdn.example.org/a.mp3", "duration": "", "size": 1},
        {"title": "平台搬运合集", "url": "https://jt.ximalaya.com/b.m4a?channel=rss", "duration": "", "size": 2},
        {"title": "蜻蜓转载", "url": "http://lhttp.qingting.fm/live/1/64k.mp3", "duration": "", "size": 3},
    ]

    def test_ugc_hosts_are_rejected_by_default(self):
        kept, rejected = pm.partition_by_license(self.TRACKS, allow_ugc=False)
        self.assertEqual(["院团官方专辑"], [t["title"] for t in kept])
        self.assertEqual(2, len(rejected), [t["url"] for t in rejected])

    def test_allow_ugc_keeps_everything_and_says_so(self):
        """显式放行时不能悄悄过：flagged 列表依旧要返回，事后能查到收了什么。"""
        kept, rejected = pm.partition_by_license(self.TRACKS, allow_ugc=True)
        self.assertEqual(3, len(kept))
        self.assertEqual(2, len(rejected))


class TestEpisodeNaming(unittest.TestCase):
    def test_safe_filename_deletes_illegal_chars_and_collapses_space(self):
        """规则要能一句话说清：文件系统非法字符直接删，剩下空白压缩。"""
        self.assertEqual("第 3 期贵妃醉酒 m4a", pm.safe_filename('第 3 期/贵妃*醉酒:  m4a'))

    def test_safe_filename_never_empty(self):
        self.assertEqual("未命名", pm.safe_filename("   ...  "))

    def test_episode_filename_prefixes_index_and_keeps_audio_extension(self):
        """序号前缀保证目录树里按出品顺序排，不会被拼音/时间戳打乱。"""
        track = {"title": "空城计", "url": "https://self.example.org/f/01.m4a?sign=abc"}
        self.assertEqual("0001 空城计.m4a", pm.episode_filename(track, 1))

    def test_episode_filename_refuses_unknown_extension(self):
        """拿不到音频扩展名就不下载：造一个无后缀文件，app 里点开放不出来还洗不掉。"""
        track = {"title": "无后缀", "url": "https://self.example.org/stream/123"}
        self.assertIsNone(pm.episode_filename(track, 1))


class TestFeedTitle(unittest.TestCase):
    def test_reads_channel_title(self):
        """频道名是下载目录名，取不到就用默认名，不能空。"""
        self.assertEqual("测试节目", pm.feed_title(FEED))
        self.assertEqual("播客节目", pm.feed_title("<rss><channel></channel></rss>"))


Ugc_FEED = """<rss><channel><title>平台合集</title>
  <item><title>搬运一</title><enclosure url="https://jt.ximalaya.com/a.m4a?channel=rss" type="audio/mp4"/></item>
  <item><title>搬运二</title><enclosure url="http://lhttp.qingting.fm/live/1/64k.mp3" type="audio/mpeg"/></item>
</channel></rss>"""


class TestMain(unittest.TestCase):
    """CLI 只测接线：网络 fetch 一律注入假件，真取数走另一条命令。"""

    def setUp(self):
        import tempfile
        self.dir = tempfile.mkdtemp()
        self.out = self.dir + "/show.m3u"

    def test_writes_m3u_from_a_local_feed_file(self):
        rc = pm.main(["--feed", "-", "--out", self.out], fetch=lambda: FEED)
        self.assertEqual(0, rc)
        text = open(self.out, encoding="utf-8").read()
        self.assertTrue(text.startswith("#EXTM3U"))
        self.assertIn("https://cdn.example.org/a.mp3?token=xyz", text)

    def test_refuses_to_write_a_playlist_with_no_tracks(self):
        """版权闸下空清单退出码必须非 0 且不落盘：写个空文件会让用户以为成功了。"""
        rc = pm.main(["--feed", "-", "--out", self.out], fetch=lambda: Ugc_FEED)
        self.assertNotEqual(0, rc)
        self.assertFalse(os.path.exists(self.out))

    def test_allow_ugc_writes_but_says_what_was_let_through(self):
        """放行不静默：报告里必须带上“其中 N 条来自平台转载”。"""
        import io
        import contextlib
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rc = pm.main(["--feed", "-", "--out", self.out, "--allow-ugc"],
                         fetch=lambda: Ugc_FEED)
        self.assertEqual(0, rc)
        self.assertIn("转载", buf.getvalue())

    def test_limit_takes_a_head_slice(self):
        rc = pm.main(["--feed", "-", "--out", self.out, "--limit", "1"], fetch=lambda: FEED)
        self.assertEqual(0, rc)
        self.assertEqual(1, open(self.out, encoding="utf-8").read().count("#EXTINF"))


class TestDownloadPlan(unittest.TestCase):
    def test_plan_skips_tracks_without_audio_extension(self):
        tracks = [{"title": "空城计", "url": "https://self.example.org/f/1.m4a?sign=x"},
                  {"title": "无后缀", "url": "https://self.example.org/stream/123"}]
        plan, skipped = pm.plan_downloads(tracks, "/tmp/x")
        self.assertEqual(1, len(plan))
        self.assertEqual(1, skipped)
        self.assertTrue(plan[0][1].endswith("0001 空城计.m4a"), plan[0][1])


class _BrokenBody:
    """写完第一个 chunk 就断的连接：模拟下到一半掉线。"""

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False

    def raise_for_status(self):
        pass

    def iter_content(self, size):
        yield b"PARTIAL"
        raise RuntimeError("connection reset")


class _GoodBody:
    def __init__(self, data):
        self.data = data

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False

    def raise_for_status(self):
        pass

    def iter_content(self, size):
        yield self.data


class TestDownloadTracks(unittest.TestCase):
    """下载只测落盘约定：网络用注入的假件，不拿真连断当断言。"""

    def setUp(self):
        import tempfile
        self.dir = tempfile.mkdtemp()
        self.dest = os.path.join(self.dir, "0001 空城计.mp3")

    def test_partial_download_is_not_left_behind_as_a_track(self):
        """半截文件留在目录里，WebDAV 侧就成了一首能点开、永远放不完的曲目。"""
        ok = pm.download_tracks([("https://x/a.mp3", self.dest)],
                                log=lambda *a: None,
                                get=lambda url, **kw: _BrokenBody())
        self.assertEqual(0, ok)
        self.assertFalse(os.path.exists(self.dest))
        self.assertEqual([], [f for f in os.listdir(self.dir)], "连残留的中间文件也不许留下")

    def test_finished_download_lands_at_dest(self):
        ok = pm.download_tracks([("https://x/a.mp3", self.dest)],
                                log=lambda *a: None,
                                get=lambda url, **kw: _GoodBody(b"ID3fake-audio"))
        self.assertEqual(1, ok)
        self.assertEqual(b"ID3fake-audio", open(self.dest, "rb").read())


if __name__ == "__main__":
    unittest.main()
