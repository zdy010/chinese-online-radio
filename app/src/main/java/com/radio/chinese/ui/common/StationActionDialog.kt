package com.radio.chinese.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.radio.chinese.domain.model.RadioStation

/**
 * 电台管理菜单：检测连接 / 标记无效或恢复 / 删除自定义。
 *
 * 原先只在节目源管理页里有一份私有实现，主页电台列表想复用就得复制一遍，
 * 分类表单一来源那次的教训同理——交互入口抽成共用组件，两个页面才不会各写各的。
 * [onCheck] 为空时不渲染“检测连接”行：主页不展示检测结果，给了也点了没反馈。
 */
@Composable
fun StationActionDialog(
    station: RadioStation,
    isInvalid: Boolean,
    isCustom: Boolean,
    onDismiss: () -> Unit,
    onMarkInvalid: () -> Unit,
    onMarkValid: () -> Unit,
    onDelete: () -> Unit,
    onCheck: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(station.name) },
        text = {
            Column {
                if (station.streamUrl.isNotBlank()) {
                    Text(
                        text = station.primaryUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                    val sourceCount = station.allSources().size
                    if (sourceCount > 1) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "共 $sourceCount 个节目源",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Check button
                if (onCheck != null) {
                    ListItem(
                        headlineContent = { Text("检测连接") },
                        leadingContent = { Icon(Icons.Default.Refresh, null) },
                        modifier = Modifier.clickable(onClick = onCheck)
                    )
                    HorizontalDivider()
                }

                // Mark invalid / valid
                if (isInvalid) {
                    ListItem(
                        headlineContent = { Text("恢复为有效") },
                        leadingContent = { Icon(Icons.Default.CheckCircle, null,
                            tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable(onClick = onMarkValid)
                    )
                } else {
                    ListItem(
                        headlineContent = { Text("标记为无效") },
                        supportingContent = { Text("标记后不在主页显示，但保留记录") },
                        leadingContent = { Icon(Icons.Default.Warning, null,
                            tint = MaterialTheme.colorScheme.error) },
                        modifier = Modifier.clickable(onClick = onMarkInvalid)
                    )
                }

                // Delete (only for custom)
                if (isCustom) {
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text("删除此电台") },
                        supportingContent = { Text("仅可删除自定义添加的电台") },
                        leadingContent = { Icon(Icons.Default.Delete, null,
                            tint = MaterialTheme.colorScheme.error) },
                        modifier = Modifier.clickable(onClick = onDelete)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}
