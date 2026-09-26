package com.radio.chinese.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 方控/车机按键的现场诊断记录，进程内保留最近 50 条。
 *
 * 加装车机的按键链路差异太大——标准媒体键、Activity 按键、私有广播 Intent
 * 各走各的通道，只能上车按一遍才知道走的哪条。记录点埋在三处：
 * [com.radio.chinese.MainActivity.dispatchKeyEvent]（到达 Activity 的按键）、
 * [RadioService] 的 MediaSession 回调（媒体键与会话命令）、
 * [com.radio.chinese.MainActivity.onNewIntent]（被路由到 Activity 的 Intent）。
 * 设置页连点「关于」7 次可打开查看。
 */
object KeyDiagnostics {

    private const val MAX_ENTRIES = 50

    private val _entries = MutableStateFlow<List<String>>(emptyList())

    /** 最新记录在最前，最多 [MAX_ENTRIES] 条 */
    val entries: StateFlow<List<String>> = _entries

    fun record(tag: String, detail: String) {
        // SimpleDateFormat 非线程安全，而调用点分布在主线程与 Binder 线程，故每次新建
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _entries.update { current -> (listOf("$time  $tag  $detail") + current).take(MAX_ENTRIES) }
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
