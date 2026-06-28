package com.radio.chinese.config

import com.radio.chinese.domain.SourceType

/**
 * 彩蛋/快捷码配置。
 *
 * 此文件已 git commit（占位版本），并标记 skip-worktree：
 *    git update-index --skip-worktree app/src/main/java/com/radio/chinese/config/EasterEggConfig.kt
 *
 * 本地可安全填入真实值，不会被 git 提交覆盖或误推。
 */
object EasterEggConfig {

    // ── 电台彩蛋 ──────────────────────────────────────────
    /** 在管理页添加电台时，流媒体地址输入此值触发加载默认电台列表 */
    const val RADIO_EASTER_EGG = "xxx"

    // ── 音频库快捷码 ───────────────────────────────────────
    /** 快捷码 → WebDAV 源配置映射（url 输入快捷码即可） */
    val QUICK_CODES: Map<String, QuickCodeEntry> = mapOf(
        "xxx" to QuickCodeEntry("123云盘·豫剧·按类型", SourceType.WEBDAV, "https://webdav.example.com", "username", "password"),
        "xxx2" to QuickCodeEntry("123云盘·豫剧·按演唱者", SourceType.WEBDAV, "https://webdav.example.com", "username", "password")
    )
}

data class QuickCodeEntry(
    val name: String,
    val type: SourceType,
    val url: String,
    val username: String,
    val password: String
)
