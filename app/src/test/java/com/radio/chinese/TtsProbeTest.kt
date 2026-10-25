package com.radio.chinese

import com.radio.chinese.domain.model.CandidateSource
import com.radio.chinese.domain.model.EngineProbe
import com.radio.chinese.domain.model.TtsProbe
import com.radio.chinese.domain.model.TtsReport
import com.radio.chinese.domain.model.TtsVerdictLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 车机「肯定有引擎却读不到」的分级判定的纯逻辑部分。
 * 真机绑定/枚举的实际结果走真机裁决，这里只锁定判定与优先级不许含糊。
 */
class TtsProbeTest {

    private fun probe(
        pkg: String = "com.acme.tts",
        source: CandidateSource = CandidateSource.INTENT_QUERY,
        initOk: Boolean = true,
        voiceApi: Boolean = true,
        zhVoices: Int = 2,
        zhLang: Boolean = true,
    ) = EngineProbe(pkg, "引擎", source, initOk, voiceApi, zhVoices, zhLang)

    // ===== 候选合并：系统默认解析必须参与，且不能被枚举漏掉 =====

    @Test fun `候选首位恒为系统默认解析`() {
        val merged = TtsProbe.mergeCandidates(listOf("com.a", "com.b"), null)
        assertEquals(TtsProbe.SYSTEM_DEFAULT, merged.first().packageLabel)
        assertEquals(CandidateSource.SYSTEM_DEFAULT, merged.first().source)
    }

    @Test fun `设置项默认合成器即使枚举不到也进候选`() {
        val merged = TtsProbe.mergeCandidates(listOf("com.a"), "com.hidden.tts")
        assertTrue(merged.any { it.packageLabel == "com.hidden.tts" && it.source == CandidateSource.SETTINGS_DEFAULT_SYNTH })
    }

    @Test fun `同一包只留一个候选且保留先出现的来源`() {
        val merged = TtsProbe.mergeCandidates(listOf("com.a", "com.a"), "com.a")
        assertEquals(2, merged.size) // 系统默认 + com.a
        assertEquals(CandidateSource.INTENT_QUERY, merged.last().source)
    }

    @Test fun `空枚举与空设置项也只产生系统默认一个候选`() {
        val merged = TtsProbe.mergeCandidates(emptyList(), null)
        assertEquals(1, merged.size)
    }

    // ===== 可播判定：无音色清单不等于不能播 =====

    @Test fun `有中文音色即可播`() {
        assertTrue(TtsProbe.speakable(probe(zhVoices = 1, zhLang = false)))
    }

    @Test fun `老式引擎无音色清单但中文语言可用仍算可播`() {
        assertTrue(TtsProbe.speakable(probe(voiceApi = false, zhVoices = 0, zhLang = true)))
    }

    @Test fun `初始化失败的引擎永不入选`() {
        assertFalse(TtsProbe.speakable(probe(initOk = false, zhVoices = 5)))
    }

    @Test fun `绑定成功但中文既无音色也无语言支持算不可播`() {
        assertFalse(TtsProbe.speakable(probe(zhVoices = 0, zhLang = false)))
    }

    // ===== 分级诊断：车机现场要能分辨断在哪一环 =====

    @Test fun `枚举不到但系统有默认合成器时判为不可见引擎`() {
        val report = TtsReport(
            defaultSynthComponent = "com.hidden.tts/.TtsService",
            intentQueryCount = 0,
            engines = listOf(probe("com.hidden.tts", CandidateSource.SETTINGS_DEFAULT_SYNTH, initOk = false)),
        )
        val v = TtsProbe.verdict(report)
        assertEquals(TtsVerdictLevel.ENGINE_INVISIBLE, v.level)
        assertTrue(v.headline.contains("com.hidden.tts"))
    }

    @Test fun `系统未注册任何引擎判为需安装引擎`() {
        val report = TtsReport(defaultSynthComponent = null, intentQueryCount = 0, engines = emptyList())
        assertEquals(TtsVerdictLevel.NO_ENGINE, TtsProbe.verdict(report).level)
    }

    @Test fun `找到引擎但全部绑定失败判为绑定失败`() {
        val report = TtsReport(
            defaultSynthComponent = "com.a/.S",
            intentQueryCount = 1,
            engines = listOf(probe("com.a", initOk = false), probe("com.b", CandidateSource.SETTINGS_DEFAULT_SYNTH, initOk = false)),
        )
        assertEquals(TtsVerdictLevel.BIND_FAILED, TtsProbe.verdict(report).level)
    }

    @Test fun `绑定成功但无中文语音数据判为缺语音包`() {
        val report = TtsReport(
            defaultSynthComponent = "com.a/.S",
            intentQueryCount = 1,
            engines = listOf(probe("com.a", voiceApi = true, zhVoices = 0, zhLang = false)),
        )
        assertEquals(TtsVerdictLevel.NO_CHINESE_DATA, TtsProbe.verdict(report).level)
    }

    @Test fun `老式引擎走内置默认音色判为兼容可播`() {
        val report = TtsReport(
            defaultSynthComponent = "com.a/.S",
            intentQueryCount = 1,
            engines = listOf(probe("com.a", voiceApi = false, zhVoices = 0, zhLang = true)),
        )
        assertEquals(TtsVerdictLevel.LEGACY_DEFAULT_VOICE, TtsProbe.verdict(report).level)
    }

    @Test fun `有中文音色判为正常并给出数量`() {
        val report = TtsReport(
            defaultSynthComponent = "com.a/.S",
            intentQueryCount = 1,
            engines = listOf(probe("com.a", zhVoices = 3)),
        )
        val v = TtsProbe.verdict(report)
        assertEquals(TtsVerdictLevel.OK, v.level)
        assertTrue(v.detail.contains("3"))
    }

    @Test fun `诊断结论不许为空`() {
        val v = TtsProbe.verdict(TtsReport(null, 0, emptyList()))
        assertTrue(v.headline.isNotBlank())
        assertTrue(v.detail.isNotBlank())
    }

    // ===== 重试闸门：解析全失败后不得每次切台都重跑枚举绑定 =====

    @Test fun `闸门在窗口内拦截重复探测`() {
        val gate = TtsProbe.RetryGate(60_000L)
        assertFalse(gate.blocked(1_000))
        gate.block(1_000)
        assertTrue(gate.blocked(1_000 + 59_999))
        assertFalse(gate.blocked(1_000 + 60_000))
    }

    @Test fun `手动重置后立即可再探测`() {
        val gate = TtsProbe.RetryGate(60_000L)
        gate.block(0)
        gate.reset()
        assertFalse(gate.blocked(1))
    }
}
