package com.saferoute.app.candidates

import android.content.Context
import android.util.Log
import com.saferoute.app.pack.RoutePack
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import org.json.JSONArray
import org.json.JSONObject

/** A place that meets the official guidance for [hazard] but is not a designated shelter. */
data class Candidate(
    val id: String, val hazard: String, val name: String, val nameEn: String,
    val lat: Double, val lon: Double, val noteKo: String, val noteEn: String,
)

data class NearestCandidate(val candidate: Candidate, val walkM: Int)

/**
 * Background search for guidance-matching places around the user's own places only.
 *
 * Criteria (행정안전부 국민행동요령, VALIDATION V39):
 * - earthquake: "건물과 거리를 두고 운동장이나 공원 등 넓은 공간" → open space with a spot ≥ 15 m from its edge,
 *   room for ≥ 1,000 people at 0.825 m² each (옥외대피장소 지정 지침), slope ≤ 15 % ("산사태·절벽 주의").
 * - flood: "높은 곳으로", avoid "저지대·상습 침수지역·하천 제방 근처·약해진 비탈면" → ground ≥ 10 m above the
 *   nearest river/stream, ≥ 150 m from it, outside past flood areas, slope ≤ 25 %.
 *
 * Map data is fetched per ~550 m tile around learned places (the server sees a tile, not a position) and
 * only while online in the background. Judging uses the pack's terrain grid, so no elevation service.
 */
class CandidateFinder(private val context: Context, private val terrain: Terrain?) {
    private val file = File(context.filesDir, "candidates.json")
    private var cache: Pair<Set<String>, List<Candidate>>? = null

    @Synchronized
    fun all(): List<Candidate> = load().second

    @Synchronized
    fun tilesDone(): Int = load().first.size

    /** Fetch and judge up to [maxTiles] new tiles around these places (radius [radiusM]). Returns tiles fetched. */
    fun update(places: List<Pair<Double, Double>>, maxTiles: Int = 6, radiusM: Double = 400.0): Int {
        val (done, have) = load()
        val wanted = LinkedHashSet<String>()
        for ((lat, lon) in places) {
            val dLat = radiusM / 111_320.0
            val dLon = radiusM / (111_320.0 * cos(Math.toRadians(lat)))
            for (la in listOf(lat - dLat, lat, lat + dLat)) for (lo in listOf(lon - dLon, lon, lon + dLon)) {
                val k = tileKey(la, lo)
                if (k !in done) wanted += k
            }
        }
        var fetched = 0
        val found = have.toMutableList()
        val newDone = done.toMutableSet()
        for (k in wanted.take(maxTiles)) {
            val got = runCatching { judgeTile(k) }.onFailure { Log.w(TAG, "tile $k: ${it.message}") }.getOrNull() ?: break
            found.removeAll { c -> got.any { it.id == c.id } }
            found += got
            newDone += k
            fetched++
        }
        if (fetched > 0) save(newDone, found)
        return fetched
    }

    /** Nearest candidate for [hazard] by walking along the pack's streets from [node]; offline. */
    fun nearest(pack: RoutePack, node: Int, snapM: Double, lat: Double, lon: Double, hazard: String): NearestCandidate? {
        val near = all().filter { it.hazard == hazard && metres(lat, lon, it.lat, it.lon) < SEARCH_M }
        if (near.isEmpty()) return null
        val lats = near.map { it.lat } + lat
        val lons = near.map { it.lon } + lon
        val pad = 0.004
        val edges = pack.streetsIn(lats.min() - pad, lons.min() - pad, lats.max() + pad, lons.max() + pad)
        val adj = HashMap<Int, MutableList<Pair<Int, Double>>>()
        for ((a, b) in edges) {
            val w = metres(pack.latOf(a), pack.lonOf(a), pack.latOf(b), pack.lonOf(b))
            adj.getOrPut(a) { ArrayList() }.add(b to w)
            adj.getOrPut(b) { ArrayList() }.add(a to w)
        }
        val targets = near.mapNotNull { c -> pack.nearestNode(c.lat, c.lon)?.let { (n, d) -> Triple(c, n, d) } }
        val dist = HashMap<Int, Double>().apply { put(node, 0.0) }
        val pq = PriorityQueue<Pair<Double, Int>>(compareBy { it.first }).apply { add(0.0 to node) }
        val wanted = targets.map { it.second }.toSet()
        var reached = 0
        while (pq.isNotEmpty() && reached < wanted.size) {
            val (d, n) = pq.poll()!!
            if (d > (dist[n] ?: Double.MAX_VALUE)) continue
            if (n in wanted) reached++
            for ((m, w) in adj[n].orEmpty()) {
                val nd = d + w
                if (nd < (dist[m] ?: Double.MAX_VALUE)) { dist[m] = nd; pq.add(nd to m) }
            }
        }
        return targets.mapNotNull { (c, n, extra) -> dist[n]?.let { NearestCandidate(c, (it + extra + snapM).toInt()) } }
            .minByOrNull { it.walkM }
    }

    private fun judgeTile(key: String): List<Candidate> {
        val (ty, tx) = key.split(":").map { it.toLong() }
        val s = ty * TILE_DEG; val w = tx * TILE_DEG
        // Water is fetched with a margin: the nearest stream may lie outside the tile.
        val m = 0.004
        val q = """[out:json][timeout:80];
            (way["leisure"~"^(park|playground|pitch|stadium|recreation_ground|garden)$"]($s,$w,${s + TILE_DEG},${w + TILE_DEG});
             way["landuse"="recreation_ground"]($s,$w,${s + TILE_DEG},${w + TILE_DEG});
             way["place"="square"]($s,$w,${s + TILE_DEG},${w + TILE_DEG});
             relation["leisure"="park"]($s,$w,${s + TILE_DEG},${w + TILE_DEG});
             way["waterway"~"^(river|stream|canal)$"](${s - m},${w - m},${s + TILE_DEG + m},${w + TILE_DEG + m});
             way["natural"="water"](${s - m},${w - m},${s + TILE_DEG + m},${w + TILE_DEG + m}););
            out geom;"""
        val json = overpass(q)
        val elements = json.getJSONArray("elements")
        val waters = ArrayList<List<Pair<Double, Double>>>()
        val opens = ArrayList<Triple<String, JSONObject, List<Pair<Double, Double>>>>()
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: JSONObject()
            val ring = when (e.getString("type")) {
                "way" -> points(e.optJSONArray("geometry"))
                // Multipolygon park: its largest closed outer member stands in for the area.
                else -> e.optJSONArray("members")?.let { ms ->
                    (0 until ms.length()).map { ms.getJSONObject(it) }.filter { it.optString("role") == "outer" }
                        .map { points(it.optJSONArray("geometry")) }.filter { it.size >= 4 && it.first() == it.last() }
                        .maxByOrNull { it.size }
                }.orEmpty()
            }
            if (ring.size < 2) continue
            if (tags.has("waterway") || tags.optString("natural") == "water") waters += ring
            else if (ring.size >= 4 && ring.first() == ring.last()) opens += Triple("${e.getString("type")}/${e.getLong("id")}", tags, ring)
        }
        return opens.flatMap { (id, tags, ring) -> judge(id, tags, ring, waters) }
            .filter { floor(it.lat / TILE_DEG).toLong() == ty && floor(it.lon / TILE_DEG).toLong() == tx }
    }

    private fun judge(id: String, tags: JSONObject, ring: List<Pair<Double, Double>>, waters: List<List<Pair<Double, Double>>>): List<Candidate> {
        val lat0 = ring.map { it.first }.average(); val lon0 = ring.map { it.second }.average()
        val kx = 111_320.0 * cos(Math.toRadians(lat0)); val ky = 111_320.0
        val poly = ring.map { (la, lo) -> (lo - lon0) * kx to (la - lat0) * ky }
        val areaM2 = abs(poly.zipWithNext { a, b -> a.first * b.second - b.first * a.second }.sum()) / 2
        val (spot, clearance) = deepestPoint(poly) ?: return emptyList()
        val lat = lat0 + spot.second / ky; val lon = lon0 + spot.first / kx
        val name = tags.optString("name:ko").ifBlank { tags.optString("name") }.ifBlank { "이름 없는 공원" }
        val nameEn = tags.optString("name:en").ifBlank { if (name == "이름 없는 공원") "Unnamed park" else Romanize.name(name) }
        val slope = terrain?.slopePct(lat, lon)
        val people = (areaM2 / PERSON_M2).toInt()
        val out = ArrayList<Candidate>()
        if (clearance >= EQ_CLEARANCE_M && people >= EQ_MIN_PEOPLE && (slope == null || slope <= EQ_MAX_SLOPE)) {
            out += Candidate("eq:$id", "earthquake", name, nameEn, lat, lon,
                "넓은 공간 · 가장자리에서 ${clearance.toInt()} m · 약 ${"%,d".format(people)}명 · 경사 ${slope?.toInt() ?: "?"}%",
                "Open space · ${clearance.toInt()} m from its edge · room for ~${"%,d".format(people)} · slope ${slope?.toInt() ?: "?"}%")
        }
        val ground = terrain?.elevation(lat, lon)
        if (ground != null && areaM2 >= FLOOD_MIN_AREA && terrain?.pastFlood(lat, lon) == false && (slope == null || slope <= FLOOD_MAX_SLOPE)) {
            val nearest = waters.flatMap { it.zipWithNext() }.map { (a, b) ->
                closest(lat, lon, a, b)
            }.minByOrNull { it.third }
            val waterElev = nearest?.let { terrain.elevation(it.first, it.second) }
            val distW = nearest?.third
            val rise = if (waterElev != null) ground - waterElev else null
            // No stream within the fetched margin counts as far from water; rise is then judged against nothing.
            if ((distW == null || distW >= FLOOD_MIN_WATER_M) && (rise == null || rise >= FLOOD_MIN_RISE)) {
                out += Candidate("flood:$id", "flood", name, nameEn, lat, lon,
                    "높은 곳 · " + (rise?.let { "하천보다 ${it.toInt()} m 높음 · 하천에서 ${distW!!.toInt()} m · " } ?: "주변에 하천 없음 · ") + "침수흔적 밖",
                    "High ground · " + (rise?.let { "${it.toInt()} m above the nearest stream · ${distW!!.toInt()} m from it · " } ?: "no stream nearby · ") + "outside past floods")
            }
        }
        return out
    }

    /** Point inside the polygon farthest from its edge (coarse grid, then refine) and that distance. */
    private fun deepestPoint(poly: List<Pair<Double, Double>>): Pair<Pair<Double, Double>, Double>? {
        val minX = poly.minOf { it.first }; val maxX = poly.maxOf { it.first }
        val minY = poly.minOf { it.second }; val maxY = poly.maxOf { it.second }
        var best: Pair<Double, Double>? = null; var bestD = 0.0
        var step = max(4.0, max(maxX - minX, maxY - minY) / 40)
        var x0 = minX; var x1 = maxX; var y0 = minY; var y1 = maxY
        repeat(3) {
            var x = x0
            while (x <= x1) {
                var y = y0
                while (y <= y1) {
                    if (inside(x, y, poly)) { val d = edgeDist(x, y, poly); if (d > bestD) { bestD = d; best = x to y } }
                    y += step
                }
                x += step
            }
            val b = best ?: return null
            x0 = b.first - step; x1 = b.first + step; y0 = b.second - step; y1 = b.second + step
            step = max(1.0, step / 5)
        }
        return best?.let { it to bestD }
    }

    private fun inside(x: Double, y: Double, p: List<Pair<Double, Double>>): Boolean {
        var c = false
        for (i in 0 until p.size - 1) {
            val (xi, yi) = p[i]; val (xj, yj) = p[i + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c
        }
        return c
    }

    private fun edgeDist(x: Double, y: Double, p: List<Pair<Double, Double>>): Double =
        p.zipWithNext().minOf { (a, b) -> segDist(x, y, a.first, a.second, b.first, b.second) }

    private fun segDist(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax; val dy = by - ay
        val t = if (dx == 0.0 && dy == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    /** Closest point (lat, lon, metres) on segment a-b to (lat, lon). */
    private fun closest(lat: Double, lon: Double, a: Pair<Double, Double>, b: Pair<Double, Double>): Triple<Double, Double, Double> {
        val kx = 111_320.0 * cos(Math.toRadians(lat)); val ky = 111_320.0
        val ax = (a.second - lon) * kx; val ay = (a.first - lat) * ky
        val bx = (b.second - lon) * kx; val by = (b.first - lat) * ky
        val dx = bx - ax; val dy = by - ay
        val t = if (dx == 0.0 && dy == 0.0) 0.0 else ((-ax * dx - ay * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        val cx = ax + t * dx; val cy = ay + t * dy
        return Triple(lat + cy / ky, lon + cx / kx, hypot(cx, cy))
    }

    private fun points(g: JSONArray?): List<Pair<Double, Double>> =
        if (g == null) emptyList() else (0 until g.length()).map { g.getJSONObject(it).let { p -> p.getDouble("lat") to p.getDouble("lon") } }

    private fun overpass(query: String): JSONObject {
        var last: Exception? = null
        // Public Overpass servers are often busy: a "busy" answer (429/502/503/504) gets one retry on the same
        // server; a timeout or connection failure moves straight on to the next server.
        mirrors@ for (url in MIRRORS) {
            for (attempt in 1..2) {
                try {
                    val con = URL(url).openConnection() as HttpURLConnection
                    con.requestMethod = "POST"; con.doOutput = true; con.connectTimeout = 10_000; con.readTimeout = 60_000
                    con.setRequestProperty("User-Agent", "SafeRoute/0.1 (hackathon prototype)")
                    con.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
                    val code = con.responseCode
                    if (code == 200) return JSONObject(con.inputStream.bufferedReader().readText())
                    last = IllegalStateException("HTTP $code from $url")
                    Log.w(TAG, "overpass ${last.message} (attempt $attempt)")
                    if (code !in setOf(429, 502, 503, 504)) continue@mirrors
                    Thread.sleep(5_000)
                } catch (e: Exception) {
                    last = e
                    Log.w(TAG, "overpass $url: ${e.javaClass.simpleName}: ${e.message}")
                    continue@mirrors
                }
            }
        }
        throw last ?: IllegalStateException("no mirror")
    }

    private fun load(): Pair<Set<String>, List<Candidate>> {
        cache?.let { return it }
        val loaded = runCatching {
            val o = JSONObject(file.readText())
            val tiles = o.getJSONArray("tiles").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            val cs = o.getJSONArray("candidates").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
                Candidate(it.getString("id"), it.getString("hazard"), it.getString("name"), it.getString("nameEn"),
                    it.getDouble("lat"), it.getDouble("lon"), it.getString("noteKo"), it.getString("noteEn"))
            } }
            tiles to cs
        }.getOrDefault(emptySet<String>() to emptyList())
        return loaded.also { cache = it }
    }

    @Synchronized
    private fun save(tiles: Set<String>, cs: List<Candidate>) {
        val o = JSONObject().put("tiles", JSONArray(tiles.toList())).put("candidates", JSONArray(cs.map {
            JSONObject().put("id", it.id).put("hazard", it.hazard).put("name", it.name).put("nameEn", it.nameEn)
                .put("lat", it.lat).put("lon", it.lon).put("noteKo", it.noteKo).put("noteEn", it.noteEn)
        }))
        file.writeText(o.toString())
        cache = tiles to cs
    }

    companion object {
        private const val TAG = "SafeRoute"
        const val TILE_DEG = 0.005 // ~550 m × 440 m in Seoul
        private const val SEARCH_M = 1_500.0
        private const val PERSON_M2 = 0.825
        private const val EQ_CLEARANCE_M = 15.0
        private const val EQ_MIN_PEOPLE = 1_000
        private const val EQ_MAX_SLOPE = 15.0
        private const val FLOOD_MIN_RISE = 10.0
        private const val FLOOD_MIN_WATER_M = 150.0
        private const val FLOOD_MAX_SLOPE = 25.0
        private const val FLOOD_MIN_AREA = 500.0
        private val MIRRORS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        )

        fun tileKey(lat: Double, lon: Double) = "${floor(lat / TILE_DEG).toLong()}:${floor(lon / TILE_DEG).toLong()}"

        fun metres(la1: Double, lo1: Double, la2: Double, lo2: Double): Double =
            hypot((la2 - la1) * 111_320.0, (lo2 - lo1) * 111_320.0 * cos(Math.toRadians((la1 + la2) / 2)))
    }
}
