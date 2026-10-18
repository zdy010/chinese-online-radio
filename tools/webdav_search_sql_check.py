"""用真 SQLite 验证 AudioCacheDao.searchTracks 那条 SQL 的 LIKE ESCAPE 语义。

本项目没有 Robolectric，Room 生成的查询无法在 JVM 单测里跑；转义纯函数
IndexQuery.likePattern 已有单测，但 `ESCAPE '\'` 子句本身只能拿真 SQLite 验。
这里把 DAO 注解里的 SQL 原样抄过来（用 sqlite3 命名参数），断言通配符不会失效成"匹配全部"。
"""
import sqlite3

SQL = """
SELECT * FROM audio_cache
WHERE isFolder = 0
  AND (:sourceId IS NULL OR sourceId = :sourceId)
  AND name LIKE :pattern ESCAPE '\\'
ORDER BY name ASC
LIMIT :limit
"""

ROWS = [
    (1, "花木兰 选段", "/豫剧/花木兰.mp3", "/豫剧", 0, 8_000_000),
    (1, "常香玉 花木兰", "/常香玉/花.mp3", "/常香玉", 0, 7_000_000),
    (2, "100%现场录音", "/g/live.mp3", "/g", 0, 6_000_000),
    (2, "a_b 唱段", "/g/ab.mp3", "/g", 0, 5_000_000),
    (2, "豫剧目录", "/豫剧", "/豫剧", 1, 0),          # 目录行不得出现在结果里
]


def like_pattern(raw):
    """与 IndexQuery.likePattern 保持一致：先转反斜杠，再 % 与 _。"""
    q = raw.strip()
    if not q:
        return ""
    esc = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    return f"%{esc}%"


def run(query, source_id=None, limit=200):
    pattern = like_pattern(query)
    if not pattern:
        return []
    con = sqlite3.connect(":memory:")
    con.execute("CREATE TABLE audio_cache (sourceId INTEGER, name TEXT, path TEXT, "
                "parentPath TEXT, isFolder INTEGER, size INTEGER)")
    con.executemany("INSERT INTO audio_cache VALUES (?,?,?,?,?,?)", ROWS)
    # 用命名参数：:sourceId 在 SQL 里出现两次，位置参数会数量对不上
    cur = con.execute(SQL, {"sourceId": source_id, "pattern": pattern, "limit": limit})
    return [r[1] for r in cur.fetchall()]


checks = []


def check(label, got, want):
    checks.append((label, got == want, got, want))


# 1. 普通中文子串（预期序按码位：常 U+5E38 在 花 U+82B1 之前，与 Kotlin sortedBy 一致）
check("中文子串命中两条", run("花木兰"), ["常香玉 花木兰", "花木兰 选段"])
# 2. % 不得退化成"匹配全部"
check("百分号按字面匹配", run("100%"), ["100%现场录音"])
# 3. _ 不得当单字符通配符
check("下划线按字面匹配", run("a_b"), ["a_b 唱段"])
check("下划线不误配 axb", run("a_b"), ["a_b 唱段"])
# 4. 目录行被排除
check("目录行不参与搜索", [n for n in run("豫剧")], [])
# 5. 单源过滤
check("限定 sourceId 后不含另一源", run("花木兰", source_id=1), ["常香玉 花木兰", "花木兰 选段"])
check("sourceId=2 搜不到豫剧源内容", run("花木兰", source_id=2), [])
# 6. 空查询短路
check("空白查询返回空", run("   "), [])

failed = [c for c in checks if not c[1]]
for label, ok, got, want in checks:
    print(f"{'PASS' if ok else 'FAIL'}  {label}  got={got} want={want}")
print(f"\nSUMMARY {len(checks) - len(failed)}/{len(checks)} pass")
raise SystemExit(1 if failed else 0)
