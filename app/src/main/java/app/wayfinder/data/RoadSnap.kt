package app.wayfinder.data

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * The roads and tracks of one area (from OpenStreetMap), for laying lines onto them by
 * geometry: no line numbers involved.
 *
 * [snap] walks along a shape in ~12 m steps and moves each point onto the nearest road or track
 * of a kind the mode uses, preferring ones running the same way and staying on the same street;
 * where the line turns from one street onto another it passes through their shared junction
 * instead of cutting the corner. Points with nothing suitable nearby keep their place.
 */
class RoadNet(lines: Sequence<String>) {
    private val ways = ArrayList<FloatArray>()
    private val kinds = ArrayList<Char>()
    private var segWay = IntArray(1 shl 14)
    private var segIdx = IntArray(1 shl 14)
    private var segCount = 0
    private val grid = HashMap<Long, IntArray>()

    init {
        for (line in lines) {
            val parts = line.trim().split(' ')
            if (parts.size < 5) continue
            val k = parts[0][0]
            val n = (parts.size - 1) / 2
            val pts = FloatArray(n * 2) { parts[it + 1].toFloat() }
            val w = ways.size
            ways += pts; kinds += k
            for (i in 0 until n - 1) addSegment(w, i)
        }
    }

    val wayCount get() = ways.size

    private fun addSegment(w: Int, i: Int) {
        if (segCount == segWay.size) { segWay = segWay.copyOf(segCount * 2); segIdx = segIdx.copyOf(segCount * 2) }
        val s = segCount++
        segWay[s] = w; segIdx[s] = i
        val p = ways[w]
        val la0 = p[i * 2]; val lo0 = p[i * 2 + 1]; val la1 = p[i * 2 + 2]; val lo1 = p[i * 2 + 3]
        val steps = (maxOf(abs(la1 - la0), abs(lo1 - lo0)) / (CELL / 2)).toInt().coerceIn(1, 4000)
        var last = Long.MIN_VALUE
        for (k in 0..steps) {
            val f = k.toFloat() / steps
            val c = cell(la0 + (la1 - la0) * f, lo0 + (lo1 - lo0) * f)
            if (c == last) continue
            last = c
            val cur = grid[c]
            if (cur == null) grid[c] = intArrayOf(1, s, 0, 0)
            else if (cur[cur[0]] != s) {
                val arr = if (cur[0] + 1 >= cur.size) cur.copyOf(cur.size * 2).also { grid[c] = it } else cur
                arr[0]++; arr[arr[0]] = s
            }
        }
    }

    /** [pts] ([lat, lon, …]) laid onto ways whose kind is in [allowed]. */
    fun snap(pts: FloatArray, allowed: String, radiusM: Double): FloatArray {
        val dense = densify(pts)
        val n = dense.size / 2
        if (n < 2) return pts
        val out = FloatList(dense.size + 16)
        var prevWay = -1
        var lastWay = -1      // the way before a stretch with nothing near (a cut corner, say)
        var gapStart = -1     // where that stretch begins in [out]
        var gapLen = 0.0
        var snapped = 0
        for (i in 0 until n) {
            val lat = dense[i * 2]; val lon = dense[i * 2 + 1]
            val a = maxOf(0, i - 1); val b = minOf(n - 1, i + 1)
            val hx = (dense[b * 2 + 1] - dense[a * 2 + 1]) * cos(Math.toRadians(lat.toDouble())); val hy = (dense[b * 2] - dense[a * 2]).toDouble()
            var best = -1; var bestCost = Double.MAX_VALUE; var bLat = 0f; var bLon = 0f
            forNear(lat, lon) { s ->
                val w = segWay[s]
                if (allowed.indexOf(kinds[w]) < 0) return@forNear
                val p = ways[w]; val k = segIdx[s] * 2
                val (pLat, pLon, d) = project(lat, lon, p[k], p[k + 1], p[k + 2], p[k + 3])
                if (d > radiusM) return@forNear
                val sx = (p[k + 3] - p[k + 1]) * cos(Math.toRadians(lat.toDouble())); val sy = (p[k + 2] - p[k]).toDouble()
                val hl = sqrt(hx * hx + hy * hy); val sl = sqrt(sx * sx + sy * sy)
                val sin = if (hl > 0 && sl > 0) sqrt(maxOf(0.0, 1 - ((hx * sx + hy * sy) / (hl * sl)).let { it * it })) else 0.0
                val cost = d + 30.0 * sin - (if (w == prevWay) 8.0 else 0.0)
                if (cost < bestCost) { bestCost = cost; best = s; bLat = pLat; bLon = pLon }
            }
            if (best < 0) {
                if (prevWay >= 0) { lastWay = prevWay; gapStart = out.n; gapLen = 0.0 }
                else if (gapStart >= 0 && out.n >= 2) gapLen += metres(out.a[out.n - 2], out.a[out.n - 1], lat, lon)
                out.add(lat, lon); prevWay = -1; continue
            }
            val w = segWay[best]
            if (prevWay < 0 && lastWay >= 0 && gapLen < 250.0) {
                // Back on a street after a short stretch with none: replace the stretch with the
                // junction between the two streets (or nothing, when it is the same street).
                if (w == lastWay) out.n = gapStart
                else junction(lastWay, w, bLat, bLon, 250.0)?.let { (jl, jo) -> out.n = gapStart; out.add(jl, jo) }
            } else if (prevWay >= 0 && w != prevWay) {
                junction(prevWay, w, bLat, bLon, 60.0)?.let { (jl, jo) -> out.add(jl, jo) }
            }
            lastWay = -1; gapStart = -1
            out.add(bLat, bLon)
            prevWay = w
            snapped++
        }
        // Mostly unmatched: probably the wrong kind of way nearby; keep the original shape.
        if (snapped < n * 0.6) return pts
        return TransitRoutes.simplify(out.toArray(), SIMPLIFY)
    }

    /** A vertex shared by ways [a] and [b] near the snapped point (where one street meets the next). */
    private fun junction(a: Int, b: Int, lat: Float, lon: Float, within: Double): Pair<Float, Float>? {
        val pa = ways[a]; val pb = ways[b]
        var best: Pair<Float, Float>? = null; var bestD = within
        var i = 0
        while (i < pb.size) {
            val d = metres(lat, lon, pb[i], pb[i + 1])
            if (d < bestD) {
                var j = 0
                while (j < pa.size) {
                    if (abs(pa[j] - pb[i]) < 2e-5f && abs(pa[j + 1] - pb[i + 1]) < 2e-5f) { best = pb[i] to pb[i + 1]; bestD = d; break }
                    j += 2
                }
            }
            i += 2
        }
        return best
    }

    private inline fun forNear(lat: Float, lon: Float, f: (Int) -> Unit) {
        val cy = Math.floor((lat / CELL).toDouble()).toLong()
        val cx = Math.floor((lon * cos(Math.toRadians(lat.toDouble())) / CELL)).toLong()
        val seen = HashSet<Int>()
        for (dx in -1L..1L) for (dy in -1L..1L) {
            val arr = grid[key(cx + dx, cy + dy)] ?: continue
            for (k in 1..arr[0]) if (seen.add(arr[k])) f(arr[k])
        }
    }

    private class FloatList(cap: Int) {
        var a = FloatArray(cap); var n = 0
        fun add(x: Float, y: Float) { if (n + 2 > a.size) a = a.copyOf(a.size * 2 + 2); a[n++] = x; a[n++] = y }
        fun toArray() = a.copyOf(n)
    }

    companion object {
        /** About 55 m. */
        private const val CELL = 0.0005f
        private val SIMPLIFY = (360.0 / (256.0 * Math.pow(2.0, 17.0)) / 3.0).toFloat()

        private fun key(cx: Long, cy: Long) = (cx shl 32) xor (cy and 0xFFFFFFFFL)
        private fun cell(lat: Float, lon: Float): Long =
            key(Math.floor((lon * cos(Math.toRadians(lat.toDouble())) / CELL)).toLong(), Math.floor((lat / CELL).toDouble()).toLong())

        private fun metres(la0: Float, lo0: Float, la1: Float, lo1: Float): Double {
            val dx = (lo1 - lo0) * cos(Math.toRadians(la0.toDouble())) * 111_320.0; val dy = (la1 - la0) * 110_540.0
            return sqrt(dx * dx + dy * dy)
        }

        /** Nearest point on a segment and its distance in metres. */
        private fun project(lat: Float, lon: Float, la0: Float, lo0: Float, la1: Float, lo1: Float): Triple<Float, Float, Double> {
            val c = cos(Math.toRadians(lat.toDouble()))
            val ax = (lo0 - lon) * c; val ay = (la0 - lat).toDouble(); val bx = (lo1 - lon) * c; val by = (la1 - lat).toDouble()
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val pLat = (la0 + (la1 - la0) * t).toFloat(); val pLon = (lo0 + (lo1 - lo0) * t).toFloat()
            return Triple(pLat, pLon, metres(lat, lon, pLat, pLon))
        }

        /** Points no more than ~12 m apart. */
        private fun densify(pts: FloatArray): FloatArray {
            val out = FloatList(pts.size * 2)
            var k = 0
            while (k + 1 < pts.size) {
                if (k + 3 < pts.size) {
                    val steps = (metres(pts[k], pts[k + 1], pts[k + 2], pts[k + 3]) / 12.0).toInt().coerceIn(1, 2000)
                    for (s in 0 until steps) {
                        val f = s.toFloat() / steps
                        out.add(pts[k] + (pts[k + 2] - pts[k]) * f, pts[k + 1] + (pts[k + 3] - pts[k + 1]) * f)
                    }
                } else out.add(pts[k], pts[k + 1])
                k += 2
            }
            return out.toArray()
        }

        /** Which ways a mode may be laid on, and how far away they may be. */
        fun allowed(mode: TransitMode): Pair<String, Double>? = when (mode) {
            TransitMode.Bus -> "r" to 35.0
            TransitMode.Tram -> "tl" to 30.0
            TransitMode.Metro -> "slmt" to 40.0
            TransitMode.Train -> "Rl" to 40.0
            else -> null
        }

        /** [tile] with every line laid onto the roads and tracks of [net]. */
        fun snapTile(tile: TileData, net: RoadNet): TileData = TileData(tile.routes.map { r ->
            val (kinds, radius) = allowed(r.mode) ?: return@map r
            val parts = r.parts.map { net.snap(it, kinds, radius) }
            var s = 90f; var n = -90f; var w = 180f; var e = -180f
            for (p in parts) { var k = 0; while (k < p.size) { val la = p[k]; val lo = p[k + 1]; if (la < s) s = la; if (la > n) n = la; if (lo < w) w = lo; if (lo > e) e = lo; k += 2 } }
            TileRoute(r.key, r.mode, r.osmRoute, r.ref, r.name, r.color, r.text, r.rank, parts,
                IntArray(parts.size) { parts[it].contentHashCode() }, r.stops,
                Bounds(s.toDouble(), w.toDouble(), n.toDouble(), e.toDouble()), r.routeIdx)
        }, tile.zoomFiltered)
    }
}
