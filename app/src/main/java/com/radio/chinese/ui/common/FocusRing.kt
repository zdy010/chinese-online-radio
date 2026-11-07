package com.radio.chinese.ui.common

import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * 焦点描边：电视/车机这类没有触摸的设备上，方向键移动焦点必须有可见反馈。
 *
 * 4dp 主色描边：实测 2dp 在浅紫卡片上几乎看不出来，4dp 才有明确边界。
 *
 * 不加阴影、不做缩放 —— 两者都会盖住相邻卡片（实测 1.03 缩放 + 10dp 阴影就能
 * 把右边卡片的封面压掉一块），而布局不会因此让位。
 *
 * 手机触摸设备也会命中（点按后短暂 focused），只是轻微高亮，不碍事。
 */
@Composable
fun Modifier.focusRing(shape: Shape = MaterialTheme.shapes.medium): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.isFocused }
        .then(
            if (focused) {
                // 只用描边，不加阴影：10dp 阴影会盖住相邻卡片（实测右侧封面被压掉一块）
                Modifier.border(4.dp, MaterialTheme.colorScheme.primary, shape)
            } else {
                Modifier
            }
        )
}
