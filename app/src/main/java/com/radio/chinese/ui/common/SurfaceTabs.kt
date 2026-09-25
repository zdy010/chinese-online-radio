package com.radio.chinese.ui.common

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.radio.chinese.ui.theme.Dimens
import com.radio.chinese.ui.theme.usesRailNavigation

/** 一个页签：文案 + 图标。图标只在车机档的左侧导航栏上用得到。 */
data class SurfaceTab(val label: String, val icon: ImageVector)

/**
 * 页签栏双形态：手机档是顶部 TabRow，车机档是左侧 NavigationRail。
 *
 * 这不是"把 Tab 删掉"——同一批页签、同一个 currentPage 与 onSelect，
 * HorizontalPager 的左右滑动照常可用；只是横屏上顶部横条既占高度又浪费宽度，
 * 换成左侧栏后内容区能拿到整屏高度。
 */
@Composable
fun SurfaceTabBar(
    tabs: List<SurfaceTab>,
    currentPage: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (usesRailNavigation()) {
        NavigationRail(modifier = modifier.fillMaxHeight()) {
            tabs.forEachIndexed { index, tab ->
                NavigationRailItem(
                    selected = currentPage == index,
                    onClick = { onSelect(index) },
                    icon = {
                        Icon(
                            tab.icon,
                            contentDescription = null,
                            modifier = Modifier.size(Dimens.IconLarge)
                        )
                    },
                    label = { Text(tab.label, style = MaterialTheme.typography.titleSmall) },
                    // M3 的 rail 项目宽度固定 80dp，不铺满就凑不到 88dp 短边
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Dimens.TouchMin)
                )
            }
        }
    } else {
        TabRow(selectedTabIndex = currentPage, modifier = modifier) {
            tabs.forEachIndexed { index, tab ->
                Tab(
                    selected = currentPage == index,
                    onClick = { onSelect(index) },
                    text = { Text(tab.label) }
                )
            }
        }
    }
}
