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
    private class FloatList(cap: Int) {
        var a = FloatArray(cap); var n = 0
        fun add(x: Float, y: Float) { if (n + 2 > a.size) a = a.copyOf(a.size * 2 + 2); a[n++] = x; a[n++] = y }
        fun toArray() = a.copyOf(n)
    }

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

    // ---------- Road graph (built lazily: only for map matching) ----------

    private var nodeA = IntArray(0)     // per segment: start node
    private var nodeB = IntArray(0)     // per segment: end node
    private var segLen = DoubleArray(0) // per segment: length in metres
    private var adj: Array<IntArray> = emptyArray() // per node: segments touching it
    private var nodeLat = FloatArray(0)
    private var nodeLon = FloatArray(0)

    private val graph by lazy {
        val ids = HashMap<Long, Int>()
        val lats = FloatList(1024); 
        fun node(la: Float, lo: Float): Int = ids.getOrPut((Math.round(la * 1e6).toLong() shl 32) xor (Math.round(lo * 1e6).toLong() and 0xFFFFFFFFL)) {
            lats.add(la, lo); ids.size
        }
        nodeA = IntArray(segCount); nodeB = IntArray(segCount); segLen = DoubleArray(segCount)
        for (sg in 0 until segCount) {
            val p = ways[segWay[sg]]; val k = segIdx[sg] * 2
            nodeA[sg] = node(p[k], p[k + 1]); nodeB[sg] = node(p[k + 2], p[k + 3])
            segLen[sg] = metres(p[k], p[k + 1], p[k + 2], p[k + 3])
        }
        val count = IntArray(ids.size)
        for (sg in 0 until segCount) { count[nodeA[sg]]++; count[nodeB[sg]]++ }
        adj = Array(ids.size) { IntArray(count[it]) }
        val fill = IntArray(ids.size)
        for (sg in 0 until segCount) { adj[nodeA[sg]][fill[nodeA[sg]]++] = sg; adj[nodeB[sg]][fill[nodeB[sg]]++] = sg }
        val all = lats.toArray()
        nodeLat = FloatArray(ids.size) { all[it * 2] }; nodeLon = FloatArray(ids.size) { all[it * 2 + 1] }
        true
    }

    private class Cand(val seg: Int, val frac: Double, val lat: Float, val lon: Float, val cost: Double, val d: Double)

    /**
     * [pts] ([lat, lon, …]) laid onto ways whose kind is in [allowed], by map matching: for each
     * point along the shape, the nearby ways it could be on; then the sequence of those that is
     * both close to the shape and connected through the network with a travel distance like the
     * shape's own (Viterbi). The result follows the roads from junction to junction.
     */
    fun snap(pts: FloatArray, allowed: String, radiusM: Double, hopM: Double = MAX_HOP_M): FloatArray {
        graph
        val dense = densify(pts, 20.0)
        val n = dense.size / 2
        if (n < 3) { why.set("too short"); return pts }
        val cands = Array(n) { i ->
            val lat = dense[i * 2]; val lon = dense[i * 2 + 1]
            val a = maxOf(0, i - 1); val b = minOf(n - 1, i + 1)
            val c = cos(Math.toRadians(lat.toDouble()))
            val hx = (dense[b * 2 + 1] - dense[a * 2 + 1]) * c; val hy = (dense[b * 2] - dense[a * 2]).toDouble()
            val list = ArrayList<Cand>()
            forNear(lat, lon) { sg ->
                val w = segWay[sg]
                if (allowed.indexOf(kinds[w]) < 0) return@forNear
                val p = ways[w]; val k = segIdx[sg] * 2
                val (pLat, pLon, d, f) = projectT(lat, lon, p[k], p[k + 1], p[k + 2], p[k + 3])
                if (d > radiusM) return@forNear
                val sx = (p[k + 3] - p[k + 1]) * c; val sy = (p[k + 2] - p[k]).toDouble()
                val hl = sqrt(hx * hx + hy * hy); val sl = sqrt(sx * sx + sy * sy)
                val sin = if (hl > 0 && sl > 0) sqrt(maxOf(0.0, 1 - ((hx * sx + hy * sy) / (hl * sl)).let { it * it })) else 0.0
                list += Cand(sg, f, pLat, pLon, d * d / (2 * SIGMA * SIGMA) + 3.0 * sin, d)
            }
            list.sortedBy { it.cost }.take(MAX_CANDS)
        }

        // Distance along the shape, so short stretches with no way nearby (a cut corner) can be
        // bridged through the network; longer ones split the matching into separate pieces.
        val cum = DoubleArray(n)
        for (q in 1 until n) cum[q] = cum[q - 1] + metres(dense[q * 2 - 2], dense[q * 2 - 1], dense[q * 2], dense[q * 2 + 1])
        val withCands = (0 until n).filter { cands[it].isNotEmpty() }
        if (withCands.size < n / 2) { why.set("no ways nearby"); return pts }
        // Already following the roads (good source shapes): keep it exactly as it is.
        val onRoad = (0 until n).count { q -> cands[q].any { it.d <= ON_ROAD_M } }
        if (onRoad >= n * 0.85) { why.set("already on the road"); return pts }
        val chains = ArrayList<List<Int>>()
        var cur = ArrayList<Int>()
        for (q in withCands) {
            if (cur.isNotEmpty() && cum[q] - cum[cur.last()] > MAX_GAP_M) { chains += cur; cur = ArrayList() }
            cur += q
        }
        if (cur.isNotEmpty()) chains += cur

        val out = FloatList(dense.size + 32)
        var matched = 0
        var next = 0 // first point not yet written
        for (chain in chains) {
            for (q in next until chain.first()) out.add(dense[q * 2], dense[q * 2 + 1])
            val path = if (chain.size >= 2) viterbi(cands, cum, chain, hopM) else null
            if (path == null) for (q in chain.first()..chain.last()) out.add(dense[q * 2], dense[q * 2 + 1])
            else { var k = 0; while (k < path.size) { out.add(path[k], path[k + 1]); k += 2 }; matched += chain.last() - chain.first() + 1 }
            next = chain.last() + 1
        }
        for (q in next until n) out.add(dense[q * 2], dense[q * 2 + 1])
        // Mostly unmatched: probably the wrong kind of way nearby; keep the original shape.
        if (matched < n * 0.5) { why.set("no connected path"); return pts }
        why.set("snapped")
        return TransitRoutes.simplify(out.toArray(), SIMPLIFY)
    }

    /** Best connected candidate sequence for the points [ids] (in order), as geometry; null if none connects. */
    private fun viterbi(cands: Array<List<Cand>>, cum: DoubleArray, ids: List<Int>, hopM: Double): FloatArray? {
        val len = ids.size
        val score = arrayOfNulls<DoubleArray>(len)
        val back = arrayOfNulls<IntArray>(len)
        val legs = arrayOfNulls<Array<FloatArray?>>(len) // geometry from the chosen previous candidate
        score[0] = DoubleArray(cands[ids[0]].size) { cands[ids[0]][it].cost }
        for (t in 1 until len) {
            val prev = cands[ids[t - 1]]; val cur = cands[ids[t]]
            val gc = cum[ids[t]] - cum[ids[t - 1]]
            val sc = DoubleArray(cur.size) { Double.MAX_VALUE }
            val bk = IntArray(cur.size) { -1 }
            val lg = arrayOfNulls<FloatArray>(cur.size)
            for ((pi, p) in prev.withIndex()) {
                val base = score[t - 1]!![pi]
                if (base == Double.MAX_VALUE) continue
                val routes = route(p, cur, maxOf(gc * 3, gc + 150.0))
                for ((ci, c) in cur.withIndex()) {
                    val r = routes[ci]
                    val s: Double; val geom: FloatArray
                    if (r != null) {
                        s = base + c.cost + abs(r.first - gc) / BETA; geom = r.second
                    } else {
                        // No route between them (e.g. parallel tracks joined by a far-off switch):
                        // allow a short step across, at a price, so the line stays matched.
                        val hop = metres(p.lat, p.lon, c.lat, c.lon)
                        if (hop > hopM) continue
                        s = base + c.cost + HOP_PENALTY + abs(hop - gc) / BETA; geom = floatArrayOf(c.lat, c.lon)
                    }
                    if (s < sc[ci]) { sc[ci] = s; bk[ci] = pi; lg[ci] = geom }
                }
            }
            if (sc.all { it == Double.MAX_VALUE }) return null // the chain breaks: no connected way
            score[t] = sc; back[t] = bk; legs[t] = lg
        }
        var best = score[len - 1]!!.indices.minByOrNull { score[len - 1]!![it] } ?: return null
        val pieces = ArrayList<FloatArray>()
        for (t in len - 1 downTo 1) {
            pieces += legs[t]!![best]!!
            best = back[t]!![best]
        }
        val first = cands[ids[0]][best]
        val out = FloatList(64)
        out.add(first.lat, first.lon)
        for (pc in pieces.asReversed()) { var k = 0; while (k < pc.size) { out.add(pc[k], pc[k + 1]); k += 2 } }
        return out.toArray()
    }

    /**
     * Shortest network distances (and their geometry) from candidate [a] to each of [targets],
     * searching no further than [limit] metres. Direction of travel is not restricted.
     */
    private fun route(a: Cand, targets: List<Cand>, limit: Double): Array<Pair<Double, FloatArray>?> {
        val res = arrayOfNulls<Pair<Double, FloatArray>>(targets.size)
        // Same segment: straight along it.
        for ((ti, t) in targets.withIndex()) if (t.seg == a.seg) {
            res[ti] = abs(t.frac - a.frac) * segLen[a.seg] to floatArrayOf(t.lat, t.lon)
        }
        val dist = HashMap<Int, Double>(); val prevSeg = HashMap<Int, Int>(); val prevNode = HashMap<Int, Int>()
        val pq = java.util.PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        val sa = nodeA[a.seg]; val sb = nodeB[a.seg]
        dist[sa] = a.frac * segLen[a.seg]; dist[sb] = (1 - a.frac) * segLen[a.seg]
        pq += dist[sa]!! to sa; pq += dist[sb]!! to sb
        val wanted = HashSet<Int>(); for (t in targets) { wanted += nodeA[t.seg]; wanted += nodeB[t.seg] }
        var found = 0
        while (pq.isNotEmpty() && found < wanted.size) {
            val (d, u) = pq.poll()
            if (d > (dist[u] ?: Double.MAX_VALUE) || d > limit) continue
            if (u in wanted) found++
            for (sg in adj[u]) {
                val v = if (nodeA[sg] == u) nodeB[sg] else nodeA[sg]
                val nd = d + segLen[sg]
                if (nd < (dist[v] ?: Double.MAX_VALUE) && nd <= limit) { dist[v] = nd; prevSeg[v] = sg; prevNode[v] = u; pq += nd to v }
            }
        }
        fun nodePath(end: Int): FloatArray {
            val nodes = ArrayList<Int>()
            var u: Int? = end
            while (u != null && u != sa && u != sb) { nodes += u; u = prevNode[u] }
            if (u != null) nodes += u
            nodes.reverse()
            return FloatArray(nodes.size * 2) { if (it % 2 == 0) nodeLat[nodes[it / 2]] else nodeLon[nodes[it / 2]] }
        }
        for ((ti, t) in targets.withIndex()) {
            if (t.seg == a.seg) continue
            val viaA = dist[nodeA[t.seg]]?.let { it + t.frac * segLen[t.seg] }
            val viaB = dist[nodeB[t.seg]]?.let { it + (1 - t.frac) * segLen[t.seg] }
            val useA = viaA != null && (viaB == null || viaA <= viaB)
            val total = (if (useA) viaA else viaB) ?: continue
            val nodes = nodePath(if (useA) nodeA[t.seg] else nodeB[t.seg])
            res[ti] = total to (nodes + floatArrayOf(t.lat, t.lon))
        }
        return res
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

    companion object {
        /** About 55 m. */
        private const val CELL = 0.0005f
        private val SIMPLIFY = (360.0 / (256.0 * Math.pow(2.0, 17.0)) / 3.0).toFloat()
        /** GPS-like noise of the source shapes, metres. */
        private const val SIGMA = 12.0
        /** How strongly travel distance must agree with the shape's own distance. */
        private const val BETA = 8.0
        private const val MAX_CANDS = 8
        /** Straight steps between ways with no route between them: how far, and their cost. */
        private const val MAX_HOP_M = 80.0
        private const val HOP_PENALTY = 25.0
        /** Within this of a suitable way counts as already on it. */
        private const val ON_ROAD_M = 6.0
        /** Longest stretch with no way nearby that is bridged through the network. */
        private const val MAX_GAP_M = 300.0

        private fun key(cx: Long, cy: Long) = (cx shl 32) xor (cy and 0xFFFFFFFFL)
        private fun cell(lat: Float, lon: Float): Long =
            key(Math.floor((lon * cos(Math.toRadians(lat.toDouble())) / CELL)).toLong(), Math.floor((lat / CELL).toDouble()).toLong())

        private fun metres(la0: Float, lo0: Float, la1: Float, lo1: Float): Double {
            val dx = (lo1 - lo0) * cos(Math.toRadians(la0.toDouble())) * 111_320.0; val dy = (la1 - la0) * 110_540.0
            return sqrt(dx * dx + dy * dy)
        }

        private data class Proj(val lat: Float, val lon: Float, val d: Double, val t: Double)

        /** Nearest point on a segment, its distance in metres and where along the segment (0..1). */
        private fun projectT(lat: Float, lon: Float, la0: Float, lo0: Float, la1: Float, lo1: Float): Proj {
            val c = cos(Math.toRadians(lat.toDouble()))
            val ax = (lo0 - lon) * c; val ay = (la0 - lat).toDouble(); val bx = (lo1 - lon) * c; val by = (la1 - lat).toDouble()
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val pLat = (la0 + (la1 - la0) * t).toFloat(); val pLon = (lo0 + (lo1 - lo0) * t).toFloat()
            return Proj(pLat, pLon, metres(lat, lon, pLat, pLon), t)
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

        /** Points no more than [step] metres apart. */
        private fun densify(pts: FloatArray, step: Double = 12.0): FloatArray {
            val out = FloatList(pts.size * 2)
            var k = 0
            while (k + 1 < pts.size) {
                if (k + 3 < pts.size) {
                    val steps = (metres(pts[k], pts[k + 1], pts[k + 2], pts[k + 3]) / step).toInt().coerceIn(1, 2000)
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

        /** Straight steps allowed between unconnected ways: short on streets (no stubs), longer between station tracks. */
        private fun hopFor(mode: TransitMode) = if (mode == TransitMode.Train || mode == TransitMode.Metro) 80.0 else 20.0

        /** Kilometres of shape snapped, or kept and why, by mode (for the pack builder's log). */
        val stats = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
        private val why = ThreadLocal<String>()

        /** [tile] with every line laid onto the roads and tracks of [net]. */
        fun snapTile(tile: TileData, net: RoadNet): TileData = TileData(tile.routes.map { r ->
            val (kinds, radius) = allowed(r.mode) ?: return@map r
            val snappedParts = r.parts.map { p ->
                why.set("?")
                net.snap(p, kinds, radius, hopFor(r.mode)).also {
                    var m = 0.0; var k = 0
                    while (k + 3 < p.size) { m += metres(p[k], p[k + 1], p[k + 2], p[k + 3]); k += 2 }
                    stats.getOrPut("${r.mode.name} ${why.get()}") { java.util.concurrent.atomic.AtomicLong() }.addAndGet(m.toLong())
                }
            }
            // Variants of a line that share a stretch draw it once.
            val parts = TransitRoutes.unionParts(snappedParts)
            var s = 90f; var n = -90f; var w = 180f; var e = -180f
            for (p in parts) { var k = 0; while (k < p.size) { val la = p[k]; val lo = p[k + 1]; if (la < s) s = la; if (la > n) n = la; if (lo < w) w = lo; if (lo > e) e = lo; k += 2 } }
            TileRoute(r.key, r.mode, r.osmRoute, r.ref, r.name, r.color, r.text, r.rank, parts,
                IntArray(parts.size) { parts[it].contentHashCode() }, r.stops,
                Bounds(s.toDouble(), w.toDouble(), n.toDouble(), e.toDouble()), r.routeIdx)
        }, tile.zoomFiltered)
    }
}
