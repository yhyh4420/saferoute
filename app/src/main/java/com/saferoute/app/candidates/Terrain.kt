package com.saferoute.app.candidates

import android.content.res.AssetManager
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Ground elevation and past-flood mask for the city (pipeline/terrain.py), memory-mapped from assets.
 * Lets the phone judge "높은 곳" and "상습 침수지역" offline, without asking an elevation service.
 */
class Terrain private constructor(private val buf: ByteBuffer) {
    private val south: Double
    private val west: Double
    private val dLat: Double
    private val dLon: Double
    private val rows: Int
    private val cols: Int
    private val elevOffset = HEADER
    private val maskOffset: Int

    init {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        require(buf.get(0).toInt().toChar() == 'S' && buf.getShort(4).toInt() == 1) { "not a terrain grid" }
        south = buf.getDouble(6); west = buf.getDouble(14); dLat = buf.getDouble(22); dLon = buf.getDouble(30)
        rows = buf.getInt(38); cols = buf.getInt(42)
        maskOffset = elevOffset + rows * cols * 2
    }

    private fun cell(lat: Double, lon: Double): Pair<Int, Int>? {
        val r = floor((lat - south) / dLat).toInt()
        val c = floor((lon - west) / dLon).toInt()
        return if (r in 0 until rows && c in 0 until cols) r to c else null
    }

    fun elevation(lat: Double, lon: Double): Double? =
        cell(lat, lon)?.let { (r, c) -> buf.getShort(elevOffset + 2 * (r * cols + c)).toDouble() }

    fun pastFlood(lat: Double, lon: Double): Boolean = cell(lat, lon)?.let { (r, c) ->
        val i = r * cols + c
        (buf.get(maskOffset + i / 8).toInt() shr (i % 8)) and 1 == 1
    } ?: false

    /** Percent slope from the four neighbouring cells. */
    fun slopePct(lat: Double, lon: Double): Double? {
        val e = elevation(lat + dLat, lon) ?: return null
        val w = elevation(lat - dLat, lon) ?: return null
        val n = elevation(lat, lon + dLon) ?: return null
        val s = elevation(lat, lon - dLon) ?: return null
        val my = dLat * 111_320.0 * 2
        val mx = dLon * 111_320.0 * cos(Math.toRadians(lat)) * 2
        return hypot((n - s) / mx, (e - w) / my) * 100
    }

    companion object {
        private const val HEADER = 46

        fun open(assets: AssetManager, name: String): Terrain? = runCatching {
            val fd = assets.openFd(name)
            FileInputStream(fd.fileDescriptor).channel.use { ch ->
                Terrain(ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length))
            }
        }.getOrNull()
    }
}
