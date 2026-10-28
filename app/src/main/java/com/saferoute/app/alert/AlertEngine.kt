package com.saferoute.app.alert

import android.os.SystemClock
import android.util.Log
import com.saferoute.app.data.Shelter
import com.saferoute.app.data.ShelterDb
import com.saferoute.app.location.Fix
import com.saferoute.app.pack.RoutePack
import java.time.DayOfWeek
import java.time.LocalDateTime
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt

/** Walking speeds from the FDMA tsunami evacuation guide (VALIDATION V4d). */
const val WALK_MPS = 1.0
const val STEP_FREE_MPS = 0.5
private const val HEADING_LOOKAHEAD_M = 30.0
private const val TAG = "SafeRoute"
/** School gates are typically closed outside these hours and at weekends (VALIDATION V14). */
private const val GATES_OPEN_FROM = 6
private const val GATES_OPEN_UNTIL = 18
private const val MAP_MIN_PAD_DEG = 0.0015

/** The nearest destination that is never a school yard, from the pack's `unlocked` tree. */
data class Alternative(val shelter: Shelter, val distanceM: Int, val walkMinutes: Int)

data class Guidance(
    val shelter: Shelter,
    val distanceM: Int,
    val walkMinutes: Int,
    /** Bearing from the user to a point ~30 m along the route, degrees from north. */
    val bearingDeg: Float,
    val routeLatLon: List<Pair<Double, Double>>,
    val snapM: Double,
    val computeMs: Double,
    /** Set when the destination is a school yard and it is night or a weekend. */
    val lockWarning: LockWarning? = null,
    val map: MapData? = null,
)

data class LockWarning(val alternative: Alternative?)

/** Offline base map around the route, drawn from the pack itself. */
class MapData(
    val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double,
    val streets: List<Pair<Pair<Double, Double>, Pair<Double, Double>>>,
    val shelters: List<Shelter>,
)

sealed interface AlertResult {
    data class Ok(val guidance: Guidance) : AlertResult
    data object FarFromRoads : AlertResult
    data object NoStepFreeRoute : AlertResult
}

/** Offline guidance: nearest node -> follow the hazard's pre-computed tree. No network, no LLM. */
class AlertEngine(private val pack: RoutePack, private val db: ShelterDb) {

    fun guide(fix: Fix, hazard: String, stepFree: Boolean, now: LocalDateTime = LocalDateTime.now()): AlertResult {
        val t0 = SystemClock.elapsedRealtimeNanos()
        val (node, snapM) = pack.nearestNode(fix.lat, fix.lon) ?: return AlertResult.FarFromRoads
        val profile = if (stepFree) "stepfree" else "default"
        val route = pack.route(node, RoutePack.TreeKey(hazard, profile))
            ?: return if (stepFree) AlertResult.NoStepFreeRoute else AlertResult.FarFromRoads
        val shelter = db.shelter(route.shelterIdx) ?: return AlertResult.FarFromRoads
        val points = route.nodes.map { pack.latOf(it) to pack.lonOf(it) }
        val totalM = route.distanceM + snapM
        val minutes = (totalM / (if (stepFree) STEP_FREE_MPS else WALK_MPS) / 60).roundToInt().coerceAtLeast(1)
        val lock = if (shelter.mayLock && gatesMayBeClosed(now)) LockWarning(alternative(node, snapM, hazard, stepFree)) else null
        val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
        Log.i(TAG, "alert_compute_ms=%.2f hazard=%s profile=%s nodes=%d".format(ms, hazard, profile, route.nodes.size))
        return AlertResult.Ok(
            Guidance(shelter, totalM.roundToInt(), minutes, bearingAlong(fix, points), points, snapM, ms, lock,
                mapAround(points + (fix.lat to fix.lon) + (shelter.lat to shelter.lon) +
                    listOfNotNull(lock?.alternative?.shelter?.let { it.lat to it.lon }), hazard))
        )
    }

    private fun mapAround(points: List<Pair<Double, Double>>, hazard: String): MapData {
        val lats = points.map { it.first }
        val lons = points.map { it.second }
        // Pad the route box by a quarter of its size, at least ~150 m, so nearby streets show.
        val padLat = maxOf((lats.max() - lats.min()) / 4, MAP_MIN_PAD_DEG)
        val padLon = maxOf((lons.max() - lons.min()) / 4, MAP_MIN_PAD_DEG)
        val box = doubleArrayOf(lats.min() - padLat, lons.min() - padLon, lats.max() + padLat, lons.max() + padLon)
        val streets = pack.streetsIn(box[0], box[1], box[2], box[3])
            .map { (a, b) -> (pack.latOf(a) to pack.lonOf(a)) to (pack.latOf(b) to pack.lonOf(b)) }
        return MapData(box[0], box[1], box[2], box[3], streets, db.suitableIn(hazard, box[0], box[1], box[2], box[3]))
    }

    private fun alternative(node: Int, snapM: Double, hazard: String, stepFree: Boolean): Alternative? {
        val route = pack.route(node, RoutePack.TreeKey(hazard, "unlocked")) ?: return null
        val shelter = db.shelter(route.shelterIdx) ?: return null
        val totalM = route.distanceM + snapM
        val minutes = (totalM / (if (stepFree) STEP_FREE_MPS else WALK_MPS) / 60).roundToInt().coerceAtLeast(1)
        return Alternative(shelter, totalM.roundToInt(), minutes)
    }

    private fun bearingAlong(fix: Fix, points: List<Pair<Double, Double>>): Float {
        var walked = 0.0
        var target = points.last()
        for (i in 1 until points.size) {
            walked += metres(points[i - 1], points[i])
            if (walked >= HEADING_LOOKAHEAD_M) { target = points[i]; break }
        }
        return bearing(fix.lat to fix.lon, target)
    }

    companion object {
        fun gatesMayBeClosed(t: LocalDateTime): Boolean =
            t.hour < GATES_OPEN_FROM || t.hour >= GATES_OPEN_UNTIL ||
                t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY

        fun metres(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
            val dy = (b.first - a.first) * 111_320.0
            val dx = (b.second - a.second) * 111_320.0 * cos(Math.toRadians(a.first))
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }

        fun bearing(a: Pair<Double, Double>, b: Pair<Double, Double>): Float {
            val dy = b.first - a.first
            val dx = (b.second - a.second) * cos(Math.toRadians(a.first))
            return ((Math.toDegrees(atan2(dx, dy)) + 360) % 360).toFloat()
        }
    }
}
