package com.radio.chinese.domain.model

/**
 * 播放可用性评分：连续成功/失败计数，用于台名着色与"按连续失败隐藏"。
 *
 * 语义（与用户约定的版本一致）：
 * - 失败：成功中失败一次**直接归 -1**，不是从 +50 减到 +49 —— 否则刚被坑过的台仍显示"极好"；
 * - 成功：失败中成功一次回到 +1（reset），连续成功继续累加；
 * - 上下界 [-9, +9]：无上界会让颜色不可分，也会让老台永久压住新台。
 *
 * 台级由"本次播放尝试"结果记一次；源级单独记（见 [sourceKey]），多源时任一成功算台成功。
 */
object PlayabilityScore {

    const val MIN = -9
    const val MAX = 9
    const val UNKNOWN = 0

    fun onSuccess(current: Int): Int =
        if (current <= 0) 1 else (current + 1).coerceAtMost(MAX)

    fun onFailure(current: Int): Int =
        if (current >= 0) -1 else (current - 1).coerceAtLeast(MIN)

    /** 连续失败 >= threshold 时隐藏；threshold <= 0 表示不隐藏（默认） */
    fun isHidden(score: Int, threshold: Int): Boolean = threshold > 0 && score <= -threshold

    fun levelOf(score: Int): PlayabilityLevel = when {
        score <= -6 -> PlayabilityLevel.BAD_SEVERE
        score <= -3 -> PlayabilityLevel.BAD
        score <= -1 -> PlayabilityLevel.WARN
        score == 0 -> PlayabilityLevel.UNKNOWN
        score <= 2 -> PlayabilityLevel.GOOD
        score <= 4 -> PlayabilityLevel.GOOD_STRONG
        else -> PlayabilityLevel.GOOD_BEST
    }
}

/** 台名着色档位。数值刻度见 [PlayabilityScore.levelOf]，颜色由 UI 层映射。 */
enum class PlayabilityLevel {
    BAD_SEVERE,
    BAD,
    WARN,
    UNKNOWN,
    GOOD,
    GOOD_STRONG,
    GOOD_BEST,
}
