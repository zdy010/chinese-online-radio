package com.radio.chinese.domain.sidecar

import java.nio.charset.Charset

/**
 * 由音频路径/URL 推导同名附属文件路径：只替换最后一段的尾扩展名，
 * 查询参数与百分号编码原样保留（123 云盘 href 带 %E8%B1%AB、M3U 条目带 ?token=）。
 * ext 不含点；base 或 ext 为空时原样返回，调用方无需再做防御。
 */
fun sidecarPath(base: String, ext: String): String {
    if (base.isEmpty() || ext.isEmpty()) return base
    val q = base.indexOf('?')
    val pathPart = if (q >= 0) base.substring(0, q) else base
    val tail = if (q >= 0) base.substring(q) else ""
    val seg = pathPart.substringAfterLast('/')
    val dot = seg.lastIndexOf('.')
    val newPath = if (dot > 0) {
        pathPart.dropLast(seg.length - dot) + "." + ext
    } else {
        "$pathPart.$ext"
    }
    return newPath + tail
}

/**
 * 歌词字节解码：UTF-8（含去 BOM）优先，出现替换符说明多半是老工具产出的 GBK 文件，
 * 回退 GB18030（Android 与 JVM 都带该 charset）。
 */
fun decodeTextBytes(bytes: ByteArray): String {
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
        return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
    }
    val utf8 = String(bytes, Charsets.UTF_8)
    return if (utf8.contains('\uFFFD')) String(bytes, Charset.forName("GB18030")) else utf8
}
