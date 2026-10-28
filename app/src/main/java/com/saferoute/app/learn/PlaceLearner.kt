package com.saferoute.app.learn

import android.content.Context
import kotlin.math.floor
import org.json.JSONArray
import org.json.JSONObject

data class Visit(val lat: Double, val lon: Double, val timeMs: Long)

data class FrequentPlace(val key: String, val lat: Double, val lon: Double, val days: Int, val hours: Int, val label: String)

/**
 * Learns the user's regular places from coarse location samples, on the device only.
 *
 * Samples are bucketed into ~110 m cells; a cell becomes a regular place once it was seen on
 * [MIN_DAYS] different days. This is the "know the shelters around home, work and school in
 * advance" step that Korean guidance asks people to do by hand (VALIDATION V5, V10).
 */
class PlaceLearner(context: Context) {
    private val prefs = context.getSharedPreferences("visits", Context.MODE_PRIVATE)

    fun record(v: Visit) {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        arr.put(JSONObject().put("lat", v.lat).put("lon", v.lon).put("t", v.timeMs))
        // Keep the last MAX_VISITS samples; older history is not needed to find regular places.
        val start = (arr.length() - MAX_VISITS).coerceAtLeast(0)
        val kept = JSONArray((start until arr.length()).map { arr.getJSONObject(it) })
        prefs.edit().putString(KEY, kept.toString()).apply()
    }

    fun visits(): List<Visit> {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        return (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Visit(o.getDouble("lat"), o.getDouble("lon"), o.getLong("t")) } }
    }

    fun clear() = prefs.edit().remove(KEY).apply()

    fun places(): List<FrequentPlace> = visits()
        .groupBy { cellKey(it.lat, it.lon) }
        .mapNotNull { (key, vs) ->
            val days = vs.map { it.timeMs / DAY_MS }.toSet().size
            if (days < MIN_DAYS) return@mapNotNull null
            val hours = vs.map { ((it.timeMs / HOUR_MS) % 24).toInt() }
            FrequentPlace(key, vs.map { it.lat }.average(), vs.map { it.lon }.average(), days, hours.toSet().size, label(hours))
        }
        .sortedByDescending { it.days }

    /** "집" if mostly seen at night, "직장·학교" if mostly on weekday daytime, else "자주 가는 곳". */
    private fun label(hours: List<Int>): String {
        val night = hours.count { it >= 21 || it < 7 }
        val day = hours.count { it in 9..18 }
        return when {
            night * 2 >= hours.size -> "집 (추정)"
            day * 2 >= hours.size -> "직장·학교 (추정)"
            else -> "자주 가는 곳"
        }
    }

    private fun cellKey(lat: Double, lon: Double) = "${floor(lat / CELL_DEG).toLong()}:${floor(lon / CELL_DEG).toLong()}"

    companion object {
        private const val KEY = "visits_v1"
        private const val CELL_DEG = 0.001 // ~110 m
        private const val MIN_DAYS = 3
        private const val MAX_VISITS = 2_000
        private const val HOUR_MS = 3_600_000L
        private const val DAY_MS = 86_400_000L

        /** Demo/emulator: a week of commuting between two points (no real history on an emulator). */
        fun simulatedWeek(home: Pair<Double, Double>, work: Pair<Double, Double>, now: Long): List<Visit> = buildList {
            for (d in 1..7) {
                val day = now - d * DAY_MS - (now % DAY_MS)
                listOf(22, 23, 6).forEach { h -> add(Visit(home.first, home.second, day + h * HOUR_MS)) }
                if (d <= 5) listOf(10, 13, 16).forEach { h -> add(Visit(work.first, work.second, day + h * HOUR_MS)) }
            }
        }
    }
}
