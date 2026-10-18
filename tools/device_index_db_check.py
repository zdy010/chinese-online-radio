"""把设备上 Room 的 audio_library_database 拉下来，用真 SQLite 核对索引与搜索。

设备侧 uiautomator 打不了中文（input text 只支持 ASCII），所以这里不靠界面输入，
直接验证两件事：
1. 后台索引是否真的把整棵目录树落进了 audio_cache（行数、目录数、曲目名抽样）；
2. AudioCacheDao.searchTracks 那条 SQL 在真实数据上能否命中，且不会返回目录行。
"""
import sqlite3
import subprocess
import sys

ADB = sys.argv[1] if len(sys.argv) > 1 else "adb"
LOCAL = ".qtest/device_audio_library.db"   # 拉下来的设备库副本，.qtest/ 不入库

subprocess.run([ADB, "exec-out", "run-as", "com.radio.chinese.debug",
                "cat", "databases/audio_library_database"],
               stdout=open(LOCAL, "wb"), check=True)

con = sqlite3.connect(LOCAL)
q = lambda sql, *a: con.execute(sql, a).fetchall()

total = q("SELECT COUNT(*) FROM audio_cache")[0][0]
files = q("SELECT COUNT(*) FROM audio_cache WHERE isFolder = 0")[0][0]
dirs = q("SELECT COUNT(DISTINCT parentPath) FROM audio_cache")[0][0]
sources = q("SELECT id, name, lastSyncAt FROM audio_sources")
print(f"audio_cache rows={total} files={files} distinctParentPaths={dirs}")
print(f"sources={sources}")

# DAO 里那条 SQL 原样执行（命名参数）
SQL = """
SELECT name, path FROM audio_cache
WHERE isFolder = 0
  AND (:sourceId IS NULL OR sourceId = :sourceId)
  AND name LIKE :pattern ESCAPE '\\'
ORDER BY name ASC
LIMIT :limit
"""
for kw in ("花木兰", "穆桂英", "七仙女", "100%", "a_b"):
    pat = "%" + kw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
    hits = con.execute(SQL, {"sourceId": None, "pattern": pat, "limit": 200}).fetchall()
    print(f"search {kw!r:10} -> {len(hits)} 命中  样例={[h[0] for h in hits[:3]]}")

# 索引新鲜度判定所需的目录时间戳分布
stamps = q("SELECT parentPath, MIN(cachedAt), COUNT(*) FROM audio_cache GROUP BY parentPath ORDER BY 2 DESC LIMIT 3")
print("最新三个目录:", stamps)

names = [r[0] for r in q("SELECT name FROM audio_cache WHERE isFolder = 0 LIMIT 5")]
print("落库曲目名抽样:", names)
