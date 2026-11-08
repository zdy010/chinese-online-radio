package com.radio.chinese.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * 焦点描边：电视/车机这类没有触摸的设备上，方向键移动焦点必须有可见反馈（4dp 主色）。
 *
 * **关键：焦点变化只在 draw 阶段生效，不触发布局。**
 *
 * 早期实现把 `Modifier.border(...)` 按 focused 动态加进 Modifier 链，焦点一来一回就
 * 重建链、detach/attach 焦点目标节点；改成 border 常驻只换颜色后，链虽稳定，但
 * `focused` 仍是 Compose state，在 composition 里读它会让列表项重组 →
 * LazyVerticalGrid 重新测量 → 焦点项 bringIntoView → 再次测量。电视上在 iptv 中国页
 * 按方向键，表现为列表整块水平错位、列宽在相邻帧跳变、刷新停不下来。
 *
 * 现在 `onFocusChanged` 只写一个 state，`drawWithContent` 在绘制阶段读它：
 * 焦点变化只产生一次重绘，不改 Modifier 链、不重新测量、不触发 LazyGrid 滚动。
 *
 * [shape] 保留以兼容各调用点；描边统一用 12dp 圆角，与各卡片 shape 视觉一致。
 * 不加阴影、不做缩放 —— 两者都会盖住相邻卡片。
 */
@Composable
fun Modifier.focusRing(shape: Shape = MaterialTheme.shapes.medium): Modifier {
    val focused = remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    val width = 4.dp
    val corner = 12.dp
    return this
        .onFocusChanged { focused.value = it.isFocused }
        .drawWithContent {
            drawContent()
            if (focused.value) {
                drawRoundRect(
                    color = color,
                    cornerRadius = CornerRadius(corner.toPx()),
                    style = Stroke(width = width.toPx()),
                )
            }
        }
}
