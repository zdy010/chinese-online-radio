"""audit 的 live 可追溯检查：落库形态与实测形态只差会话参数时必须算同一条。

不认这种等价关系就会出假失败：清单里是剥掉 wsSession 的干净地址，
实测缓存里记的是当时取到的带 token 地址，两边字符串不等但确实是同一个源。
"""
import unittest

import audit_stations as au

CHECK = "每个地址都有本次 live 实测"


class TestLiveProvenance(unittest.TestCase):
    def _run(self, stored, measured):
        stations = [{
            "id": "st_中国交通广播", "name": "中国交通广播", "category": "traffic",
            "streamUrl": stored, "logoUrl": "", "frequency": "", "description": "",
            "website": "", "sources": [],
        }]
        rows = au.audit(stations, {measured}, prev_ids={"别的台"}, baseline=None)
        hit = [(ok, detail) for name, ok, detail in rows if CHECK in name]
        self.assertEqual(1, len(hit), [n for n, _, _ in rows])
        return hit[0]

    def test_session_param_cleaned_url_counts_as_measured(self):
        ok, detail = self._run(
            "https://satellitepull.cnr.cn/live/wxzgjtgb/playlist.m3u8",
            "https://satellitepull.cnr.cn/live/wxzgjtgb/playlist.m3u8"
            "?wsSession=28e11e-1749&wsIPSercert=f7b6cfa8",
        )
        self.assertTrue(ok, detail)

    def test_url_never_measured_still_fails(self):
        """反过来不许松：真没测过的地址必须继续报出来，否则这道闸就白设了。"""
        ok, detail = self._run(
            "https://never-probed.example.org/live.m3u8",
            "https://satellitepull.cnr.cn/live/wxzgjtgb/playlist.m3u8",
        )
        self.assertFalse(ok, detail)


if __name__ == "__main__":
    unittest.main()
