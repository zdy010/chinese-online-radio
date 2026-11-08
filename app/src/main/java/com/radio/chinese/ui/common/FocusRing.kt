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
 * 焦点反馈：电视/车机这类没有触摸的设备上，方向键移动焦点必须一眼能看出停在哪。
 *
 * 三层递进（用户实测「只有 4dp 描边时和非焦点区分不明显」后加强）：
 * 1. 整块卡片染上主色（alpha 0.22）—— 远看就知道哪一块亮了
 * 2. 6dp 主色内描边 —— 近看有明确边界
 * 3. 描边**完全画在卡片 bounds 内**（topLeft/size 内缩半个线宽），不外扩压到相邻卡片
 *
 * **两个实测踩过的坑：**
 * 1. 必须放在 Modifier 链**最前面**（在 `clickable` 之前）。`onFocusChanged` 只观察
 *    链中位于它之后的焦点节点；放在 `itemModifier.focusRing()` 这种位置，clickable 的
 *    焦点目标在上游，回调永远收不到变化，描边整个消失。
 * 2. 焦点状态要在 **composition** 里折算成颜色再交给绘制 lambda。直接把 `State` 留给
 *    `drawWithContent` 读，绘制阶段不建立 snapshot 订阅，同样不会重绘。
 *    Modifier 链结构保持恒定（不按 focused 增删节点），只有颜色值变化。
 *    电视上已移除 iptv 的 HorizontalPager，列表项重组不会再触发横向错位。
 *
 * 早期实现把 `Modifier.border(...)` 按 focused 动态加进链里，会 detach/attach 焦点目标
 * 节点造成抖动，已废弃。不加阴影、不做缩放 —— 两者都会盖住相邻卡片。
 * [shape] 保留以兼容各调用点；描边统一用 12dp 圆角，与各卡片 shape 视觉一致。
 */
@Composable
fun Modifier.focusRing(shape: Shape = MaterialTheme.shapes.medium): Modifier {
    val focused = remember { mutableStateOf(false) }
    // 必须在 composition 里读 state：drawWithContent 的 lambda 属于绘制阶段，
    // 直接读 State 不会建立订阅，焦点变了不会重绘（实测描边整个消失）。
    // 这里只把它折算成一个颜色值，Modifier 链结构保持恒定。
    val tint = if (focused.value) MaterialTheme.colorScheme.primary else Color.Transparent
    val width = 6.dp
    val corner = 12.dp
    return this
        .onFocusChanged { focused.value = it.isFocused }
        .drawWithContent {
            drawContent()
            if (tint == Color.Transparent) return@drawWithContent
            val radius = CornerRadius(corner.toPx())
            // 整卡染色：叠在内容之上，alpha 低到不影响文字可读
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
