"""iptv_probe 的离线单测：不联网、不读真实 assets、不写真实产物。

最关键的三组断言：
- 三态语义：超时/403/5xx/认不出的媒体只能 UNKNOWN，绝不许当死源；
- 缓存键带 header：同 url 不同 UA/Referer 必须分开测；
- 精简不误杀：只要有一源未测或 UNKNOWN，该台就得留在清单里。
"""
import contextlib
import io
import json
import sys
import tempfile
import threading
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import requests

import iptv_probe as ip


# ---------------------------------------------------------------- 假响应


class FakeRaw:
    def __init__(self, body: bytes):
        self.body = body
        self.pos = 0

    def read(self, n, decode_content=True):
        chunk = self.body[self.pos:self.pos + n]
        self.pos += len(chunk)
        return chunk


class FakeResponse:
    def __init__(self, status=200, ct="", body=b""):
        self.status_code = status
        self.headers = {"Content-Type": ct}
        self.raw = FakeRaw(body)

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False


class FakeSession:
    def __init__(self, *responses):
        self.responses = list(responses)
        self.calls = []

    def get(self, url, **kwargs):
        self.calls.append((url, kwargs))
        if self.responses:
            return self.responses.pop(0)
        return FakeResponse(404, "", b"")


MEDIA_PLAYLIST = (
    b"#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-MEDIA-SEQUENCE:100\n"
    b"#EXTINF:2.0,\n100.ts\n#EXTINF:2.0,\n101.ts\n"
)
MASTER_PLAYLIST = (
    b"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1200000\nmedia.m3u8\n"
)
MPD = (
    b'<?xml version="1.0"?><MPD type="dynamic"><Period id="p0">'
    b'<AdaptationSet><Representation id="v0" bandwidth="900000"/></AdaptationSet>'
    b"</Period></MPD>"
)


def ts_bytes():
    return b"\x47" + b"\x00" * 187 + b"\x47" + b"\x00" * 400


# ---------------------------------------------------------------- 键与展开


class TestStreamKey(unittest.TestCase):
    def test_same_url_different_headers_are_distinct(self):
        u = "https://h/live.m3u8"
        self.assertNotEqual(ip.stream_key(u, "UA-A", "https://a/"),
                            ip.stream_key(u, "UA-B", "https://a/"))
        self.assertNotEqual(ip.stream_key(u, "", ""), ip.stream_key(u, "", "https://a/"))

    def test_same_inputs_are_stable(self):
        self.assertEqual(ip.stream_key("https://h/a", "x", "y"),
                         ip.stream_key("https://h/a", "x", "y"))


class TestBuildHeaders(unittest.TestCase):
    def test_default_ua_is_used_when_blank(self):
        self.assertEqual(ip.build_headers("", "")["User-Agent"], ip.DEFAULT_UA)

    def test_custom_ua_and_referer_are_forwarded(self):
        h = ip.build_headers("ExoPlayer/9", "https://player.example/")
        self.assertEqual(h["User-Agent"], "ExoPlayer/9")
        self.assertEqual(h["Referer"], "https://player.example/")

    def test_no_referer_key_when_blank(self):
        self.assertNotIn("Referer", ip.build_headers("UA", ""))


def station(sid, sources, **extra):
    row = {"id": sid, "name": sid, "country": "", "language": "",
           "streamUrl": sources[0]["url"] if sources else "", "sources": sources}
    row.update(extra)
    return row


class TestCollectStreams(unittest.TestCase):
    def test_same_url_different_header_not_merged(self):
        stations = [
            station("iptv:A", [
                {"url": "https://h/a.m3u8", "userAgent": "UA1", "referrer": "https://r1/"},
                {"url": "https://h/b.m3u8"},
            ]),
            station("iptv:B", [
                {"url": "https://h/a.m3u8", "userAgent": "UA2", "referrer": "https://r1/"},
            ]),
        ]
        streams, station_keys = ip.collect_streams(stations)
        self.assertEqual(len(streams), 3)
        self.assertEqual(len(station_keys["iptv:A"]), 2)
        self.assertEqual(len(station_keys["iptv:B"]), 1)
        uas = {(r["url"], r["userAgent"]) for r in streams.values()}
        self.assertIn(("https://h/a.m3u8", "UA1"), uas)
        self.assertIn(("https://h/a.m3u8", "UA2"), uas)

    def test_headers_survive_expansion(self):
        stations = [station("iptv:A", [
            {"url": "https://h/a.m3u8", "userAgent": "UA", "referrer": "https://r/"},
        ])]
        streams, _ = ip.collect_streams(stations)
        row = next(iter(streams.values()))
        self.assertEqual(row["userAgent"], "UA")
        self.assertEqual(row["referrer"], "https://r/")

    def test_shared_url_is_one_stream_with_two_stations(self):
        stations = [
            station("iptv:A", [{"url": "https://h/a.m3u8"}]),
            station("iptv:B", [{"url": "https://h/a.m3u8"}]),
        ]
        streams, _ = ip.collect_streams(stations)
        self.assertEqual(len(streams), 1)
        self.assertEqual(len(next(iter(streams.values()))["stations"]), 2)

    def test_non_http_urls_are_skipped(self):
        streams, keys = ip.collect_streams([station("iptv:A", [{"url": "rtmp://h/x"}])])
        self.assertEqual(streams, {})
        self.assertEqual(keys["iptv:A"], [])

    def test_duplicate_source_within_one_station_is_deduped(self):
        streams, keys = ip.collect_streams([station("iptv:A", [
            {"url": "https://h/a.m3u8"}, {"url": "https://h/a.m3u8"},
        ])])
        self.assertEqual(len(streams), 1)
        self.assertEqual(len(keys["iptv:A"]), 1)


# ---------------------------------------------------------------- 三态判定


class TestClassifyResponse(unittest.TestCase):
    def test_404_and_410_are_gone(self):
        for code in (404, 410):
            self.assertEqual(ip.classify_response(code, "", b"")["verdict"], ip.GONE)

    def test_auth_throttle_and_5xx_are_unknown(self):
        for code in (401, 403, 429, 500, 502, 503):
            v = ip.classify_response(code, "", b"")
            self.assertEqual(v["verdict"], ip.UNKNOWN, code)

    def test_timeout_is_never_gone(self):
        session = FakeSession()
        session.get = lambda *a, **k: (_ for _ in ()).throw(requests.Timeout())
        opts = ip.ProbeOptions(session=session)
        v = ip.probe_stream({"url": "https://h/a.m3u8"}, opts)
        self.assertEqual(v["verdict"], ip.UNKNOWN)
        self.assertEqual(v["why"], "timeout")

    def test_html_error_page_with_200_is_gone(self):
        v = ip.classify_response(200, "text/html", b"<html><body>not found</body></html>")
        self.assertEqual(v["verdict"], ip.GONE)

    def test_json_app_error_with_200_is_unknown(self):
        v = ip.classify_response(200, "application/json",
                                 b'{"msg":"stream offline","ret":2011}')
        self.assertEqual(v["verdict"], ip.UNKNOWN)
        self.assertEqual(v["why"], "app-error-body")

    def test_media_playlist_with_segments_is_live(self):
        v = ip.classify_response(200, "application/vnd.apple.mpegurl", MEDIA_PLAYLIST)
        self.assertEqual(v["verdict"], ip.LIVE)
        self.assertIn("segments=2", v["detail"])

    def test_empty_playlist_is_gone(self):
        self.assertEqual(ip.classify_response(200, "", b"#EXTM3U\n")["verdict"], ip.GONE)

    def test_master_playlist_asks_for_drill(self):
        v = ip.classify_response(200, "", MASTER_PLAYLIST,
                                 base_url="https://h/live/master.m3u8")
        self.assertEqual(v["why"], "master-drill")
        self.assertEqual(v["drill"], "https://h/live/media.m3u8")

    def test_dash_mpd_with_representation_is_live(self):
        v = ip.classify_response(200, "application/dash+xml", MPD)
        self.assertEqual(v["verdict"], ip.LIVE)
        self.assertEqual(v["why"], "dash-mpd")

    def test_ts_sync_bytes_are_live(self):
        v = ip.classify_response(200, "video/mp2t", ts_bytes())
        self.assertEqual(v["verdict"], ip.LIVE)
        self.assertEqual(v["why"], "media-magic")

    def test_mp4_ftyp_is_live(self):
        v = ip.classify_response(200, "video/mp4", b"\x00\x00\x00\x18ftypisom" + b"\x00" * 200)
        self.assertEqual(v["verdict"], ip.LIVE)

    def test_undetermined_media_is_unknown_not_gone(self):
        v = ip.classify_response(200, "video/mp2t", b"\x01\x02\x03" * 100)
        self.assertEqual(v["verdict"], ip.UNKNOWN)
        self.assertEqual(v["why"], "media-undetermined")

    def test_empty_body_is_gone(self):
        self.assertEqual(ip.classify_response(200, "", b"")["verdict"], ip.GONE)


class TestProbeStreamDrill(unittest.TestCase):
    def test_master_is_drilled_once_with_same_headers(self):
        session = FakeSession(
            FakeResponse(200, "application/vnd.apple.mpegurl", MASTER_PLAYLIST),
            FakeResponse(200, "application/vnd.apple.mpegurl", MEDIA_PLAYLIST),
        )
        opts = ip.ProbeOptions(session=session)
        v = ip.probe_stream({"url": "https://h/live/master.m3u8",
                             "userAgent": "UA", "referrer": "https://r/"}, opts)
        self.assertEqual(v["verdict"], ip.LIVE)
        self.assertEqual(len(session.calls), 2)
        self.assertEqual(session.calls[1][0], "https://h/live/media.m3u8")
        self.assertEqual(session.calls[0][1]["headers"]["Referer"], "https://r/")
        self.assertEqual(session.calls[1][1]["headers"]["Referer"], "https://r/")

    def test_variant_404_is_gone(self):
        session = FakeSession(
            FakeResponse(200, "", MASTER_PLAYLIST),
            FakeResponse(404, "", b""),
        )
        v = ip.probe_stream({"url": "https://h/live/master.m3u8"},
                            ip.ProbeOptions(session=session))
        self.assertEqual(v["verdict"], ip.GONE)


class TestStationVerdict(unittest.TestCase):
    def test_any_live_wins(self):
        self.assertEqual(ip.station_verdict([ip.GONE, ip.LIVE]), ip.LIVE)

    def test_all_gone_is_gone(self):
        self.assertEqual(ip.station_verdict([ip.GONE, ip.GONE]), ip.GONE)

    def test_unknown_or_untested_blocks_drop(self):
        self.assertEqual(ip.station_verdict([ip.GONE, ip.UNKNOWN]), ip.UNKNOWN)
        self.assertEqual(ip.station_verdict([ip.GONE, None]), ip.UNKNOWN)

    def test_no_sources_is_unknown(self):
        self.assertEqual(ip.station_verdict([]), ip.UNKNOWN)


# ---------------------------------------------------------------- 断点续跑


class TestState(unittest.TestCase):
    def test_roundtrip_keeps_entries(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "state.json"
            assets = Path(d) / "assets.json"
            fp = "a" * 64
            state = ip.new_state(fp, assets)
            state["entries"]["k1"] = {"verdict": ip.LIVE, "why": "media-playlist"}
            ip.save_state(p, state)
            loaded = ip.load_state(p, fp, assets)
            self.assertEqual(loaded["entries"]["k1"]["verdict"], ip.LIVE)

    def test_fingerprint_mismatch_is_refused(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "state.json"
            assets = Path(d) / "assets.json"
            ip.save_state(p, ip.new_state("a" * 64, assets))
            with self.assertRaises(SystemExit):
                ip.load_state(p, "b" * 64, assets)

    def test_force_new_clears_stale_entries(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "state.json"
            assets = Path(d) / "assets.json"
            state = ip.new_state("a" * 64, assets)
            state["entries"]["k1"] = {"verdict": ip.LIVE}
            ip.save_state(p, state)
            fresh = ip.load_state(p, "b" * 64, assets, force_new=True)
            self.assertEqual(fresh["entries"], {})

    def test_corrupt_state_is_refused(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "state.json"
            p.write_text("{broken", encoding="utf-8")
            with self.assertRaises(SystemExit):
                ip.load_state(p, "a" * 64, Path(d) / "a.json")

    def test_rebase_keeps_results_when_stream_set_unchanged(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "state.json"
            assets = Path(d) / "assets.json"
            state = ip.new_state("a" * 64, assets)
            state["entries"] = {"k1": {"verdict": ip.LIVE}, "k2": {"verdict": ip.GONE}}
            ip.save_state(p, state)
            loaded = ip.load_state(p, "b" * 64, assets, rebase_keys={"k1", "k2"})
            self.assertEqual(loaded["assetsSha256"], "b" * 64)
            self.assertEqual(loaded["rebasedFrom"], "a" * 64)
            self.assertEqual(len(loaded["entries"]), 2)

    def test_rebase_refused_when_any_stream_added_or_removed(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "state.json"
            assets = Path(d) / "assets.json"
            state = ip.new_state("a" * 64, assets)
            state["entries"] = {"k1": {"verdict": ip.LIVE}, "k2": {"verdict": ip.GONE}}
            ip.save_state(p, state)
            for keys in ({"k1"}, {"k1", "k2", "k3"}):
                with self.assertRaises(SystemExit):
                    ip.load_state(p, "b" * 64, assets, rebase_keys=keys)

    def test_without_rebase_fingerprint_mismatch_still_refused(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "state.json"
            assets = Path(d) / "assets.json"
            ip.save_state(p, ip.new_state("a" * 64, assets))
            with self.assertRaises(SystemExit):
                ip.load_state(p, "b" * 64, assets)


# ---------------------------------------------------------------- 报告与精简


def fixture():
    stations = [
        station("iptv:A", [
            {"url": "https://h/a1.m3u8"}, {"url": "https://h/a2.m3u8"},
        ], country="中国", language="zho"),
        station("iptv:B", [
            {"url": "https://h/b1.m3u8"}, {"url": "https://h/b2.m3u8"},
        ], country="美国", language="eng"),
        station("iptv:C", [{"url": "https://h/c1.m3u8"}], language=""),
    ]
    streams, keys = ip.collect_streams(stations)
    entries = {}
    for st, verdicts in ((stations[0], [ip.GONE, ip.LIVE]),
                         (stations[1], [ip.GONE, ip.GONE]),
                         (stations[2], [ip.UNKNOWN])):
        for key, verdict in zip(keys[st["id"]], verdicts):
            entries[key] = {"verdict": verdict, "why": "test"}
    return stations, streams, keys, entries


class TestSummarize(unittest.TestCase):
    def test_station_counts(self):
        stations, streams, keys, entries = fixture()
        s = ip.summarize(stations, keys, streams, entries)
        self.assertEqual(s["stationCounts"],
                         {ip.LIVE: 1, ip.GONE: 1, ip.UNKNOWN: 1})
        self.assertEqual(s["byVerdict"][ip.GONE], ["iptv:B"])
        self.assertEqual(s["streamTested"], 5)
        self.assertEqual(s["streamTotal"], 5)

    def test_untested_stream_keeps_station_out_of_blacklist(self):
        stations, streams, keys, entries = fixture()
        del entries[keys["iptv:B"][1]]
        s = ip.summarize(stations, keys, streams, entries)
        self.assertEqual(s["stationCounts"][ip.GONE], 0)
        self.assertEqual(s["stationCounts"][ip.UNKNOWN], 2)

    def test_report_states_the_no_false_positive_rule(self):
        stations, streams, keys, entries = fixture()
        text = ip.render_report(ip.summarize(stations, keys, streams, entries), "f" * 64)
        self.assertIn("超时≠源已死", text)
        self.assertIn("黑名单候选", text)


class TestPrune(unittest.TestCase):
    def test_only_all_gone_station_is_dropped(self):
        stations, streams, keys, entries = fixture()
        out, stats = ip.build_pruned(stations, keys, entries)
        self.assertEqual([s["id"] for s in out], ["iptv:A", "iptv:C"])
        self.assertEqual(stats["droppedStations"], 1)
        # 只有 A 的失效源被删；B 是整台剔除，不计入逐源删除
        self.assertEqual(stats["droppedSources"], 1)

    def test_live_source_is_promoted_to_primary(self):
        stations, streams, keys, entries = fixture()
        out, _ = ip.build_pruned(stations, keys, entries)
        a = out[0]
        self.assertEqual(a["streamUrl"], "https://h/a2.m3u8")
        self.assertEqual(len(a["sources"]), 1)

    def test_original_stations_not_mutated(self):
        stations, streams, keys, entries = fixture()
        ip.build_pruned(stations, keys, entries)
        self.assertEqual(stations[0]["streamUrl"], "https://h/a1.m3u8")
        self.assertEqual(len(stations[0]["sources"]), 2)

    def test_unknown_station_is_kept_with_its_source(self):
        stations, streams, keys, entries = fixture()
        out, _ = ip.build_pruned(stations, keys, entries)
        self.assertEqual(out[1]["id"], "iptv:C")
        self.assertEqual(len(out[1]["sources"]), 1)

    def test_refuses_to_write_assets(self):
        with self.assertRaises(SystemExit):
            ip.main(["prune", "--out", str(ip.DEFAULT_ASSETS)])
        with self.assertRaises(SystemExit):
            ip.main(["prune", "--out", str(ip.DEFAULT_ASSETS.parent / "x.json")])


class TestHostLimiter(unittest.TestCase):
    def test_runs_the_callable_and_returns_result(self):
        self.assertEqual(ip.HostLimiter(2).run("https://h/a", lambda: 42), 42)


class TestFastMode(unittest.TestCase):
    """宽松口径的边界：快，但 200/403/5xx 的错误页都会被算成有效。"""

    def test_any_data_is_live_even_html_error_page(self):
        v = ip.classify_fast_response(200, "text/html", b"<html>channel gone</html>")
        self.assertEqual(v["verdict"], ip.LIVE)
        self.assertEqual(v["why"], ip.FAST_ANY_DATA)

    def test_auth_error_page_is_live_by_design(self):
        v = ip.classify_fast_response(403, "text/html", b"<html>forbidden</html>")
        self.assertEqual(v["verdict"], ip.LIVE)
        self.assertIn("status=403", v["detail"])

    def test_404_and_410_still_gone(self):
        self.assertEqual(ip.classify_fast_response(404, "", b"not found")["verdict"], ip.GONE)
        self.assertEqual(ip.classify_fast_response(410, "", b"")["verdict"], ip.GONE)

    def test_empty_body_is_unknown(self):
        self.assertEqual(ip.classify_fast_response(200, "", b"")["verdict"], ip.UNKNOWN)

    def test_fast_skips_master_drill(self):
        session = FakeSession(FakeResponse(200, "", MASTER_PLAYLIST))
        v = ip.probe_stream({"url": "https://h/live/master.m3u8"},
                            ip.ProbeOptions(session=session, fast=True))
        self.assertEqual(v["verdict"], ip.LIVE)
        self.assertEqual(len(session.calls), 1)

    def test_strict_mode_still_drills(self):
        session = FakeSession(
            FakeResponse(200, "", MASTER_PLAYLIST),
            FakeResponse(200, "", MEDIA_PLAYLIST),
        )
        v = ip.probe_stream({"url": "https://h/live/master.m3u8"},
                            ip.ProbeOptions(session=session))
        self.assertEqual(v["verdict"], ip.LIVE)
        self.assertEqual(len(session.calls), 2)

    def test_report_flags_loose_verdicts_and_error_pages(self):
        stations, streams, keys, entries = fixture()
        for e in entries.values():
            if e["verdict"] == ip.LIVE:
                e["why"] = ip.FAST_ANY_DATA
                e["detail"] = "status=403 ct=text/html bytes=64"
        s = ip.summarize(stations, keys, streams, entries)
        self.assertEqual(s["fastCounts"]["looseLive"], 1)
        self.assertEqual(s["fastCounts"]["errorPage"], 1)
        self.assertIn("【宽松口径】", ip.render_report(s, "f" * 64))


class TestEndToEndLocalServer(unittest.TestCase):
    """真实 requests 路径 + CLI 全链路。只用 loopback，不访问外网、不碰真实 assets。

    纯函数单测证明不了 _fetch 的 stream/raw 读取在真 HTTP 上会不会卡住，
    也证明不了断点续跑与三个子命令能否串起来跑通，所以留这一条。
    """

    @classmethod
    def setUpClass(cls):
        from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                path = self.path.split("?")[0]
                if path == "/dead.m3u8":
                    self.send_response(404)
                    self.end_headers()
                    return
                if path == "/html.m3u8":
                    body, ct = b"<html>channel gone</html>", "text/html"
                elif path == "/master.m3u8":
                    body, ct = (b"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n/live.m3u8\n",
                                "application/vnd.apple.mpegurl")
                elif path == "/ts":
                    body, ct = ts_bytes(), "video/mp2t"
                else:
                    body, ct = MEDIA_PLAYLIST, "application/vnd.apple.mpegurl"
                self.send_response(200)
                self.send_header("Content-Type", ct)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        cls.httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        cls.httpd.daemon_threads = True
        cls.port = cls.httpd.server_address[1]
        cls.thread = threading.Thread(target=cls.httpd.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()

    def _quiet(self, argv):
        with contextlib.redirect_stderr(io.StringIO()), contextlib.redirect_stdout(io.StringIO()):
            return ip.main(argv)

    def test_probe_report_prune_pipeline(self):
        base = f"http://127.0.0.1:{self.port}"
        stations = [
            station("iptv:live1", [{"url": base + "/live.m3u8"}],
                    country="中国", language="zho"),
            station("iptv:master", [{"url": base + "/master.m3u8"}]),
            station("iptv:ts", [{"url": base + "/ts", "userAgent": "UA",
                                 "referrer": base + "/"}]),
            station("iptv:dead", [{"url": base + "/dead.m3u8"},
                                  {"url": base + "/html.m3u8"}]),
        ]
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            assets = root / "iptv_channels.json"
            state = root / "state.json"
            assets.write_text(json.dumps({"stations": stations}, ensure_ascii=False),
                              encoding="utf-8")
            common = ["--assets", str(assets), "--state", str(state)]

            rc = self._quiet(["probe", *common, "--workers", "2", "--timeout", "3",
                              "--no-env-proxy"])
            self.assertEqual(rc, 0)
            entries = json.loads(state.read_text(encoding="utf-8"))["entries"]
            self.assertEqual(len(entries), 5)
            counts = {v: sum(1 for e in entries.values() if e["verdict"] == v)
                      for v in (ip.LIVE, ip.GONE, ip.UNKNOWN)}
            self.assertEqual(counts, {ip.LIVE: 3, ip.GONE: 2, ip.UNKNOWN: 0})

            # 断点续跑：第二次不该再发起任何请求
            rc = self._quiet(["probe", *common, "--workers", "2", "--timeout", "3",
                              "--no-env-proxy"])
            self.assertEqual(rc, 0)
            self.assertEqual(len(json.loads(state.read_text(encoding="utf-8"))["entries"]), 5)

            whitelist = root / "white.json"
            blacklist = root / "black.json"
            report = root / "report.txt"
            rc = self._quiet(["report", *common, "--whitelist", str(whitelist),
                              "--blacklist", str(blacklist), "--report", str(report)])
            self.assertEqual(rc, 0)
            self.assertEqual(json.loads(whitelist.read_text(encoding="utf-8"))["stationIds"],
                             ["iptv:live1", "iptv:master", "iptv:ts"])
            self.assertEqual(json.loads(blacklist.read_text(encoding="utf-8"))["stationIds"],
                             ["iptv:dead"])
            self.assertIn("超时≠源已死", report.read_text(encoding="utf-8"))

            pruned = root / "pruned.json"
            rc = self._quiet(["prune", *common, "--out", str(pruned)])
            self.assertEqual(rc, 0)
            out = json.loads(pruned.read_text(encoding="utf-8"))["stations"]
            self.assertEqual([s["id"] for s in out],
                             ["iptv:live1", "iptv:master", "iptv:ts"])
            # 原始 assets 一个字节都没动
            self.assertEqual(len(json.loads(assets.read_text(encoding="utf-8"))["stations"]), 4)

            # 宽松口径走真实 HTTP：HTML 错页算 live、404 仍算 gone、不下钻
            fast_state = root / "fast_state.json"
            rc = self._quiet(["probe", "--fast", "--assets", str(assets),
                              "--state", str(fast_state), "--workers", "2",
                              "--timeout", "3", "--no-env-proxy"])
            self.assertEqual(rc, 0)
            fast = json.loads(fast_state.read_text(encoding="utf-8"))
            self.assertEqual(len(fast["entries"]), 5)
            self.assertEqual(fast["modes"], ["fast"])
            self.assertTrue(all(e["mode"] == "fast" for e in fast["entries"].values()))
            self.assertEqual(sum(1 for e in fast["entries"].values()
                                 if e["verdict"] == ip.GONE), 1)


if __name__ == "__main__":
    unittest.main()
