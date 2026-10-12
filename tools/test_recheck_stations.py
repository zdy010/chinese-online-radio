"""recheck_stations 纯逻辑测试（不碰网络，探针被替换成假记录器）。

最重要的一条是 test_probe_one_does_not_put_timeout_in_the_session_slot：
上一版就是那里写错，465 台全被判成不可播，而报告表面看起来完全正常。
体检工具的错误结论比没有体检更糟，因为它会让人放心地删台。
"""
import unittest

import recheck_stations as rs
import station_probe as sp


class TestProbeOne(unittest.TestCase):
    def setUp(self):
        self.calls = []
        self._real = sp.probe_with_retry

        def fake(url, **kw):
            self.calls.append((url, kw))
            return {"verdict": sp.LIVE, "why": "fake", "detail": ""}

        sp.probe_with_retry = fake

    def tearDown(self):
        sp.probe_with_retry = self._real

    def test_probe_one_does_not_put_timeout_in_the_session_slot(self):
        """probe_with_retry 的第 4 个形参是 session，位置传参会把 float 塞进去。"""
        rs.probe_one("https://x/y.m3u8", attempts=2, pause=1.5, timeout=12.0)
        _url, kw = self.calls[0]
        self.assertNotIn("session", kw)
        self.assertEqual(kw["timeout"], 12.0)
        self.assertEqual(kw["attempts"], 2)

    def test_probe_urls_keeps_label_and_order(self):
        urls = [{"url": "https://a", "label": "主源"}, {"url": "https://b", "label": "镜像源"}]
        rows = rs.probe_urls(urls, attempts=2, timeout=9.0, pause=1.0)
        self.assertEqual([r["label"] for r in rows], ["主源", "镜像源"])
        self.assertEqual([r["verdict"] for r in rows], [sp.LIVE, sp.LIVE])


class TestStationUrls(unittest.TestCase):
    def test_primary_first_and_dedup(self):
        st = {"streamUrl": "https://a", "sources": [
            {"url": "https://a", "label": "主源"}, {"url": "https://b", "label": "镜像源"}]}
        got = rs.station_urls(st)
        self.assertEqual([u["url"] for u in got], ["https://a", "https://b"])

    def test_station_without_sources_still_yields_one_url(self):
        """大陆存量台大多没有 sources 数组，只测 streamUrl 才是对的口径。"""
        got = rs.station_urls({"streamUrl": "https://a", "sources": []})
        self.assertEqual(len(got), 1)

    def test_missing_label_falls_back(self):
        got = rs.station_urls({"streamUrl": "", "sources": [{"url": "https://b"}]})
        self.assertEqual(got[0]["label"], "镜像源")


class TestSummarize(unittest.TestCase):
    def _row(self, verdicts):
        return [{"url": "https://%s" % v, "label": "主源", "verdict": v, "why": ""}
                for v in verdicts]

    def test_any_live_makes_station_playable(self):
        """主源死、镜像活 → 这台是「能听到」的，不能算进死台清单。"""
        r = rs.summarize({"id": "a", "name": "A", "category": "news"},
                         self._row([sp.GONE, sp.LIVE]), "serial")
        self.assertEqual(r["verdict"], sp.LIVE)
        self.assertEqual(r["live_index"], 1)

    def test_all_gone_is_gone(self):
        r = rs.summarize({"id": "a", "name": "A", "category": "news"},
                         self._row([sp.GONE, sp.GONE]), "serial")
        self.assertEqual(r["verdict"], sp.GONE)
        self.assertEqual(r["live_index"], -1)

    def test_timeout_only_is_unknown_not_gone(self):
        """连不上/超时不能写成「已死」：那是本机网络也做得到的事。"""
        r = rs.summarize({"id": "a", "name": "A", "category": "news"},
                         self._row([sp.UNKNOWN, sp.UNKNOWN]), "serial")
        self.assertEqual(r["verdict"], sp.UNKNOWN)

    def test_gone_beats_unknown_in_severity(self):
        r = rs.summarize({"id": "a", "name": "A", "category": "news"},
                         self._row([sp.UNKNOWN, sp.GONE]), "serial")
        self.assertEqual(r["verdict"], sp.GONE)

    def test_live_index_points_at_the_first_live_url(self):
        r = rs.summarize({"id": "a", "name": "A", "category": "news"},
                         self._row([sp.UNKNOWN, sp.GONE, sp.LIVE]), "serial")
        self.assertEqual(r["urls"][r["live_index"]]["verdict"], sp.LIVE)


if __name__ == "__main__":
    unittest.main()
