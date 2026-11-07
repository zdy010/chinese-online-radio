package com.radio.chinese.domain.model

/**
 * 切台基准的唯一选取规则（spec V11「跟当前页走」）。
 *
 * 改造前 `PlayerManager.handleMediaSkip()` 硬编码 `stationRepository.getActiveStations()`：
 * 在 iptv 播放页按方控滚轮会把人切回电台列表，用户看到的是"我明明在选电视频道"。
 *
 * 规则放 domain 是为了让 JVM 单测能直接钉住：PlayerManager 挂着 Hilt、协程作用域与
 * MediaController，不适合起 JVM 测；接线层只负责把两个输入递进来。
 */
object SkipBase {

    /**
     * [registered] 是当前页注册的基准（iptv 页注册自己的可见集合）。
     * null 与空表都视为未注册，回落 [radioActive] —— 电台页一行都不用改，
     * 行为与改造前一致。空表按未注册处理是因为清单加载期页面会先给一个空表，
     * 拿它当基准会让滚轮彻底切不动，比"暂时切回电台"更难诊断。
     */
    fun resolve(
        registered: List<RadioStation>?,
        radioActive: List<RadioStation>,
    ): List<RadioStation> = if (registered.isNullOrEmpty()) radioActive else registered
}
