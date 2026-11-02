package com.saferoute.app.learn

import android.content.Context
import com.saferoute.app.SafeRouteGraph
import com.saferoute.app.agent.Profile
import com.saferoute.app.alert.AlertResult
import com.saferoute.app.location.Fix
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.floor
import org.json.JSONArray
import org.json.JSONObject

/** For one hazard at one stop: the nearest official shelter and the nearest guidance-matching place. */
data class StopHazard(val hazard: String, val official: String?, val officialM: Int?, val manual: String?, val manualM: Int?)

data class RouteStop(val timeMs: Long, val outsideCity: Boolean, val hazards: List<StopHazard>)

/**
 * "Where would I have gone today?": for every ~110 m cell passed today, the nearest official shelter and
 * the nearest place meeting the official guidance, by walking distance. Computed offline from the pack in
 * the background worker and when the screen opens; nothing leaves the phone.
 */
object RouteReview {
    private const val KEY = "route_review_v1"
    private const val CELL_DEG = 0.001
    private const val MAX_STOPS = 40

    fun compute(context: Context, g: SafeRouteGraph): List<RouteStop> {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val stepFree = Profile.fromSettings(context).stepFree
        val hazards = g.db.rules().map { it.hazard }
        val seen = HashSet<String>()
        val stops = g.learner.visits().filter { it.timeMs >= start }.sortedBy { it.timeMs }
            .filter { seen.add("${floor(it.lat / CELL_DEG).toLong()}:${floor(it.lon / CELL_DEG).toLong()}") }
            .takeLast(MAX_STOPS)
            .map { v ->
                val fix = Fix(v.lat, v.lon, "route")
                var outside = false
                val per = hazards.map { h ->
                    val ok = (g.engine.guide(fix, h, stepFree) as? AlertResult.Ok)?.guidance
                    if (ok?.outsideCity == true) outside = true
                    StopHazard(h, ok?.shelter?.bilingual, ok?.distanceM, ok?.manual?.shelter?.bilingual, ok?.manual?.distanceM)
                }
                RouteStop(v.timeMs, outside, per)
            }
        save(context, stops)
        return stops
    }

    fun load(context: Context): List<RouteStop> {
        val raw = context.getSharedPreferences("route_review", Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val hs = o.getJSONArray("h")
            RouteStop(o.getLong("t"), o.getBoolean("out"), (0 until hs.length()).map { j ->
                val h = hs.getJSONObject(j)
                StopHazard(h.getString("hazard"), h.optString("o").ifBlank { null }, h.optInt("om", -1).takeIf { it >= 0 },
                    h.optString("m").ifBlank { null }, h.optInt("mm", -1).takeIf { it >= 0 })
            })
        }
    }

    private fun save(context: Context, stops: List<RouteStop>) {
        val arr = JSONArray(stops.map { s ->
            JSONObject().put("t", s.timeMs).put("out", s.outsideCity).put("h", JSONArray(s.hazards.map { h ->
                JSONObject().put("hazard", h.hazard).put("o", h.official ?: "").put("om", h.officialM ?: -1)
                    .put("m", h.manual ?: "").put("mm", h.manualM ?: -1)
            }))
        })
        context.getSharedPreferences("route_review", Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun clock(timeMs: Long): String = Instant.ofEpochMilli(timeMs).atZone(ZoneId.systemDefault()).toLocalTime()
        .withSecond(0).withNano(0).toString()
}
