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
    suspend fun loadTile(area: Bounds, zoom: Int, includeBus: Boolean): TileData {
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
                    parse(body.charStream(), zoom.toDouble(), includeBus)
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
    )

    private class Raw {
        val routes = ArrayList<RawRoute>()
        /** Decoded, simplified polylines as [lat0, lon0, …]; null when dropped. */
        val polylines = ArrayList<FloatArray?>()
        val stops = ArrayList<StopRef?>()
        var zoomFiltered = false
    }

    fun parse(reader: Reader, zoom: Double, includeBus: Boolean = true): TileData {
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
        return build(raw, includeBus)
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
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "mode" -> mode = r.nextStringOrEmpty()
                    "pathSource" -> pathSource = r.nextStringOrEmpty()
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
            raw.routes += RawRoute(mode, ref, name, color, text, segments, pathSource)
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

    private fun build(raw: Raw, includeBus: Boolean): TileData {
        val ordered = raw.routes
            // Routes without real shapes would be drawn as straight chords between stops.
            .filter { it.pathSource != "NONE" }
            .filter { includeBus || classify(it.mode).first != TransitMode.Bus }
            .sortedByDescending { Palette.rank(classify(it.mode).second, "") }
        val out = ArrayList<TileRoute>()
        for (route in ordered.take(MAX_ROUTES)) {
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
                parts += pts
                ids += pts.contentHashCode()
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
            )
        }
        return TileData(out, raw.zoomFiltered || raw.routes.size > MAX_ROUTES)
    }

    /** Merges tiles into one network: shapes de-duplicated per line, longest stop list kept. */
    /**
     * Joins tiles fetched for parts of one area into a single tile: routes with the same key
     * become one route with their shapes de-duplicated, so nothing is stored twice.
     */
    fun combine(tiles: Collection<TileData>): TileData {
        class Acc(val first: TileRoute) {
            val parts = ArrayList<FloatArray>()
            val ids = ArrayList<Int>()
            val seen = HashSet<Int>()
            var stops: List<StopRef> = first.stops
            var s = first.bounds.south; var w = first.bounds.west; var n = first.bounds.north; var e = first.bounds.east
        }
        val byKey = LinkedHashMap<String, Acc>()
        var zoomFiltered = false
        for (tile in tiles) {
            zoomFiltered = zoomFiltered || tile.zoomFiltered
            for (r in tile.routes) {
                val acc = byKey.getOrPut(r.key) { Acc(r) }
                r.parts.forEachIndexed { k, part ->
                    if (acc.seen.add(r.partIds[k])) { acc.parts += part; acc.ids += r.partIds[k] }
                }
                if (r.stops.size > acc.stops.size) acc.stops = r.stops
                acc.s = minOf(acc.s, r.bounds.south); acc.w = minOf(acc.w, r.bounds.west)
                acc.n = maxOf(acc.n, r.bounds.north); acc.e = maxOf(acc.e, r.bounds.east)
            }
        }
        val routes = byKey.values.map { a ->
            val r = a.first
            TileRoute(r.key, r.mode, r.osmRoute, r.ref, r.name, r.color, r.text, r.rank,
                a.parts, a.ids.toIntArray(), a.stops, Bounds(a.s, a.w, a.n, a.e))
        }
        return TileData(routes, zoomFiltered)
    }

    fun merge(tiles: Collection<TileData>): TransitData {
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

        val lines = StringBuilder(1 shl 16).append("""{"type":"FeatureCollection","features":[""")
        val stopFeatures = LinkedHashMap<String, Pair<Int, String>>() // id -> (rank, json)
        val loaded = ArrayList<LoadedLine>()
        var first = true
        for ((key, acc) in byKey) {
            val r = acc.first
            val colorHex = Palette.hex(r.color)
            if (!first) lines.append(',')
            first = false
            lines.append("""{"type":"Feature","geometry":{"type":"MultiLineString","coordinates":[""")
            acc.parts.forEachIndexed { pi, pts ->
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
                .append(""","color":"$colorHex","text":"${Palette.hex(r.text)}","rank":${r.rank}""")
            if (r.ref.isNotBlank()) lines.append(""","pill":"${pillId(key)}"""")
            lines.append("}}")

            for (st in acc.stops) {
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
