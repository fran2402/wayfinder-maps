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

/** A line in the loaded area, with the bounds of its geometry (for "lines in view"). */
data class LoadedLine(val info: LineInfo, val bounds: Bounds, val stops: List<String> = emptyList())

/** Public transport routes for an area, as GeoJSON plus the line list. */
data class TransitData(
    val lines: String,
    val stops: String,
    val routes: List<LoadedLine>,
    val area: Bounds,
    val zoom: Double,
    /** True when the server left out minor routes because of the zoom level. */
    val zoomFiltered: Boolean,
)

/**
 * Every public transport route in an area, with GTFS line colours and numbers, from
 * Transitous (MOTIS `/api/experimental/map/routes`). The server thins the network by
 * zoom: long-distance rail when zoomed out, metro, trams and buses as you zoom in.
 *
 * Responses for busy areas are large, so they are parsed as a stream into compact arrays
 * (never a JSON object tree), shapes are simplified to the current zoom, and the GeoJSON
 * is written straight into one StringBuilder.
 */
object TransitRoutes {
    private const val URL = "https://api.transitous.org/api/experimental/map/routes"

    /** Upper bound on drawn routes, to keep memory and rendering in check. */
    private const val MAX_ROUTES = 1500

    private val client = Http.client.newBuilder()
        .callTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun load(area: Bounds, zoom: Double): TransitData {
        val url = URL.toHttpUrl().newBuilder()
            .addQueryParameter("zoom", String.format(Locale.US, "%.1f", zoom))
            .addQueryParameter("min", String.format(Locale.US, "%.6f,%.6f", area.south, area.west))
            .addQueryParameter("max", String.format(Locale.US, "%.6f,%.6f", area.north, area.east))
            .build()
        return withContext(Dispatchers.IO) {
            val call = client.newCall(Request.Builder().url(url).build())
            val handle = coroutineContext[Job]?.invokeOnCompletion { if (it != null) call.cancel() }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw HttpException(response.code, "HTTP ${response.code}")
                    val body = response.body ?: throw HttpException(response.code, "empty response")
                    parse(body.charStream(), area, zoom)
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
    )

    private class Raw {
        val routes = ArrayList<RawRoute>()
        /** Decoded polylines as [lat0, lon0, lat1, lon1, …]. */
        val polylines = ArrayList<FloatArray?>()
        /** Stops as [lat, lon] pairs, index-aligned with the response's stop array. */
        var stopCoords = FloatArray(0)
        var stopCount = 0
        var stopNames: Array<String?> = emptyArray()
        var zoomFiltered = false
    }

    fun parse(reader: Reader, area: Bounds, zoom: Double): TransitData {
        val raw = Raw()
        // Simplify to roughly a third of a screen pixel at the requested zoom.
        val tolerance = (360.0 / (256.0 * 2.0.pow(zoom))) / 3.0
        JsonReader(reader).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "routes" -> readRoutes(r, raw)
                    "polylines" -> readPolylines(r, raw, tolerance.toFloat())
                    "stops" -> readStops(r, raw)
                    "zoomFiltered" -> raw.zoomFiltered = r.nextBooleanOrFalse()
                    else -> r.skipValue()
                }
            }
            r.endObject()
        }
        return build(raw, area, zoom)
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
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "mode" -> mode = r.nextStringOrEmpty()
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
            raw.routes += RawRoute(mode, ref, name, color, text, segments)
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
                runCatching { simplify(decodeFloats(it, precision), tolerance) }.getOrNull()
            }
        }
        r.endArray()
    }

    private fun readStops(r: JsonReader, raw: Raw) {
        var coords = FloatArray(512)
        val names = ArrayList<String?>()
        var n = 0
        r.beginArray()
        while (r.hasNext()) {
            var lat = Float.NaN; var lon = Float.NaN; var name: String? = null
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "lat" -> lat = r.nextDouble().toFloat()
                    "lon" -> lon = r.nextDouble().toFloat()
                    "name" -> name = r.nextStringOrEmpty().takeIf { it.isNotBlank() }
                    else -> r.skipValue()
                }
            }
            r.endObject()
            if (n + 2 > coords.size) coords = coords.copyOf(coords.size * 2)
            coords[n++] = lat; coords[n++] = lon
            names += name
        }
        r.endArray()
        raw.stopCoords = coords.copyOf(n)
        raw.stopCount = n / 2
        raw.stopNames = names.toTypedArray()
    }

    // ---------- Output ----------

    private fun classify(mode: String): Pair<TransitMode, String> = when (mode) {
        "TRAM", "CABLE_CAR" -> TransitMode.Tram to "tram"
        "SUBWAY", "METRO" -> TransitMode.Metro to "subway"
        "BUS", "COACH", "ODM" -> TransitMode.Bus to "bus"
        "FERRY" -> TransitMode.Ferry to "ferry"
        "FUNICULAR", "AERIAL_LIFT", "AREAL_LIFT", "OTHER", "AIRPLANE" -> TransitMode.Other to "funicular"
        else -> TransitMode.Train to "train" // RAIL, SUBURBAN, REGIONAL_RAIL, HIGHSPEED_RAIL, …
    }

    private fun build(raw: Raw, area: Bounds, zoom: Double): TransitData {
        val lines = StringBuilder(1 shl 16).append("""{"type":"FeatureCollection","features":[""")
        var firstFeature = true
        val loaded = LinkedHashMap<String, LoadedLine>()
        // Best (highest-rank) route per stop: rank, colour, key, mode.
        val stopRank = IntArray(raw.stopCount) { -1 }
        val stopInfo = arrayOfNulls<String>(raw.stopCount)

        // Most important routes first, so the cap drops buses before trains.
        val ordered = raw.routes.sortedByDescending { Palette.rank(classify(it.mode).second, "") }
        for (route in ordered.take(MAX_ROUTES)) {
            val (mode, osmRoute) = classify(route.mode)
            // Only real line numbers become badges; long descriptive names stay off the map.
            val ref = route.ref.takeIf { it.length <= 10 } ?: ""
            val color = route.color ?: mode.colorArgb
            val text = route.text?.takeIf { it != color }
                ?: if (Palette.luminance(color) > 0.6) 0xFF1B1B1F.toInt() else 0xFFFFFFFF.toInt()
            val rank = Palette.rank(osmRoute, "")
            val key = "${mode.name}|${ref.ifBlank { route.name }}|${Palette.hex(color)}"
            val colorHex = Palette.hex(color)

            var s = 90f; var n = -90f; var w = 180f; var e = -180f
            val geometry = StringBuilder()
            val used = HashSet<Int>()
            var i = 0
            while (i + 2 < route.segments.size) {
                val from = route.segments[i]; val to = route.segments[i + 1]; val pi = route.segments[i + 2]
                i += 3
                for (stop in intArrayOf(from, to)) {
                    if (stop in 0 until raw.stopCount && stopRank[stop] < rank) {
                        stopRank[stop] = rank
                        stopInfo[stop] = """"color":"$colorHex","rank":$rank,"key":${quote(key)},"mode":"${mode.name}""""
                    }
                }
                val pts = raw.polylines.getOrNull(pi) ?: continue
                if (pts.size < 4 || !used.add(pi)) continue
                if (geometry.isNotEmpty()) geometry.append(',')
                geometry.append('[')
                var k = 0
                while (k < pts.size) {
                    val lat = pts[k]; val lon = pts[k + 1]
                    if (k > 0) geometry.append(',')
                    geometry.append('[').appendCoord(lon).append(',').appendCoord(lat).append(']')
                    if (lat < s) s = lat; if (lat > n) n = lat; if (lon < w) w = lon; if (lon > e) e = lon
                    k += 2
                }
                geometry.append(']')
            }
            if (geometry.isEmpty()) continue

            if (!firstFeature) lines.append(',')
            firstFeature = false
            lines.append("""{"type":"Feature","geometry":{"type":"MultiLineString","coordinates":[""")
                .append(geometry)
                .append("""]},"properties":{"key":""").append(quote(key))
                .append(""","route":"$osmRoute","mode":"${mode.name}","ref":""").append(quote(ref))
                .append(""","color":"$colorHex","text":"${Palette.hex(text)}","rank":$rank""")
            if (ref.isNotBlank()) lines.append(""","pill":"${pillId(key)}"""")
            lines.append("}}")

            if (ref.isNotBlank()) {
                val b = Bounds(s.toDouble(), w.toDouble(), n.toDouble(), e.toDouble())
                val stopNames = orderedStops(route, raw)
                val prev = loaded[key]
                loaded[key] = if (prev == null) {
                    LoadedLine(LineInfo(key, ref, route.name, osmRoute, mode, color, text), b, stopNames)
                } else {
                    val p = prev.bounds
                    prev.copy(
                        bounds = Bounds(minOf(p.south, b.south), minOf(p.west, b.west), maxOf(p.north, b.north), maxOf(p.east, b.east)),
                        // Variants of one line: keep the longest stop sequence.
                        stops = if (stopNames.size > prev.stops.size) stopNames else prev.stops,
                    )
                }
            }
        }
        lines.append("]}")

        val stops = StringBuilder(1 shl 12).append("""{"type":"FeatureCollection","features":[""")
        var firstStop = true
        for (idx in 0 until raw.stopCount) {
            val info = stopInfo[idx] ?: continue
            val lat = raw.stopCoords[idx * 2]; val lon = raw.stopCoords[idx * 2 + 1]
            if (lat.isNaN() || lon.isNaN()) continue
            if (!firstStop) stops.append(',')
            firstStop = false
            stops.append("""{"type":"Feature","geometry":{"type":"Point","coordinates":[""")
                .appendCoord(lon).append(',').appendCoord(lat)
                .append("]},\"properties\":{").append(info)
            raw.stopNames.getOrNull(idx)?.let { stops.append(",\"name\":").append(quote(it)) }
            stops.append("}}")
        }
        stops.append("]}")

        return TransitData(
            lines = lines.toString(),
            stops = stops.toString(),
            routes = loaded.values.toList(),
            area = area,
            zoom = zoom,
            zoomFiltered = raw.zoomFiltered || raw.routes.size > MAX_ROUTES,
        )
    }

    /** Stop names in travel order, from the route's consecutive segments. */
    private fun orderedStops(route: RawRoute, raw: Raw): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i + 2 < route.segments.size) {
            val from = route.segments[i]; val to = route.segments[i + 1]
            if (out.isEmpty()) raw.stopNames.getOrNull(from)?.let { out += it }
            raw.stopNames.getOrNull(to)?.let { if (out.lastOrNull() != it) out += it }
            i += 3
        }
        return out
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
