package com.saferoute.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

data class Shelter(
    val idx: Int,
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val facility: String,
    val address: String,
    val source: String,
    /** School yard whose gates may be locked at night and on holidays (VALIDATION V14). */
    val mayLock: Boolean = false,
)

data class HazardRule(
    val hazard: String,
    val verbKo: String,
    val verbEn: String,
    val forbidKo: String,
    val forbidEn: String,
    val source: String,
)

/** Read-only view of `pack_<city>.sqlite` (shelters + hazard rule table), copied out of assets once per install. */
class ShelterDb(context: Context, assetName: String) {
    val db: SQLiteDatabase

    init {
        val file = File(context.noBackupFilesDir, assetName)
        // SQLite files grow in whole pages, so a rebuilt pack can keep the same size: key the copy on the install.
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        if (!file.exists() || file.lastModified() < installed) {
            context.assets.open(assetName).use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
    }

    fun shelter(idx: Int): Shelter? = db.rawQuery(
        "SELECT idx, id, name, lat, lon, facility, address, source, may_lock FROM shelter WHERE idx = ?",
        arrayOf(idx.toString()),
    ).use { c ->
        if (!c.moveToFirst()) null
        else Shelter(c.getInt(0), c.getString(1), c.getString(2), c.getDouble(3), c.getDouble(4),
            c.getString(5), c.getString(6) ?: "", c.getString(7), c.getInt(8) == 1)
    }

    /** Shelters suitable for [hazard] according to the pack's rule table (destination candidates). */
    fun suitableFor(hazard: String): List<Shelter> = db.rawQuery(
        "SELECT idx, id, name, lat, lon, facility, address, source, may_lock FROM shelter WHERE ',' || hazards || ',' LIKE ?",
        arrayOf("%,$hazard,%"),
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(Shelter(c.getInt(0), c.getString(1), c.getString(2), c.getDouble(3),
                c.getDouble(4), c.getString(5), c.getString(6) ?: "", c.getString(7), c.getInt(8) == 1))
        }
    }

    /** Suitable shelters inside a lat/lon box (map markers). */
    fun suitableIn(hazard: String, minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): List<Shelter> =
        db.rawQuery(
            "SELECT idx, id, name, lat, lon, facility, address, source, may_lock FROM shelter " +
                "WHERE ',' || hazards || ',' LIKE ? AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
            arrayOf("%,$hazard,%", "$minLat", "$maxLat", "$minLon", "$maxLon"),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(Shelter(c.getInt(0), c.getString(1), c.getString(2), c.getDouble(3),
                    c.getDouble(4), c.getString(5), c.getString(6) ?: "", c.getString(7), c.getInt(8) == 1))
            }
        }

    fun byId(id: String): Shelter? = db.rawQuery("SELECT idx FROM shelter WHERE id = ?", arrayOf(id))
        .use { c -> if (c.moveToFirst()) shelter(c.getInt(0)) else null }

    fun rules(): List<HazardRule> = db.rawQuery(
        "SELECT hazard, verb_ko, verb_en, forbid_ko, forbid_en, source FROM hazard_rule ORDER BY hazard",
        null,
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(HazardRule(c.getString(0), c.getString(1), c.getString(2),
                c.getString(3), c.getString(4), c.getString(5)))
        }
    }
}
