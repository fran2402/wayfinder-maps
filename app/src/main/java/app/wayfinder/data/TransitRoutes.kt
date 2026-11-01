package app.wayfinder.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A line in the loaded area, with the bounds of its geometry (for "lines in view"). */
data class LoadedLine(val info: LineInfo, val bounds: Bounds)

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
 */
object TransitRoutes {
    private const val URL = "https://api.transitous.org/api/experimental/map/routes"

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
        val body = get(url.toString())
        return withContext(Dispatchers.Default) { parse(JSONObject(body), area, zoom) }
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(Request.Builder().url(url).build())
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val text = it.body?.string().orEmpty()
                        if (it.isSuccessful) cont.resume(text)
                        else cont.resumeWithException(HttpException(it.code, "HTTP ${it.code}"))
                    }
                }
            })
        }
    }

    /** Maps a MOTIS mode to the app's categories and an OSM-style route name used for draw order. */
    private fun classify(mode: String): Pair<TransitMode, String> = when (mode) {
        "TRAM", "CABLE_CAR" -> TransitMode.Tram to "tram"
        "SUBWAY", "METRO" -> TransitMode.Metro to "subway"
        "BUS", "COACH", "ODM" -> TransitMode.Bus to "bus"
        "FERRY" -> TransitMode.Ferry to "ferry"
        "FUNICULAR", "AERIAL_LIFT", "AREAL_LIFT", "OTHER", "AIRPLANE" -> TransitMode.Other to "funicular"
        else -> TransitMode.Train to "train" // RAIL, SUBURBAN, REGIONAL_RAIL, HIGHSPEED_RAIL, …
    }

    fun parse(json: JSONObject, area: Bounds, zoom: Double): TransitData {
        val routes = json.optJSONArray("routes") ?: JSONArray()
        val polylinesJson = json.optJSONArray("polylines") ?: JSONArray()
        val stopsJson = json.optJSONArray("stops") ?: JSONArray()

        // Decode every shared polyline once.
        val polylines = ArrayList<List<LatLon>>(polylinesJson.length())
        for (i in 0 until polylinesJson.length()) {
            val p = polylinesJson.getJSONObject(i).optJSONObject("polyline")
            polylines += p?.optString("points")?.takeIf { it.isNotEmpty() }
                ?.let { runCatching { decodePolyline(it, p.optInt("precision", 6)) }.getOrNull() }
                ?: emptyList()
        }

        val lineFeatures = JSONArray()
        val stopRank = HashMap<Int, Pair<Int, JSONObject>>()
        val loaded = ArrayList<LoadedLine>()
        val seenKeys = HashSet<String>()

        for (i in 0 until routes.length()) {
            val r = routes.getJSONObject(i)
            val (mode, route) = classify(r.optString("mode"))
            val transit = r.optJSONArray("transitRoutes") ?: JSONArray()
            var ref = ""
            var name = ""
            var color: Int? = null
            var text: Int? = null
            for (t in 0 until transit.length()) {
                val tr = transit.getJSONObject(t)
                if (ref.isBlank()) ref = tr.optString("shortName").trim()
                if (name.isBlank()) name = tr.optString("longName").trim()
                if (color == null) color = parseHex(tr.optString("color"))
                if (text == null) text = parseHex(tr.optString("textColor"))
            }
            if (ref.isBlank()) ref = name.take(18)
            val lineColor = color ?: mode.colorArgb
            val lineText = text?.takeIf { it != lineColor }
                ?: if (Palette.luminance(lineColor) > 0.6) 0xFF1B1B1F.toInt() else 0xFFFFFFFF.toInt()
            val rank = Palette.rank(route, "")
            val key = "${mode.name}|${ref.ifBlank { name }}|${Palette.hex(lineColor)}"

            val parts = JSONArray()
            var s = 90.0; var n = -90.0; var w = 180.0; var e = -180.0
            val segments = r.optJSONArray("segments") ?: JSONArray()
            val usedPolylines = HashSet<Int>()
            for (k in 0 until segments.length()) {
                val seg = segments.getJSONObject(k)
                val pi = seg.optInt("polyline", -1)
                for (stopIdx in intArrayOf(seg.optInt("from", -1), seg.optInt("to", -1))) {
                    if (stopIdx < 0) continue
                    val prev = stopRank[stopIdx]
                    if (prev == null || prev.first < rank) {
                        stopRank[stopIdx] = rank to JSONObject().put("color", Palette.hex(lineColor))
                            .put("rank", rank).put("key", key).put("mode", mode.name)
                    }
                }
                if (pi < 0 || pi >= polylines.size || !usedPolylines.add(pi)) continue
                val pts = polylines[pi]
                if (pts.size < 2) continue
                val coords = JSONArray()
                for (p in pts) {
                    coords.put(JSONArray().put(p.lon).put(p.lat))
                    s = minOf(s, p.lat); n = maxOf(n, p.lat); w = minOf(w, p.lon); e = maxOf(e, p.lon)
                }
                parts.put(coords)
            }
            if (parts.length() == 0) continue
            lineFeatures.put(
                JSONObject().put("type", "Feature")
                    .put("geometry", JSONObject().put("type", "MultiLineString").put("coordinates", parts))
                    .put(
                        "properties", JSONObject()
                            .put("key", key).put("route", route).put("mode", mode.name)
                            .put("ref", ref).put("color", Palette.hex(lineColor))
                            .put("text", Palette.hex(lineText)).put("rank", rank)
                    )
            )
            if (ref.isNotBlank() && seenKeys.add(key)) {
                loaded += LoadedLine(LineInfo(key, ref, name, route, mode, lineColor, lineText), Bounds(s, w, n, e))
            } else if (ref.isNotBlank()) {
                // Same line in several route variants: widen its bounds.
                val idx = loaded.indexOfFirst { it.info.key == key }
                if (idx >= 0) {
                    val b = loaded[idx].bounds
                    loaded[idx] = loaded[idx].copy(bounds = Bounds(minOf(b.south, s), minOf(b.west, w), maxOf(b.north, n), maxOf(b.east, e)))
                }
            }
        }

        val stopFeatures = JSONArray()
        for ((idx, entry) in stopRank) {
            val st = stopsJson.optJSONObject(idx) ?: continue
            if (!st.has("lat")) continue
            stopFeatures.put(
                JSONObject().put("type", "Feature")
                    .put("geometry", JSONObject().put("type", "Point")
                        .put("coordinates", JSONArray().put(st.getDouble("lon")).put(st.getDouble("lat"))))
                    .put("properties", entry.second.put("name", st.optString("name")))
            )
        }

        return TransitData(
            lines = JSONObject().put("type", "FeatureCollection").put("features", lineFeatures).toString(),
            stops = JSONObject().put("type", "FeatureCollection").put("features", stopFeatures).toString(),
            routes = loaded,
            area = area,
            zoom = zoom,
            zoomFiltered = json.optBoolean("zoomFiltered", false),
        )
    }

    /** Lines passing through [view], grouped by mode then sorted by number. */
    fun legend(routes: List<LoadedLine>, modes: Set<TransitMode>, view: Bounds): List<LineInfo> =
        routes.asSequence()
            .filter { it.info.mode in modes && it.bounds.intersects(view) }
            .map { it.info }
            .sortedWith(compareBy<LineInfo> { it.mode?.ordinal ?: 99 }.thenComparator { a, b -> Palette.naturalCompare(a.ref, b.ref) })
            .toList()
}
