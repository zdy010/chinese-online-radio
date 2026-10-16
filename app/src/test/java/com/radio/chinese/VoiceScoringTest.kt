package com.radio.chinese

import com.radio.chinese.domain.model.VoiceMeta
import com.radio.chinese.domain.model.VoiceScoring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 音色评分：中文优先、premium 胜过普通、联网扣分、跨多引擎选最自然、无候选 null。
 */
class VoiceScoringTest {
    private fun v(name: String, lang: String = "zh-CN", net: Boolean = false, prem: Boolean = false, cheap: Boolean = false) =
        VoiceMeta(name, lang, net, prem, cheap)

    @Test fun `中文音色优先于非中文`() {
        assertNull(VoiceScoring.bestChinese(listOf(v("english", "en-US"))))
    }

    @Test fun `premium 胜过普通`() {
        val premium = v("zh premium", prem = true)
        val plain = v("zh plain")
        assertEquals(premium, VoiceScoring.bestChinese(listOf(plain, premium)))
    }

    @Test fun `联网音色扣分`() {
        val network = v("zh net", net = true)
        val cheap = v("zh cheap", cheap = true)
        assertEquals(cheap, VoiceScoring.bestChinese(listOf(network, cheap)))
    }

    @Test fun `无候选返回 null 而非崩溃`() {
        assertNull(VoiceScoring.bestChinese(emptyList()))
    }

    @Test fun `跨多个引擎选最自然的那个`() {
        val sys = v("sys voice", prem = false)
        val thirdPartyPremium = VoiceMeta("better", "zh-CN", isNetwork = false, isPremium = true, isCheap = false, enginePackage = "com.acme.tts", engineLabel = "ACME")
        assertEquals(thirdPartyPremium, VoiceScoring.bestChinese(listOf(sys, thirdPartyPremium)))
    }
}
