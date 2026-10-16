package com.radio.chinese

import com.radio.chinese.domain.model.VoiceMeta
import com.radio.chinese.service.VoiceCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * pickDefault 是纯逻辑（跨引擎取最优中文音色），可直接单测；
 * AndroidVoiceCatalog 枚举引擎/voice 的部分依赖真机，走真机裁决。
 */
class VoiceCatalogTest {
    @Test fun `pickDefault 跨引擎取最优中文音色`() {
        val opts = listOf(
            VoiceMeta("en1", "en-US", false, false, false, "com.a", "引擎A"),
            VoiceMeta("zh-low", "zh-CN", false, false, true, "com.a", "引擎A"),
            VoiceMeta("zh-premium", "zh-CN", false, true, false, "com.b", "引擎B"),
        )
        assertEquals("com.b", VoiceCatalog.pickDefault(opts)?.enginePackage)
    }

    @Test fun `无中文音色返回 null`() {
        assertNull(VoiceCatalog.pickDefault(listOf(VoiceMeta("en", "en-US", false, false, false))))
    }
}
