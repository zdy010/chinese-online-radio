package com.radio.chinese.domain.model

/** 与 Android Voice 解耦的音色元数据快照，便于纯函数单测。enginePackage/engineLabel 用于多引擎跨选。 */
data class VoiceMeta(
    val name: String,
    val localeTag: String,
    val isNetwork: Boolean,
    val isPremium: Boolean,
    val isCheap: Boolean,
    val enginePackage: String = "",
    val engineLabel: String = "",
)

/**
 * “挑好听自然的声音”的打分：premium/神经网络 +2；非低质 +1；联网 -1（车机弱网）。
 * bestChinese 只在中文 locale 中选最高分、分同取名小（稳定）。无中文返回 null。
 */
object VoiceScoring {
    fun isChinese(v: VoiceMeta): Boolean {
        val l = v.localeTag.lowercase()
        return l.startsWith("zh") || l.contains("chinese") || l.contains("cmn")
    }

    fun rank(v: VoiceMeta): Int {
        var s = 0
        if (v.isPremium) s += 2
        if (!v.isCheap) s += 1
        if (v.isNetwork) s -= 1
        return s
    }

    fun bestChinese(list: List<VoiceMeta>): VoiceMeta? =
        list.filter { isChinese(it) }
            .sortedWith(compareByDescending<VoiceMeta> { rank(it) }.thenBy { it.name })
            .firstOrNull()
}
