package com.saferoute.app.agent

import com.saferoute.app.alert.AlertEngine
import com.saferoute.app.alert.AlertResult
import com.saferoute.app.data.ShelterDb
import com.saferoute.app.location.Fix
import java.time.LocalDateTime
import org.json.JSONArray
import org.json.JSONObject

data class Profile(val with: String, val stepFree: Boolean)

data class Plan(
    val placeLabel: String,
    val lat: Double,
    val lon: Double,
    val hazard: String,
    val primaryId: String,
    val backupId: String?,
    val meetingPointId: String?,
    val notesKo: String,
)

data class AgentRun(
    val plan: Plan?,
    val problems: List<String>,
    val toolLog: List<String>,
    val promptTokens: Int,
    val completionTokens: Int,
)

/**
 * Prepares a plan for one regular place while online (Muse Spark tool calling). Port of
 * `eval/agent.py`; every saved id is checked by [validate] against what the tools returned.
 */
class PlanAgent(
    private val api: MetaApiClient,
    private val engine: AlertEngine,
    private val db: ShelterDb,
) {
    fun run(place: Fix, hazard: String, profile: Profile, onTool: (String) -> Unit = {}): AgentRun {
        val seen = HashSet<String>()
        var oracleId: String? = null
        var saved: JSONObject? = null
        val log = ArrayList<String>()
        var promptTokens = 0
        var completionTokens = 0

        fun tool(name: String, args: JSONObject): JSONObject = when (name) {
            "route_oracle" -> {
                val stepFree = if (args.has("step_free")) args.getBoolean("step_free") else profile.stepFree
                val h = args.getString("hazard")
                // Ask at night so a school-yard destination also returns its locked-gate fallback.
                when (val r = engine.guide(Fix(args.getDouble("lat"), args.getDouble("lon"), "agent"), h, stepFree, NIGHT)) {
                    is AlertResult.Ok -> {
                        val s = r.guidance.shelter
                        seen += s.id
                        if (h == hazard) oracleId = s.id
                        JSONObject().put("shelter_id", s.id).put("name", s.name)
                            .put("walk_m", r.guidance.distanceM).put("walk_min", r.guidance.walkMinutes)
                            .put("may_be_locked_at_night", s.mayLock)
                            .apply {
                                r.guidance.lockWarning?.alternative?.let { a ->
                                    seen += a.shelter.id
                                    put("night_alternative", JSONObject().put("shelter_id", a.shelter.id)
                                        .put("name", a.shelter.name).put("walk_m", a.distanceM).put("walk_min", a.walkMinutes))
                                }
                            }
                    }
                    else -> JSONObject().put("error", "no route from here")
                }
            }
            "get_shelters" -> {
                val k = args.optInt("k", 5).coerceIn(1, 8)
                val here = args.getDouble("lat") to args.getDouble("lon")
                val near = db.suitableFor(args.getString("hazard"))
                    .map { it to AlertEngine.metres(here, it.lat to it.lon) }
                    .sortedBy { it.second }.take(k)
                near.forEach { seen += it.first.id }
                JSONObject().put("note", "straight-line distance, not walking").put("shelters", JSONArray(near.map { (s, d) ->
                    JSONObject().put("shelter_id", s.id).put("name", s.name).put("straight_m", d.toInt())
                }))
            }
            "get_guideline" -> db.rules().first { it.hazard == args.getString("hazard") }.let {
                JSONObject().put("verb_ko", it.verbKo).put("forbid_ko", it.forbidKo).put("source", it.source)
            }
            "get_profile" -> JSONObject().put("with", profile.with).put("step_free", profile.stepFree)
            "save_plan" -> { saved = args; JSONObject().put("saved", true) }
            else -> JSONObject().put("error", "unknown tool $name")
        }

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", SYSTEM))
            .put(JSONObject().put("role", "user").put("content",
                "Place: ${place.label} at lat ${place.lat}, lon ${place.lon}. Prepare my $hazard plan for this place."))
        var nudged = false
        for (turn in 0 until MAX_TURNS) {
            val reply = api.chat(messages, TOOLS)
            promptTokens += reply.usage.promptTokens
            completionTokens += reply.usage.completionTokens
            messages.put(JSONObject().put("role", "assistant")
                .put("content", reply.message.optString("content", ""))
                .apply { if (reply.toolCalls.length() > 0) put("tool_calls", reply.toolCalls) })
            if (reply.toolCalls.length() == 0) {
                if (saved != null || nudged) break
                // tool_choice is auto-only, so the model sometimes answers in prose: ask once more.
                nudged = true
                messages.put(JSONObject().put("role", "user").put("content", "Use the tools and finish by calling save_plan."))
                continue
            }
            for (i in 0 until reply.toolCalls.length()) {
                val call = reply.toolCalls.getJSONObject(i)
                val fn = call.getJSONObject("function")
                val name = fn.getString("name")
                val args = fn.optString("arguments").takeIf { it.isNotBlank() }?.let { JSONObject(it) } ?: JSONObject()
                val result = tool(name, args)
                val line = "$name(${args.toString().take(80)}) → ${result.toString().take(100)}"
                log += line
                onTool(line)
                messages.put(JSONObject().put("role", "tool").put("tool_call_id", call.getString("id")).put("content", result.toString()))
            }
            if (saved != null) break
        }

        val plan = saved?.let {
            Plan(place.label, place.lat, place.lon, hazard, it.optString("primary_shelter_id"),
                it.optString("backup_shelter_id").ifBlank { null }, it.optString("meeting_point_id").ifBlank { null },
                it.optString("notes_ko"))
        }
        return AgentRun(plan, validate(plan, hazard, seen, oracleId), log, promptTokens, completionTokens)
    }

    /** Mirrors `validate_plan` in eval/agent.py. Empty = valid. */
    fun validate(plan: Plan?, hazard: String, seen: Set<String>, oracleId: String?): List<String> {
        if (plan == null) return listOf("no plan saved")
        val problems = ArrayList<String>()
        if (plan.hazard != hazard) problems += "wrong hazard"
        if (plan.primaryId != oracleId) problems += "primary is not the verified walking-nearest shelter"
        val suitable = db.suitableFor(hazard).map { it.id }.toHashSet()
        listOf("primary" to plan.primaryId, "backup" to plan.backupId, "meeting point" to plan.meetingPointId).forEach { (k, id) ->
            if (id == null) return@forEach
            if (id !in seen) problems += "$k $id was never returned by a tool"
            else if (id !in suitable) problems += "$k $id not suitable for $hazard"
        }
        if (plan.notesKo.length > 300) problems += "notes too long"
        return problems
    }

    private companion object {
        val NIGHT: LocalDateTime = LocalDateTime.of(2026, 11, 4, 23, 0)
        const val MAX_TURNS = 6
        const val SYSTEM = "You prepare personal evacuation plans ahead of time for one of the user's regular places. " +
            "Call route_oracle first: its shelter is the verified walking-nearest suitable destination. " +
            "If the primary may be locked at night, use its night_alternative as the backup. " +
            "Use get_shelters only to pick a backup and a family meeting point. Never name a place, id or " +
            "distance that a tool did not return. Follow get_guideline for the hazard. When done, call " +
            "save_plan exactly once. Notes must be short, in Korean, action-first."

        val TOOLS: JSONArray by lazy {
            fun fn(name: String, desc: String, props: JSONObject, required: List<String>) = JSONObject()
                .put("type", "function")
                .put("function", JSONObject().put("name", name).put("description", desc)
                    .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required))))
            val hazard = JSONObject().put("type", "string").put("enum", JSONArray(listOf("earthquake", "flood")))
            val num = JSONObject().put("type", "number")
            JSONArray()
                .put(fn("route_oracle", "Verified walking-nearest suitable shelter for a hazard from a point, with real walking distance and minutes. Works offline.",
                    JSONObject().put("lat", num).put("lon", num).put("hazard", hazard).put("step_free", JSONObject().put("type", "boolean")),
                    listOf("lat", "lon", "hazard")))
                .put(fn("get_shelters", "Up to k suitable shelters for a hazard ordered by straight-line distance (NOT walking distance). Use for backup / meeting point.",
                    JSONObject().put("lat", num).put("lon", num).put("hazard", hazard).put("k", JSONObject().put("type", "integer")),
                    listOf("lat", "lon", "hazard")))
                .put(fn("get_guideline", "Official action guideline for a hazard (Korean Ministry of the Interior and Safety).",
                    JSONObject().put("hazard", hazard), listOf("hazard")))
                .put(fn("get_profile", "The user's situation: who they are with and whether they need step-free routes.",
                    JSONObject(), emptyList()))
                .put(fn("save_plan", "Save the final plan for this place and hazard.",
                    JSONObject().put("hazard", hazard).put("primary_shelter_id", JSONObject().put("type", "string"))
                        .put("backup_shelter_id", JSONObject().put("type", "string"))
                        .put("meeting_point_id", JSONObject().put("type", "string"))
                        .put("notes_ko", JSONObject().put("type", "string")),
                    listOf("hazard", "primary_shelter_id", "notes_ko")))
        }
    }
}
