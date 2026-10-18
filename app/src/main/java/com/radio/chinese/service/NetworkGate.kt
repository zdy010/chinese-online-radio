package com.radio.chinese.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.EnumSet
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

enum class NetCap { WIFI, ETHERNET, CELLULAR, NOT_METERED, METERED, NONE }

interface NetworkGate {
    fun isIndexingAllowed(): Boolean

    /** 挂起直到允许索引。不允许期间由调用方转 PausedNoWifi，禁止丢进度。 */
    suspend fun awaitAllowed()
}

/**
 * 后台索引的网络闸门。
 *
 * 本项目此前没有任何 ConnectivityManager 调用，索引是第一个需要"只在 Wi-Fi 跑"
 * 的后台行为，因此把判定拆成纯函数，设备侧只负责把 NetworkCapabilities 翻译成
 * NetCap 集合。
 */
@Singleton
class NetworkGateImpl @Inject constructor(
    @ApplicationContext private val appContext: Context,
) : NetworkGate {

    companion object {
        /**
         * 判定表：不计费且（Wi‑Fi 或以太），且不是蜂窝。
         *
         * 蜂窝即便上报 not-metered 也拒绝：部分车机 USB 共享就是这样报的，
         * 而索引是两百多次请求、可以后台慢慢做的事，不该消耗用户流量。
         */
        fun isAllowed(caps: Set<NetCap>): Boolean =
            NetCap.NOT_METERED in caps &&
                (NetCap.WIFI in caps || NetCap.ETHERNET in caps) &&
                NetCap.CELLULAR !in caps

        /** 框架能力 → 判定用枚举。抽出来是为了让单测不必造 NetworkCapabilities。 */
        fun classify(c: NetworkCapabilities): Set<NetCap> {
            val out = EnumSet.noneOf(NetCap::class.java)
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) out.add(NetCap.WIFI)
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) out.add(NetCap.ETHERNET)
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) out.add(NetCap.CELLULAR)
            // NetworkCapabilities 只有 NET_CAPABILITY_NOT_METERED，计费靠"没有该能力"表达
            if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) out.add(NetCap.NOT_METERED)
            else out.add(NetCap.METERED)
            if (out.size == 1) out.add(NetCap.NONE)
            return out
        }
    }

    private val cm: ConnectivityManager?
        get() = appContext.getSystemService(ConnectivityManager::class.java)

    override fun isIndexingAllowed(): Boolean {
        val manager = cm ?: return false
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return isAllowed(classify(caps))
    }

    /**
     * 等网络回到允许状态。用 suspendCoroutine 而不是 callbackFlow：
     * 调用方只需要"等到可以了"这一个信号，没有流式语义，不该引入 Flow。
     */
    override suspend fun awaitAllowed() {
        if (isIndexingAllowed()) return
        val manager = cm ?: return
        suspendCoroutine { cont ->
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = settle()
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (isAllowed(classify(caps))) settle()
                }

                override fun onLost(network: Network) = settle()   // 网络丢了就唤醒调用方重判，避免死等

                private fun settle() {
                    manager.unregisterNetworkCallback(this)
                    cont.resume(Unit)
                }
            }
            runCatching { manager.registerDefaultNetworkCallback(callback) }
                .onFailure { cont.resume(Unit) }    // 注册不上就放行，让调用方按当前状态判定
        }
    }
}
