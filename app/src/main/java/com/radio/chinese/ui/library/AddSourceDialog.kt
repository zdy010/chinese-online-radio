package com.radio.chinese.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.radio.chinese.domain.SourceType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSourceDialog(
    isLoading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, type: SourceType, url: String, username: String, password: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(SourceType.WEBDAV) }
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showTypeMenu by remember { mutableStateOf(false) }

    val types = listOf(
        SourceType.WEBDAV to "WebDAV",
        SourceType.LOCAL to "本地存储",
        SourceType.M3U to "M3U 播放列表",
        SourceType.HTTP to "HTTP 直链"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加音频库") },
        text = {
            // 可滚动：选本地存储以外的类型、大字号下五个输入框会把按钮顶到窗口外
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 来源类型：以前是 Box + 普通 DropdownMenu + 一层 matchParentSize 点击遮罩，
                // 在对话框里点开后菜单根本不显示（弹窗窗口层级问题），
                // 导致本地存储 / M3U / HTTP 三种源无法添加。改用与“添加电台”一致的
                // ExposedDropdownMenuBox（实测在 AlertDialog 内可正常弹出）。
                ExposedDropdownMenuBox(
                    expanded = showTypeMenu,
                    onExpandedChange = { if (!isLoading) showTypeMenu = it }
                ) {
                    OutlinedTextField(
                        value = types.first { it.first == selectedType }.second,
                        onValueChange = {},
                        label = { Text("来源类型") },
                        readOnly = true,
                        singleLine = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(showTypeMenu) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        enabled = !isLoading
                    )
                    ExposedDropdownMenu(
                        expanded = showTypeMenu,
                        onDismissRequest = { showTypeMenu = false }
                    ) {
                        types.forEach { (type, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = { selectedType = type; showTypeMenu = false }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("名称") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), enabled = !isLoading,
                    placeholder = { Text("如：123云盘·豫剧") }
                )

                if (selectedType != SourceType.LOCAL) {
                    OutlinedTextField(
                        value = url, onValueChange = { url = it },
                        label = { Text("服务器地址") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), enabled = !isLoading,
                        placeholder = { Text("https://webdav.123pan.cn/webdav") }
                    )
                }

                if (selectedType == SourceType.WEBDAV) {
                    OutlinedTextField(
                        value = username, onValueChange = { username = it },
                        label = { Text("用户名") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), enabled = !isLoading
                    )
                    OutlinedTextField(
                        value = password, onValueChange = { password = it },
                        label = { Text("密码") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), enabled = !isLoading,
                        visualTransformation = PasswordVisualTransformation()
                    )
                }

                if (error != null) {
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, selectedType, url, username, password) },
                enabled = (selectedType == SourceType.LOCAL || url.isNotBlank()) && !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("测试连接并保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
