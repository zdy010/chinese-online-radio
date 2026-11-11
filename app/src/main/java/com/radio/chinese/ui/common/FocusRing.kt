package com.radio.chinese.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * 焦点描边的**纯绘制**版本：焦点状态由调用方传入（通常来自 InteractionSource）。
 *
 * **Lazy 列表里不要用自己观察焦点的版本**：`Modifier.onFocusChanged` 在
 * `LazyVerticalGrid` 中失焦时不一定回调（实测焦点从第一行移到第二行，
 * 第一行收不到 `isFocused=false`，蓝框一直留在旧行上；第二行也收不到 `true`，
 * 于是屏幕上出现「框和焦点对不上」的闪烁感）。
 *
 * 用 `clickable(interactionSource = ...)` + `collectIsFocusedAsState()` 驱动，
 * 焦点状态与点击手势共用同一个 source，Compose 会稳定维护它。
 *
 * 只画在卡片内（`topLeft/size` 内缩半个线宽），不越界压到相邻卡片。
 */
@Composable
fun Modifier.focusRingVisual(
    focused: Boolean,
    shape: Shape = MaterialTheme.shapes.medium,
): Modifier {
    val tint = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent
    val width = 6.dp
    val corner = 12.dp
    return this.drawWithContent {
        drawContent()
        if (tint == Color.Transparent) return@drawWithContent
        val radius = CornerRadius(corner.toPx())
        // 整卡染色：只靠描边在深/浅主题下对比都不够，聚焦项要「整块亮起来」
        drawRoundRect(color = tint.copy(alpha = 0.22f), cornerRadius = radius)
        // 内描边：不外扩，不压到相邻卡片
        val w = width.toPx()
        drawRoundRect(
            color = tint,
            topLeft = Offset(w / 2, w / 2),
            size = Size(size.width - w, size.height - w),
            cornerRadius = radius,
            style = Stroke(width = w),
        )
    }
}

/**
 * 自带焦点观察的版本，给**非 Lazy 容器**用（九键面板按钮、候选行、入口卡）。
 * 列表行请用 [focusRingVisual] + InteractionSource。
 */
@Composable
fun Modifier.focusRing(shape: Shape = MaterialTheme.shapes.medium): Modifier {
    val focused = remember { mutableStateOf(false) }
    val observed = focused.value
    return this
        .onFocusChanged { focused.value = it.isFocused }
        .then(focusRingVisual(observed, shape))
}
