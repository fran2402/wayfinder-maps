package app.wayfinder.data

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.Reader
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/** A stop of a line, as Transitous identifies it (the id is needed for departures). */
data class StopRef(val id: String, val name: String, val lat: Double, val lon: Double)

/** A line in the loaded area, with the bounds of its geometry (for "lines in view"). */
data class LoadedLine(val info: LineInfo, val bounds: Bounds, val stops: List<StopRef> = emptyList())

/** One route as parsed from one map tile; merged across tiles by [key]. */
class TileRoute(
    val key: String,
    val mode: TransitMode,
    val osmRoute: String,
    val ref: String,
    val name: String,
    val color: Int,
    val text: Int,
    val rank: Int,
    /** Simplified shapes as [lat, lon, …]; [partIds] are content hashes for de-duplication. */
    val parts: List<FloatArray>,
    val partIds: IntArray,
    val stops: List<StopRef>,
    val bounds: Bounds,
    /** Transitous route indexes behind this line, for fetching the whole route. */
    val routeIdx: IntArray = IntArray(0),
)

/** Everything one tile request returned. */
class TileData(val routes: List<TileRoute>, val zoomFiltered: Boolean)

/** The merged network for the loaded tiles, as GeoJSON plus the line list. */
data class TransitData(
    val lines: String,
    val stops: String,
    val routes: List<LoadedLine>,
    /** True when the server left out minor routes because of the zoom level. */
    val zoomFiltered: Boolean,
)

/**
 * Public transport routes with GTFS line colours and numbers from Transitous
 * (MOTIS `/api/experimental/map/routes`), fetched per map tile and merged.
 *
 * Responses are stream-parsed into compact arrays; shapes are simplified to the tile's zoom;
 * stop-to-stop straight chords (routes without real shapes) are dropped so they cannot
 * streak across the map.
 */
object TransitRoutes {
    private const val URL = "https://api.transitous.org/api/experimental/map/routes"

    /** Upper bound on routes kept per tile. */
    private const val MAX_ROUTES = 800

    /** Straight two-point segments longer than this are chords, not track. */
    private const val MAX_CHORD_M = 1000.0

    private val client = Http.client.newBuilder()
        .callTimeout(60, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    /**
     * Loads one tile; [zoom] is the detail level asked of the server. Buses are dropped when
     * [includeBus] is false so zoomed-out views stay readable while metro and trams still show.
     */
    suspend fun loadTile(area: Bounds, zoom: Int, includeBus: Boolean, maxRoutes: Int = MAX_ROUTES): TileData {
        val url = URL.toHttpUrl().newBuilder()
            .addQueryParameter("zoom", zoom.toString())
            .addQueryParameter("min", String.format(Locale.US, "%.6f,%.6f", area.south, area.west))
            .addQueryParameter("max", String.format(Locale.US, "%.6f,%.6f", area.north, area.east))
            .build()
        return withContext(Dispatchers.IO) {
            val call = client.newCall(Request.Builder().url(url).build())
            val handle = coroutineContext[Job]?.invokeOnCompletion { if (it != null) call.cancel() }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw HttpException(response.code, "HTTP ${response.code}", response.header("Retry-After")?.trim()?.toLongOrNull())
                    }
                    val body = response.body ?: throw HttpException(response.code, "empty response")
                    parse(body.charStream(), zoom.toDouble(), includeBus, maxRoutes)
                }
            } finally {
                handle?.dispose()
            }
        }
    }

    // ---------- Compact intermediate model ----------

    private class RawRoute(
        val mode: String,
        val ref: String,
        val name: String,
        val color: Int?,
        val text: Int?,
        /** Triples of (fromStop, toStop, polyline). */
        val segments: IntArray,
        /** "NONE" means the server has no real shape and joins stops with straight lines. */
        val pathSource: String,
        val routeIdx: Int,
    )

    private class Raw {
        val routes = ArrayList<RawRoute>()
        /** Decoded, simplified polylines as [lat0, lon0, …]; null when dropped. */
        val polylines = ArrayList<FloatArray?>()
        val stops = ArrayList<StopRef?>()
        var zoomFiltered = false
    }

    fun parse(reader: Reader, zoom: Double, includeBus: Boolean = true, maxRoutes: Int = MAX_ROUTES): TileData {
        val raw = Raw()
        val tolerance = ((360.0 / (256.0 * 2.0.pow(zoom))) / 3.0).toFloat()
        JsonReader(reader).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "routes" -> readRoutes(r, raw)
                    "polylines" -> readPolylines(r, raw, tolerance)
                    "stops" -> readStops(r, raw)
                    "zoomFiltered" -> raw.zoomFiltered = r.nextBooleanOrFalse()
                    else -> r.skipValue()
                }
            }
            r.endObject()
        }
        return build(raw, includeBus, maxRoutes)
    }

    private fun readRoutes(r: JsonReader, raw: Raw) {
        r.beginArray()
        while (r.hasNext()) {
            var mode = ""
            var ref = ""
            var name = ""
            var color: Int? = null
            var text: Int? = null
            var segments = IntArray(0)
            var pathSource = ""
            var routeIdx = -1
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "mode" -> mode = r.nextStringOrEmpty()
                    "pathSource" -> pathSource = r.nextStringOrEmpty()
                    "routeIdx" -> routeIdx = if (r.peek() == JsonToken.NUMBER) r.nextInt() else { r.skipValue(); -1 }
                    "transitRoutes" -> {
                        r.beginArray()
                        while (r.hasNext()) {
                            r.beginObject()
                            while (r.hasNext()) {
                                when (r.nextName()) {
                                    "shortName" -> r.nextStringOrEmpty().trim().let { if (ref.isEmpty()) ref = it }
                                    "longName" -> r.nextStringOrEmpty().trim().let { if (name.isEmpty()) name = it }
                                    "color" -> parseHex(r.nextStringOrEmpty()).let { if (color == null) color = it }
                                    "textColor" -> parseHex(r.nextStringOrEmpty()).let { if (text == null) text = it }
                                    else -> r.skipValue()
                                }
                            }
                            r.endObject()
                        }
                        r.endArray()
                    }
                    "segments" -> {
                        var buf = IntArray(48)
                        var n = 0
                        r.beginArray()
                        while (r.hasNext()) {
                            var from = -1; var to = -1; var poly = -1
                            r.beginObject()
                            while (r.hasNext()) {
                                when (r.nextName()) {
                                    "from" -> from = r.nextInt()
                                    "to" -> to = r.nextInt()
                                    "polyline" -> poly = r.nextInt()
                                    else -> r.skipValue()
                                }
                            }
                            r.endObject()
                            if (n + 3 > buf.size) buf = buf.copyOf(buf.size * 2)
                            buf[n++] = from; buf[n++] = to; buf[n++] = poly
                        }
                        r.endArray()
                        segments = buf.copyOf(n)
                    }
                    else -> r.skipValue()
                }
            }
            r.endObject()
            raw.routes += RawRoute(mode, ref, name, color, text, segments, pathSource, routeIdx)
        }
        r.endArray()
    }

    private fun readPolylines(r: JsonReader, raw: Raw, tolerance: Float) {
        r.beginArray()
        while (r.hasNext()) {
            var points: String? = null
            var precision = 6
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() == "polyline" && r.peek() == JsonToken.BEGIN_OBJECT) {
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "points" -> points = r.nextStringOrEmpty()
                            "precision" -> precision = r.nextInt()
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                } else {
                    r.skipValue()
                }
            }
            r.endObject()
            raw.polylines += points?.takeIf { it.isNotEmpty() }?.let {
                runCatching {
                    val pts = decodeFloats(it, precision)
                    // A long two-point segment is a stop-to-stop chord (no real shape): drop it.
                    if (pts.size == 4 && metres(pts[0], pts[1], pts[2], pts[3]) > MAX_CHORD_M) null
                    else simplify(pts, tolerance)
                }.getOrNull()
            }
        }
        r.endArray()
    }

    private fun readStops(r: JsonReader, raw: Raw) {
        r.beginArray()
        while (r.hasNext()) {
            var lat = Double.NaN; var lon = Double.NaN; var name = ""; var id = ""
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "lat" -> lat = r.nextDouble()
                    "lon" -> lon = r.nextDouble()
                    "name" -> name = r.nextStringOrEmpty()
                    "stopId" -> id = r.nextStringOrEmpty()
                    else -> r.skipValue()
                }
            }
            r.endObject()
            raw.stops += if (lat.isNaN() || lon.isNaN()) null else StopRef(id, name, lat, lon)
        }
        r.endArray()
    }

    private fun classify(mode: String): Pair<TransitMode, String> = when (mode) {
        "TRAM", "CABLE_CAR" -> TransitMode.Tram to "tram"
        "SUBWAY", "METRO" -> TransitMode.Metro to "subway"
        "BUS", "COACH", "ODM" -> TransitMode.Bus to "bus"
        "FERRY" -> TransitMode.Ferry to "ferry"
        "FUNICULAR", "AERIAL_LIFT", "AREAL_LIFT", "OTHER", "AIRPLANE" -> TransitMode.Other to "funicular"
        else -> TransitMode.Train to "train" // RAIL, SUBURBAN, REGIONAL_RAIL, HIGHSPEED_RAIL, …
    }

    private fun build(raw: Raw, includeBus: Boolean, maxRoutes: Int): TileData {
        val ordered = raw.routes
            // Routes without real shapes would be drawn as straight chords between stops.
            .filter { it.pathSource != "NONE" }
            .filter { includeBus || classify(it.mode).first != TransitMode.Bus }
            .sortedByDescending { Palette.rank(classify(it.mode).second, "") }
        val out = ArrayList<TileRoute>()
        for (route in ordered.take(maxRoutes)) {
            val (mode, osmRoute) = classify(route.mode)
            val ref = route.ref.takeIf { it.length <= 10 } ?: ""
            val color = route.color ?: mode.colorArgb
            val text = route.text?.takeIf { it != color }
                ?: if (Palette.luminance(color) > 0.6) 0xFF1B1B1F.toInt() else 0xFFFFFFFF.toInt()
            val key = "${mode.name}|${ref.ifBlank { route.name }}|${Palette.hex(color)}"

            val parts = ArrayList<FloatArray>()
            val ids = ArrayList<Int>()
            val stops = ArrayList<StopRef>()
            val used = HashSet<Int>()
            var s = 90f; var n = -90f; var w = 180f; var e = -180f
            var i = 0
            while (i + 2 < route.segments.size) {
                val from = route.segments[i]; val to = route.segments[i + 1]; val pi = route.segments[i + 2]
                i += 3
                if (stops.isEmpty()) raw.stops.getOrNull(from)?.let { stops += it }
                raw.stops.getOrNull(to)?.let { if (stops.lastOrNull()?.id != it.id) stops += it }
                val pts = raw.polylines.getOrNull(pi) ?: continue
                if (pts.size < 4 || !used.add(pi)) continue
                // A shape far longer than the stop-to-stop distance is a routing glitch (the
                // "fans" of lines that all detour to one wrong point): leave it out.
                val a = raw.stops.getOrNull(from); val b = raw.stops.getOrNull(to)
                if (a != null && b != null && mode != TransitMode.Ferry) {
                    val direct = metres(a.lat.toFloat(), a.lon.toFloat(), b.lat.toFloat(), b.lon.toFloat())
                    val path = pathLength(pts)
                    if (path > 1500 && path > direct * 3 + 600) continue
                }
                for (piece in cutLongEdges(pts, mode, 0.0)) {
                    parts += piece
                    ids += piece.contentHashCode()
                }
                var k = 0
                while (k < pts.size) {
                    val lat = pts[k]; val lon = pts[k + 1]
                    if (lat < s) s = lat; if (lat > n) n = lat; if (lon < w) w = lon; if (lon > e) e = lon
                    k += 2
                }
            }
            if (parts.isEmpty()) continue
            out += TileRoute(
                key = key, mode = mode, osmRoute = osmRoute, ref = ref, name = route.name,
                color = color, text = text, rank = Palette.rank(osmRoute, ""),
                parts = parts, partIds = ids.toIntArray(), stops = stops,
                bounds = Bounds(s.toDouble(), w.toDouble(), n.toDouble(), e.toDouble()),
                routeIdx = if (route.routeIdx >= 0) intArrayOf(route.routeIdx) else IntArray(0),
            )
        }
        return TileData(out, raw.zoomFiltered || raw.routes.size > maxRoutes)
    }

    /** Merges tiles into one network: shapes de-duplicated per line, longest stop list kept. */
    /**
     * Accumulates routes into one tile: routes with the same key become one route, and shapes
     * already present (same content) are not stored twice.
     */
    class TileBuilder {
        private class Acc(val first: TileRoute) {
            val parts = ArrayList<FloatArray>()
            val ids = ArrayList<Int>()
            val seen = HashSet<Int>()
            var stops: List<StopRef> = first.stops
            val routeIdx = LinkedHashSet<Int>()
            var s = first.bounds.south; var w = first.bounds.west; var n = first.bounds.north; var e = first.bounds.east
        }
        private val byKey = LinkedHashMap<String, Acc>()
        private var zoomFiltered = false

        val isEmpty get() = byKey.isEmpty()

        fun add(r: TileRoute) {
            val acc = byKey.getOrPut(r.key) { Acc(r) }
            r.parts.forEachIndexed { k, part ->
                if (acc.seen.add(r.partIds[k])) { acc.parts += part; acc.ids += r.partIds[k] }
            }
            if (r.stops.size > acc.stops.size) acc.stops = r.stops
            r.routeIdx.forEach { acc.routeIdx += it }
            acc.s = minOf(acc.s, r.bounds.south); acc.w = minOf(acc.w, r.bounds.west)
            acc.n = maxOf(acc.n, r.bounds.north); acc.e = maxOf(acc.e, r.bounds.east)
        }

        fun addAll(t: TileData) {
            zoomFiltered = zoomFiltered || t.zoomFiltered
            t.routes.forEach(::add)
        }

        fun build(): TileData = TileData(byKey.values.map { a ->
            val r = a.first
            TileRoute(r.key, r.mode, r.osmRoute, r.ref, r.name, r.color, r.text, r.rank,
                a.parts, a.ids.toIntArray(), a.stops, Bounds(a.s, a.w, a.n, a.e), a.routeIdx.toIntArray())
        }, zoomFiltered)
    }

    /** Joins tiles fetched for parts of one area into a single tile. */
    fun combine(tiles: Collection<TileData>): TileData =
        TileBuilder().apply { tiles.forEach(::addAll) }.build()

    /**
     * The part of [r] inside [area]: each shape is cut into the runs of segments that touch the
     * area, so a long line stored in many tiles is not stored whole in each. Null when nothing is left.
     */
    fun clip(r: TileRoute, area: Bounds): TileRoute? {
        if (!r.bounds.intersects(area)) return null
        if (area.contains(r.bounds)) return r
        val s = area.south.toFloat(); val n = area.north.toFloat(); val w = area.west.toFloat(); val e = area.east.toFloat()
        val parts = ArrayList<FloatArray>()
        for (pts in r.parts) {
            var start = -1
            var k = 0
            while (k + 3 < pts.size) {
                val la0 = pts[k]; val lo0 = pts[k + 1]; val la1 = pts[k + 2]; val lo1 = pts[k + 3]
                val touches = minOf(la0, la1) <= n && maxOf(la0, la1) >= s && minOf(lo0, lo1) <= e && maxOf(lo0, lo1) >= w
                if (touches && start < 0) start = k
                if (!touches && start >= 0) { parts += pts.copyOfRange(start, k + 2); start = -1 }
                k += 2
            }
            if (start >= 0) parts += pts.copyOfRange(start, pts.size)
        }
        if (parts.isEmpty()) return null
        return withParts(r, parts)
    }

    /**
     * [t] simplified for drawing at [zoom]: shapes lose detail finer than a third of a pixel, and
     * lines smaller than about three pixels (invisible at that zoom) are left out. At most
     * [maxRoutes] are kept, most important and largest first.
     */
    fun reduce(t: TileData, zoom: Int, maxRoutes: Int): TileData {
        val px = 360.0 / (256.0 * 2.0.pow(zoom))
        val tolerance = (px / 3.0).toFloat()
        val minExtent = px * 3.0
        val out = ArrayList<Pair<TileRoute, Double>>()
        for (r in t.routes) {
            val extent = maxOf(r.bounds.north - r.bounds.south, r.bounds.east - r.bounds.west)
            if (extent < minExtent) continue
            val parts = r.parts.mapNotNull { p -> simplify(p, tolerance).takeIf { it.size >= 4 } }
            if (parts.isEmpty()) continue
            out += withParts(r, parts) to extent
        }
        val kept = out.sortedWith(compareByDescending<Pair<TileRoute, Double>> { it.first.rank }.thenByDescending { it.second })
            .take(maxRoutes).map { it.first }
        return TileData(kept, t.zoomFiltered || kept.size < out.size)
    }

    /**
     * Splits a shape wherever one straight edge is implausibly long for its mode: such edges
     * are stop-to-stop jumps from missing or broken shapes, not track. [toleranceM] is how much
     * the shape was simplified (simplifying legitimately makes edges longer).
     */
    fun cutLongEdges(pts: FloatArray, mode: TransitMode, toleranceM: Double): List<FloatArray> {
        val base = when (mode) {
            TransitMode.Bus -> 1_500.0
            TransitMode.Tram -> 1_200.0
            TransitMode.Metro -> 3_000.0
            TransitMode.Other -> 2_500.0
            TransitMode.Train -> 20_000.0
            TransitMode.Ferry -> return listOf(pts)
        }
        val limit = maxOf(base, toleranceM * 25)
        var out: ArrayList<FloatArray>? = null
        var start = 0
        var k = 0
        while (k + 3 < pts.size) {
            if (metres(pts[k], pts[k + 1], pts[k + 2], pts[k + 3]) > limit) {
                if (out == null) out = ArrayList()
                if (k + 2 - start >= 4) out += pts.copyOfRange(start, k + 2)
                start = k + 2
            }
            k += 2
        }
        if (out == null) return listOf(pts)
        if (pts.size - start >= 4) out += pts.copyOfRange(start, pts.size)
        return out
    }

    /** [cutLongEdges] for a whole tile (also cleans data stored by older versions). */
    fun clean(t: TileData, toleranceM: Double): TileData = TileData(t.routes.mapNotNull { r ->
        val parts = r.parts.flatMap { cutLongEdges(it, r.mode, toleranceM) }
        when {
            parts.isEmpty() -> null
            parts.size == r.parts.size -> r
            else -> withParts(r, parts)
        }
    }, t.zoomFiltered)

    private fun pathLength(pts: FloatArray): Double {
        var sum = 0.0
        var k = 0
        while (k + 3 < pts.size) { sum += metres(pts[k], pts[k + 1], pts[k + 2], pts[k + 3]); k += 2 }
        return sum
    }

    private const val DETAILS_URL = "https://api.transitous.org/api/experimental/map/route-details"

    /** The whole of one Transitous route (every stop and shape), whatever the map shows. */
    suspend fun loadRouteDetails(routeIdx: Int): TileData {
        val url = DETAILS_URL.toHttpUrl().newBuilder().addQueryParameter("routeIdx", routeIdx.toString()).build()
        return withContext(Dispatchers.IO) {
            val call = client.newCall(Request.Builder().url(url).build())
            val handle = coroutineContext[Job]?.invokeOnCompletion { if (it != null) call.cancel() }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw HttpException(response.code, "HTTP ${response.code}")
                    parse(response.body!!.charStream(), 15.0, includeBus = true, maxRoutes = Int.MAX_VALUE)
                }
            } finally {
                handle?.dispose()
            }
        }
    }

    /** GeoJSON for one picked line, drawn on its own layer at every zoom. */
    fun lineGeoJson(key: String, routes: List<TileRoute>): String {
        val sb = StringBuilder("""{"type":"FeatureCollection","features":[""")
        var first = true
        for (r in routes) for (pts in r.parts) {
            if (!first) sb.append(',')
            first = false
            sb.append("""{"type":"Feature","geometry":{"type":"LineString","coordinates":[""")
            var k = 0
            while (k < pts.size) {
                if (k > 0) sb.append(',')
                sb.append('[').appendCoord(pts[k + 1]).append(',').appendCoord(pts[k]).append(']')
                k += 2
            }
            sb.append("""]},"properties":{"key":""").append(quote(key))
                .append(""","mode":"${r.mode.name}","color":"${Palette.hex(r.color)}"}}""")
        }
        return sb.append("]}").toString()
    }

    private fun withParts(r: TileRoute, parts: List<FloatArray>): TileRoute {
        var s = 90f; var n = -90f; var w = 180f; var e = -180f
        for (p in parts) {
            var k = 0
            while (k < p.size) {
                val lat = p[k]; val lon = p[k + 1]
                if (lat < s) s = lat; if (lat > n) n = lat; if (lon < w) w = lon; if (lon > e) e = lon
                k += 2
            }
        }
        return TileRoute(r.key, r.mode, r.osmRoute, r.ref, r.name, r.color, r.text, r.rank,
            parts, IntArray(parts.size) { parts[it].contentHashCode() }, r.stops,
            Bounds(s.toDouble(), w.toDouble(), n.toDouble(), e.toDouble()), r.routeIdx)
    }

    fun merge(tiles: Collection<TileData>, withStops: Boolean = true, spread: Boolean = false): TransitData {
        class Acc(val first: TileRoute) {
            val parts = ArrayList<FloatArray>()
            val ids = HashSet<Int>()
            var stops: List<StopRef> = first.stops
            var s = first.bounds.south; var w = first.bounds.west; var n = first.bounds.north; var e = first.bounds.east
        }
        val byKey = LinkedHashMap<String, Acc>()
        var zoomFiltered = false
        for (tile in tiles) {
            zoomFiltered = zoomFiltered || tile.zoomFiltered
            for (r in tile.routes) {
                val acc = byKey.getOrPut(r.key) { Acc(r) }
                r.parts.forEachIndexed { k, part -> if (acc.ids.add(r.partIds[k])) acc.parts += part }
                if (r.stops.size > acc.stops.size) acc.stops = r.stops
                acc.s = minOf(acc.s, r.bounds.south); acc.w = minOf(acc.w, r.bounds.west)
                acc.n = maxOf(acc.n, r.bounds.north); acc.e = maxOf(acc.e, r.bounds.east)
            }
        }

        // Lines sharing a street are drawn side by side: each run of a shape gets a slot offset.
        val runs: Map<String, Map<Float, List<FloatArray>>>? =
            if (spread && byKey.values.sumOf { a -> a.parts.sumOf { it.size } } < 1_500_000)
                spreadRuns(byKey.entries.map { (k, a) -> Triple(k, a.first, a.parts as List<FloatArray>) })
            else null

        val lines = StringBuilder(1 shl 16).append("""{"type":"FeatureCollection","features":[""")
        val stopFeatures = LinkedHashMap<String, Pair<Int, String>>() // id -> (rank, json)
        val loaded = ArrayList<LoadedLine>()
        var first = true
        for ((key, acc) in byKey) {
            val r = acc.first
            val colorHex = Palette.hex(r.color)
            val groups: Map<Float, List<FloatArray>> = runs?.get(key) ?: mapOf(0f to acc.parts)
            for ((off, parts) in groups) {
                if (!first) lines.append(',')
                first = false
                lines.append("""{"type":"Feature","geometry":{"type":"MultiLineString","coordinates":[""")
                parts.forEachIndexed { pi, pts ->
                    if (pi > 0) lines.append(',')
                    lines.append('[')
                    var k = 0
                    while (k < pts.size) {
                        if (k > 0) lines.append(',')
                        lines.append('[').appendCoord(pts[k + 1]).append(',').appendCoord(pts[k]).append(']')
                        k += 2
                    }
                    lines.append(']')
                }
                lines.append("""]},"properties":{"key":""").append(quote(key))
                    .append(""","route":"${r.osmRoute}","mode":"${r.mode.name}","ref":""").append(quote(r.ref))
                    .append(""","color":"$colorHex","text":"${Palette.hex(r.text)}","rank":${r.rank},"off":$off""")
                if (r.ref.isNotBlank()) lines.append(""","pill":"${pillId(key)}"""")
                lines.append("}}")
            }

            if (withStops) for (st in acc.stops) {
                val id = st.id.ifBlank { "${st.lat},${st.lon}" }
                val prev = stopFeatures[id]
                if (prev == null || prev.first < r.rank) {
                    stopFeatures[id] = r.rank to buildString {
                        append("""{"type":"Feature","geometry":{"type":"Point","coordinates":[""")
                        appendCoord(st.lon.toFloat()).append(',').appendCoord(st.lat.toFloat())
                        append("""]},"properties":{"color":"$colorHex","rank":${r.rank},"key":""").append(quote(key))
                        append(""","mode":"${r.mode.name}","name":""").append(quote(st.name)).append("}}")
                    }
                }
            }
            if (r.ref.isNotBlank()) {
                loaded += LoadedLine(
                    LineInfo(key, r.ref, r.name, r.osmRoute, r.mode, r.color, r.text),
                    Bounds(acc.s, acc.w, acc.n, acc.e),
                    acc.stops,
                )
            }
        }
        lines.append("]}")
        val stops = StringBuilder("""{"type":"FeatureCollection","features":[""")
        stopFeatures.values.forEachIndexed { i, (_, json) -> if (i > 0) stops.append(','); stops.append(json) }
        stops.append("]}")
        return TransitData(lines.toString(), stops.toString(), loaded, zoomFiltered)
    }

    /** Map image name for a line's number badge. */
    fun pillId(key: String) = "pill-" + Integer.toHexString(key.hashCode())

    /** Lines passing through [view], grouped by mode then sorted by number. */
    fun legend(routes: List<LoadedLine>, modes: Set<TransitMode>, view: Bounds): List<LineInfo> =
        routes.asSequence()
            .filter { it.info.mode in modes && it.bounds.intersects(view) }
            .map { it.info }
            .sortedWith(compareBy<LineInfo> { it.mode?.ordinal ?: 99 }.thenComparator { a, b -> Palette.naturalCompare(a.ref, b.ref) })
            .toList()

    private fun metres(lat1: Float, lon1: Float, lat2: Float, lon2: Float): Double {
        val dLat = Math.toRadians((lat2 - lat1).toDouble())
        val dLon = Math.toRadians((lon2 - lon1).toDouble()) * kotlin.math.cos(Math.toRadians(lat1.toDouble()))
        return 6_371_000.0 * kotlin.math.sqrt(dLat * dLat + dLon * dLon)
    }

    // ---------- Side-by-side lines ----------

    /**
     * For every line, splits its shapes into runs that each carry a sideways slot: where n lines
     * follow the same street in the same direction band, they get slots -(n-1)/2 … (n-1)/2 in
     * a fixed order (trains, metro, trams, buses; then by number), so they sit next to each
     * other instead of on top of each other. Slots are flipped for shapes drawn the other way
     * round, so each line keeps its side whichever way its shape runs.
     */
    private fun spreadRuns(lines: List<Triple<String, TileRoute, List<FloatArray>>>): Map<String, Map<Float, List<FloatArray>>> {
        val order = lines.sortedWith(compareBy<Triple<String, TileRoute, List<FloatArray>>> { it.second.mode.ordinal }
            .thenComparator { a, b -> Palette.naturalCompare(a.second.ref, b.second.ref) })
        val gi = HashMap<String, Int>(order.size * 2).apply { order.forEachIndexed { i, t -> put(t.first, i) } }
        val cell = 0.00025 // about 28 m
        val buckets = 12
        val grid = HashMap<Long, IntArray>(1 shl 16) // key -> small set of line indexes (-1 = empty)

        fun keyOf(lat: Double, lon: Double, dir: Int): Long {
            val cx = Math.floor(lon * Math.cos(Math.toRadians(lat)) / cell).toLong() and 0x3FFFFF
            val cy = Math.floor(lat / cell).toLong() and 0x3FFFFF
            return (cx shl 26) or (cy shl 4) or dir.toLong()
        }
        fun add(key: Long, g: Int) {
            val cur = grid[key]
            if (cur == null) { grid[key] = intArrayOf(g, -1, -1, -1); return }
            for (i in cur.indices) { if (cur[i] == g) return; if (cur[i] == -1) { cur[i] = g; return } }
            val grown = cur.copyOf(cur.size * 2); grown.fill(-1, cur.size); grown[cur.size] = g; grid[key] = grown
        }
        fun angleOf(la0: Float, lo0: Float, la1: Float, lo1: Float): Double {
            val dx = (lo1 - lo0) * Math.cos(Math.toRadians(la0.toDouble())); val dy = (la1 - la0).toDouble()
            var a = Math.atan2(dy, dx); if (a < 0) a += Math.PI; if (a >= Math.PI) a -= Math.PI
            return a / Math.PI * buckets // 0 until buckets
        }

        // Pass 1: mark the cells each line passes through, per direction band.
        for ((k, _, parts) in lines) {
            val g = gi[k] ?: continue
            for (pts in parts) {
                var i = 0
                while (i + 3 < pts.size) {
                    val la0 = pts[i]; val lo0 = pts[i + 1]; val la1 = pts[i + 2]; val lo1 = pts[i + 3]
                    val a = angleOf(la0, lo0, la1, lo1)
                    val b = a.toInt() % buckets
                    val nb = (if (a - b >= 0.5) b + 1 else b - 1 + buckets) % buckets
                    val dLat = (la1 - la0).toDouble(); val dLon = (lo1 - lo0).toDouble()
                    val len = Math.hypot(dLat, dLon * Math.cos(Math.toRadians(la0.toDouble())))
                    val steps = (len / (cell / 2)).toInt().coerceIn(1, 400)
                    for (sIdx in 0..steps) {
                        val f = sIdx.toDouble() / steps
                        val la = la0 + dLat * f; val lo = lo0 + dLon * f
                        add(keyOf(la, lo, b), g); add(keyOf(la, lo, nb), g)
                    }
                    i += 2
                }
            }
        }

        // Pass 2: each segment takes its slot at its midpoint; equal slots join into runs.
        val out = HashMap<String, Map<Float, List<FloatArray>>>(lines.size * 2)
        for ((k, _, parts) in lines) {
            val g = gi[k] ?: continue
            val byOff = HashMap<Float, MutableList<FloatArray>>()
            for (pts in parts) {
                var runStart = 0
                var runOff = Float.NaN
                var i = 0
                while (i + 3 < pts.size) {
                    val la0 = pts[i]; val lo0 = pts[i + 1]; val la1 = pts[i + 2]; val lo1 = pts[i + 3]
                    val b = angleOf(la0, lo0, la1, lo1).toInt() % buckets
                    val set = grid[keyOf((la0 + la1) / 2.0, (lo0 + lo1) / 2.0, b)]
                    var n = 0; var slot = 0
                    if (set != null) for (x in set) { if (x == -1) break; n++; if (x < g) slot++ }
                    var off = if (n <= 1) 0f else slot - (n - 1) / 2f
                    val reversed = lo1 < lo0 || (lo1 == lo0 && la1 < la0)
                    if (reversed) off = -off
                    if (runOff.isNaN()) runOff = off
                    if (off != runOff) {
                        byOff.getOrPut(runOff) { ArrayList() } += pts.copyOfRange(runStart, i + 2)
                        runStart = i
                        runOff = off
                    }
                    i += 2
                }
                if (runOff.isNaN()) runOff = 0f
                byOff.getOrPut(runOff) { ArrayList() } += if (runStart == 0) pts else pts.copyOfRange(runStart, pts.size)
            }
            out[k] = byOff
        }
        return out
    }

    // ---------- Helpers ----------

    /** Decodes a Google polyline into [lat, lon, lat, lon, …]. */
    internal fun decodeFloats(encoded: String, precision: Int): FloatArray {
        val factor = 10.0.pow(precision)
        var out = FloatArray(64)
        var n = 0
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < encoded.length) {
            var result = 0L; var shift = 0; var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            lat += if (result and 1L != 0L) (result shr 1).inv() else result shr 1
            result = 0L; shift = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            lon += if (result and 1L != 0L) (result shr 1).inv() else result shr 1
            if (n + 2 > out.size) out = out.copyOf(out.size * 2)
            out[n++] = (lat / factor).toFloat()
            out[n++] = (lon / factor).toFloat()
        }
        return out.copyOf(n)
    }

    /** Douglas–Peucker simplification on an interleaved [lat, lon, …] array. */
    internal fun simplify(pts: FloatArray, tolerance: Float): FloatArray {
        val count = pts.size / 2
        if (count <= 2) return pts
        val keep = BooleanArray(count)
        keep[0] = true; keep[count - 1] = true
        val stack = ArrayDeque<Long>()
        stack.addLast((0L shl 32) or (count - 1).toLong())
        val tol2 = tolerance * tolerance
        while (stack.isNotEmpty()) {
            val packed = stack.removeLast()
            val a = (packed shr 32).toInt(); val b = (packed and 0xFFFFFFFFL).toInt()
            if (b <= a + 1) continue
            val ax = pts[a * 2 + 1]; val ay = pts[a * 2]
            val bx = pts[b * 2 + 1]; val by = pts[b * 2]
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            var maxD = -1f; var maxI = -1
            for (i in a + 1 until b) {
                val px = pts[i * 2 + 1]; val py = pts[i * 2]
                val d = if (len2 == 0f) {
                    (px - ax) * (px - ax) + (py - ay) * (py - ay)
                } else {
                    val cross = dx * (py - ay) - dy * (px - ax)
                    cross * cross / len2
                }
                if (d > maxD) { maxD = d; maxI = i }
            }
            if (maxD > tol2) {
                keep[maxI] = true
                stack.addLast((a.toLong() shl 32) or maxI.toLong())
                stack.addLast((maxI.toLong() shl 32) or b.toLong())
            }
        }
        val kept = keep.count { it }
        val out = FloatArray(kept * 2)
        var j = 0
        for (i in 0 until count) if (keep[i]) { out[j++] = pts[i * 2]; out[j++] = pts[i * 2 + 1] }
        return out
    }

    /** Appends a coordinate with 5 decimals (~1 m) without String.format. */
    private fun StringBuilder.appendCoord(v: Float): StringBuilder {
        val scaled = (v.toDouble() * 100000.0).roundToLong()
        if (scaled < 0) append('-')
        val a = abs(scaled)
        append(a / 100000).append('.')
        val frac = (a % 100000).toString()
        for (z in frac.length until 5) append('0')
        return append(frac)
    }

    private fun quote(s: String): String = org.json.JSONObject.quote(s)

    private fun JsonReader.nextStringOrEmpty(): String =
        if (peek() == JsonToken.NULL) { nextNull(); "" } else nextString()

    private fun JsonReader.nextBooleanOrFalse(): Boolean =
        if (peek() == JsonToken.BOOLEAN) nextBoolean() else { skipValue(); false }
}
