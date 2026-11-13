"""iptv_exclusions 的离线单测：准入只认全源 GONE，剔除可复现，清单与名单零交集。

最关键的三件事：
- 含 403/超时/UNKNOWN 的台绝不进名单；
- apply 幂等，二次执行不会把已剔除的台从名单里抹掉；
- 写回 assets 前的数据闸门真的会拦住残缺清单。
"""
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))

import iptv_exclusions as ex
import iptv_probe as ip

CHECKED_AT = "2026-11-12T00:00:00+00:00"


def station(sid, urls, alt=None, ua=""):
    srcs = [{"url": u, "label": "", "quality": "720p",
             "userAgent": ua, "referrer": ""} for u in urls]
    return {
        "id": sid, "name": sid, "altNames": alt or [], "category": "general",
        "streamUrl": urls[0], "sources": srcs, "website": "", "description": "",
        "country": "中国", "language": "zho", "region": "北京",
        "nsfw": False, "logoUrl": "", "tags": ["general"],
    }


def fixture():
    stations = [
        station("iptv:A", ["https://h/a.m3u8"]),
        station("iptv:B", ["https://h/b1.m3u8", "https://h/b2.m3u8"]),
        station("iptv:C", ["https://h/c.m3u8"]),
        station("iptv:D", ["https://h/d.m3u8"]),
        station("iptv:E", ["https://h/e1.m3u8", "https://h/e2.m3u8"]),
    ]
    verdicts = {
        "iptv:A": [ip.GONE],
        "iptv:B": [ip.GONE, ip.GONE],
        "iptv:C": [ip.LIVE],
        "iptv:D": [ip.UNKNOWN],
        "iptv:E": [ip.GONE, ip.LIVE],
    }
    streams, keys = ip.collect_streams(stations)
    entries = {}
    for sid, vs in verdicts.items():
        for k, v in zip(keys[sid], vs):
            entries[k] = {
                "verdict": v,
                "why": "fast-http-404" if v == ip.GONE else "fast-any-data",
                "detail": "" if v == ip.GONE else "status=200 ct=video/mp2t bytes=4096",
                "mode": "fast", "elapsed": 0.2,
            }
    state = {
        "schemaVersion": ip.SCHEMA_VERSION, "assetsSha256": "",
        "updatedAt": CHECKED_AT, "modes": ["fast"], "entries": entries,
    }
    return stations, streams, keys, entries, state


def document(entries):
    return {"schemaVersion": ex.SCHEMA_VERSION, "count": len(entries), "entries": entries}


class TestBuildEntries(unittest.TestCase):
    def test_only_all_sources_gone_are_excluded(self):
        stations, streams, keys, entries, state = fixture()
        got = ex.build_entries(stations, keys, streams, entries, state)
        self.assertEqual([e["id"] for e in got], ["iptv:A", "iptv:B"])

    def test_every_stream_has_reason_and_http_status(self):
        stations, streams, keys, entries, state = fixture()
        b = next(e for e in ex.build_entries(stations, keys, streams, entries, state)
                 if e["id"] == "iptv:B")
        self.assertEqual(b["reason"], ex.REASON_ALL_GONE)
        self.assertEqual(len(b["streams"]), 2)
        self.assertEqual(len(b["urls"]), 2)
        self.assertTrue(all(s["verdict"] == ip.GONE for s in b["streams"]))
        self.assertTrue(all(s["httpStatus"] == 404 for s in b["streams"]))
        self.assertIn("HTTP 404", b["reasonDetail"])
        self.assertEqual(b["excludedAt"], CHECKED_AT)

    def test_unknown_and_loose_live_never_enter(self):
        stations, streams, keys, entries, state = fixture()
        ids = {e["id"] for e in ex.build_entries(stations, keys, streams, entries, state)}
        self.assertNotIn("iptv:C", ids)
        self.assertNotIn("iptv:D", ids)
        self.assertNotIn("iptv:E", ids)  # 有一个源活着

    def test_defensive_check_rejects_non_gone_source(self):
        """即使汇总层被改坏说它 GONE，逐源复核也必须拦下。"""
        stations, streams, keys, entries, state = fixture()
        fake = {"byVerdict": {ip.GONE: ["iptv:C"], ip.LIVE: [], ip.UNKNOWN: []}}
        with patch.object(ex.ip, "summarize", return_value=fake):
            with self.assertRaises(SystemExit):
                ex.build_entries(stations, keys, streams, entries, state)


class TestMerge(unittest.TestCase):
    def test_second_run_does_not_wipe_previously_excluded(self):
        stations, streams, keys, entries, state = fixture()
        fresh = ex.build_entries(stations, keys, streams, entries, state)
        once, n1 = ex.merge_entries(document([]), fresh)
        twice, n2 = ex.merge_entries(document(once), [])
        self.assertEqual((n1, n2), (2, 0))
        self.assertEqual([e["id"] for e in twice], ["iptv:A", "iptv:B"])

    def test_new_dead_stations_are_appended(self):
        stations, streams, keys, entries, state = fixture()
        fresh = ex.build_entries(stations, keys, streams, entries, state)
        merged, _ = ex.merge_entries(document([fresh[0]]), fresh[1:])
        self.assertEqual(len(merged), 2)


class TestVerify(unittest.TestCase):
    def test_detects_leaked_id_and_url_before_removal(self):
        stations, streams, keys, entries, state = fixture()
        doc = document(ex.build_entries(stations, keys, streams, entries, state))
        problems = ex.verify_exclusions(doc, stations)
        self.assertTrue(any("已排除频道" in p for p in problems))
        self.assertTrue(any("已排除流地址" in p for p in problems))

    def test_passes_after_removal(self):
        stations, streams, keys, entries, state = fixture()
        doc = document(ex.build_entries(stations, keys, streams, entries, state))
        kept = [s for s in stations if s["id"] not in {e["id"] for e in doc["entries"]}]
        self.assertEqual(ex.verify_exclusions(doc, kept), [])

    def test_bad_entry_shape_is_reported(self):
        stations, streams, keys, entries, state = fixture()
        entries_list = ex.build_entries(stations, keys, streams, entries, state)
        entries_list[0]["urls"] = []
        entries_list[1]["reason"] = "timeout"
        problems = ex.verify_exclusions(document(entries_list), [])
        self.assertTrue(any("没有记录任何流地址" in p for p in problems))
        self.assertTrue(any("reason 不在准入集合" in p for p in problems))


class TestValidatePruned(unittest.TestCase):
    def setUp(self):
        self.stations, self.streams, self.keys, self.entries, self.state = fixture()
        self.doc = document(ex.build_entries(
            self.stations, self.keys, self.streams, self.entries, self.state))
        self.pruned, _ = ip.build_pruned(
            self.stations, self.keys, self.entries, promote_live=False)

    def _validate(self, pruned=None):
        with patch.object(ex, "MIN_STATIONS", 2), \
             patch.object(ex, "MIN_ALIASES", -1), \
             patch.object(ex, "MIN_HEADERS", 0):
            return ex.validate_pruned(
                self.pruned if pruned is None else pruned, self.stations, self.doc)

    def test_clean_prune_passes(self):
        self.assertEqual(self._validate(), [])

    def test_prune_only_deletes_gone_stations_and_sources(self):
        self.assertEqual([s["id"] for s in self.pruned], ["iptv:C", "iptv:D", "iptv:E"])
        e = self.pruned[-1]
        self.assertEqual(e["streamUrl"], "https://h/e2.m3u8")
        self.assertEqual(len(e["sources"]), 1)

    def test_rejects_when_excluded_station_survives(self):
        bad = self.pruned + [self.stations[0]]
        self.assertTrue(any("剔除集合不精确" in p or "已排除频道" in p
                            for p in self._validate(bad)))

    def test_rejects_duplicate_id(self):
        bad = self.pruned + [dict(self.pruned[0])]
        self.assertTrue(any("id 有重复" in p for p in self._validate(bad)))

    def test_rejects_stream_url_mismatch(self):
        bad = [dict(s) for s in self.pruned]
        bad[0] = dict(bad[0], streamUrl="https://h/wrong.m3u8")
        self.assertTrue(any("streamUrl 与首个源不一致" in p for p in self._validate(bad)))

    def test_rejects_below_minimum_count(self):
        with patch.object(ex, "MIN_STATIONS", 99):
            self.assertTrue(any("低于下限" in p
                                for p in ex.validate_pruned(self.pruned, self.stations, self.doc)))


class TestCli(unittest.TestCase):
    def _setup(self, root: Path):
        stations, streams, keys, entries, state = fixture()
        assets = root / "iptv_channels.json"
        state_path = root / "state.json"
        exclusions = root / "exclusions.json"
        assets.write_text(json.dumps({"stations": stations}, ensure_ascii=False),
                          encoding="utf-8")
        fp = ip.sha256_file(assets)
        state["assetsSha256"] = fp
        ip.save_state(state_path, state)
        return assets, state_path, exclusions, stations

    def _common(self, assets, state_path, exclusions):
        return ["--assets", str(assets), "--state", str(state_path),
                "--exclusions", str(exclusions)]

    def test_dry_run_writes_nothing(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            assets, state_path, exclusions, _ = self._setup(root)
            before = assets.read_bytes()
            with patch.object(ex, "MIN_STATIONS", 2), \
                 patch.object(ex, "MIN_ALIASES", -1), \
                 patch.object(ex, "MIN_HEADERS", 0), \
                 patch.object(ex, "MIN_BYTES", 0):
                rc = ex.main(["apply", "--dry-run", *self._common(assets, state_path, exclusions)])
            self.assertEqual(rc, 0)
            self.assertEqual(assets.read_bytes(), before)
            self.assertFalse(exclusions.exists())

    def test_apply_then_verify_and_idempotent_rerun(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            assets, state_path, exclusions, stations = self._setup(root)
            backup_dir = root / "backup"
            with patch.object(ex, "MIN_STATIONS", 2), \
                 patch.object(ex, "MIN_ALIASES", -1), \
                 patch.object(ex, "MIN_HEADERS", 0), \
                 patch.object(ex, "MIN_BYTES", 0), \
                 patch.object(ex, "BACKUP_DIR", backup_dir):
                rc = ex.main(["apply", *self._common(assets, state_path, exclusions)])
                self.assertEqual(rc, 0)
                doc = json.loads(exclusions.read_text(encoding="utf-8"))
                self.assertEqual(doc["count"], 2)
                after = json.loads(assets.read_text(encoding="utf-8"))["stations"]
                self.assertEqual([s["id"] for s in after], ["iptv:C", "iptv:D", "iptv:E"])
                self.assertEqual(ex.main(["verify", *self._common(assets, state_path, exclusions)]), 0)
                self.assertEqual(len(list(backup_dir.glob("assets_backup_*.json"))), 1)

                # state 已对齐新清单：不需要 --rebase 也能继续读
                aligned = ip.load_state(state_path, ip.sha256_file(assets), assets)
                self.assertEqual(aligned["appliedExclusions"]["removedThisRun"], 2)

                # 二次执行不得清空名单、不得再删台
                rc2 = ex.main(["apply", *self._common(assets, state_path, exclusions)])
                self.assertEqual(rc2, 0)
                doc2 = json.loads(exclusions.read_text(encoding="utf-8"))
                self.assertEqual(doc2["count"], 2)
                self.assertEqual(
                    len(json.loads(assets.read_text(encoding="utf-8"))["stations"]), 3)


class TestRepositoryGuard(unittest.TestCase):
    """真正的防回归闸门：盯住仓库里入仓的名单和真实 assets，不是临时 fixture。

    上游重跑同步、有人手工贴回旧清单、或者名单被清空，都会在这里红。
    """

    def test_repository_assets_have_no_excluded_stations(self):
        doc = ex.load_exclusions(ex.DEFAULT_EXCLUSIONS)
        data, _ = ip._load_assets(ex.DEFAULT_ASSETS)
        self.assertGreater(doc.get("count", 0), 0, "排除名单不该是空的")
        self.assertEqual(ex.verify_exclusions(doc, data["stations"]), [])

    def test_every_repository_entry_is_auditable(self):
        doc = ex.load_exclusions(ex.DEFAULT_EXCLUSIONS)
        for e in doc["entries"]:
            self.assertIn(e["reason"], ex.ALLOWED_REASONS, e["id"])
            self.assertTrue(e["urls"], e["id"])
            self.assertTrue(e["reasonDetail"], e["id"])
            self.assertTrue(e["excludedAt"], e["id"])
            self.assertTrue(all(s["verdict"] == ip.GONE for s in e["streams"]), e["id"])


def recheck_fixture():
    """A/B 整台该剔；C 直连救回；D 只剔坏源；E 本来就 live。"""
    stations = [
        station("iptv:A", ["https://h/a.m3u8"]),
        station("iptv:B", ["https://h/b1.m3u8", "https://h/b2.m3u8"]),
        station("iptv:C", ["https://h/c.m3u8"]),
        station("iptv:D", ["https://h/d1.m3u8", "https://h/d2.m3u8"]),
        station("iptv:E", ["https://h/e.m3u8"]),
    ]
    streams, keys = ip.collect_streams(stations)
    base, re = {}, {}

    def put(d, key, verdict, why):
        d[key] = {"verdict": verdict, "why": why, "detail": "",
                  "mode": "fast", "elapsed": 0.1}

    for sid in ("iptv:A", "iptv:B", "iptv:C"):
        for k in keys[sid]:
            put(base, k, ip.UNKNOWN, "timeout")
    put(base, keys["iptv:D"][0], ip.UNKNOWN, "timeout")
    put(base, keys["iptv:D"][1], ip.LIVE, "fast-any-data")
    put(base, keys["iptv:E"][0], ip.LIVE, "fast-any-data")

    put(re, keys["iptv:A"][0], ip.UNKNOWN, "conn-error")
    put(re, keys["iptv:B"][0], ip.UNKNOWN, "timeout")
    put(re, keys["iptv:B"][1], ip.GONE, "fast-http-404")
    put(re, keys["iptv:C"][0], ip.LIVE, "fast-any-data")
    put(re, keys["iptv:D"][0], ip.UNKNOWN, "timeout")
    return stations, streams, keys, base, re


class TestRecheck(unittest.TestCase):
    def setUp(self):
        self.stations, self.streams, self.keys, self.base, self.re = recheck_fixture()

    def test_dead_keys_are_unknown_in_both_networks(self):
        dead = ex.recheck_dead_keys(self.base, self.re)
        expect = {self.keys["iptv:A"][0], *self.keys["iptv:B"],
                  self.keys["iptv:D"][0]}
        self.assertEqual(set(dead), expect)

    def test_direct_live_is_rescued(self):
        dead = ex.recheck_dead_keys(self.base, self.re)
        self.assertNotIn(self.keys["iptv:C"][0], dead)
        self.assertNotIn(self.keys["iptv:E"][0], dead)

    def test_unrechecked_key_is_left_alone(self):
        partial = dict(self.re)
        del partial[self.keys["iptv:A"][0]]
        self.assertNotIn(self.keys["iptv:A"][0],
                         ex.recheck_dead_keys(self.base, partial))

    def test_station_and_stream_entries_are_separated(self):
        dead = ex.recheck_dead_keys(self.base, self.re)
        entries = ex.build_recheck_entries(self.stations, self.keys, self.streams, dead)
        self.assertEqual([e["id"] for e in entries], ["iptv:A", "iptv:B"])
        self.assertTrue(all(e["reason"] == ex.REASON_BOTH_UNREACHABLE for e in entries))
        self.assertTrue(all({p["network"] for p in s["probes"]} == {"proxy-on", "proxy-off"}
                            for e in entries for s in e["streams"]))
        stream_ex = ex.build_stream_exclusions(
            self.streams, dead, {e["id"] for e in entries})
        self.assertEqual([s["url"] for s in stream_ex], ["https://h/d1.m3u8"])

    def test_prune_keeps_station_that_still_has_a_live_source(self):
        dead = ex.recheck_dead_keys(self.base, self.re)
        synthetic = ex.synthetic_entries(self.base, dead)
        pruned, _ = ip.build_pruned(self.stations, self.keys, synthetic,
                                    promote_live=False)
        self.assertEqual([s["id"] for s in pruned], ["iptv:C", "iptv:D", "iptv:E"])
        d = next(s for s in pruned if s["id"] == "iptv:D")
        self.assertEqual([x["url"] for x in d["sources"]], ["https://h/d2.m3u8"])

    def test_original_baseline_is_not_mutated(self):
        dead = ex.recheck_dead_keys(self.base, self.re)
        ex.synthetic_entries(self.base, dead)
        self.assertEqual(self.base[self.keys["iptv:A"][0]]["verdict"], ip.UNKNOWN)


class TestRecheckCli(unittest.TestCase):
    def test_apply_recheck_end_to_end(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            stations, streams, keys, base, re = recheck_fixture()
            assets, state_p = root / "a.json", root / "s.json"
            recheck_p, excl = root / "r.json", root / "e.json"
            assets.write_text(json.dumps({"stations": stations}, ensure_ascii=False),
                              encoding="utf-8")
            fp = ip.sha256_file(assets)
            common = {"schemaVersion": 1, "assetsSha256": fp,
                      "updatedAt": CHECKED_AT, "modes": ["fast"]}
            ip.save_state(state_p, dict(common, entries=base))
            ip.save_state(recheck_p, dict(common, entries=re))
            argv = ["apply", "--recheck", str(recheck_p), "--assets", str(assets),
                    "--state", str(state_p), "--exclusions", str(excl)]
            with patch.object(ex, "MIN_STATIONS", 2), \
                 patch.object(ex, "MIN_ALIASES", -1), \
                 patch.object(ex, "MIN_HEADERS", 0), \
                 patch.object(ex, "MIN_BYTES", 0), \
                 patch.object(ex, "BACKUP_DIR", root / "backup"):
                self.assertEqual(ex.main(argv), 0)
                doc = json.loads(excl.read_text(encoding="utf-8"))
                self.assertEqual(doc["count"], 2)
                self.assertEqual(doc["countStreams"], 1)
                self.assertEqual(doc["entries"][0]["reason"],
                                 ex.REASON_BOTH_UNREACHABLE)
                after = json.loads(assets.read_text(encoding="utf-8"))["stations"]
                self.assertEqual([s["id"] for s in after], ["iptv:C", "iptv:D", "iptv:E"])
                self.assertEqual(ex.main(["verify", "--assets", str(assets),
                                          "--state", str(state_p),
                                          "--exclusions", str(excl)]), 0)
                # 直连救回的源，state 里不再是 UNKNOWN
                aligned = ip.load_state(state_p, ip.sha256_file(assets), assets)
                self.assertEqual(aligned["entries"][keys["iptv:C"][0]]["verdict"], ip.LIVE)


def strict_entry(verdict):
    return {"verdict": verdict,
            "why": "http-404" if verdict == ip.GONE else "media-playlist",
            "detail": "", "mode": "strict", "elapsed": 0.2}


def intersect_fixture(fp):
    stations = [
        station("iptv:A", ["https://h/a.m3u8"]),
        station("iptv:B", ["https://h/b1.m3u8", "https://h/b2.m3u8"]),
        station("iptv:C", ["https://h/c.m3u8"]),
    ]
    streams, keys = ip.collect_streams(stations)
    gone = {keys["iptv:A"][0], keys["iptv:B"][0]}
    mk = lambda: {"modes": ["strict"], "assetsSha256": fp,
                  "entries": {k: strict_entry(ip.GONE if k in gone else ip.LIVE)
                              for k in streams}}
    isect = {
        "assetsSha256": fp, "streamTotal": len(streams),
        "coverage": {"bothTested": len(streams)},
        "streamClasses": {"both-gone": sorted(gone)},
        "stationClasses": {"both-gone": ["iptv:A"]},
        "direct": {"path": "d.json"}, "proxy": {"path": "p.json"},
    }
    return stations, streams, keys, isect, mk(), mk()


class TestIntersectExclusions(unittest.TestCase):
    def setUp(self):
        self.stations, self.streams, self.keys, self.isect, self.d, self.p = \
            intersect_fixture("f" * 64)

    def _build(self):
        return ex.build_intersect_entries(
            self.stations, self.keys, self.streams, self.isect, self.d, self.p)

    def test_station_and_stream_are_separated(self):
        entries, se, gone = self._build()
        self.assertEqual([e["id"] for e in entries], ["iptv:A"])
        self.assertEqual(entries[0]["reason"], ex.REASON_BOTH_GONE)
        self.assertEqual(len(gone), 2)
        self.assertEqual([s["url"] for s in se], ["https://h/b1.m3u8"])
        self.assertEqual(se[0]["stationIds"], ["iptv:B"])
        self.assertEqual({p["network"] for p in entries[0]["streams"][0]["probes"]},
                         {"proxy-on", "proxy-off"})

    def test_rejects_stream_not_gone_in_both_rounds(self):
        self.d["entries"][self.keys["iptv:A"][0]] = strict_entry(ip.UNKNOWN)
        with self.assertRaises(SystemExit):
            self._build()

    def test_verify_accepts_c_class_after_removal(self):
        entries, se, _ = self._build()
        doc = {"entries": entries, "streamExclusions": se}
        kept = [
            dict(self.stations[1], sources=[self.stations[1]["sources"][1]],
                 streamUrl="https://h/b2.m3u8"),
            self.stations[2],
        ]
        self.assertEqual(ex.verify_exclusions(doc, kept), [])

    def test_stream_entries_sorted_by_url(self):
        # gone_keys 是 set，迭代序随 PYTHONHASHSEED 变；不排序的话每次重新
        # 生成的入仓 diff 都不同（B 类已排序，C 类必须同样保证）
        stations = [
            station("iptv:F", ["https://h/f1.m3u8", "https://h/f2.m3u8"]),
            station("iptv:G", ["https://h/g1.m3u8"]),
        ]
        streams, keys = ip.collect_streams(stations)
        gone = {keys["iptv:F"][0], keys["iptv:G"][0]}
        isect = {"streamClasses": {"both-gone": sorted(gone)},
                 "stationClasses": {"both-gone": []}}
        state = {"entries": {k: strict_entry(ip.GONE if k in gone else ip.LIVE)
                             for k in streams}}
        _, se, _ = ex.build_intersect_entries(stations, keys, streams, isect,
                                              state, state)
        self.assertEqual(len(se), 2)  # 两个保留台的失效源都进流级名单
        self.assertEqual([e["url"] for e in se],
                         sorted(e["url"] for e in se))


class TestIntersectCli(unittest.TestCase):
    def test_apply_intersect_end_to_end(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            stations, streams, keys, isect, dstate, pstate = intersect_fixture("f" * 64)
            assets, state_p = root / "a.json", root / "s.json"
            excl = root / "e.json"
            dp, pp, ip_ = root / "d.json", root / "p.json", root / "i.json"
            assets.write_text(json.dumps({"stations": stations}, ensure_ascii=False),
                              encoding="utf-8")
            fp = ip.sha256_file(assets)
            ip.save_state(state_p, {
                "schemaVersion": 1, "assetsSha256": fp, "updatedAt": CHECKED_AT,
                "modes": ["fast"],
                "entries": {k: {"verdict": ip.LIVE, "why": "fast-any-data",
                                "detail": "status=200", "mode": "fast", "elapsed": 0.1}
                            for k in streams},
            })
            isect["assetsSha256"] = fp
            isect["direct"]["path"] = str(dp)
            isect["proxy"]["path"] = str(pp)
            dp.write_text(json.dumps(dstate), encoding="utf-8")
            pp.write_text(json.dumps(pstate), encoding="utf-8")
            ip_.write_text(json.dumps(isect), encoding="utf-8")
            argv = ["apply", "--intersect", str(ip_), "--assets", str(assets),
                    "--state", str(state_p), "--exclusions", str(excl)]
            with patch.object(ex, "MIN_STATIONS", 1), \
                 patch.object(ex, "MIN_ALIASES", -1), \
                 patch.object(ex, "MIN_HEADERS", 0), \
                 patch.object(ex, "MIN_BYTES", 0), \
                 patch.object(ex, "BACKUP_DIR", root / "backup"):
                self.assertEqual(ex.main(argv), 0)
                after = json.loads(assets.read_text(encoding="utf-8"))["stations"]
                self.assertEqual([s["id"] for s in after], ["iptv:B", "iptv:C"])
                self.assertEqual(len(after[0]["sources"]), 1)
                doc = json.loads(excl.read_text(encoding="utf-8"))
                self.assertEqual(doc["entries"][0]["reason"], ex.REASON_BOTH_GONE)
                self.assertIn("intersectResult", doc["source"])
                self.assertEqual(ex.main(["verify", "--assets", str(assets),
                                          "--state", str(state_p),
                                          "--exclusions", str(excl)]), 0)


if __name__ == "__main__":
    unittest.main()
