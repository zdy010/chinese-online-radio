"""derive_tags 的纯逻辑测试（不碰网络）。

三类会静默污染清单的错误是这里的主攻方向：
  1. 拼音撞名（越剧/粤剧）与和字段同名（country）
  2. 把真频率当「国家 · 语言」拆掉，老用户认不出台
  3. 把 adult contemporary 这种音乐格式当成人内容，误杀主流台
派生结果的"覆盖率"不在这里断言——覆盖率是实测事实，由 dry-run 报告，不写进测试当目标。
"""
import re
import unittest

import derive_tags as dt


class TestTagsFromName(unittest.TestCase):
    def test_dialect_names(self):
        self.assertIn("yuju", dt.tags_from_name("河南豫剧广播"))
        self.assertIn("yueju", dt.tags_from_name("听·越剧"))
        self.assertIn("yueopera", dt.tags_from_name("潮州粤曲广播"))
        self.assertIn("kunqu", dt.tags_from_name("江苏昆曲广播"))

    def test_generic_opera_name_gets_no_dialect_tag(self):
        """只写「戏曲」不写剧种的台拿不到剧种标签。

        用户已明确决定不加「戏曲合辑」这层中间标签，所以宁可空着也不猜成豫剧。
        """
        self.assertEqual(dt.tags_from_name("河南戏曲广播"), [])
        self.assertEqual(dt.tags_from_name("安徽戏曲广播"), [])

    def test_storytelling_and_crosstalk(self):
        self.assertIn("storytelling", dt.tags_from_name("单田芳评书广播"))
        self.assertIn("crosstalk", dt.tags_from_name("天津相声广播"))

    def test_classic_fm_is_classical_not_oldies(self):
        self.assertIn("classical", dt.tags_from_name("Classic FM"))

    def test_province_news_is_local_not_banned_word(self):
        """命中省份 + 新闻 → localnews，但不许产出与分类同名的 news。"""
        self.assertIn("localnews", dt.tags_from_name("浙江新闻广播"))
        self.assertNotIn("news", dt.tags_from_name("浙江新闻广播"))

    def test_no_banned_category_duplicate_keys(self):
        for name in ["北京新闻广播", "浙江交通之声", "山东体育休闲广播", "湖南经济广播"]:
            for tag in dt.tags_from_name(name):
                self.assertNotIn(tag, dt.BANNED_KEYS, f"{name} 派生出禁用语义 {tag}")

    def test_result_is_deduped_and_capped(self):
        got = dt.tags_from_name("豫剧京剧越剧黄梅戏评剧昆曲秦腔川剧沪剧评弹相声")
        self.assertLessEqual(len(got), 6)
        self.assertEqual(len(got), len(set(got)))

    def test_every_emitted_key_is_in_vocabulary(self):
        for name in ["戏曲", "古典音乐", "JAZZ FM", "路况广播", "少儿故事"]:
            for tag in dt.tags_from_name(name):
                self.assertIn(tag, dt.TAG_KEYS, name)


class TestMapRbTags(unittest.TestCase):
    def test_adult_contemporary_is_a_music_format_not_porn(self):
        self.assertEqual(dt.map_rb_tags("adult contemporary,pop"), ["lightmusic", "pop"])
        self.assertEqual(dt.map_rb_tags("hot adult contemporary"), ["lightmusic"])
        self.assertEqual(dt.map_rb_tags("adult hits"), ["oldies"])

    def test_smooth_jazz_and_classic_hits(self):
        self.assertEqual(dt.map_rb_tags("smooth jazz"), ["jazz"])
        self.assertEqual(dt.map_rb_tags("classic hits,oldies"), ["oldies"])

    def test_unknown_tags_are_dropped(self):
        """脏标签直接丢：waynesboro 是美国镇名，full service 是运营形态，不是内容。"""
        self.assertEqual(dt.map_rb_tags("waynesboro,full service"), [])

    def test_output_is_deduped_and_capped(self):
        tags = dt.map_rb_tags("rock,classic rock,metal,punk,blues,jazz,folk,country,oldies")
        self.assertLessEqual(len(tags), 6)
        self.assertEqual(len(tags), len(set(tags)))

    def test_order_follows_vocabulary_not_input(self):
        self.assertEqual(dt.map_rb_tags("pop,jazz"), ["jazz", "pop"])


class TestSplitFrequency(unittest.TestCase):
    def test_real_fm_value_is_kept(self):
        self.assertEqual(dt.split_frequency("FM 88.0"), ("", "", "FM 88.0"))
        self.assertEqual(dt.split_frequency("AM 846"), ("", "", "AM 846"))
        self.assertEqual(dt.split_frequency("FM 106.1"), ("", "", "FM 106.1"))

    def test_padded_country_language_is_extracted(self):
        self.assertEqual(dt.split_frequency("德国 · 英语"), ("德国", "英语", ""))
        self.assertEqual(dt.split_frequency("中国澳门 · 华语"), ("中国澳门", "华语", ""))

    def test_unknown_language_is_not_guessed(self):
        """分隔符有、但语言不在白名单里 → 整串原样留作频率，不猜。"""
        self.assertEqual(dt.split_frequency("某某 · 某某"), ("", "", "某某 · 某某"))

    def test_empty_stays_empty(self):
        self.assertEqual(dt.split_frequency(""), ("", "", ""))


class TestDescription(unittest.TestCase):
    def test_placeholder_desc_is_replaced_but_real_desc_is_kept(self):
        self.assertEqual(dt.strip_desc("全球节目源 · 德国 · 综合"), "")
        self.assertEqual(dt.strip_desc("中央人民广播电台第一套节目"), "中央人民广播电台第一套节目")

    def test_template_uses_only_verified_fields(self):
        got = dt.template_desc("德国", "英语", "新闻", ["本地新闻"])
        self.assertIn("德国", got)
        self.assertIn("英语", got)
        for banned in ("覆盖", "成立", "创建于", "著名", "权威", "领先", "唯一", "全省", "全国"):
            self.assertNotIn(banned, got)

    def test_template_without_any_info_is_empty(self):
        self.assertEqual(dt.template_desc("", "", "", []), "")

    def test_category_alone_is_not_a_description(self):
        """dry-run 实测坑：光有分类就生成句子，等于把分类重说了一遍，
        并让“简介覆盖率”假性变成 100%。"""
        self.assertEqual(dt.template_desc("", "", "交通", ["实时路况"]), "")

    def test_overseas_news_station_is_world_not_local(self):
        """CRI 类对外频率不能被打成“本地新闻”：它的新闻是国际新闻。"""
        got = dt.derive_station({"id": "gl_cn_cri", "name": "华语环球广播", "category": "news",
                                 "streamUrl": "https://a/1", "sources": [], "frequency": "",
                                 "description": "", "logoUrl": "", "website": ""},
                                {"cc": "CN", "language": "chinese", "tags": "news", "country_zh": "中国"})
        self.assertIn("worldnews", got["tags"])
        self.assertNotIn("localnews", got["tags"])

    def test_derive_drops_country_when_language_unknown(self):
        """语言推不出时国家也不写：“中国”可能是粤语/闽南语，猜就是编内容。"""
        got = dt.derive_station({"id": "x", "name": "某台", "category": "opera",
                                 "streamUrl": "https://a/1", "sources": [], "frequency": "",
                                 "description": "", "logoUrl": "", "website": ""},
                                {"country_zh": "中国", "language": "", "tags": ""})
        self.assertEqual(got["country"], "")
        self.assertEqual(got["language"], "")

    def test_no_fake_source_claim_when_meta_missing(self):
        """模板句不许长得像官网自述：不得带引文分隔符。"""
        got = dt.template_desc("英国", "英语", "音乐", ["爵士"])
        self.assertNotIn("—", got)
        self.assertNotIn(" - ", got)

    def test_meta_is_appended_as_quote_not_as_fact(self):
        body = dt.template_desc("英国", "英语", "音乐", ["爵士"])
        got = dt.compose_desc("", "the art and soul of jazz", body)
        self.assertIn("—", got)
        self.assertIn("the art and soul of jazz", got)
        self.assertLessEqual(len(got), 60)


class TestCleanMeta(unittest.TestCase):
    def test_extracts_meta_description(self):
        html = ('<html><head><title>Heart UK</title>'
                '<meta name="description" content="  The UK\'s biggest hits.  ">'
                "</head><body>x</body></html>")
        self.assertEqual(dt.clean_meta(html), "The UK's biggest hits.")

    def test_prefers_og_description_over_meta(self):
        html = ('<meta name="description" content="short">'
                '<meta property="og:description" content="longer official blurb">')
        self.assertEqual(dt.clean_meta(html), "longer official blurb")

    def test_title_is_not_used_as_description(self):
        """网站标题不是介绍：抽样看到过把站名当自述接在模板句后面的噪声。"""
        self.assertEqual(dt.clean_meta("<title>SomaFM Groove Salad</title>"), "")

    def test_strips_html_and_truncates(self):
        html = '<meta name="description" content="a <b>tag</b> ' + "x" * 200 + '">'
        got = dt.clean_meta(html)
        self.assertNotIn("<", got)
        self.assertLessEqual(len(got), 48)

    def test_nothing_useful_returns_empty(self):
        self.assertEqual(dt.clean_meta("<html><head></head></html>"), "")

    def test_reverse_attribute_order_still_works(self):
        """有些 CMS 写 `<meta content="..." name="description">`，不能只认一种属性顺序。"""
        html = '<meta content="reverse order blurb" name="description">'
        self.assertEqual(dt.clean_meta(html), "reverse order blurb")

    def test_apostrophe_in_value_is_not_a_quote_end(self):
        """真实坑：闭合引号用字符类会把 UK's 里的撇号当结束，把简介截成 'The UK'。"""
        html = '<meta name="description" content="The UK\'s #1 jazz station."'
        self.assertEqual(dt.clean_meta(html), "")   # #1 是营销断言，整条当没抓到
        html2 = '<meta name="description" content="Jazz, blues and the soul of New Orleans."'
        self.assertEqual(dt.clean_meta(html2), "Jazz, blues and the soul of New Orleans.")

    def test_neighboring_meta_is_not_mistaken_for_own_content(self):
        """两个 meta 相邻时，不得抓错内容（窗口法越界就是这个问题）。"""
        html = ('<meta name="description" content="A A">'
                '<meta property="og:description" content="B B">')
        self.assertEqual(dt.clean_meta(html), "B B")

    def test_url_in_meta_is_rejected(self):
        """带裸 URL 的自述不配进简介（实测抓到过“河南音乐广播（http://fm881.hnr.cn）…”）。"""
        html = '<meta name="description" content="河南音乐广播（http://fm881.hnr.cn）伴随品质音乐。">'
        self.assertEqual(dt.clean_meta(html), "")

    def test_unterminated_text_is_dropped_not_cut_mid_sentence(self):
        """48 字内收不了句就直接不要，不给车机留半句话。"""
        html = '<meta name="description" content="' + "很长的一段介绍没有结束符" * 6 + '">'
        self.assertEqual(dt.clean_meta(html), "")

    def test_cut_at_sentence_end_within_limit(self):
        """能收句就只留那一句，后面的长串丢掉。"""
        html = ('<meta name="description" '
                'content="中国精品短波广播。后面还有一长串不该出现在车机上的文字">')
        self.assertEqual(dt.clean_meta(html), "中国精品短波广播。")

    def test_short_phrase_without_terminator_is_kept(self):
        """真实 og:description 很多就是不带句号的长短语，不该被当半句丢掉。"""
        html = '<meta name="description" content="The art and soul of jazz">'
        self.assertEqual(dt.clean_meta(html), "The art and soul of jazz")

    def test_marketing_promise_is_rejected(self):
        html = '<meta name="description" content="award-winning best radio station">'
        self.assertEqual(dt.clean_meta(html), "")


class TestKeywordMatching(unittest.TestCase):
    """dry-run 实测报出 32 个“佛教台”，就是单字关键字造成的：
    「经济」里有「经」；Hit FM 里有「it」。"""

    def test_economy_station_is_not_buddhist(self):
        for name in ["河南经济广播", "山东经济广播", "已经开播"]:
            self.assertNotIn("buddhist", dt.tags_from_name(name), name)

    def test_hit_fm_is_not_tech(self):
        for name in ["Hit FM 97.3", "White Smoke Radio", "Traffic Report"]:
            self.assertNotIn("tech", dt.tags_from_name(name), name)

    def test_real_buddhist_and_tech_still_match(self):
        self.assertIn("buddhist", dt.tags_from_name("人间佛教广播"))
        self.assertIn("tech", dt.tags_from_name("IT科技之声"))

    def test_single_char_chinese_keywords_are_banned(self):
        """单字关键字几乎必然误匹配，表里不允许出现。"""
        for kws, _ in dt.NAME_RULES:
            for kw in kws:
                pure_ascii = bool(re.fullmatch(r"[a-z0-9 .&#'\-]+", kw.lower()))
                if not pure_ascii:
                    self.assertGreaterEqual(len(kw), 2, f"单字中文关键字会乱匹配: {kw}")


class TestValidate(unittest.TestCase):
    BASE = [{"id": "a", "name": "A", "category": "opera", "streamUrl": "https://x/1",
             "logoUrl": "", "frequency": "FM 88.0", "description": "旧简介", "website": "",
             "sources": [{"url": "https://x/1", "label": "", "bitrate": 0}]}]

    def test_clean_change_passes(self):
        after = [dict(self.BASE[0], tags=["yuju"], country="河南", language="华语")]
        self.assertEqual(dt.validate(self.BASE, after), [])

    def test_stream_urls_must_not_change(self):
        """本次只改文字与字段，动到流地址就是越界。"""
        after = [dict(self.BASE[0], streamUrl="https://x/dead", tags=["yuju"])]
        self.assertTrue(any("streamUrl" in p for p in dt.validate(self.BASE, after)))

    def test_sources_must_not_change(self):
        after = [dict(self.BASE[0], sources=[], tags=["yuju"])]
        self.assertTrue(any("sources" in p for p in dt.validate(self.BASE, after)))

    def test_unknown_tag_is_reported(self):
        after = [dict(self.BASE[0], tags=["京剧"])]
        self.assertTrue(any("词表外" in p for p in dt.validate(self.BASE, after)))

    def test_station_count_must_not_shrink(self):
        self.assertTrue(any("台数" in p for p in dt.validate(self.BASE, [])))

    def test_country_without_language_reported(self):
        after = [dict(self.BASE[0], country="德国", language="")]
        self.assertTrue(any("语言" in p for p in dt.validate(self.BASE, after)))

    def test_real_frequency_cannot_be_emptied(self):
        after = [dict(self.BASE[0], frequency="", tags=["yuju"])]
        self.assertTrue(any("频率" in p for p in dt.validate(self.BASE, after)))

    def test_too_many_tags_reported(self):
        after = [dict(self.BASE[0],
                      tags=["yuju", "jingju", "rock", "jazz", "pop", "folk", "blues"])]
        self.assertTrue(any("标签数" in p for p in dt.validate(self.BASE, after)))

    def test_duplicate_tags_reported(self):
        after = [dict(self.BASE[0], tags=["yuju", "yuju"])]
        self.assertTrue(any("重复" in p for p in dt.validate(self.BASE, after)))

    def test_id_set_change_reported(self):
        other = [dict(self.BASE[0], id="b")]
        self.assertTrue(any("id" in p for p in dt.validate(self.BASE, other)))


class TestVocabularySync(unittest.TestCase):
    """词表必须与 Kotlin 枚举逐项一致——脚本漂了而枚举没改，界面上就会出现英文原词。"""

    def _kotlin_keys(self):
        import re
        from pathlib import Path
        src = Path(__file__).resolve().parent.parent / \
            "app/src/main/java/com/radio/chinese/domain/model/StationTag.kt"
        text = src.read_text(encoding="utf-8")
        return re.findall(r'^\s*[A-Z0-9_]+\("([a-z0-9]+)",', text, re.M)

    def test_script_vocabulary_equals_kotlin_vocabulary(self):
        self.assertEqual(list(dt.TAG_KEYS), self._kotlin_keys())

    def test_ambiguity_guards(self):
        self.assertIn("yueopera", dt.TAG_KEYS)
        self.assertIn("countrymusic", dt.TAG_KEYS)
        self.assertNotIn("country", dt.TAG_KEYS)
        self.assertNotIn("news", dt.TAG_KEYS)

    def test_language_whitelist(self):
        self.assertIn("华语", dt.LANGUAGES)
        self.assertNotIn("德语 · 英国", dt.LANGUAGES)


class TestDerivedSemantics(unittest.TestCase):
    """抽样实看时抓到的三处“用户能看见的错”。"""

    def _st(self, name, cat, cc="CN", lang="chinese", tags="", freq="", zh="中国"):
        return dt.derive_station(
            {"id": "x", "name": name, "category": cat, "streamUrl": "https://a/1",
             "sources": [], "frequency": freq, "description": "", "logoUrl": "", "website": ""},
            {"cc": cc, "language": lang, "tags": tags, "country_zh": zh})

    def test_chinese_pop_station_is_cpop(self):
        """河南音乐广播 不能显示成“欧美流行”。"""
        got = self._st("河南音乐广播", "music", tags="pop")
        self.assertIn("cpop", got["tags"])
        self.assertNotIn("pop", got["tags"])

    def test_english_pop_station_stays_pop(self):
        got = self._st("Heart UK", "music", cc="GB", lang="english", tags="pop", zh="英国")
        self.assertIn("pop", got["tags"])
        self.assertNotIn("cpop", got["tags"])

    def test_world_category_not_written_into_description(self):
        """category=world 是收录容器，写进句子就是“英国的英语世界电台”。"""
        got = self._st("Radio X 90s", "world", cc="GB", lang="english", zh="英国")
        self.assertNotIn("世界电台", got["description"])
        self.assertIn("英国", got["description"])

    def test_mainland_opera_station_does_not_invent_genre(self):
        got = self._st("河南戏曲广播", "opera")
        self.assertEqual(got["tags"], [])
        self.assertIn("戏曲", got["description"])   # 分类进了句子，但没编造剧种


if __name__ == "__main__":
    unittest.main()
