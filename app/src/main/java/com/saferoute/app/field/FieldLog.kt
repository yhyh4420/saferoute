package com.saferoute.app.field

import android.content.Context
import java.io.File
import org.json.JSONObject

/**
 * Opt-in field-test log ("준비·설정 → 현장 테스트 기록"), kept on the phone only and pulled over USB
 * afterwards (`scripts/pull_fieldlog.sh`). Works offline, which is the condition being tested.
 */
class FieldLog(context: Context) {
    private val file = File(context.filesDir, "fieldlog.jsonl")
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("fieldlog", false)
        set(v) { prefs.edit().putBoolean("fieldlog", v).apply() }

    fun write(event: String, fields: Map<String, Any?>) {
        if (!enabled) return
        val o = JSONObject().put("t", System.currentTimeMillis()).put("event", event)
        fields.forEach { (k, v) -> o.put(k, v ?: JSONObject.NULL) }
        synchronized(this) {
            file.appendText(o.toString() + "\n")
            count = lines() + 1
        }
    }

    // Counted once, then kept in memory: re-reading a day-long log on every GPS fix would drain the battery.
    @Volatile private var count = -1

    fun lines(): Int {
        if (count < 0) count = if (file.exists()) file.useLines { it.count() } else 0
        return count
    }
}
