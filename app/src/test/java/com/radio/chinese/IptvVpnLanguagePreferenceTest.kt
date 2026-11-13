package com.radio.chinese

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.radio.chinese.data.local.SettingsPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 新增两个 iptv 偏好在 DataStore 层的默认值闸门。
 *
 * 只测 IptvViewLogic 不够：那边假设传进来的开关与语言集合是默认值，
 * 而默认值写在 SettingsPreferences。谁把 `?: false` 改成 `?: true`，
 * 用户首次打开就会看到一堆需 VPN 的频道，纯逻辑测试与编译全绿。
 */
class IptvVpnLanguagePreferenceTest {

    private fun withStore(block: suspend (SettingsPreferences) -> Unit) = runTest {
        val file = File.createTempFile("iptv-vpn-prefs", ".preferences_pb")
        try {
            val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
                scope = backgroundScope,
                produceFile = { file },
            )
            block(SettingsPreferences(store))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `需 VPN 开关默认关`() = withStore { prefs ->
        assertFalse(prefs.iptvVpnOnlyEnabled.first())
    }

    @Test
    fun `需 VPN 开关可读写`() = withStore { prefs ->
        prefs.setIptvVpnOnlyEnabled(true)
        assertTrue(prefs.iptvVpnOnlyEnabled.first())
        prefs.setIptvVpnOnlyEnabled(false)
        assertFalse(prefs.iptvVpnOnlyEnabled.first())
    }

    @Test
    fun `语言多选默认是空集`() = withStore { prefs ->
        assertEquals(emptySet<String>(), prefs.iptvLanguageFilter.first())
    }

    @Test
    fun `语言多选可写可读也可清空`() = withStore { prefs ->
        prefs.setIptvLanguageFilter(setOf("spa", "rus"))
        assertEquals(setOf("spa", "rus"), prefs.iptvLanguageFilter.first())
        prefs.setIptvLanguageFilter(emptySet())
        assertEquals(emptySet<String>(), prefs.iptvLanguageFilter.first())
    }
}
