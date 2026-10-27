package com.radio.chinese.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.radio.chinese.ui.theme.isCarSurface

/**
 * 「正在播放」波浪标记：四根错相跳动的短条。
 *
 * 为什么不用一个静态 GraphicEq 图标：静态图标在列表里既不像「正在响」，也不够扎眼，
 * 用户实测要求「后边画波浪线，颜色突出」。动起来的条形均衡器一眼能看出声音在走。
 *
 * 只在播放中挂载：暂停/缓冲时整个组件不参与组合，动画才不会空跑
 * （rememberInfiniteTransition 会持续产帧，常驻就是白耗重组）。
 *
 * 直接动画每根条的高度比例，不用 scale：scale 会连带圆角一起拉伸，
 * 矮下去时四根条会变成四个扁椭圆。
 *
 * @param size 整体边长；调用方一般给 Dimens.IconMedium
 */
@Composable
fun NowPlayingWave(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
) {
    // 车机档放大一档：16dp 的动效在 10 寸屏 + 行车距离下基本看不出来
    val side = if (isCarSurface()) size * 1.5f else size
    val tint = MaterialTheme.colorScheme.primary
    // 条宽与间隙都取 side/8：四根条 4×side/8 加三道缝 3×side/8 = 7/8·side，稳稳装进 side。
    // 早先写成 side/6（一根占 1/3·side）时内容宽 4/3·side，第四根被容器裁掉，
    // 真机上只看得到三根——2026-10-27 真视觉验证发现（不是“渲染慢”，是永远出不来）。
    val barWidth = side / 8f
    val gap = side / 8f
    val transition = rememberInfiniteTransition(label = "now-playing-wave")
    // 每根条不同周期 + 不同起始延迟：同频会像一整块在呼吸，错开才像频谱在跳
    val heights = listOf(380, 520, 440, 620).mapIndexed { i, dur ->
        transition.animateFloat(
            initialValue = 0.30f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = dur, delayMillis = i * 90, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "wave-bar-$i",
        )
    }
    Row(
        modifier = modifier.size(side),
        horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        heights.forEach { anim ->
            val fraction by anim
            Box(
                modifier = Modifier
                    .width(barWidth)
                    .height(side * fraction)
                    .background(tint, RoundedCornerShape(barWidth / 2f))
            )
        }
    }
}
