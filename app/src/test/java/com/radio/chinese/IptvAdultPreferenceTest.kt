package com.radio.chinese

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.radio.chinese.data.local.SettingsPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V2 唯一的默认隐藏闸门，落在 DataStore 上的那一层。
 *
 * 只测 IptvViewLogic 不够：那边假设 adultEnabled 传进来是 false，
 * 而默认值写在 SettingsPreferences 里。谁把 `?: false` 改成 `?: true`，
 * 用户首次打开就看到成人频道，纯逻辑测试与编译全绿。
 */
class IptvAdultPreferenceTest {

    private fun withStore(block: suspend (SettingsPreferences) -> Unit) = runTest {
        val file = File.createTempFile("iptv-adult-prefs", ".preferences_pb")
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
    fun `成人开关默认关`() = withStore { prefs ->
        assertFalse(prefs.iptvAdultEnabled.first())
    }

    @Test
    fun `打开后可读回，关回也生效`() = withStore { prefs ->
        prefs.setIptvAdultEnabled(true)
        assertTrue(prefs.iptvAdultEnabled.first())
        prefs.setIptvAdultEnabled(false)
        assertFalse(prefs.iptvAdultEnabled.first())
    }
}
