package com.radio.chinese.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.media3.common.Player
import com.radio.chinese.domain.model.RadioStation
import com.radio.chinese.service.PlayerManager

/** 当前在放哪个台、是否在放。列表行用它决定波浪标记与点击语义。 */
class NowPlayingState(val stationId: String?, val isPlaying: Boolean) {
    /** 该行是否就是正在播放的行（波浪标记的判据）。成员函数而非扩展：调用方不必多记一个 import。 */
    fun matches(station: RadioStation): Boolean = station.id == stationId && isPlaying
}

/**
 * 订阅 PlayerManager 的「当前台 + 是否在放」。
 *
 * 七个电台列表（首页 / 地区 / 分类 / 收藏 / 标签 / 最近 / 节目单）都要判同一件事，
 * 各自散着 collectAsState 会出现某一页漏判或写死 false（标签页此前就是 isPlaying = false）。
 */
@Composable
fun rememberNowPlaying(playerManager: PlayerManager): NowPlayingState {
    val station by playerManager.currentStation.collectAsState()
    val playing by playerManager.isPlaying.collectAsState()
    return NowPlayingState(station?.id, playing)
}

/**
 * 电台列表行点击的统一语义。
 *
 * 点一个不是当前的台：只起播，**留在列表里**。底部迷你条已经能显示在放什么、
 * 也能就地暂停，再自动跳全屏播放页等于每点一个台都要手动返回一次。
 * 点当前正在放的那个台：进播放页——这是用户主动要看细节、要换节目源的手势。
 * 但「所有节目源均无法播放」之后例外：再点同一个台是用户唯一的重试手势，
 * 这时候只把播放页打开等于把红色错误继续钉在屏上，必须重新起播
 * （2026-10-27 断网回归多源轮转时发现）。
 *
 * 直接走 playerManager.playStation：各 VM 上那层只负责转发的 playStation 已删，
 * 七个页面共用同一条起播路径，不会再出现某一页多一道处理。最近播放由 PlayerManager
 * 在真正确认起播成功后写入 DataStore，这里不能再维护第二份列表，否则两个写入源互相覆盖。
 * PlayerViewModel.loadStation 里有「同一台直接返回」的防重播保护，
 * 所以起播后再进播放页不会二次握手。
 */
@Composable
fun rememberStationRowOnClick(
    playerManager: PlayerManager,
    onNavigateToPlayer: (String) -> Unit,
): (RadioStation) -> Unit {
    val station by playerManager.currentStation.collectAsState()
    val error by playerManager.error.collectAsState()
    val playback = playerManager.playbackState.collectAsState().value
    val currentId = station?.id
    return { target ->
        val isCurrent = target.id == currentId
        // 报错或播放器已停下：这个台需要重新走一遍轮转，不能只当“看详情”
        val needsRetry = isCurrent && (error != null || playback == Player.STATE_IDLE)
        if (isCurrent && !needsRetry) onNavigateToPlayer(target.id)
        else playerManager.playStation(target)
    }
}
