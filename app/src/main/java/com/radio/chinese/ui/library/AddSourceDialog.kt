package com.radio.chinese.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.radio.chinese.domain.SourceType

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
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box {
                    OutlinedTextField(
                        value = types.first { it.first == selectedType }.second,
                        onValueChange = {},
                        label = { Text("来源类型") },
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading
                    )
                    DropdownMenu(expanded = showTypeMenu, onDismissRequest = { showTypeMenu = false }) {
                        types.forEach { (type, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = { selectedType = type; showTypeMenu = false }
                            )
                        }
                    }
                    Box(modifier = Modifier.matchParentSize().clickable(enabled = !isLoading) { showTypeMenu = true })
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
