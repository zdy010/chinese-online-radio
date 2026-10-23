"""fetch_epg 的纯逻辑测试：不碰网络，接口一律走假件。

最要紧的是 test_station_with_two_broadcast_ids_is_not_guessed 和签名那三条：
前者管「会不会静默挂错节目单」，后者管「Python 与 Kotlin 两边算法有没有各自跑偏」。
"""
import unittest

import fetch_epg as fe


def epg(title, bid, province="国家", count=5):
    return {"broadcastId": bid, "title": title, "province": province, "programCount": count}


def local(station_id, name):
    return {"id": station_id, "name": name}


class TestNorm(unittest.TestCase):
    def test_strips_and_collapses_whitespace(self):
        # 云听实测有「 经济之声」这种前导空格，不处理就匹配不上
        self.assertEqual("经济之声", fe.norm(" 经济之声 "))
        self.assertEqual("A B", fe.norm("A\t\tB"))
        self.assertEqual("", fe.norm(None))


class TestSign(unittest.TestCase):
    """黄金样例与 YuntingEpgClientTest 同源：两边任一边改错，这里就红。"""

    def test_two_params(self):
        self.assertEqual(
            "F49D4C56B9A273DFCCC3BE77638B13CB",
            fe.sign({"date": "2026-10-23", "broadcastId": "639"}, 1_730_000_000_000),
        )

    def test_no_params(self):
        self.assertEqual(
            "E1C93DA6441F5B647075D84677FFCC24", fe.sign({}, 1_730_000_000_000))

    def test_another_pair(self):
        self.assertEqual(
            "F1A29FC59E25572C11A9701B394434AD",
            fe.sign({"broadcastId": "641", "date": "2026-10-24"}, 1_700_000_000_000),
        )


class TestMatch(unittest.TestCase):
    def test_exact_name_pairs_up(self):
        result = fe.match([epg("中国之声", "639")], [local("cnr1", "中国之声")])
        self.assertEqual(
            [{"stationId": "cnr1", "broadcastId": "639", "name": "中国之声"}],
            result["matched"])
        self.assertEqual([], result["ambiguous"])

    def test_leading_space_still_matches(self):
        result = fe.match([epg(" 经济之声 ", "640")], [local("cnr2", "经济之声")])
        self.assertEqual("640", result["matched"][0]["broadcastId"])

    def test_same_name_on_two_local_stations_is_ambiguous(self):
        # 猜一个就是把 A 台的节目挂到 B 台，必须停下来交给人
        result = fe.match(
            [epg("交通广播", "700")],
            [local("a1", "交通广播"), local("a2", "交通广播")])
        self.assertEqual([], result["matched"])
        self.assertEqual([("交通广播", "700", ["a1", "a2"])], result["ambiguous"])

    def test_unmatched_goes_to_candidate_list(self):
        result = fe.match([epg("某某广播", "888", "浙江")], [local("cnr1", "中国之声")])
        self.assertEqual([("某某广播", "888", "浙江")], result["unmatched"])


class TestDedupe(unittest.TestCase):
    def test_station_with_two_broadcast_ids_is_not_guessed(self):
        rows = [
            {"stationId": "cnr1", "broadcastId": "639", "name": "中国之声"},
            {"stationId": "cnr1", "broadcastId": "999", "name": "中国之声"},
        ]
        kept, collisions = fe.dedupe_by_station(rows)
        self.assertEqual([], kept)
        self.assertEqual(1, len(collisions))
        self.assertEqual(2, len(collisions[0]))

    def test_unique_stations_are_kept(self):
        rows = [
            {"stationId": "cnr1", "broadcastId": "639", "name": "中国之声"},
            {"stationId": "cnr2", "broadcastId": "640", "name": "经济之声"},
        ]
        kept, collisions = fe.dedupe_by_station(rows)
        self.assertEqual(["cnr1", "cnr2"], sorted(r["stationId"] for r in kept))
        self.assertEqual([], collisions)


class TestValidate(unittest.TestCase):
    GOOD = [{"stationId": "cnr1", "broadcastId": "639", "name": "中国之声"}]
    LIB = [local("cnr1", "中国之声")]

    def test_clean_table_passes(self):
        self.assertEqual([], fe.validate(self.GOOD, self.LIB))

    def test_station_missing_from_library(self):
        rows = [{"stationId": "gone", "broadcastId": "639", "name": "已下架台"}]
        self.assertTrue(any("不在台库" in p for p in fe.validate(rows, self.LIB)))

    def test_name_drift_between_table_and_library(self):
        rows = [{"stationId": "cnr1", "broadcastId": "639", "name": "中央人民广播电台"}]
        self.assertTrue(any("台名不一致" in p for p in fe.validate(rows, self.LIB)))

    def test_non_numeric_broadcast_id(self):
        rows = [{"stationId": "cnr1", "broadcastId": "abc", "name": "中国之声"}]
        self.assertTrue(any("不是数字" in p for p in fe.validate(rows, self.LIB)))

    def test_duplicate_broadcast_id_rejected(self):
        rows = [
            {"stationId": "cnr1", "broadcastId": "639", "name": "中国之声"},
            {"stationId": "cnr2", "broadcastId": "639", "name": "经济之声"},
        ]
        lib = self.LIB + [local("cnr2", "经济之声")]
        self.assertTrue(any("broadcastId 重复" in p for p in fe.validate(rows, lib)))

    def test_empty_field_rejected(self):
        rows = [{"stationId": "", "broadcastId": "639", "name": "中国之声"}]
        self.assertTrue(any("空字段" in p for p in fe.validate(rows, self.LIB)))


class _FakeApi:
    """按路径回放预置响应；记录调用次数，用来断言失败时没有半途而废。

    contentId 用字符串，跟云听真实返回的形状一致（scan 会原样存下它）。
    """

    def __init__(self, provinces, broadcasts, programs, fail_provinces=()):
        self.provinces, self.broadcasts, self.programs = provinces, broadcasts, programs
        self.fail = set(fail_provinces)
        self.calls = 0

    def __call__(self, path, params):
        self.calls += 1
        if path == "/web/appProvince/list/all":
            return {"data": self.provinces}
        if path == "/web/appBroadcast/list":
            if params.get("provinceCode") in self.fail:
                raise RuntimeError("boom")
            return {"data": self.broadcasts.get(params.get("provinceCode"), [])}
        rows = self.programs.get(params.get("broadcastId"))
        if rows is None:
            raise RuntimeError("no schedule")
        return {"data": rows}


class TestScan(unittest.TestCase):
    def test_only_stations_with_programs_are_kept(self):
        api = _FakeApi(
            provinces=[{"provinceCode": "1", "provinceName": " 国家 "}],
            broadcasts={"1": [
                {"contentId": "639", "title": "中国之声"},
                {"contentId": "640", "title": "没有节目单的台"},
            ]},
            programs={"639": [{"programName": " 档案揭秘 "}]},
        )
        data = fe.scan(api, "2026-10-23", pause=0, sleep=lambda _s: None)
        self.assertEqual(["2026-10-23"], [data["checkedAt"]])
        self.assertEqual(2, len(data["stations"]))
        self.assertEqual(1, len(data["withEpg"]))
        self.assertEqual("国家", data["withEpg"][0]["province"])
        self.assertEqual("档案揭秘", data["withEpg"][0]["firstProgram"])

    def test_repeated_content_id_is_not_scanned_twice(self):
        api = _FakeApi(
            provinces=[{"provinceCode": "1", "provinceName": "A"},
                       {"provinceCode": "2", "provinceName": "B"}],
            broadcasts={"1": [{"contentId": "7", "title": "重叠台"}],
                        "2": [{"contentId": "7", "title": "重叠台"}]},
            programs={"7": [{"programName": "x"}]},
        )
        data = fe.scan(api, "2026-10-23", pause=0, sleep=lambda _s: None)
        self.assertEqual(1, len(data["stations"]))

    def test_one_failing_province_does_not_abort_the_scan(self):
        api = _FakeApi(
            provinces=[{"provinceCode": "1", "provinceName": "坏省"},
                       {"provinceCode": "2", "provinceName": "好省"}],
            broadcasts={"2": [{"contentId": "8", "title": "好台"}]},
            programs={"8": [{"programName": "y"}]},
            fail_provinces=("1",),
        )
        data = fe.scan(api, "2026-10-23", pause=0, sleep=lambda _s: None)
        self.assertEqual(["8"], [s["broadcastId"] for s in data["withEpg"]])

    def test_program_fetch_failure_counts_as_no_schedule(self):
        api = _FakeApi(
            provinces=[{"provinceCode": "1", "provinceName": "A"}],
            broadcasts={"1": [{"contentId": "9", "title": "接口报错的台"}]},
            programs={},
        )
        data = fe.scan(api, "2026-10-23", pause=0, sleep=lambda _s: None)
        self.assertEqual([], data["withEpg"])
        self.assertEqual(0, data["stations"][0]["programCount"])


class TestPayload(unittest.TestCase):
    def test_sorted_by_station_id_and_shaped(self):
        rows = [
            {"stationId": "cnr9", "broadcastId": "1", "name": "乙"},
            {"stationId": "cnr1", "broadcastId": "2", "name": "甲"},
        ]
        payload = fe.build_payload(rows, "2026-10-24")
        self.assertEqual(1, payload["schemaVersion"])
        self.assertEqual("2026-10-24", payload["generatedAt"])
        self.assertEqual(["cnr1", "cnr9"], [r["stationId"] for r in payload["stations"]])
        self.assertEqual({"stationId", "broadcastId", "name"}, set(payload["stations"][0]))


if __name__ == "__main__":
    unittest.main()
