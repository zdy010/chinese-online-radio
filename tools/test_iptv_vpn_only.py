"""iptv_vpn_only 的离线单测：台级判定、两轮覆盖校验与端到端写标。

最关键的一条：一台只要有一个源在直连下能看，就不能标「需 VPN」。
"""
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))

import iptv_probe as ip
import iptv_vpn_only as vo


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
    streams, keys = ip.collect_streams(stations)
    pairs = {
        keys["iptv:A"][0]: (ip.UNKNOWN, ip.LIVE),   # 需 VPN
        keys["iptv:B"][0]: (ip.LIVE, ip.LIVE),      # 直连可看，不标
        keys["iptv:B"][1]: (ip.UNKNOWN, ip.LIVE),
        keys["iptv:C"][0]: (ip.LIVE, ip.LIVE),
        keys["iptv:D"][0]: (ip.UNKNOWN, ip.UNKNOWN),
        keys["iptv:E"][0]: (ip.LIVE, ip.UNKNOWN),
    }
    mk = lambda i: {"modes": ["strict"], "assetsSha256": "f" * 64,
                    "entries": {k: {"verdict": v[i], "why": "test", "detail": "",
                                    "mode": "strict", "elapsed": 0.1}
                                for k, v in pairs.items()}}
    return stations, streams, keys, mk(0), mk(1)


class TestBuildIds(unittest.TestCase):
    def setUp(self):
        self.stations, self.streams, self.keys, self.d, self.p = fixture()

    def test_only_station_with_no_direct_live_is_marked(self):
        self.assertEqual(vo.build_ids(self.stations, self.keys, self.d, self.p),
                         ["iptv:A"])

    def test_one_direct_live_source_blocks_marking(self):
        b1 = self.keys["iptv:B"][0]
        self.d["entries"][b1]["verdict"] = ip.UNKNOWN
        self.d["entries"][self.keys["iptv:B"][1]]["verdict"] = ip.LIVE
        self.assertNotIn("iptv:B", vo.build_ids(self.stations, self.keys, self.d, self.p))

    def test_coverage_rejects_unprobed_stream(self):
        self.d["entries"].pop(self.keys["iptv:C"][0])
        with self.assertRaises(SystemExit):
            vo.assert_covered(self.streams, self.d, self.p)

    def test_coverage_accepts_full(self):
        vo.assert_covered(self.streams, self.d, self.p)


class TestVerify(unittest.TestCase):
    def test_detects_missing_and_extra_marks(self):
        stations, _, _, _, _ = fixture()
        doc = {"stationIds": ["iptv:A"]}
        self.assertTrue(vo.verify_doc(doc, stations))  # 没打标
        marked = [dict(s, vpnOnly=True) if s["id"] == "iptv:A" else s for s in stations]
        self.assertEqual(vo.verify_doc(doc, marked), [])
        extra = [dict(s, vpnOnly=True) if s["id"] in ("iptv:A", "iptv:C") else s
                 for s in stations]
        self.assertTrue(vo.verify_doc(doc, extra))

    def test_report_unknown_station_id(self):
        stations, _, _, _, _ = fixture()
        problems = vo.verify_doc({"stationIds": ["iptv:gone"]}, stations)
        self.assertTrue(any("已不在清单" in p for p in problems))


class TestCli(unittest.TestCase):
    def test_build_apply_verify_end_to_end(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            stations, streams, keys, dstate, pstate = fixture()
            assets = root / "assets.json"
            dp, pp, lp = root / "d.json", root / "p.json", root / "list.json"
            assets.write_text(json.dumps({"stations": stations}, ensure_ascii=False),
                              encoding="utf-8")
            fp = ip.sha256_file(assets)
            for doc, path in ((dstate, dp), (pstate, pp)):
                doc["assetsSha256"] = fp
                path.write_text(json.dumps(doc), encoding="utf-8")
            common = ["--assets", str(assets), "--list", str(lp),
                      "--direct", str(dp), "--proxy", str(pp)]
            self.assertEqual(vo.main(["build", *common]), 0)
            self.assertEqual(json.loads(lp.read_text(encoding="utf-8"))["count"], 1)
            with patch.object(vo, "BACKUP_DIR", root / "backup"):
                self.assertEqual(vo.main(["apply", *common]), 0)
                self.assertEqual(vo.main(["verify", *common]), 0)
                after = json.loads(assets.read_text(encoding="utf-8"))["stations"]
                self.assertEqual([s["id"] for s in after if s.get("vpnOnly")], ["iptv:A"])
                # 幂等：二次 apply 不重复标记、不报错
                self.assertEqual(vo.main(["apply", *common]), 0)
                self.assertEqual(vo.main(["verify", *common]), 0)


if __name__ == "__main__":
    unittest.main()
