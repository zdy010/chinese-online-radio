package com.radio.chinese.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.radio.chinese.domain.PinyinIndex
import com.radio.chinese.domain.model.NineKeyPinyin
import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.domain.model.StationDisplayName
import com.radio.chinese.ui.theme.Dimens

/**
 * 电视上的搜索入口：替代 [SearchBarRow]。
 *
 * TV 用软键盘一个字母一个字母选太慢，改成「点开九键面板」：
 * 2=ABC … 9=WXYZ，按数字序列匹配台名拼音首字母。
 */
@Composable
fun TvSearchEntry(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.GapTiny),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .focusRing(MaterialTheme.shapes.medium)
                .fillMaxWidth()
                // 与音频库的 AppSearchField 同一下限（车机档 64dp）。
                // 用 TouchMin(88dp) 会把列表整体顶下去，和另外两屏顶部对不齐。
                .heightIn(min = Dimens.TouchSecondary)
                .clickable(onClick = onOpen)
                .padding(horizontal = Dimens.GapMedium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Search, contentDescription = null)
            Spacer(Modifier.width(Dimens.GapSmall))
            Text(
                text = "搜索（九键拼音首字母，如 2288 = CCTV）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            actions()
        }
    }
}

/**
 * 九键搜索面板。
 *
 * 只显示前 60 条：电视上翻几屏还找不到就该加数字，而不是让人一直按方向键。
 */
@Composable
fun TvNineKeySearchDialog(
    stations: List<RadioStation>,
    onDismiss: () -> Unit,
    onSelect: (RadioStation) -> Unit,
) {
    var digits by remember { mutableStateOf("") }
    val results = remember(stations, digits) {
        if (digits.isEmpty()) emptyList()
        else stations
            .filter { NineKeyPinyin.matches(digits, PinyinIndex.initialsColumn(it.name)) }
            .take(60)
    }

    Dialog(
        onDismissRequest = onDismiss,
        // 默认对话框宽度按手机算，TV 上会缩成一小条
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .heightIn(min = 360.dp, max = 620.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Row(modifier = Modifier.padding(Dimens.GapMedium)) {
                Keypad(
                    digits = digits,
                    onDigit = { digits += it },
                    onDelete = { digits = digits.dropLast(1) },
                    onClear = { digits = "" },
                    modifier = Modifier.width(260.dp),
                )
                Spacer(Modifier.width(Dimens.GapMedium))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            digits.isEmpty() -> "用遥控器数字键输入台名拼音首字母\n例如 CCTV = 2288，中国之声 = 6447"
                            results.isEmpty() -> "没有匹配的台，换个数字试试"
                            else -> "匹配 ${results.size} 个"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Dimens.GapSmall))
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(results, key = { it.id }) { station ->
                            Row(
                                modifier = Modifier
                                    .focusRing(MaterialTheme.shapes.small)
                                    .fillMaxWidth()
                                    .heightIn(min = Dimens.TouchSecondary)
                                    .clickable { onSelect(station) }
                                    .padding(horizontal = Dimens.GapSmall),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = StationDisplayName.of(station),
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = stationSubtitleOf(station),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private val KEYS = listOf(
    "1" to "",
    "2" to "ABC",
    "3" to "DEF",
    "4" to "GHI",
    "5" to "JKL",
    "6" to "MNO",
    "7" to "PQRS",
    "8" to "TUV",
    "9" to "WXYZ",
)

@Composable
private fun Keypad(
    digits: String,
    onDigit: (String) -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Dimens.GapSmall)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                text = digits.ifEmpty { "—" },
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = Dimens.GapMedium, vertical = Dimens.GapSmall),
            )
        }
        KEYS.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.GapSmall)) {
                row.forEach { (digit, letters) ->
                    KeyButton(
                        label = digit,
                        hint = letters,
                        modifier = Modifier.weight(1f),
                        onClick = { onDigit(digit) },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.GapSmall)) {
            KeyButton("⌫", "", Modifier.weight(1f), onDelete)
            KeyButton("0", "", Modifier.weight(1f), { onDigit("0") })
            KeyButton("清空", "", Modifier.weight(1f), onClear)
        }
    }
}

@Composable
private fun KeyButton(
    label: String,
    hint: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier
            .focusRing(MaterialTheme.shapes.small)
            .heightIn(min = Dimens.TouchSecondary)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.GapTiny),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            if (hint.isNotEmpty()) {
                Text(
                    hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
