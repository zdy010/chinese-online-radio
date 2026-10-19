package com.radio.chinese.domain.model

import kotlin.math.abs

/**
 * 戏曲/音频库点播的倍速档位。
 *
 * 作用范围是既定约束：**只对音频库点播开放，电台直播流一律原速**——时移直播流变速会
 * 变声，而且进度会越落越远导致反复缓冲，所以倍速只对能 seek 的点播有意义。
 * 这条范围放在纯函数里而不是散进 PlayerManager，是因为 PlayerManager 持有主线程
 * Handler，JVM 单测里根本构造不出来。
 *
 * 变速不变调：播放器侧用 PlaybackParameters(speed, pitch = 1f)，交给音频渲染器做
 * time-stretch；老年用户慢放听唱词时不希望声音变尖。
 */
object OperaSpeed {

    /** 可点选的档位，从慢到快 */
    val STEPS: List<Float> = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

    const val DEFAULT = 1.0f

    /**
     * 把任意输入吸附到档位上：先钳进两端，再取最近档。
     *
     * 钳制不是装饰——把 8x 原样透传给音频渲染器会静音或爆音。
     */
    fun normalize(raw: Float): Float {
        val clamped = raw.coerceIn(STEPS.first(), STEPS.last())
        return STEPS.minByOrNull { abs(it - clamped) } ?: DEFAULT
    }

    /** 循环推进一档；不在档位表里的当前值先吸附，再从其后一档继续。 */
    fun next(current: Float): Float {
        val v = normalize(current)
        val idx = STEPS.indexOf(v).let { if (it < 0) 0 else it }
        return STEPS[(idx + 1) % STEPS.size]
    }

    /** 播放器实际该用的速度：直播流一律原速。 */
    fun parametersFor(isOperaMode: Boolean, stored: Float): Float =
        if (isOperaMode) normalize(stored) else DEFAULT

    /** 给人看的档位文案：1.0 说"原速"，其余去掉多余的零（2.0 → 2x）。 */
    fun label(speed: Float): String {
        val v = normalize(speed)
        if (v == DEFAULT) return "原速"
        return v.toString().trimEnd('0').trimEnd('.') + "x"
    }
}
