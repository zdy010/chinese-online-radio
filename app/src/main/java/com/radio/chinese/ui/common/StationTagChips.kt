package com.radio.chinese.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.radio.chinese.domain.model.StationTag
import com.radio.chinese.ui.theme.Dimens

/**
 * 一台电台的标签 chips，点击跳到该标签的电台列表。
 *
 * 最多显示 4 个，按词表分组顺序取：详情页竖向空间很紧，车机 240dpi 档横屏
 * 只有 480dp 高，全铺会把播放/暂停键挤出首屏——那个代价项目里已经付过一次。
 * 命中区沿用 carTouchTarget：手机档尺寸不变，车机档抬到 88dp。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StationTagChips(
    tags: List<String>,
    onTagClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    max: Int = 4,
) {
    val shown = StationTag.byKeyOrder(tags, limit = max)
    if (shown.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Dimens.ChipGap),
        verticalArrangement = Arrangement.spacedBy(Dimens.GapSmall),
    ) {
        shown.forEach { tag ->
            FilterChip(
                selected = false,
                onClick = { onTagClick(tag.key) },
                modifier = Modifier.carTouchTarget(),
                label = { Text(tag.label) },
            )
        }
    }
}
