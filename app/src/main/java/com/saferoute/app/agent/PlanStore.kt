package com.saferoute.app.agent

import android.content.Context
import com.saferoute.app.alert.AlertEngine
import org.json.JSONArray
import org.json.JSONObject

/** Validated plans prepared while online, read on the offline alert path. */
class PlanStore(context: Context) {
    private val prefs = context.getSharedPreferences("plans", Context.MODE_PRIVATE)

    fun all(): List<Plan> {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Plan(o.getString("place"), o.getDouble("lat"), o.getDouble("lon"), o.getString("hazard"),
                o.getString("primary"), o.optString("backup").ifBlank { null },
                o.optString("meeting").ifBlank { null }, o.getString("notes"))
        }
    }

    fun save(plan: Plan) {
        val kept = all().filterNot { it.hazard == plan.hazard && near(it, plan.lat, plan.lon) } + plan
        val arr = JSONArray(kept.map {
            JSONObject().put("place", it.placeLabel).put("lat", it.lat).put("lon", it.lon).put("hazard", it.hazard)
                .put("primary", it.primaryId).put("backup", it.backupId ?: "").put("meeting", it.meetingPointId ?: "")
                .put("notes", it.notesKo)
        })
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    /** The plan prepared for a place within [RADIUS_M] of here, for this hazard. */
    fun lookup(lat: Double, lon: Double, hazard: String): Plan? =
        all().filter { it.hazard == hazard && near(it, lat, lon) }
            .minByOrNull { AlertEngine.metres(lat to lon, it.lat to it.lon) }

    private fun near(p: Plan, lat: Double, lon: Double) = AlertEngine.metres(lat to lon, p.lat to p.lon) <= RADIUS_M

    private companion object {
        const val KEY = "plans_v1"
        const val RADIUS_M = 300.0
    }
}
