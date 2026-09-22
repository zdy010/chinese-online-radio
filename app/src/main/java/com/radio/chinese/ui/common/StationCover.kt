package com.radio.chinese.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage

/**
 * 电台/节目封面。
 *
 * 底层永远先画一个占位块（有台名就显台名缩写，否则显收音机图标），
 * 图片只叠在上层：因此**缺地址和加载失败都不会变成空洞的灰圈**。
 * 此前只对空 URL 兜底，而现有 4 个 logo 指向已失效的域名，
 * 结果就是看到的“没图标”。
 */
@Composable
fun StationCover(
    url: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    label: String? = null,
    contentDescription: String? = null
) {
    val container = MaterialTheme.colorScheme
    val (tileBg, tileFg) = placeholderPalette(container, label)

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(tileBg),
        contentAlignment = Alignment.Center
    ) {
        if (label.isNullOrEmpty()) {
            Icon(
                imageVector = Icons.Default.Radio,
                contentDescription = contentDescription,
                modifier = Modifier.size(size * 0.45f),
                tint = tileFg
            )
        } else {
            // 中文台名取前两字（“中国之声”->“中国”），拉丁台名取单词首字母
            // （“BBC World Service”->“BWS”），避免出现“BB”这种没意义的截断。
            Text(
                text = coverAbbreviation(label),
                style = if (label.length > 4) MaterialTheme.typography.titleSmall
                else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = tileFg,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }

        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

/** 占位缩写：CJK 取前两字，拉丁名取单词首字母（最多 3 个）。 */
internal fun coverAbbreviation(label: String): String {
    val cleaned = label.trim()
    if (cleaned.isEmpty()) return ""
    val hasCjk = cleaned.any { it.code in 0x4E00..0x9FFF }
    if (hasCjk) return cleaned.take(2)
    val initials = cleaned.split(" ", "-", "_", ".")
        .mapNotNull { word -> word.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() }
    return when {
        initials.isEmpty() -> cleaned.take(2)
        initials.size >= 2 -> initials.take(3).joinToString("")
        else -> cleaned.take(2).uppercase()
    }
}

/** 只用 M3 语义色对，不引入硬编码颜色；同一台颜色稳定不跳。 */
private fun placeholderPalette(scheme: androidx.compose.material3.ColorScheme, label: String?): Pair<Color, Color> {
    val pairs = listOf(
        scheme.primaryContainer to scheme.onPrimaryContainer,
        scheme.secondaryContainer to scheme.onSecondaryContainer,
        scheme.tertiaryContainer to scheme.onTertiaryContainer,
        scheme.surfaceVariant to scheme.onSurfaceVariant
    )
    val idx = (label?.hashCode()?.rem(pairs.size)) ?: 0
    return pairs[if (idx < 0) idx + pairs.size else idx]
}
