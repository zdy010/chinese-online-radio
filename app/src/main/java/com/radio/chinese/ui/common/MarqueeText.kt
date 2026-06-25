package com.radio.chinese.ui.common

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 超长文本自动滚动（走马灯效果）
 * 当文本宽度超过容器宽度时自动左右滚动，否则正常显示省略号
 */
@Composable
fun MarqueeText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    enabled: Boolean = true
) {
    val density = LocalDensity.current

    SubcomposeLayout(modifier = modifier.clipToBounds()) { constraints ->
        // 先用省略号模式测量实际宽度
        val ellipsisPlaceable = subcompose("ellipsis") {
            Text(text = text, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }.first().measure(constraints)

        val containerWidth = ellipsisPlaceable.width
        val containerHeight = ellipsisPlaceable.height

        // 完整文本测量
        val fullPlaceable = subcompose("full") {
            Text(text = text, style = style, maxLines = 1, softWrap = false)
        }.first().measure(Constraints(maxWidth = Constraints.Infinity))

        val textWidth = fullPlaceable.width

        if (!enabled || textWidth <= containerWidth) {
            // 不需要滚动
            layout(containerWidth, containerHeight) {
                ellipsisPlaceable.place(0, 0)
            }
        } else {
            // 需要滚动
            val marqueePlaceable = subcompose("marquee") {
                MarqueeAnimatingText(text, style, textWidth, containerWidth, density.run { containerWidth.toDp() })
            }.first().measure(Constraints.fixed(containerWidth, containerHeight))

            layout(containerWidth, containerHeight) {
                marqueePlaceable.place(0, 0)
            }
        }
    }
}

@Composable
private fun MarqueeAnimatingText(text: String, style: TextStyle, textWidthPx: Int, containerWidthPx: Int, containerWidthDp: Dp) {
    val offsetAnim = remember { Animatable(0f) }
    val pauseMs = 1500L
    val scrollDuration = ((textWidthPx - containerWidthPx) / 60f * 1000f).toInt().coerceAtLeast(2000)

    LaunchedEffect(text) {
        while (isActive) {
            delay(pauseMs)
            offsetAnim.animateTo(
                targetValue = -(textWidthPx - containerWidthPx).toFloat(),
                animationSpec = tween(durationMillis = scrollDuration, easing = LinearEasing)
            )
            delay(pauseMs)
            offsetAnim.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = scrollDuration, easing = LinearEasing)
            )
        }
    }

    Box(modifier = Modifier.fillMaxWidth().clipToBounds()) {
        Text(
            text = text,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = with(LocalDensity.current) { offsetAnim.value.toDp() }),
            style = style,
            maxLines = 1,
            softWrap = false
        )
    }
}
