"""fetch_stations 纯逻辑的单元测试（不碰网络）。

覆盖的是最容易写歪的部分：台名归一与镜像匹配、id 命名约定、
全球台/大陆台两种字段语义、以及"不许写入重复源"。
约定依据：现有 radio_stations.json 里大陆台 st_<中文名> 且 sources 为空、
全球台 gl_<cc>_<缩写> 且 frequency="国家中文 · 语言"、description="全球节目源 · 国家中文 · 流派"。
"""
import unittest

import fetch_stations as fs


class TestNormName(unittest.TestCase):
    def test_strips_punctuation_and_case(self):
        self.assertEqual(fs.norm_name("  CNR·中国之声 "), fs.norm_name("cnr中国之声"))

    def test_match_key_removes_broadcast_suffixes(self):
        """镜像匹配用的键：去掉 广播/电台/频率 等通配后缀后，同一台应落到同一个键。"""
        a = fs.match_key("安徽戏曲广播·动听995")
        b = fs.match_key("安徽戏曲广播")
        self.assertEqual(a, b)

    def test_match_key_keeps_region_prefix_distinct(self):
        self.assertNotEqual(fs.match_key("河南戏曲广播"), fs.match_key("河北戏曲广播"))

    def test_match_key_does_not_collapse_different_genres(self):
        self.assertNotEqual(fs.match_key("北京体育广播"), fs.match_key("北京文艺广播"))

    def test_bullet_variants_normalize_together(self):
        """实测候选里同时存在 U+00B7 与 U+2022 两种“点”，它们是同一个台。"""
        self.assertEqual(fs.match_key("青岛音乐·体育广播"), fs.match_key("青岛音乐•体育广播"))

    def test_trailing_frequency_is_stripped(self):
        """尾部频率号不是台名的一部分，不剥就会同一台写两条。"""
        self.assertEqual(fs.match_key("上海戏剧曲艺广播 AM1197 FM97.2"),
                         fs.match_key("上海戏剧曲艺广播"))

    def test_leading_channel_number_is_kept(self):
        """只剥尾部：CETV-1 与 CETV-2 的头部编号是频道身份，剥了会把四个台合成一个。"""
        self.assertNotEqual(fs.match_key("CETV-1伴音"), fs.match_key("CETV-2伴音"))

    def test_bitrate_parenthetical_is_stripped(self):
        """实测坑：“Techno.FM (320 kbit)”与“Techno.FM”是同一个台。"""
        self.assertEqual(fs.match_key("Techno.FM (320 kbit)"), fs.match_key("Techno.FM"))
        self.assertEqual(fs.match_key("Megarock Radio 320k"), fs.match_key("Megarock Radio"))
        self.assertEqual(fs.match_key("PowerFM Dublin 320Kbps MP3"), fs.match_key("PowerFM Dublin"))

    def test_non_numeric_parenthetical_is_kept(self):
        """不带数字的括号是频道身份的一部分，不能剥：“湖北卫视(美洲)”≠“湖北卫视”。"""
        self.assertNotEqual(fs.match_key("湖北卫视 (美洲)"), fs.match_key("湖北卫视"))


class TestIdConvention(unittest.TestCase):
    def test_mainland_id_uses_st_prefix(self):
        self.assertEqual(fs.make_id("河南戏曲广播", "CN", set()), "st_河南戏曲广播")

    def test_overseas_id_uses_gl_prefix_with_cc(self):
        got = fs.make_id("KZOK California 中文电台", "US", set())
        self.assertTrue(got.startswith("gl_us_"), got)

    def test_overseas_non_ascii_name_falls_back_to_stable_hash(self):
        """中文名 + 非大陆国家：ASCII 化不了就必须走稳定散列，不许拿中文塞进 gl_ 约定里。"""
        a = fs.make_id("新加坡华语电台", "SG", set())
        b = fs.make_id("新加坡华语电台", "SG", set())
        self.assertEqual(a, b)
        self.assertTrue(a.startswith("gl_sg_"), a)

    def test_id_collision_is_resolved_not_reused(self):
        taken = {"st_河南戏曲广播"}
        got = fs.make_id("河南戏曲广播", "CN", taken)
        self.assertNotEqual(got, "st_河南戏曲广播")
        self.assertTrue(got.startswith("st_河南戏曲广播"), got)


class TestEntryBuilding(unittest.TestCase):
    def test_mainland_entry_has_no_sources_and_empty_meta(self):
        e = fs.build_entry(
            name="河南戏曲广播", category="opera",
            url="https://satellitepull.cnr.cn/live/wxhnxqgb/playlist.m3u8",
            cc="CN", country_zh="", bitrate=0, homepage="", taken=set(),
        )
        self.assertEqual(e["id"], "st_河南戏曲广播")
        self.assertEqual(e["category"], "opera")
        self.assertEqual(e["logoUrl"], "")
        self.assertEqual(e["frequency"], "")
        self.assertEqual(e["streamUrl"], "https://satellitepull.cnr.cn/live/wxhnxqgb/playlist.m3u8")
        # 大陆台沿用既有约定：不写 sources，由 streamUrl 兜底
        self.assertEqual(e["sources"], [])

    def test_overseas_entry_uses_frequency_subtitle_and_network_source(self):
        e = fs.build_entry(
            name="Sabai 90.4 Chinese Radio", category="world",
            url="https://stream.example/mp3", cc="TH", country_zh="泰国",
            bitrate=128, homepage="https://example.com", taken=set(),
        )
        self.assertEqual(e["category"], "world")
        self.assertEqual(e["frequency"], "泰国 · 华语")
        self.assertTrue(e["description"].startswith("全球节目源 · 泰国"), e["description"])
        self.assertEqual(len(e["sources"]), 1)
        self.assertEqual(e["sources"][0]["label"], "网络源")
        self.assertEqual(e["sources"][0]["bitrate"], 128)
        self.assertEqual(e["website"], "https://example.com")

    def test_entry_keys_are_complete(self):
        e = fs.build_entry(name="X", category="general", url="https://a/b.mp3",
                           cc="CN", country_zh="", bitrate=0, homepage="", taken=set())
        self.assertEqual(
            set(e.keys()),
            {"id", "name", "category", "streamUrl", "logoUrl", "frequency", "description", "website", "sources"},
        )


class TestMirrorRule(unittest.TestCase):
    """多源镜像：同一台的第二个可用入口必须进 sources[]，且不许把同一 URL 塞两次。"""

    EXISTING = [{
        "id": "st_河南新闻广播", "name": "河南新闻广播", "category": "news",
        "streamUrl": "https://a/one.m3u8", "logoUrl": "", "frequency": "",
        "description": "", "website": "", "sources": [],
    }]

    def test_second_url_becomes_two_sources(self):
        got = fs.apply_mirror(self.EXISTING, {"河南新闻广播": ["https://b/two.m3u8"]})
        st = got[0]
        urls = [s["url"] for s in st["sources"]]
        self.assertIn("https://a/one.m3u8", urls)
        self.assertIn("https://b/two.m3u8", urls)
        self.assertEqual(len(urls), 2)

    def test_duplicate_url_does_not_fabricate_a_mirror(self):
        """候选与主源是同一个地址时必须什么都不加。

        写成“拷一份主源进 sources”会让 sources>1 变成假象——
        看起来有备源，实际一死全死，多源自动切源会因此彻底失效。
        """
        got = fs.apply_mirror(self.EXISTING, {"河南新闻广播": ["https://a/one.m3u8"]})
        self.assertEqual(got[0]["sources"], [])
        self.assertEqual(got[0]["streamUrl"], "https://a/one.m3u8")

    def test_mirror_keeps_primary_stream_unchanged(self):
        got = fs.apply_mirror(self.EXISTING, {"河南新闻广播": ["https://b/two.m3u8"]})
        self.assertEqual(got[0]["streamUrl"], "https://a/one.m3u8")

    def test_url_owned_by_another_station_is_not_mirrored(self):
        """已被别的电台持有的地址不能当镜像：实测造成过 somafm/sharp-stream 共用。"""
        stations = [
            {"id": "st_甲", "name": "河南新闻广播", "streamUrl": "https://a/one.m3u8", "sources": []},
            {"id": "st_乙", "name": "湖北之声", "streamUrl": "https://b/taken.m3u8", "sources": []},
        ]
        got = fs.apply_mirror(stations, {"河南新闻广播": ["https://b/taken.m3u8"]})
        self.assertEqual(got[0]["sources"], [])

    def test_same_key_duplicates_get_mirror_only_once(self):
        """存量里有本来就重两条的同名台，镜像只能挂到其中一条。"""
        stations = [
            {"id": "gl_a1", "name": "SomaFM Groove Salad", "streamUrl": "https://s/128.m3u8", "sources": []},
            {"id": "gl_a2", "name": "SomaFM Groove Salad", "streamUrl": "https://s/64.m3u8", "sources": []},
        ]
        got = fs.apply_mirror(stations, {"SomaFM Groove Salad": ["https://s/new.m3u8"]})
        owners = [s["id"] for s in got if any(x["url"] == "https://s/new.m3u8" for x in (s.get("sources") or []))]
        self.assertEqual(owners, ["gl_a1"])


class TestLanguageRule(unittest.TestCase):
    def test_chinese_display_label(self):
        self.assertEqual(fs.language_label(["chinese", "mandarin"]), "华语")
        self.assertEqual(fs.language_label(["english"]), "英语")
        self.assertEqual(fs.language_label([""]), "综合")


class TestCategoryGuard(unittest.TestCase):
    def test_category_must_exist_in_station_category_keys(self):
        """入库前最后一道闸：分类只能取 StationCategory.kt 里已有的 key。"""
        self.assertTrue(set(fs.CATEGORY_KEYS) <= fs.STATION_CATEGORY_KEYS)
        for c in fs.CATEGORY_KEYS:
            self.assertIn(c, fs.STATION_CATEGORY_KEYS)

    def test_unknown_category_is_rejected(self):
        with self.assertRaises(ValueError):
            fs.assert_category("cartoon")


class TestPoolSafety(unittest.TestCase):
    def test_adult_and_non_audio_are_droppable_but_listed(self):
        """候选过滤要能解释：被丢的条目必须留下原因，不许静默消失。"""
        dropped = fs.drop_reason({"name": "x", "tags": "adult", "url": "https://a/b.mp3"})
        self.assertEqual(dropped, "adult")

    def test_blank_url_dropped(self):
        self.assertEqual(fs.drop_reason({"name": "x", "tags": "", "url": ""}), "no-url")

    def test_keep_returns_none(self):
        self.assertIsNone(fs.drop_reason({"name": "河南戏曲广播", "tags": "chinese",
                                         "url": "https://a/b.m3u8"}))

    def test_bare_ip_is_dropped(self):
        """裸 IP 无法证明发布方身份，不收。"""
        self.assertEqual(
            fs.drop_reason({"name": "某广播", "tags": "", "url": "http://202.39.43.67:1935/live/a.m3u8",
                            "host": "202.39.43.67:1935"}),
            "bare-ip",
        )

    def test_pirate_cdn_is_dropped(self):
        """实测候选池里真实出现过的盗版转播特征（空白名 + vip1.fhbf9.com + 日期路径）。"""
        self.assertEqual(
            fs.drop_reason({"name": "FHTV", "tags": "chinese",
                            "url": "https://vip1.fhbf9.com/20210705/eoQxrzxS/index.m3u8",
                            "host": "vip1.fhbf9.com"}),
            "pirate-cdn",
        )

    def test_official_domain_is_kept(self):
        self.assertIsNone(fs.drop_reason({
            "name": "安徽戏曲广播", "tags": "", "host": "satellitepull.cnr.cn",
            "url": "https://satellitepull.cnr.cn/live/wxahxqgb/playlist.m3u8"}))


    def test_video_resolution_url_is_dropped(self):
        """实测坑：cztv 的 360p 频道能通过分层实测（它真有分片），但它是视频不是声音。"""
        self.assertEqual(fs.drop_reason({
            "name": "浙江新闻广播", "tags": "", "host": "ali-vl.cztv.com", "bitrate": 0,
            "url": "http://ali-vl.cztv.com/channels/lantian/channel007/360p.m3u8"}),
            "likely-video")

    def test_suspiciously_high_bitrate_is_dropped(self):
        self.assertEqual(fs.drop_reason({
            "name": "某广播", "tags": "", "host": "a.com", "bitrate": 800,
            "url": "https://a.com/live/x.m3u8"}), "bitrate-too-high-for-audio")

    def test_normal_audio_bitrates_survive(self):
        for br in (0, 64, 128, 320):
            self.assertIsNone(fs.drop_reason({
                "name": "安徽戏曲广播", "tags": "", "host": "satellitepull.cnr.cn", "bitrate": br,
                "url": "https://satellitepull.cnr.cn/live/wxahxqgb/playlist.m3u8"}), br)

    def test_official_audio_m3u8_not_mistaken_as_video(self):
        """cctv 伴音路径里带 audio 关键字，不能被视频规则误杀。"""
        self.assertIsNone(fs.drop_reason({
            "name": "CCTV-13新闻伴音", "tags": "", "bitrate": 0,
            "host": "piccpndali.v.myalicdn.com",
            "url": "https://piccpndali.v.myalicdn.com/audio/cctv13_2.m3u8"}))


class TestGuard(unittest.TestCase):
    """入库闸。最该验的是“未实测的地址能不能偷偷进去”。"""

    def _entry(self, url, cid="st_某台", cat="general"):
        return {"id": cid, "name": "某台", "category": cat, "streamUrl": url,
                "logoUrl": "", "frequency": "", "description": "", "website": "", "sources": []}

    def test_rejects_unverified_url(self):
        with self.assertRaises(AssertionError):
            fs.guard([], [self._entry("https://a/b.mp3")], 10, {})

    def test_rejects_unknown_verdict_url(self):
        vmap = {fs.norm_url("https://a/b.mp3"): "unknown"}
        with self.assertRaises(AssertionError):
            fs.guard([], [self._entry("https://a/b.mp3")], 10, vmap)

    def test_accepts_live_url(self):
        e = self._entry("https://a/b.mp3")
        vmap = {fs.norm_url("https://a/b.mp3"): "live"}
        out = fs.guard([], [e], 10, vmap)
        self.assertEqual(len(out), 1)

    def test_rejects_url_already_owned_by_existing_station(self):
        exist = [{"id": "cnr1", "name": "中国之声", "streamUrl": "https://a/used.m3u8", "sources": []}]
        vmap = {fs.norm_url("https://a/used.m3u8"): "live"}
        with self.assertRaises(AssertionError):
            fs.guard(exist, [self._entry("https://a/used.m3u8")], 10, vmap)

    def test_norm_url_drops_session_token(self):
        """同一源的两次不同 session 必须归成同一个地址，否则重复检查形同虚设。"""
        a = fs.norm_url("https://satellitepull.cnr.cn/live/wxahxqgb/playlist.m3u8?wsSession=aaa")
        b = fs.norm_url("https://satellitepull.cnr.cn/live/wxahxqgb/playlist.m3u8?wsSession=bbb")
        self.assertEqual(a, b)

    def test_rejects_bad_category_on_new_entry(self):
        e = self._entry("https://a/b.mp3", cat="cartoon")
        vmap = {fs.norm_url("https://a/b.mp3"): "live"}
        with self.assertRaises(ValueError):
            fs.guard([], [e], 10, vmap)

    def test_catches_two_new_entries_sharing_one_url(self):
        """selection 万一漏了，guard 仍必须拦下同一地址挂两个台。"""
        dup = [self._entry("https://a/x.m3u8", cid="st_一台"),
               self._entry("https://a/x.m3u8", cid="st_二台")]
        vmap = {fs.norm_url("https://a/x.m3u8"): "live"}
        with self.assertRaises(AssertionError):
            fs.guard([], dup, 10, vmap)


class TestBuildSelection(unittest.TestCase):
    """新台/镜像的判定。钉的是“同名不同址被当成新台”这个已发生过的 bug。"""

    STATIONS = [{
        "id": "st_河南戏曲广播", "name": "河南戏曲广播", "category": "opera",
        "streamUrl": "https://official/a.m3u8", "logoUrl": "", "frequency": "",
        "description": "", "website": "", "sources": [],
    }]

    def _cand(self, name, url, cc="CN"):
        return {"name": name, "url": url, "url_resolved": url, "cc": cc,
                "country": "China", "bitrate": 64, "homepage": "", "tags": ""}

    def test_same_name_different_url_goes_to_mirrors_not_new_entries(self):
        alive = [self._cand("河南戏曲广播", "https://mirror/b.m3u8")]
        new, mirrors, _ = fs.build_selection(alive, self.STATIONS)
        self.assertEqual(new, [])
        self.assertEqual(mirrors, {"河南戏曲广播": ["https://mirror/b.m3u8"]})

    def test_same_name_suffix_variant_still_mirrors(self):
        """“河南戏曲广播·河南戏曲频率995”这类带后缀的同一台，不能建新条目。"""
        alive = [self._cand("河南戏曲广播·戏曲995", "https://mirror/c.m3u8")]
        new, mirrors, _ = fs.build_selection(alive, self.STATIONS)
        self.assertEqual(new, [])
        self.assertIn("河南戏曲广播", mirrors)

    def test_identical_url_produces_nothing(self):
        alive = [self._cand("河南戏曲广播", "https://official/a.m3u8")]
        new, mirrors, _ = fs.build_selection(alive, self.STATIONS)
        self.assertEqual((new, mirrors), ([], {}))

    def test_brand_new_station_becomes_entry(self):
        alive = [self._cand("湖北之声", "https://x/y.m3u8")]
        new, mirrors, _ = fs.build_selection(alive, self.STATIONS)
        self.assertEqual(len(new), 1)
        self.assertEqual(new[0]["id"], "st_湖北之声")
        self.assertEqual(mirrors, {})

    def test_new_station_with_two_urls_gets_sources(self):
        """新台自带多个官方入口时直接写成 sources[]，入库就能用多源切源。"""
        alive = [self._cand("湖北之声", "https://x/one.m3u8"),
                 self._cand("湖北之声", "https://z/two.m3u8")]
        new, _, _ = fs.build_selection(alive, self.STATIONS)
        self.assertEqual(len(new), 1)
        self.assertEqual(len(new[0]["sources"]), 2)

    def test_overseas_candidate_forced_into_world(self):
        alive = [self._cand("星岛中文电台国语广播", "https://s/a.mp3", cc="TH")]
        new, _, _ = fs.build_selection(alive, self.STATIONS)
        self.assertEqual(new[0]["category"], "world")
        self.assertEqual(new[0]["frequency"], "泰国 · 华语")

    def test_blank_match_key_is_skipped_not_invented(self):
        alive = [self._cand("!!!", "https://x/a.mp3")]
        new, mirrors, skipped = fs.build_selection(alive, self.STATIONS)
        self.assertEqual(new, [])
        self.assertEqual(skipped, 1)

    def test_two_name_variants_sharing_one_url_keep_only_one(self):
        """同一个流的不同台名写法不能占两个条目（否则 guard 会当场炸）。"""
        alive = [
            self._cand("CCTV-13新闻伴音", "https://piccpndali.v.myalicdn.com/audio/cctv13_2.m3u8"),
            self._cand("CCTV-13 综合（新闻）", "https://piccpndali.v.myalicdn.com/audio/cctv13_2.m3u8"),
        ]
        new, mirrors, skipped = fs.build_selection(alive, [], max_new=10)
        self.assertEqual(len(new), 1, [e["name"] for e in new])
        urls = [u for e in new for u in fs.entry_urls(e)]
        self.assertEqual(len(urls), len(set(fs.norm_url(x) for x in urls)))


class TestRanking(unittest.TestCase):
    """名额排序。防的是“可用候选一大堆，全被 general 占满”。"""

    def test_thin_category_outranks_general(self):
        opera = fs.score_candidate("河南戏曲广播", "CN", "opera", "stream.hndt.com", 64, 5)
        general = fs.score_candidate("某综合广播", "CN", "general", "stream.hndt.com", 64, 5)
        self.assertGreater(opera, general)

    def test_official_host_outranks_aggregator(self):
        official = fs.score_candidate("河南戏曲广播", "CN", "opera", "stream.hndt.com", 64, 5)
        agg = fs.score_candidate("河南戏曲广播", "CN", "opera", "lhttp.qtfm.cn", 64, 5)
        self.assertGreater(official, agg)

    def test_higher_bitrate_not_outrank_category_need(self):
        """高码率的 general 不应该压掉低码率的戏曲：这次目的是补薄分类。"""
        thin = fs.score_candidate("戏曲广播", "CN", "opera", "a.com", 32, 0)
        fat = fs.score_candidate("音乐频道", "CN", "general", "a.com", 320, 500)
        self.assertGreater(thin, fat)

    def test_max_new_keeps_top_scored(self):
        """名额满时丢的必须是低分条目，不是字典顺序靠后的条目。"""
        alive = [{"name": f"某综合广播{i}", "url": f"https://a/{i}.m3u8",
                  "url_resolved": f"https://a/{i}.m3u8", "cc": "CN", "country": "China",
                  "host": "a.com", "bitrate": 64, "votes": 1, "homepage": "", "tags": ""}
                 for i in range(6)]
        alive.append({"name": "河南戏曲广播", "url": "https://op/x.m3u8",
                      "url_resolved": "https://op/x.m3u8", "cc": "CN", "country": "China",
                      "host": "stream.hndt.com", "bitrate": 64, "votes": 30,
                      "homepage": "", "tags": ""})
        new, _, _ = fs.build_selection(alive, [], max_new=2)
        self.assertEqual(len(new), 2)
        self.assertIn("河南戏曲广播", [e["name"] for e in new])

    def test_mirror_capped_at_three_sources(self):
        exist = [{"id": "st_x", "name": "某台", "streamUrl": "https://a/1.m3u8", "sources": []}]
        got = fs.apply_mirror(exist, {"某台": ["https://a/2.m3u8", "https://a/3.m3u8",
                                              "https://a/4.m3u8", "https://a/5.m3u8"]})
        self.assertEqual(len(got[0]["sources"]), 3)


class TestOverseasLanguageRule(unittest.TestCase):
    """海外候选按 language 字段筛，不按台名文字。

    上一版用“台名必须含汉字”筛选，实测错了：候选池 213 条的 language 全部含 chinese，
    很多合法双语台（M80 Radio Macau = chinese,portuguese）只因名字是拉丁字母就被误杀。
    """

    def _c(self, name, cc, url, language="", tags="", votes=5):
        return {"name": name, "cc": cc, "url": url, "url_resolved": url, "host": "h.com",
                "bitrate": 128, "votes": votes, "country": "X", "homepage": "",
                "language": language, "tags": tags}

    def test_chinese_language_field_accepted(self):
        alive = [self._c("Radio Mast Chinese", "US", "https://a/1.mp3", language="chinese")]
        new, _, _ = fs.build_selection(alive, [])
        self.assertEqual(len(new), 1)

    def test_english_language_field_accepted(self):
        """英语台也要收：用户要的是华语 + 英语，不是仅华语。"""
        alive = [self._c("Classic FM", "GB", "https://a/2.mp3", language="english", votes=300)]
        new, _, _ = fs.build_selection(alive, [])
        self.assertEqual(len(new), 1)
        self.assertEqual(new[0]["frequency"], "英国 · 英语")

    def test_bilingual_station_prefers_chinese_label(self):
        alive = [self._c("VOA 华语部", "US", "https://a/3.mp3",
                         language="chinese,english")]
        new, _, _ = fs.build_selection(alive, [])
        self.assertEqual(new[0]["frequency"], "美国 · 华语")

    def test_portuguese_only_is_rejected(self):
        alive = [self._c("M80 Radio Macau - Rock", "MO", "https://a/4.mp3",
                         language="portuguese")]
        new, _, _ = fs.build_selection(alive, [])
        self.assertEqual(new, [])

    def test_field_says_chinese_but_no_chinese_trace_is_rejected(self):
        """宁缺勿错：葡语台靠脏字段冒充华语台会被显示成“澳门·华语”，那是用户能看见的错。

        代价是有中文服务但台名全拉丁的台会被漏掉，比把外语台标成华语台更可接受。
        """
        alive = [self._c("M80 Radio Macau - 107.1 FM", "MO", "https://a/5.mp3",
                         language="chinese,portuguese")]
        new, _, _ = fs.build_selection(alive, [])
        self.assertEqual(new, [])

    def test_bilingual_with_chinese_name_accepted(self):
        """台名有中文痕迹 + 字段含 chinese 就收（“chinese,portuguese” 是真实取值）。"""
        alive = [self._c("澳门电台中文频道", "MO", "https://a/5b.mp3",
                         language="chinese,portuguese")]
        new, _, _ = fs.build_selection(alive, [])
        self.assertEqual(len(new), 1)
        self.assertEqual(new[0]["frequency"], "中国澳门 · 华语")

    def test_weak_english_station_needs_votes(self):
        """纯英语台靠 votes 筛质量：一个中文应用里的英语台得有人真听。"""
        alive = [self._c("KXYZ 92.3 Farm Town", "US", "https://a/6.mp3",
                         language="english", votes=1)]
        new, _, _ = fs.build_selection(alive, [])
        self.assertEqual(new, [])

    def test_cantonese_and_mandarin_variants_accepted(self):
        for lang in ("cantonese", "mandarin", "chinese,hakka,hokkien"):
            alive = [self._c("星岛普通话台", "AU", "https://a/7-" + lang + ".mp3", language=lang)]
            new, _, _ = fs.build_selection(alive, [])
            self.assertEqual(len(new), 1, lang)

    def test_english_substring_does_not_count_as_english(self):
        """token 必须精确相等：“new english”不算英语服务，sglish/creole 也不能误命中。"""
        alive = [self._c("Station X", "NG", "https://a/8.mp3", language="igbo,creole", votes=500)]
        new, _, _ = fs.build_selection(alive, [])
        self.assertEqual(new, [])

    def test_english_token_outside_native_countries_rejected(self):
        """实测坑：按 language=english 拉会出来印度/尼泊尔台（只带 english 标签）。"""
        alive = [self._c("Kantipur FM", "NP", "https://a/11.mp3", language="english", votes=9000),
                 self._c("Hits Of Lata Mangeshkar", "IN", "https://a/12.mp3",
                         language="english,hindi", votes=9000)]
        new, _, _ = fs.build_selection(alive, [])
        self.assertEqual(new, [])

    def test_bbc_from_uk_accepted(self):
        alive = [self._c("BBC World Service", "GB", "https://a/13.mp3",
                         language="english", votes=5000)]
        new, _, _ = fs.build_selection(alive, [])
        self.assertEqual(len(new), 1)
        self.assertEqual(new[0]["frequency"], "英国 · 英语")

    def test_empty_language_falls_back_to_cjk_in_name(self):
        """language 为空时用台名兼容：有汉字当华语，没汉字则不收。"""
        alive = [self._c("新西兰中文广播电台 FM90.6", "NZ", "https://a/9.mp3", language=""),
                 self._c("Anonymous Town Radio", "NZ", "https://a/10.mp3", language="")]
        new, _, _ = fs.build_selection(alive, [])
        self.assertEqual(len(new), 1)
        self.assertTrue(new[0]["name"].startswith("新西兰"))


class TestCategoryCap(unittest.TestCase):
    """每类限额。防的是“电视伴音候选最多，一个人吃掉 120 个名额”。"""

    def _it(self, cat, score):
        # 元组形状与 build_selection 的 ranked 一致：(score, key, first, cc, category, urls, lang)
        return (score, "k", {"name": "x"}, "CN", cat, ["https://a/%s.mp3" % score], "chinese")

    def test_single_category_cannot_take_all_slots(self):
        ranked = [self._it("tv_audio", 1000 - i) for i in range(50)] \
            + [self._it("world", 500 - i) for i in range(30)]
        chosen = fs.pick_indices(ranked, 60)
        cats = [ranked[i][4] for i in sorted(chosen)]
        self.assertEqual(len(chosen), 60)
        self.assertLessEqual(cats.count("tv_audio"), fs.CATEGORY_CAP["tv_audio"])
        self.assertGreater(cats.count("world"), 0)

    def test_leftover_slots_get_refilled(self):
        """限额用不完不能浪费名额：只有电视伴音候选时要把 60 个位填满。"""
        ranked = [self._it("tv_audio", 1000 - i) for i in range(50)]
        chosen = fs.pick_indices(ranked, 60)
        self.assertEqual(len(chosen), 50)

    def test_no_cap_when_max_new_zero(self):
        ranked = [self._it("tv_audio", i) for i in range(50)]
        self.assertEqual(len(fs.pick_indices(ranked, 0)), 50)

    def test_thin_categories_are_uncapped(self):
        ranked = [self._it("opera", 1000 - i) for i in range(30)]
        chosen = fs.pick_indices(ranked, 30)
        self.assertEqual(len(chosen), 30)

    def test_selection_end_to_end_respects_cap(self):
        """整条 selection 跑下来：电视伴音不能把名额吃干。"""
        alive = [{"name": "CCTV-%d综合伴音" % i, "cc": "CN", "url": "https://a/%d.m3u8" % i,
                  "url_resolved": "https://a/%d.m3u8" % i, "host": "piccpndali.example",
                  "bitrate": 128, "votes": 50, "country": "China", "homepage": "", "tags": "",
                  "language": "chinese"}
                 for i in range(1, 40)]
        alive += [{"name": nm, "cc": "CN", "url": "https://b/%d.m3u8" % i,
                   "url_resolved": "https://b/%d.m3u8" % i, "host": "stream.hndt.com",
                   "bitrate": 64, "votes": 30, "country": "China", "homepage": "", "tags": "",
                   "language": "chinese"}
                  for i, nm in enumerate(["河南戏曲广播", "绍兴戏曲广播", "安徽戏曲广播",
                                          "潮州戏曲广播", "上海戏曲曲艺广播"], start=1)]
        new, _, _ = fs.build_selection(alive, [], max_new=50)
        cats = {}
        for e in new:
            cats[e["category"]] = cats.get(e["category"], 0) + 1
        self.assertEqual(cats.get("opera", 0), 5)
        self.assertLessEqual(cats.get("tv_audio", 0), fs.CATEGORY_CAP["tv_audio"])


class TestChineseEvidence(unittest.TestCase):
    """华语台不能只看 language 字段。"""

    def _c(self, name, cc, url, language="chinese"):
        return {"name": name, "cc": cc, "url": url, "url_resolved": url, "host": "h.com",
                "bitrate": 128, "votes": 50, "country": "Poland", "homepage": "",
                "language": language, "tags": ""}

    def test_polish_dj_station_claiming_chinese_is_rejected(self):
        """实测行：候选池里 “RADIO CLUB DJ” 的 language 含 chinese，被标成“波兰·华语”。"""
        alive = [self._c("RADIO CLUB DJ", "PL", "https://a/1.mp3")]
        new, _, _ = fs.build_selection(alive, [])
        self.assertEqual(new, [])

    def test_english_named_chinese_service_still_accepted(self):
        """台名是拉丁但确实写着 Chinese 的服务不能误杀。"""
        alive = [self._c("Radio Mast Chinese", "US", "https://a/2.mp3")]
        new, _, _ = fs.build_selection(alive, [])
        self.assertEqual(len(new), 1)
        self.assertEqual(new[0]["frequency"], "美国 · 华语")

    def test_cjk_name_accepted_without_any_hint_word(self):
        alive = [self._c("城市廣播網•台南知音", "TW", "https://a/3.mp3")]
        new, _, _ = fs.build_selection(alive, [])
        self.assertEqual(len(new), 1)


if __name__ == "__main__":
    unittest.main()
