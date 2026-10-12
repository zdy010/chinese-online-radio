"""apply_source_fix 的单元测试（不碰网络，探针由参数注入）。

这里守护的是「改出厂清单」这件事最容易犯的三种错：
1. 把「未表态」当「已死」删掉——timeout 的源可能只是当时拥塞，删了就永久少一台；
2. 只改 streamUrl 不改 sources——App 播的是 sources 队列，两处不一致等于没修；
3. 手滑删掉还能播的台——所以剔除必须拿体检报告当许可证，报告说 live 就拒绝删。
另外键顺序也钉住：这个文件一旦整体重排，下次想 review 真实改动就没法看了。
"""
import json
import unittest

import apply_source_fix as af


def st(id_, url, mirrors=(), **kw):
    s = {"id": id_, "name": "T" + id_, "category": "news", "streamUrl": url,
         "logoUrl": "", "frequency": "FM 100", "description": "d",
         "sources": [{"url": url, "label": "主源", "bitrate": 0}] +
                    [{"url": u, "label": "镜像源", "bitrate": 0} for u in mirrors]}
    s.update(kw)
    return s


def row(id_, verdict, live_index, urls):
    return {"id": id_, "name": "T" + id_, "category": "news", "verdict": verdict,
            "live_index": live_index, "urls": urls, "first_pass": "serial"}


def u(url, verdict, label="主源"):
    return {"url": url, "label": label, "verdict": verdict, "why": "test"}


class TestPromote(unittest.TestCase):
    def test_live_mirror_becomes_primary_and_sources_follow(self):
        """主源死、镜像活：镜像必须同时成为 streamUrl 和 sources[0]。"""
        s = st("a", "https://dead/x.m3u8", ["https://live/y.mp3"])
        r = row("a", "live", 1, [u("https://dead/x.m3u8", "gone"),
                                 u("https://live/y.mp3", "live", "镜像源")])
        out = af.promote_station(s, r)
        self.assertEqual(out["streamUrl"], "https://live/y.mp3")
        self.assertEqual(out["sources"][0]["url"], "https://live/y.mp3")
        self.assertEqual(out["sources"][0]["label"], "主源")

    def test_confirmed_gone_url_is_dropped_entirely(self):
        """已判死的地址留在队列里没有价值，只会在重装后制造一次可预见的失败。"""
        s = st("a", "https://dead/x.m3u8", ["https://live/y.mp3"])
        r = row("a", "live", 1, [u("https://dead/x.m3u8", "gone"),
                                 u("https://live/y.mp3", "live", "镜像源")])
        out = af.promote_station(s, r)
        urls = [x["url"] for x in out["sources"]]
        self.assertNotIn("https://dead/x.m3u8", urls)

    def test_unknown_url_is_kept_as_mirror_behind_live(self):
        """未表态不等于死：保留在 live 之后，不能顺手删。"""
        s = st("a", "https://slow/x.mp3", ["https://live/y.mp3"])
        r = row("a", "live", 1, [u("https://slow/x.mp3", "unknown"),
                                 u("https://live/y.mp3", "live", "镜像源")])
        out = af.promote_station(s, r)
        urls = [x["url"] for x in out["sources"]]
        self.assertEqual(urls, ["https://live/y.mp3", "https://slow/x.mp3"])

    def test_no_change_when_primary_already_live(self):
        self.assertIsNone(af.promote_station(st("a", "https://live/x.m3u8"),
                                             row("a", "live", 0, [u("https://live/x.m3u8", "live")])))

    def test_promotes_by_url_not_by_report_index(self):
        """报告里 live_index=0 但当前清单主源已换成另一个地址时，仍要按 URL 判。"""
        s = st("a", "https://dead/x.m3u8", ["https://live/y.mp3"])
        r = row("a", "live", 0, [u("https://dead/x.m3u8", "gone"),
                                 u("https://live/y.mp3", "live", "镜像源")])
        out = af.promote_station(s, r)
        self.assertEqual(out["streamUrl"], "https://live/y.mp3")

    def test_no_change_when_nothing_live(self):
        """一台里没有任何 live 地址时不由本函数处置（该走替代或剔除）。"""
        s = st("a", "https://dead/x.m3u8", ["https://dead2/y.m3u8"])
        r = row("a", "gone", -1, [u("https://dead/x.m3u8", "gone"),
                                  u("https://dead2/y.m3u8", "gone", "镜像源")])
        self.assertIsNone(af.promote_station(s, r))

    def test_key_order_preserved(self):
        s = st("a", "https://dead/x.m3u8", ["https://live/y.mp3"], tags=["news"], country="中国")
        r = row("a", "live", 1, [u("https://dead/x.m3u8", "gone"),
                                 u("https://live/y.mp3", "live", "镜像源")])
        out = af.promote_station(s, r)
        self.assertEqual(list(out.keys()), list(s.keys()))

    def test_tags_country_language_untouched(self):
        """这个工具只动地址，标签/国家/语言是 A-2 的产物，不能被顺手抹掉。"""
        s = st("a", "https://dead/x.m3u8", ["https://live/y.mp3"],
               tags=["opera"], country="中国", language="华语")
        r = row("a", "live", 1, [u("https://dead/x.m3u8", "gone"),
                                 u("https://live/y.mp3", "live", "镜像源")])
        out = af.promote_station(s, r)
        self.assertEqual(out["tags"], ["opera"])
        self.assertEqual(out["country"], "中国")
        self.assertEqual(out["language"], "华语")
        self.assertEqual(out["name"], "Ta")

    def test_duplicate_urls_do_not_appear_twice(self):
        s = st("a", "https://dead/x.m3u8", ["https://live/y.mp3"])
        s["sources"].append({"url": "https://live/y.mp3", "label": "镜像源", "bitrate": 0})
        r = row("a", "live", 1, [u("https://dead/x.m3u8", "gone"),
                                 u("https://live/y.mp3", "live", "镜像源")])
        out = af.promote_station(s, r)
        urls = [x["url"] for x in out["sources"]]
        self.assertEqual(len(urls), len(set(urls)))

    def test_bitrate_metadata_survives_the_move(self):
        """镜像升主源时不能把 A-1 存下的码率抹成 0，那是选源评分的输入。"""
        s = st("a", "https://dead/x.m3u8")
        s["sources"].append({"url": "https://live/y.mp3", "label": "高码率镜像", "bitrate": 192})
        r = row("a", "live", 1, [u("https://dead/x.m3u8", "gone"),
                                 u("https://live/y.mp3", "live", "高码率镜像")])
        out = af.promote_station(s, r)
        self.assertEqual(out["sources"][0]["bitrate"], 192)

    def test_station_level_bitrate_is_inherited(self):
        """码率在 RadioBrowser 里是台级属性：原来记在现已删的死地址上，
        提上来时必须继承，不能因为地址换了就把 128 抹成 0（实测 Radio Caroline 就丢了）。
        """
        s = st("a", "https://dead/x.mp3")
        s["sources"] = [{"url": "https://dead/x.mp3", "label": "网络源", "bitrate": 128},
                        {"url": "https://live/y.mp3", "label": "镜像源", "bitrate": 0}]
        r = row("a", "live", 1, [u("https://dead/x.mp3", "gone", "网络源"),
                                 u("https://live/y.mp3", "live", "镜像源")])
        out = af.promote_station(s, r)
        self.assertEqual(out["sources"][0]["bitrate"], 128)

    def test_demoted_primary_label_is_not_left_standing(self):
        """原来的「主源」被挤到第二位时，标签得跟着改，否则界面会说谎。"""
        s = st("a", "https://slow/x.m3u8", ["https://live/y.mp3"])
        r = row("a", "live", 1, [u("https://slow/x.m3u8", "unknown"),
                                 u("https://live/y.mp3", "live", "镜像源")])
        out = af.promote_station(s, r)
        self.assertEqual(out["sources"][0]["label"], "主源")
        self.assertNotEqual(out["sources"][1]["label"], "主源")


class TestDrop(unittest.TestCase):
    def test_refuses_to_drop_station_reported_live(self):
        """报告说这台能播，就不允许出现在剔除名单里——这是防手滑的硬闸。"""
        stations = [st("a", "https://live/x.m3u8")]
        report = [row("a", "live", 0, [u("https://live/x.m3u8", "live")])]
        kept, dropped, refused = af.drop_stations(stations, {"a"}, report)
        self.assertEqual(len(kept), 1)
        self.assertEqual(dropped, [])
        self.assertEqual([sid for sid, _ in refused], ["a"])
        self.assertIn("live", refused[0][1])

    def test_refuses_to_drop_station_absent_from_report(self):
        """没体检过的台不许删：宁可让人重跑一次体检。"""
        stations = [st("a", "https://live/x.m3u8")]
        kept, dropped, refused = af.drop_stations(stations, {"a"}, [])
        self.assertEqual(len(kept), 1)
        self.assertEqual([sid for sid, _ in refused], ["a"])

    def test_drops_confirmed_dead(self):
        stations = [st("a", "https://dead/x.m3u8"), st("b", "https://live/y.m3u8")]
        report = [row("a", "gone", -1, [u("https://dead/x.m3u8", "gone")]),
                  row("b", "live", 0, [u("https://live/y.m3u8", "live")])]
        kept, dropped, refused = af.drop_stations(stations, {"a"}, report)
        self.assertEqual([s["id"] for s in kept], ["b"])
        self.assertEqual(dropped, ["a"])
        self.assertEqual(refused, [])

    def test_refuses_unknown_when_not_allowed(self):
        """两轮都未表态的台默认也不删：原因可能是本地网络，不是对方下线。"""
        stations = [st("a", "https://slow/x.m3u8")]
        report = [row("a", "unknown", -1, [u("https://slow/x.m3u8", "unknown")])]
        kept, dropped, refused = af.drop_stations(stations, {"a"}, report)
        self.assertEqual(len(kept), 1)
        self.assertEqual(dropped, [])

    def test_allows_unknown_only_with_explicit_flag(self):
        stations = [st("a", "https://slow/x.m3u8")]
        report = [row("a", "unknown", -1, [u("https://slow/x.m3u8", "unknown")])]
        kept, dropped, _ = af.drop_stations(stations, {"a"}, report, allow_unknown=True)
        self.assertEqual(dropped, ["a"])
        self.assertEqual(kept, [])

    def test_over_cap_requires_force(self):
        """一次删太多必须是显式决定，不能靠一个打错的参数批量蒸发。"""
        stations = [st("a", "https://dead/1"), st("b", "https://dead/2"), st("c", "https://dead/3")]
        report = [row(x, "gone", -1, [u("https://dead/" + x, "gone")]) for x in "abc"]
        kept, dropped, refused = af.drop_stations(stations, {"a", "b", "c"}, report, cap=2)
        self.assertEqual(len(kept), 3)
        self.assertEqual(dropped, [])
        self.assertEqual(len(refused), 3)
        self.assertIn("上限", refused[0][1])

    def test_force_allows_over_cap(self):
        stations = [st("a", "https://dead/1"), st("b", "https://dead/2"), st("c", "https://dead/3")]
        report = [row(x, "gone", -1, [u("https://dead/" + x, "gone")]) for x in "abc"]
        kept, dropped, _ = af.drop_stations(stations, {"a", "b", "c"}, report, cap=2, force=True)
        self.assertEqual([s["id"] for s in kept], [])
        self.assertEqual(sorted(dropped), ["a", "b", "c"])


class TestReplace(unittest.TestCase):
    def test_refuses_unverified_replacement_url(self):
        """替代地址必须当场实测过才写进去，不接受「看起来像」。"""
        stations = [st("a", "https://dead/x.m3u8")]
        probes = {"https://maybe/y.m3u8": "unknown"}
        kept, applied, refused = af.apply_replacements(stations, [{"id": "a", "url": "https://maybe/y.m3u8"}],
                                                        lambda u_: probes[u_])
        self.assertEqual(kept[0]["streamUrl"], "https://dead/x.m3u8")
        self.assertEqual(applied, [])
        self.assertEqual([sid for sid, _ in refused], ["a"])

    def test_live_replacement_becomes_primary(self):
        stations = [st("a", "https://dead/x.m3u8")]
        kept, applied, refused = af.apply_replacements(
            stations, [{"id": "a", "url": "https://live/y.m3u8", "label": "satellitepull"}],
            lambda u_: "live")
        self.assertEqual(applied, ["a"])
        self.assertEqual(refused, [])
        self.assertEqual(kept[0]["streamUrl"], "https://live/y.m3u8")
        self.assertEqual(kept[0]["sources"][0]["url"], "https://live/y.m3u8")

    def test_dead_old_urls_removed_on_replace(self):
        stations = [st("a", "https://dead/x.m3u8", ["https://dead2/y.m3u8"])]
        kept, _, _ = af.apply_replacements(stations, [{"id": "a", "url": "https://live/z.m3u8"}],
                                           lambda u_: "gone" if u_ != "https://live/z.m3u8" else "live")
        urls = [s["url"] for s in kept[0]["sources"]]
        self.assertEqual(urls, ["https://live/z.m3u8"])


class TestIdempotency(unittest.TestCase):
    def test_applying_twice_yields_identical_bytes(self):
        """重跑必须零差异，否则无法证明工具没有夹带随机改动。"""
        stations = [st("a", "https://dead/x.m3u8", ["https://live/y.mp3"]),
                    st("b", "https://live/z.m3u8")]
        report = [row("a", "live", 1, [u("https://dead/x.m3u8", "gone"),
                                       u("https://live/y.mp3", "live", "镜像源")]),
                  row("b", "live", 0, [u("https://live/z.m3u8", "live")])]
        once, _ = af.apply_promotions(stations, report)
        twice, changed2 = af.apply_promotions(once, report)
        self.assertEqual(changed2, [])
        dump = lambda xs: json.dumps({"stations": xs}, ensure_ascii=False, indent=2)
        self.assertEqual(dump(once), dump(twice))


class TestParseIdLines(unittest.TestCase):
    def test_inline_comment_is_a_reason_not_part_of_the_id(self):
        """名单里行尾写理由，解析不能把「hn_news # 说明」整个当 id。"""
        got = af.parse_id_lines("hn_news    # 主源 200 返 HTML\n")
        self.assertEqual(got, {"hn_news"})

    def test_full_line_comment_and_blank_lines_ignored(self):
        got = af.parse_id_lines("# 剔除清单\n\n  \na\nb\n")
        self.assertEqual(got, {"a", "b"})

    def test_duplicate_ids_collapse(self):
        self.assertEqual(af.parse_id_lines("a\na\n"), {"a"})


class TestIndexAndSerialize(unittest.TestCase):
    def test_report_index_lookup(self):
        report = [row("a", "live", 0, [u("x", "live")])]
        idx = af.index_report(report)
        self.assertEqual(idx["a"]["verdict"], "live")

    def test_serialize_matches_asset_formatting(self):
        """产出排版必须和现文件一致：indent=2、不转义中文、无结尾换行。"""
        text = af.serialize([st("a", "https://live/x")])
        self.assertTrue(text.startswith('{\n  "stations": [\n    {\n      "id": "a",'))
        self.assertTrue(text.endswith("}"))
        self.assertIn("主源", text)


if __name__ == "__main__":
    unittest.main()
