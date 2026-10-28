package com.saferoute.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class Fix(val lat: Double, val lon: Double, val label: String, val accuracyM: Float? = null)

/** Fixed points for demos and the emulator, where there is no real GPS. */
val DEMO_PLACES = listOf(
    Fix(37.5172, 126.8946, "문래동 (demo)"),
    Fix(37.4979, 127.0276, "강남역 (demo)"),
    Fix(37.5259, 126.9244, "여의도 (demo)"),
    Fix(37.5720, 126.9769, "광화문 (demo)"),
)

/**
 * Location without Google Play services, so it also works on de-Googled phones.
 * The last known fix is used immediately (the alert screen must not wait for a cold GPS
 * start, VALIDATION V16b) and live fixes replace it as they arrive.
 */
class LocationSource(context: Context) {
    private val lm = context.getSystemService(LocationManager::class.java)

    @SuppressLint("MissingPermission")
    fun lastKnown(): Fix? = PROVIDERS
        .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
        .maxByOrNull { it.time }
        ?.toFix("마지막 위치")

    @SuppressLint("MissingPermission")
    fun updates(): Flow<Fix> = callbackFlow {
        val listener = LocationListener { trySend(it.toFix("GPS")) }
        PROVIDERS.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }.forEach {
            lm.requestLocationUpdates(it, 2_000L, 5f, listener, Looper.getMainLooper())
        }
        awaitClose { lm.removeUpdates(listener) }
    }

    private fun Location.toFix(label: String) = Fix(latitude, longitude, label, if (hasAccuracy()) accuracy else null)

    private companion object {
        val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
    }
}

/** Device heading in degrees from north, or null when the phone has no rotation sensor (e.g. emulator). */
fun headingUpdates(context: Context): Flow<Float> = callbackFlow {
    val sm = context.getSystemService(SensorManager::class.java)
    val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    if (sensor == null) {
        close()
        return@callbackFlow
    }
    val rot = FloatArray(9)
    val orientation = FloatArray(3)
    val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rot, e.values)
            SensorManager.getOrientation(rot, orientation)
            trySend(((Math.toDegrees(orientation[0].toDouble()) + 360) % 360).toFloat())
        }

        override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
    }
    sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
    awaitClose { sm.unregisterListener(listener) }
}
