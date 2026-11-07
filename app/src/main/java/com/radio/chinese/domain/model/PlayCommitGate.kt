package com.radio.chinese.domain.model

import java.util.concurrent.atomic.AtomicLong

/**
 * 起播提交的世代号：只有最新一次起播意图允许把 MediaItem 交给播放器。
 *
 * iptv 台起播前要等 `headersFor()` 完成（首次会解析 6.33MB 清单并建 header 索引，
 * 调用方协程会挂起）。挂起期间用户可能切台、多源回退会推进到下一个源、也可能按停止。
 * 若不校验，挂起的协程恢复后会用**旧** MediaItem 和旧台的请求头覆盖当前播放：
 * UI 显示新台、播放器播旧台、`stop()` 之后又自动开播，而编译与单测都抓不到。
 *
 * 规则只有一句：新意图（含 stop、切戏曲这类"没有下一路"的动作）让此前所有未落地提交失效。
 * 放 domain 是为了让这条规则能被 JVM 单测钉住 —— PlayerManager 挂着 Hilt 与
 * MediaController，不宜直接起 JVM 测；落地层只负责登记与校验。
 */
class PlayCommitGate {
    private val generation = AtomicLong(0)

    /** 登记一次起播意图，返回它专属的 token。 */
    fun next(): Long = generation.incrementAndGet()

    /** 让所有尚未落地的提交失效（stop、切戏曲、节目源全部失败）。 */
    fun invalidate() { generation.incrementAndGet() }

    /** 该 token 是否仍代表最新意图。 */
    fun isCurrent(token: Long): Boolean = token == generation.get()
}
