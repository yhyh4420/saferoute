package com.saferoute.app.pack

import android.content.res.AssetManager
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.nio.ShortBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt
import org.json.JSONObject

/**
 * Memory-mapped route pack built by `pipeline/build_pack.py` (format: docs/PACK_FORMAT.md).
 *
 * Every walking-graph node stores, per (hazard, profile) tree, the next node towards the nearest
 * suitable shelter. Routing on the device is just following those pointers, so it works offline
 * and in constant time per step.
 */
class RoutePack private constructor(buf: ByteBuffer) {

    data class TreeKey(val hazard: String, val profile: String)

    class Tree(val next: IntBuffer, val dist: ShortBuffer, val shelter: ShortBuffer)

    data class Route(val nodes: IntArray, val distanceM: Int, val shelterIdx: Int)

    val city: String
    val nodeCount: Int
    val trees: Map<TreeKey, Tree>
    private val lat: IntBuffer
    private val lon: IntBuffer
    private val grid: Grid

    init {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { buf.get(it) }
        require(String(magic) == "SRPK") { "not a SafeRoute pack" }
        val version = buf.short.toInt()
        require(version == 2) { "unsupported pack version $version" }
        val headerLen = buf.int
        val header = JSONObject(String(ByteArray(headerLen).also { buf.get(it) }, Charsets.UTF_8))
        city = header.getString("city")
        nodeCount = header.getInt("nodes")
        val n = nodeCount

        lat = slice(buf, n * 4).asIntBuffer()
        lon = slice(buf, n * 4).asIntBuffer()
        val treeList = header.getJSONArray("trees")
        trees = (0 until treeList.length()).associate { i ->
            val t = treeList.getJSONObject(i)
            TreeKey(t.getString("hazard"), t.getString("profile")) to Tree(
                next = slice(buf, n * 4).asIntBuffer(),
                dist = slice(buf, n * 2).asShortBuffer(),
                shelter = slice(buf, n * 2).asShortBuffer(),
            )
        }
        val cellCount = buf.int
        grid = Grid(
            lat, lon,
            keys = slice(buf, cellCount * 8).asLongBuffer(),
            start = slice(buf, (cellCount + 1) * 4).asIntBuffer(),
            order = slice(buf, n * 4).asIntBuffer(),
        )
    }

    val hazards: List<String> get() = trees.keys.map { it.hazard }.distinct()

    fun latOf(node: Int) = lat[node] / SCALE
    fun lonOf(node: Int) = lon[node] / SCALE

    /** Nearest graph node and its distance in metres, or null if nothing within ~1 km. */
    fun nearestNode(latDeg: Double, lonDeg: Double): Pair<Int, Double>? = grid.nearest(latDeg, lonDeg)

    /** Follow next-pointers from [start] to the shelter. Null if the node cannot reach one. */
    fun route(start: Int, key: TreeKey): Route? {
        val tree = trees[key] ?: return null
        if (tree.next[start] < 0) return null
        val path = ArrayList<Int>()
        var node = start
        while (path.size < MAX_ROUTE_NODES) {
            path.add(node)
            val next = tree.next[node]
            if (next == node) break
            node = next
        }
        return Route(path.toIntArray(), tree.dist[start].toInt() and 0xFFFF, tree.shelter[start].toInt() and 0xFFFF)
    }

    /** Pre-built cell index from the pack: sorted cell keys, offsets into [order], node ids by cell. */
    private class Grid(
        private val lat: IntBuffer,
        private val lon: IntBuffer,
        private val keys: LongBuffer,
        private val start: IntBuffer,
        private val order: IntBuffer,
    ) {
        fun nearest(la: Double, lo: Double): Pair<Int, Double>? {
            val cy = floor(la / CELL_DEG).toLong()
            val cx = floor(lo / CELL_DEG).toLong()
            val kx = cos(Math.toRadians(la))
            var best = -1
            var bestD = Double.MAX_VALUE
            for (ring in 0..SEARCH_RINGS) {
                for (dy in -ring..ring) for (dx in -ring..ring) {
                    if (maxOf(abs(dy), abs(dx)) != ring) continue
                    val c = find((cy + dy) * CELL_KEY + (cx + dx))
                    if (c < 0) continue
                    for (k in start[c] until start[c + 1]) {
                        val i = order[k]
                        val y = lat[i] / SCALE - la
                        val x = (lon[i] / SCALE - lo) * kx
                        val d = y * y + x * x
                        if (d < bestD) { bestD = d; best = i }
                    }
                }
                // A hit in ring r can only be beaten by something in ring r+1.
                if (best >= 0 && ring >= 1) break
            }
            return if (best < 0) null else best to sqrt(bestD) * METRES_PER_DEG
        }

        private fun find(key: Long): Int {
            var lo = 0
            var hi = keys.limit() - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val v = keys[mid]
                when {
                    v < key -> lo = mid + 1
                    v > key -> hi = mid - 1
                    else -> return mid
                }
            }
            return -1
        }
    }

    companion object {
        private const val SCALE = 1_000_000.0
        private const val CELL_DEG = 0.002 // ~220 m
        private const val CELL_KEY = 1_000_000L
        private const val SEARCH_RINGS = 5 // ~1.1 km
        private const val METRES_PER_DEG = 111_320.0
        private const val MAX_ROUTE_NODES = 20_000

        private fun slice(buf: ByteBuffer, bytes: Int): ByteBuffer {
            val s = buf.slice().order(ByteOrder.LITTLE_ENDIAN)
            s.limit(bytes)
            buf.position(buf.position() + bytes)
            return s
        }

        /** Asset must be stored uncompressed (see `noCompress` in build.gradle.kts) to be mappable. */
        fun open(assets: AssetManager, name: String): RoutePack {
            val fd = assets.openFd(name)
            FileInputStream(fd.fileDescriptor).channel.use { ch ->
                val mapped = ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length)
                return RoutePack(mapped)
            }
        }
    }
}
