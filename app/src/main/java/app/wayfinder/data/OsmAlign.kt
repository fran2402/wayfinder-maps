package app.wayfinder.data

import kotlin.math.cos

/**
 * Lays Transitous lines onto OpenStreetMap's route geometry, which follows the actual tracks
 * and roads the map draws (Transitous shapes are often rough or stop-to-stop).
 *
 * A line is matched to the OSM routes with the same kind (rail, urban rail, bus) and number
 * whose path it follows: at least [MIN_FIT] of points sampled along the Transitous shape must
 * lie within about 150 m of the OSM route. Then the OSM geometry replaces the shape. Lines
 * without a convincing match keep their own shape.
 */
class OsmAlign(osm: TileData) {
    private class Rel(val parts: List<FloatArray>, val bounds: Bounds) {
        /** Cells (about 110 m) the route passes through. */
        val cells: HashSet<Long> by lazy {
            val set = HashSet<Long>()
            for (p in parts) {
                var k = 0
                while (k + 3 < p.size) {
                    val la0 = p[k]; val lo0 = p[k + 1]; val la1 = p[k + 2]; val lo1 = p[k + 3]
                    val steps = (maxOf(kotlin.math.abs(la1 - la0), kotlin.math.abs(lo1 - lo0)) / (CELL / 2)).toInt().coerceIn(1, 2000)
                    for (s in 0..steps) {
                        val f = s.toFloat() / steps
                        set += cellOf(la0 + (la1 - la0) * f, lo0 + (lo1 - lo0) * f)
                    }
                    k += 2
                }
            }
            set
        }
    }

    private val index = HashMap<String, MutableList<Rel>>()

    init {
        for (r in osm.routes) {
            val group = groupOfOsm(r.osmRoute) ?: continue
            val ref = norm(r.ref)
            if (ref.isEmpty()) continue
            index.getOrPut("$group|$ref") { ArrayList() } += Rel(r.parts, r.bounds)
        }
    }

    val size get() = index.values.sumOf { it.size }

    /** [tile] with every matched line moved onto OSM geometry, clipped to [area]. */
    fun align(tile: TileData, area: Bounds): TileData = TileData(tile.routes.map { r -> alignRoute(r, area) ?: r }, tile.zoomFiltered)

    private fun alignRoute(r: TileRoute, area: Bounds): TileRoute? {
        val group = groupOf(r.mode) ?: return null
        val ref = norm(r.ref)
        if (ref.isEmpty()) return null
        val candidates = index["$group|$ref"] ?: return null
        val samples = sample(r.parts)
        if (samples.isEmpty()) return null
        val near = r.bounds.expanded(0.2)
        val matched = candidates.filter { it.bounds.intersects(near) && fit(samples, it) >= MIN_FIT }
        if (matched.isEmpty()) return null
        // Together, the matched routes must cover the line (variants and directions add up).
        val cover = samples.count { (la, lo) -> matched.any { m -> nearCell(m.cells, la, lo) } }.toDouble() / samples.size
        if (cover < MIN_COVER) return null
        val seen = java.util.IdentityHashMap<FloatArray, Boolean>()
        val parts = ArrayList<FloatArray>()
        for (m in matched) for (p in m.parts) if (seen.put(p, true) == null) parts += p
        val whole = TileRoute(
            r.key, r.mode, r.osmRoute, r.ref, r.name, r.color, r.text, r.rank,
            parts, IntArray(parts.size) { parts[it].contentHashCode() }, r.stops,
            matched.fold(matched.first().bounds) { a, m -> Bounds(minOf(a.south, m.bounds.south), minOf(a.west, m.bounds.west), maxOf(a.north, m.bounds.north), maxOf(a.east, m.bounds.east)) },
            r.routeIdx,
        )
        return TransitRoutes.clip(whole, area)
    }

    private fun fit(samples: List<Pair<Float, Float>>, rel: Rel): Double =
        samples.count { (la, lo) -> nearCell(rel.cells, la, lo) }.toDouble() / samples.size

    companion object {
        private const val CELL = 0.001f
        private const val MIN_FIT = 0.5
        private const val MIN_COVER = 0.8

        private fun cellOf(lat: Float, lon: Float): Long {
            val cy = Math.floor((lat / CELL).toDouble()).toLong()
            val cx = Math.floor((lon * cos(Math.toRadians(lat.toDouble())) / CELL)).toLong()
            return (cx shl 32) xor (cy and 0xFFFFFFFFL)
        }

        private fun nearCell(cells: Set<Long>, lat: Float, lon: Float): Boolean {
            val cy = Math.floor((lat / CELL).toDouble()).toLong()
            val cx = Math.floor((lon * cos(Math.toRadians(lat.toDouble())) / CELL)).toLong()
            for (dx in -1L..1L) for (dy in -1L..1L) if (((cx + dx) shl 32) xor ((cy + dy) and 0xFFFFFFFFL) in cells) return true
            return false
        }

        /** Up to 40 points spread along a shape. */
        private fun sample(parts: List<FloatArray>): List<Pair<Float, Float>> {
            val total = parts.sumOf { it.size / 2 }
            if (total == 0) return emptyList()
            val step = maxOf(1, total / 40)
            val out = ArrayList<Pair<Float, Float>>()
            var i = 0
            for (p in parts) {
                var k = 0
                while (k + 1 < p.size) {
                    if (i++ % step == 0) out += p[k] to p[k + 1]
                    k += 2
                }
            }
            return out
        }

        private fun norm(ref: String) = ref.trim().lowercase().replace(" ", "").trimStart('0')

        private fun groupOf(mode: TransitMode) = when (mode) {
            TransitMode.Train -> "rail"
            TransitMode.Metro, TransitMode.Tram -> "urban"
            TransitMode.Bus -> "bus"
            else -> null
        }

        private fun groupOfOsm(route: String) = when (route) {
            "train" -> "rail"
            "subway", "light_rail", "monorail", "tram" -> "urban"
            "bus", "trolleybus" -> "bus"
            else -> null
        }
    }
}
