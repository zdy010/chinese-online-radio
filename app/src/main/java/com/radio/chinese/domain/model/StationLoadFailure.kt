package com.radio.chinese.domain.model

/**
 * 按 id 起播失败时给用户看的那句话。
 *
 * PlayerViewModel.loadStation 是通知栏点回、媒体键恢复、进程死亡后重建共用的入口；早先解析到 null
 * 就 `if (station != null)` 什么都不做，用户只看到"点了没反应"。
 * iptv 失败多半是内置清单没加载出来，提示要指向 iptv 页，别让人误以为是网络问题。
 */
object StationLoadFailure {
    fun messageFor(isIptv: Boolean): String = if (isIptv) {
        "找不到该频道：内置清单可能未加载成功，请返回 iptv 页重试"
    } else {
        "找不到该电台，可能已从清单中移除"
    }
}
