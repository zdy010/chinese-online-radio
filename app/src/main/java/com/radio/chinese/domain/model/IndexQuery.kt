package com.radio.chinese.domain.model

/**
 * 索引与搜索的纯判定集合。
 *
 * SQL 侧只负责取行，语义（转义、归并、面包屑、新鲜度）全部留在这里：
 * 本项目没有 Robolectric，DAO 行为无法在 JVM 单测里验证，能把判定搬出 SQL
 * 的就搬出来，剩下真正无法单测的只有一条 LIKE 语句本身。
 */
object IndexQuery {

    /**
     * 转成 Room 的 LIKE 参数。转义顺序固定：先反斜杠，再 `%` 与 `_`，
     * 否则补出来的 `\%` 会被反斜杠规则再吃掉一层。
     * 配套 SQL 必须写 `ESCAPE '\'`。
     *
     * @return 空白输入返回空串，由调用方短路，不能把它送进 SQL
     */
    fun likePattern(raw: String): String {
        val q = raw.trim()
        if (q.isEmpty()) return ""
        val escaped = q.replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        return "%$escaped%"
    }
}
