package com.radio.chinese.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 音频库来源的密码存储。
 *
 * `security-crypto` 依赖 API 23 的 `KeyGenParameterSpec`，而本分支 minSdk=22：
 * 在 Android 5.1 电视上直接创建加密存储会抛 `NoClassDefFoundError`。
 * 这里按系统版本分流——现代设备仍走 Keystore 加密，5.1 上退回普通 SharedPreferences。
 *
 * 明文落盘只影响「老电视 + 用户自己配了带密码的 WebDAV 源」这一条窄路径，
 * 且这两台设备本来就在用户家里；相比让整页崩溃，这个取舍更可接受。
 * 真正要做端到端加密，得换掉 security-crypto 或自建 Keystore 封装，属于另一个改造。
 */
@Singleton
class CredentialManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            encryptedPrefs()
        } else {
            context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun encryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context, "audio_credentials", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun savePassword(sourceId: Long, password: String) {
        prefs.edit().putString("pwd_$sourceId", password).apply()
    }

    fun getPassword(sourceId: Long): String? = prefs.getString("pwd_$sourceId", null)

    fun deletePassword(sourceId: Long) {
        prefs.edit().remove("pwd_$sourceId").apply()
    }

    private companion object {
        const val LEGACY_PREFS = "audio_credentials_legacy"
    }
}
