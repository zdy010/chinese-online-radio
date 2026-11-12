"""iptv_intersect 的离线单测：四象限分类、UNKNOWN 红线、口径校验与端到端。

最关键的一条：只有两轮都明确 GONE 才能判死。
超时/403/5xx/未测、以及只在单边网络通过的，一律不许进「确证不可用」。
"""
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import iptv_intersect as ix
import iptv_probe as ip


def entry(verdict):
    return {"verdict": verdict, "why": "test", "detail": "", "mode": "strict"}


def station(sid, urls):
    return {
        "id": sid, "name": sid, "altNames": [], "category": "general",
        "streamUrl": urls[0], "sources": [{"url": u, "label": "", "quality": "",
                                           "userAgent": "", "referrer": ""} for u in urls],
        "website": "", "description": "", "country": "", "language": "",
        "region": "", "nsfw": False, "logoUrl": "", "tags": [],
    }


def fixture():
    stations = [
        station("iptv:A", ["https://h/a.m3u8"]),
        station("iptv:B", ["https://h/b1.m3u8", "https://h/b2.m3u8"]),
        station("iptv:C", ["https://h/c.m3u8"]),
        station("iptv:D", ["https://h/d.m3u8"]),
        station("iptv:E", ["https://h/e.m3u8"]),
    ]
    meta, keys = ip.collect_streams(stations)
    direct = {
        keys["iptv:A"][0]: entry(ip.LIVE),
        keys["iptv:B"][0]: entry(ip.LIVE),
        keys["iptv:B"][1]: entry(ip.LIVE),
        keys["iptv:C"][0]: entry(ip.LIVE),
        keys["iptv:D"][0]: entry(ip.GONE),
        keys["iptv:E"][0]: entry(ip.UNKNOWN),
    }
    proxy = {
        keys["iptv:A"][0]: entry(ip.LIVE),
        keys["iptv:B"][0]: entry(ip.LIVE),
        keys["iptv:B"][1]: entry(ip.UNKNOWN),
        keys["iptv:C"][0]: entry(ip.UNKNOWN),
        keys["iptv:D"][0]: entry(ip.GONE),
        keys["iptv:E"][0]: entry(ip.UNKNOWN),
    }
    return stations, meta, keys, direct, proxy


class TestClassifyStream(unittest.TestCase):
    def test_both_live(self):
        self.assertEqual(ix.classify_stream(entry(ip.LIVE), entry(ip.LIVE)),
                         ix.BOTH_LIVE)

    def test_single_side_live(self):
        self.assertEqual(ix.classify_stream(entry(ip.LIVE), entry(ip.UNKNOWN)),
                         ix.DIRECT_ONLY)
        self.assertEqual(ix.classify_stream(entry(ip.GONE), entry(ip.LIVE)),
                         ix.PROXY_ONLY)

    def test_both_gone_is_the_only_death_verdict(self):
        self.assertEqual(ix.classify_stream(entry(ip.GONE), entry(ip.GONE)),
                         ix.BOTH_GONE)

    def test_unknown_is_never_dead(self):
        for dv, pv in ((ip.UNKNOWN, ip.UNKNOWN), (ip.GONE, ip.UNKNOWN),
                       (ip.UNKNOWN, ip.GONE), (ip.LIVE, None), (None, None),
                       (None, ip.GONE)):
            d = entry(dv) if dv else None
            p = entry(pv) if pv else None
            self.assertEqual(ix.classify_stream(d, p), ix.UNDETERMINED, (dv, pv))


class TestClassifyStation(unittest.TestCase):
    def test_solid_and_usable(self):
        self.assertEqual(ix.classify_station([ix.BOTH_LIVE]), ix.SOLID)
        self.assertEqual(ix.classify_station([ix.BOTH_LIVE, ix.DIRECT_ONLY]),
                         ix.USABLE)

    def test_environment_only_and_dead(self):
        self.assertEqual(ix.classify_station([ix.DIRECT_ONLY, ix.UNDETERMINED]),
                         ix.ENV_ONLY)
        self.assertEqual(ix.classify_station([ix.BOTH_GONE, ix.BOTH_GONE]),
                         ix.BOTH_GONE)

    def test_undetermined_blocks_any_drop(self):
        self.assertEqual(ix.classify_station([ix.BOTH_GONE, ix.UNDETERMINED]),
                         ix.UNDETERMINED)
        self.assertEqual(ix.classify_station([]), ix.UNDETERMINED)


class TestIntersect(unittest.TestCase):
    def setUp(self):
        self.stations, self.meta, self.keys, self.direct, self.proxy = fixture()

    def _result(self):
        d = {"modes": ["strict"], "entries": self.direct}
        p = {"modes": ["strict"], "entries": self.proxy}
        return ix.intersect(self.stations, self.keys, self.meta, d, p,
                            Path("d.json"), Path("p.json"), "f" * 64)

    def test_stream_four_quadrants(self):
        sc = self._result()["streamCounts"]
        self.assertEqual(sc[ix.BOTH_LIVE], 2)
        self.assertEqual(sc[ix.DIRECT_ONLY], 2)
        self.assertEqual(sc[ix.PROXY_ONLY], 0)
        self.assertEqual(sc[ix.BOTH_GONE], 1)
        self.assertEqual(sc[ix.UNDETERMINED], 1)

    def test_station_classes(self):
        tc = self._result()["stationCounts"]
        self.assertEqual(tc[ix.SOLID], 1)
        self.assertEqual(tc[ix.USABLE], 1)
        self.assertEqual(tc[ix.ENV_ONLY], 1)
        self.assertEqual(tc[ix.BOTH_GONE], 1)
        self.assertEqual(tc[ix.UNDETERMINED], 1)

    def test_coverage(self):
        self.assertEqual(self._result()["coverage"]["bothTested"], 6)


class TestLoadProbe(unittest.TestCase):
    def _write(self, root, name, modes, fp="f" * 64):
        p = root / name
        p.write_text(json.dumps({"modes": modes, "assetsSha256": fp,
                                 "entries": {}}), encoding="utf-8")
        return p

    def test_rejects_fast_mode(self):
        with tempfile.TemporaryDirectory() as d:
            p = self._write(Path(d), "x.json", ["fast"])
            with self.assertRaises(SystemExit):
                ix.load_probe(p, "直连", "f" * 64)

    def test_rejects_fingerprint_mismatch(self):
        with tempfile.TemporaryDirectory() as d:
            p = self._write(Path(d), "x.json", ["strict"])
            with self.assertRaises(SystemExit):
                ix.load_probe(p, "直连", "a" * 64)

    def test_accepts_strict(self):
        with tempfile.TemporaryDirectory() as d:
            p = self._write(Path(d), "x.json", ["strict"])
            self.assertEqual(ix.load_probe(p, "直连", "f" * 64)["modes"],
                             ["strict"])


class TestCli(unittest.TestCase):
    def test_main_writes_report_without_touching_assets(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            stations, meta, keys, direct, proxy = fixture()
            assets = root / "assets.json"
            assets.write_text(json.dumps({"stations": stations},
                                         ensure_ascii=False), encoding="utf-8")
            fp = ip.sha256_file(assets)
            before = assets.read_bytes()
            common = {"schemaVersion": 1, "assetsSha256": fp, "updatedAt": "t"}
            dp = root / "direct.json"
            pp = root / "proxy.json"
            dp.write_text(json.dumps(dict(common, modes=["strict"],
                                         entries=direct)), encoding="utf-8")
            pp.write_text(json.dumps(dict(common, modes=["strict"],
                                         entries=proxy)), encoding="utf-8")
            out, report = root / "out.json", root / "report.txt"
            rc = ix.main(["--assets", str(assets), "--direct", str(dp),
                          "--proxy", str(pp), "--out", str(out),
                          "--report", str(report)])
            self.assertEqual(rc, 0)
            self.assertEqual(assets.read_bytes(), before)
            result = json.loads(out.read_text(encoding="utf-8"))
            self.assertEqual(result["streamCounts"][ix.BOTH_LIVE], 2)
            text = report.read_text(encoding="utf-8")
            self.assertIn("UNKNOWN", text)
            self.assertIn("两轮都验到内容", text)


if __name__ == "__main__":
    unittest.main()
