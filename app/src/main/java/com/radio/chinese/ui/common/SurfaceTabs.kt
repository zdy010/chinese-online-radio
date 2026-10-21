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
import com.radio.chinese.ui.theme.canHostRail

/** 一个页签：文案 + 图标。图标只在车机档的左侧导航栏上用得到。 */
data class SurfaceTab(val label: String, val icon: ImageVector)

/**
 * 页签栏双形态：手机档是顶部 TabRow，车机档是左侧 NavigationRail。
 *
 * 这不是"把 Tab 删掉"——同一批页签、同一个 currentPage 与 onSelect，
 * HorizontalPager 的左右滑动照常可用；只是横屏上顶部横条既占高度又浪费宽度，
 * 换成左侧栏后内容区能拿到整屏高度。
 *
 * [useRail] 由调用方传入：外层要按同一个结论决定「横向 Row 还是纵向 Column」，
 * 内层再自己判一次就会出现外层按 rail 排、内层却渲染顶部横条的错位。
 */
@Composable
fun SurfaceTabBar(
    tabs: List<SurfaceTab>,
    currentPage: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    useRail: Boolean = canHostRail(tabs.size),
) {
    if (useRail) {
        // 内层不能再算一遍系统栏：外层 Scaffold 已把状态栏与导航栏算进 padding 了，
        // NavigationRail 的默认 insets 会再加一次，凭空吃掉四五十 dp——
        // 5 个页签正好差这一口，第 5 个直接落在屏幕外
        NavigationRail(
            modifier = modifier.fillMaxHeight(),
            windowInsets = NestedWindowInsets,
        ) {
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
