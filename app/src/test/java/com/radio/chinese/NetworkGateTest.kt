package com.radio.chinese

import com.radio.chinese.service.NetCap
import com.radio.chinese.service.NetworkGateImpl
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 判定表：只有“不计费 + (Wi‑Fi 或以太)”放行。
 *
 * 蜂窝即便上报 not-metered 也必须拒绝（部分车机 USB 共享就会这样）：
 * 索引是两百多次请求、可以后台慢慢做的事，不该消耗用户流量。
 */
class NetworkGateTest {
    private fun caps(vararg c: NetCap): Set<NetCap> = setOf(*c)

    @Test fun `wifi 不计费 放行`() {
        assertEquals(true, NetworkGateImpl.isAllowed(caps(NetCap.WIFI, NetCap.NOT_METERED)))
    }

    @Test fun `以太 不计费 放行（车机可能走 USB 共享或有线）`() {
        assertEquals(true, NetworkGateImpl.isAllowed(caps(NetCap.ETHERNET, NetCap.NOT_METERED)))
    }

    @Test fun `wifi 计费 拒绝`() {
        assertEquals(false, NetworkGateImpl.isAllowed(caps(NetCap.WIFI, NetCap.METERED)))
    }

    @Test fun `蜂窝即使不计费也拒绝`() {
        assertEquals(false, NetworkGateImpl.isAllowed(caps(NetCap.CELLULAR, NetCap.NOT_METERED)))
    }

    @Test fun `无网络 拒绝`() {
        assertEquals(false, NetworkGateImpl.isAllowed(caps(NetCap.NONE)))
    }

    @Test fun `能力集合为空 拒绝`() {
        assertEquals(false, NetworkGateImpl.isAllowed(emptySet()))
    }
}
