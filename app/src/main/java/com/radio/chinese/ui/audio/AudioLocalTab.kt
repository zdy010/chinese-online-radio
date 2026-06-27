package com.radio.chinese.ui.audio

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.radio.chinese.domain.AudioTrack
import com.radio.chinese.domain.browser.LocalBrowser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AudioLocalTab(playerManager: com.radio.chinese.service.PlayerManager, searchQuery: String = "") {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<AudioTrack>>(emptyList()) }
    val filteredItems = items.filter { searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true) }
    var isLoading by remember { mutableStateOf(true) }
    var currentFolder by remember { mutableStateOf("") }
    var folderStack by remember { mutableStateOf<List<String>>(emptyList()) }

    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scope.launch { loadItems(context, currentFolder) { r -> items = r.getOrDefault(emptyList()); isLoading = false } }
    }
    val hasPerm = ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    fun reload() {
        isLoading = true
        scope.launch {
            loadItems(context, currentFolder) { r -> items = r.getOrDefault(emptyList()); isLoading = false }
        }
    }

    LaunchedEffect(Unit) {
        if (!hasPerm) {
            launcher.launch(perm)
        } else {
            reload()
        }
    }

    if (!hasPerm) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("需要媒体音频权限", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { launcher.launch(perm) }) { Text("授予权限") }
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (currentFolder.isNotEmpty()) {
            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                TextButton(onClick = {
                    currentFolder = ""
                    folderStack = emptyList()
                    reload()
                }) { Text("📁 根目录", style = MaterialTheme.typography.labelSmall) }
                if (folderStack.isNotEmpty()) {
                    Text(" > ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(currentFolder, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (filteredItems.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("未找到音频文件", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp)) {
                items(filteredItems) { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            if (item.isFolder) {
                                folderStack = folderStack + currentFolder
                                currentFolder = item.path
                                reload()
                            } else {
                                // 播放本地文件，构建播放列表
                                val audioItems = items.filter { !it.isFolder }
                                val playlist = audioItems.map { ai ->
                                    com.radio.chinese.domain.model.OperaAudioFile(
                                        fileId = ai.path.hashCode().toLong(),
                                        name = ai.name, size = ai.size,
                                        categoryName = "本地", operaName = "",
                                        downloadUrl = ai.path
                                    )
                                }
                                val index = audioItems.indexOfFirst { it.path == item.path }.coerceAtLeast(0)
                                playerManager.playOperaFile(playlist[index], playlist)
                            }
                        }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (item.isFolder) Icons.Default.Folder else Icons.Default.Audiotrack, null,
                            tint = if (item.isFolder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(item.name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

private suspend fun loadItems(context: android.content.Context, path: String, onResult: (Result<List<AudioTrack>>) -> Unit) {
    withContext(Dispatchers.IO) {
        val browser = LocalBrowser(context)
        val result = browser.listChildren(path)
        withContext(Dispatchers.Main) { onResult(result) }
    }
}
