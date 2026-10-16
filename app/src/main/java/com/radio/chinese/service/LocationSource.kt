package com.radio.chinese.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class Coords(val lat: Double, val lng: Double)

interface LocationSource {
    suspend fun currentCoords(): Coords?
}

/**
 * 车机多为安卓 10、无 Google Play 服务：走系统 LocationManager，不用 FusedLocation。
 * 先取 last-known（GPS/network 任一，取时间最新的），拿不到再在主线程注册一次性更新，
 * 带 5s 超时；权限未授予 / 无 provider / 超时 => null，交给上层手动兜底。
 */
@Singleton
class AndroidLocationSource @Inject constructor(
    @ApplicationContext private val context: Context
) : LocationSource {

    private companion object {
        const val LAST_KNOWN_MAX_AGE_MS = 10 * 60 * 1000L // 10 分钟内的 last-known 才算“当前”
    }

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    override suspend fun currentCoords(): Coords? {
        if (locationManager == null) return null
        if (!hasPermission()) return null
        lastKnown()?.let { return it }
        return requestUpdate(timeoutMs = 5_000)
    }

    private fun hasPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    private fun lastKnown(): Coords? {
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        val best = providers.mapNotNull {
            runCatching { locationManager?.getLastKnownLocation(it) }.getOrNull()
        }.maxByOrNull { it.time } ?: return null
        // 陈旧保护：车机 last-known 常是几天前/几百公里外的点，超过新鲜度窗口就不当“当前位置”，改走实时定位
        if (System.currentTimeMillis() - best.time > LAST_KNOWN_MAX_AGE_MS) return null
        return Coords(best.latitude, best.longitude)
    }

    private fun pickProvider(): String? {
        val enabled = runCatching { locationManager?.getProviders(true) }.getOrNull().orEmpty()
        return when {
            enabled.contains(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            enabled.contains(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
    }

    private suspend fun requestUpdate(timeoutMs: Long): Coords? =
        withContext(Dispatchers.Main) {
            val provider = pickProvider() ?: return@withContext null
            var listener: LocationListener? = null
            try {
                withTimeoutOrNull(timeoutMs) {
                    suspendCancellableCoroutine { cont ->
                        val l = object : LocationListener {
                            override fun onLocationChanged(location: Location) {
                                if (cont.isActive) cont.resume(Coords(location.latitude, location.longitude))
                            }

                            override fun onProviderEnabled(provider: String) {}

                            override fun onProviderDisabled(provider: String) {}

                            @Deprecated("Deprecated in API 29")
                            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                        }
                        listener = l
                        try {
                            locationManager?.requestLocationUpdates(provider, 0L, 0f, l, Looper.getMainLooper())
                        } catch (_: SecurityException) {
                            if (cont.isActive) cont.resume(null)
                        }
                        cont.invokeOnCancellation { runCatching { locationManager?.removeUpdates(l) } }
                    }
                }
            } finally {
                // 无论首次回调、超时还是取消，都确保停更，不留监听器空转耗电
                runCatching { listener?.let { locationManager?.removeUpdates(it) } }
            }
        }
}
