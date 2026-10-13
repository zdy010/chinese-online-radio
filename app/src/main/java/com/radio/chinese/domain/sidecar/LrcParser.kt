package com.radio.chinese.domain.sidecar

data class LrcLine(val timeMs: Long, val text: String)

/**
 * 标准 LRC 解析。只认 [mm:ss(.frac)] 时间标签：一行可挂多个时间戳；
 * [ti:] 之类元数据标签、无时间戳纯文本、坏行一律跳过（不中断整文件）；
 * 结果按时间升序。`[99:99.99]` 这类越界值照收——它只是永远到不了的一句词，无害。
 */
object LrcParser {
    private val TIME = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?\]""")
    private val LINE = Regex("""^((?:\[\d{1,2}:\d{2}(?:[.:]\d{1,3})?\])+)\s*(.*)$""")

    fun parse(text: String): List<LrcLine> {
        val out = mutableListOf<LrcLine>()
        for (raw in text.lineSequence()) {
            val m = LINE.matchEntire(raw.trim()) ?: continue
            val body = m.groupValues[2].trim()
            if (body.isEmpty()) continue
            for (tm in TIME.findAll(m.groupValues[1])) {
                val min = tm.groupValues[1].toLongOrNull() ?: continue
                val sec = tm.groupValues[2].toLongOrNull() ?: continue
                val fracRaw = tm.groupValues[3]
                val fracMs = when (fracRaw.length) {
                    0 -> 0L
                    1 -> (fracRaw.toLongOrNull() ?: 0L) * 100
                    2 -> (fracRaw.toLongOrNull() ?: 0L) * 10
                    else -> fracRaw.take(3).toLongOrNull() ?: 0L
                }
                out += LrcLine(min * 60_000 + sec * 1_000 + fracMs, body)
            }
        }
        return out.sortedBy { it.timeMs }
    }

    /** 播放位置对应的行号；未到第一行或空列表返回 -1。lines 必须已按时间排序。 */
    fun indexAt(lines: List<LrcLine>, posMs: Long): Int {
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= posMs) idx = i else break
        }
        return idx
    }
}
