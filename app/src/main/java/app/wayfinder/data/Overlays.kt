package app.wayfinder.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/** A rectangular area in degrees. */
data class Bounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    fun contains(o: Bounds) = o.south >= south && o.north <= north && o.west >= west && o.east <= east
    fun intersects(o: Bounds) = o.south <= north && o.north >= south && o.west <= east && o.east >= west
    fun expanded(f: Double): Bounds {
        val dLat = (north - south) * f
        val dLon = (east - west) * f
        return Bounds(
            (south - dLat).coerceAtLeast(-85.0), (west - dLon).coerceAtLeast(-180.0),
            (north + dLat).coerceAtMost(85.0), (east + dLon).coerceAtMost(180.0),
        )
    }
}

/** Public-transport categories the user can show or hide. */
enum class TransitMode(val label: String, val colorArgb: Int) {
    Train("Trains", 0xFF2F3A4A.toInt()),
    Metro("Metro", 0xFF1E63E9.toInt()),
    Tram("Trams", 0xFFE8473F.toInt()),
    Bus("Buses", 0xFF7E57C2.toInt()),
    Ferry("Ferries", 0xFF0068A8.toInt()),
    Other("Other", 0xFFB0761E.toInt());

    companion object {
        fun of(route: String): TransitMode? = when (route) {
            "train" -> Train
            "subway", "light_rail", "monorail" -> Metro
            "tram" -> Tram
            "bus", "trolleybus" -> Bus
            "ferry" -> Ferry
            "funicular", "aerialway" -> Other
            else -> null
        }
    }
}

/** One line, as shown in the line-number pills. */
data class LineInfo(
    val key: String,
    val ref: String,
    val name: String,
    val route: String,
    val mode: TransitMode?,
    val color: Int,
    val textColor: Int,
)

/**
 * Tiers of OSM route relations, each fetched in fixed map tiles so results can be cached
 * and requests never overlap. Dense tiers (buses) use smaller tiles and need more zoom.
 */
enum class Tier(val tileZoom: Int, val minViewZoom: Double, val routes: String, val withStops: Boolean) {
    Bike(12, 11.5, "bicycle|mtb", false),
}

data class TileId(val tier: Tier, val x: Int, val y: Int)

object Tiles {
    fun lonToX(lon: Double, z: Int) = floor((lon + 180.0) / 360.0 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)
    fun latToY(lat: Double, z: Int): Int {
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return floor((1.0 - ln(tan(r) + 1.0 / kotlin.math.cos(r)) / PI) / 2.0 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)
    }
    private fun xToLon(x: Int, z: Int) = x.toDouble() / (1 shl z) * 360.0 - 180.0
    private fun yToLat(y: Int, z: Int) = Math.toDegrees(atan(sinh(PI * (1 - 2.0 * y / (1 shl z)))))

    fun bounds(t: TileId): Bounds {
        val z = t.tier.tileZoom
        return Bounds(yToLat(t.y + 1, z), xToLon(t.x, z), yToLat(t.y, z), xToLon(t.x + 1, z))
    }

    /** Tiles covering [area], nearest to its centre first. */
    fun covering(tier: Tier, area: Bounds): List<TileId> {
        val z = tier.tileZoom
        val x0 = lonToX(area.west, z); val x1 = lonToX(area.east, z)
        val y0 = latToY(area.north, z); val y1 = latToY(area.south, z)
        val cx = (x0 + x1) / 2.0; val cy = (y0 + y1) / 2.0
        val out = ArrayList<TileId>()
        for (x in x0..x1) for (y in y0..y1) out += TileId(tier, x, y)
        return out.sortedBy { (it.x - cx) * (it.x - cx) + (it.y - cy) * (it.y - cy) }
    }
}

/** Everything fetched so far for one overlay, merged across tiles. */
class RouteStore {
    private class Rel(
        val route: String,
        val ref: String,
        val name: String,
        val color: Int,
        val textColor: Int,
        val rank: Int,
        val key: String,
        val ways: LongArray,
        val stops: LongArray,
    )

    private val relations = HashMap<Long, Rel>()
    private val ways = HashMap<Long, JSONArray>() // id -> [[lon,lat],…]
    private val wayBounds = HashMap<Long, Bounds>()
    private val nodes = HashMap<Long, JSONArray>()

    val isEmpty get() = relations.isEmpty()

    fun clear() {
        relations.clear(); ways.clear(); wayBounds.clear(); nodes.clear()
    }

    fun merge(json: JSONObject, bike: Boolean) {
        val elements = json.optJSONArray("elements") ?: return
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            val id = el.optLong("id")
            when (el.optString("type")) {
                "relation" -> {
                    val tags = el.optJSONObject("tags") ?: JSONObject()
                    val members = el.optJSONArray("members") ?: JSONArray()
                    val wayIds = ArrayList<Long>()
                    val stopIds = ArrayList<Long>()
                    for (m in 0 until members.length()) {
                        val mem = members.getJSONObject(m)
                        val role = mem.optString("role")
                        when (mem.optString("type")) {
                            "way" -> if (!role.startsWith("platform")) wayIds += mem.optLong("ref")
                            "node" -> if (role.startsWith("stop") || role.startsWith("platform")) stopIds += mem.optLong("ref")
                        }
                    }
                    val route = tags.optString("route")
                    val color = Palette.colorFor(tags, route, bike)
                    val ref = tags.optString("ref").ifBlank { if (bike) tags.optString("name") else "" }.trim().take(24)
                    val name = tags.optString("name")
                    relations[id] = Rel(
                        route = route, ref = ref, name = name, color = color,
                        textColor = if (Palette.luminance(color) > 0.6) 0xFF1B1B1F.toInt() else 0xFFFFFFFF.toInt(),
                        rank = Palette.rank(route, tags.optString("network")),
                        key = "$route|${ref.ifBlank { name }}|${Palette.hex(color)}",
                        ways = wayIds.toLongArray(), stops = stopIds.toLongArray(),
                    )
                }
                "way" -> el.optJSONArray("geometry")?.let { geom ->
                    val coords = JSONArray()
                    var s = 90.0; var n = -90.0; var w = 180.0; var e = -180.0
                    for (g in 0 until geom.length()) {
                        val p = geom.optJSONObject(g) ?: continue
                        val lat = p.getDouble("lat"); val lon = p.getDouble("lon")
                        coords.put(JSONArray().put(lon).put(lat))
                        s = minOf(s, lat); n = maxOf(n, lat); w = minOf(w, lon); e = maxOf(e, lon)
                    }
                    if (coords.length() >= 2) {
                        ways[id] = coords
                        wayBounds[id] = Bounds(s, w, n, e)
                    }
                }
                "node" -> if (el.has("lat")) nodes[id] = JSONArray().put(el.getDouble("lon")).put(el.getDouble("lat"))
            }
        }
    }

    /** Lines and stops as GeoJSON, limited to the given modes (null = everything, for bike routes). */
    fun geoJson(modes: Set<TransitMode>?): Pair<String, String> {
        val lines = JSONArray()
        val stops = HashMap<Long, JSONObject>()
        for (rel in relations.values) {
            if (modes != null && TransitMode.of(rel.route).let { it == null || it !in modes }) continue
            val parts = JSONArray()
            rel.ways.forEach { w -> ways[w]?.let { parts.put(it) } }
            if (parts.length() == 0) continue
            lines.put(feature(
                JSONObject().put("type", "MultiLineString").put("coordinates", parts),
                JSONObject().put("key", rel.key).put("route", rel.route).put("ref", rel.ref)
                    .put("color", Palette.hex(rel.color)).put("text", Palette.hex(rel.textColor)).put("rank", rel.rank),
            ))
            for (sId in rel.stops) {
                val pos = nodes[sId] ?: continue
                val existing = stops[sId]
                if (existing == null || existing.getJSONObject("properties").getInt("rank") < rel.rank) {
                    stops[sId] = feature(
                        JSONObject().put("type", "Point").put("coordinates", pos),
                        JSONObject().put("rank", rel.rank).put("color", Palette.hex(rel.color)).put("key", rel.key),
                    )
                }
            }
        }
        return collection(lines) to collection(JSONArray(stops.values))
    }

    /** Lines that actually pass through [view], grouped by mode then sorted by number. */
    fun legend(modes: Set<TransitMode>, view: Bounds): List<LineInfo> {
        val out = LinkedHashMap<String, LineInfo>()
        for (rel in relations.values) {
            if (rel.ref.isBlank()) continue
            val mode = TransitMode.of(rel.route) ?: continue
            if (mode !in modes || rel.key in out) continue
            if (rel.ways.none { w -> wayBounds[w]?.intersects(view) == true }) continue
            out[rel.key] = LineInfo(rel.key, rel.ref, rel.name, rel.route, mode, rel.color, rel.textColor)
        }
        return out.values.sortedWith(
            compareBy<LineInfo> { it.mode?.ordinal ?: 99 }.thenComparator { a, b -> Palette.naturalCompare(a.ref, b.ref) }
        )
    }

    private fun feature(geometry: JSONObject, props: JSONObject) =
        JSONObject().put("type", "Feature").put("geometry", geometry).put("properties", props)

    private fun collection(features: JSONArray) =
        JSONObject().put("type", "FeatureCollection").put("features", features).toString()
}

/** Fetches route relations from the Overpass API (OpenStreetMap). */
object Overpass {
    private val endpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )

    /** Hard limit per request, so a busy server turns into an error instead of an endless spinner. */
    private val client = Http.client.newBuilder()
        .callTimeout(35, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(tier: Tier, area: Bounds): JSONObject {
        val bbox = String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f", area.south, area.west, area.north, area.east)
        val query = buildString {
            append("[out:json][timeout:25];\n")
            append("relation[\"type\"=\"route\"][\"route\"~\"^(${tier.routes})$\"]($bbox)->.r;\n")
            append(".r out body;\n")
            append("way(r.r)($bbox);\n")
            append("out geom;\n")
            if (tier.withStops) append("node(r.r)($bbox);\nout skel;\n")
        }
        var last: Exception? = null
        for (endpoint in endpoints) {
            try {
                val body = post(endpoint, query)
                return withContext(Dispatchers.Default) { JSONObject(body) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("Overpass unavailable")
    }

    private suspend fun post(url: String, query: String): String = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            val request = Request.Builder().url(url)
                .post(FormBody.Builder().add("data", query).build())
                .build()
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val text = it.body?.string().orEmpty()
                        when {
                            !it.isSuccessful -> cont.resumeWithException(
                                HttpException(it.code, if (it.code == 429 || it.code == 504) "server busy (${it.code})" else "HTTP ${it.code}")
                            )
                            // Overpass reports query timeouts inside a 200 response.
                            text.contains("\"remark\"") && text.contains("runtime error") ->
                                cont.resumeWithException(IOException("server busy"))
                            else -> cont.resume(text)
                        }
                    }
                }
            })
        }
    }
}

object Palette {
    fun rank(route: String, network: String) = when (route) {
        "bus", "trolleybus" -> 1
        "ferry" -> 2
        "tram" -> 3
        "light_rail", "monorail", "funicular" -> 4
        "subway" -> 5
        "train" -> 6
        "bicycle", "mtb" -> when (network) { "icn" -> 4; "ncn" -> 3; "rcn" -> 2; else -> 1 }
        else -> 0
    }

    fun colorFor(tags: JSONObject, route: String, bike: Boolean): Int {
        val raw = tags.optString("colour").ifBlank { tags.optString("color") }.trim()
        parseHex(raw)?.let { return it }
        named[raw.lowercase()]?.let { return it }
        if (bike) {
            return when (tags.optString("network")) {
                "icn" -> 0xFF8E24AA; "ncn" -> 0xFFE53935; "rcn" -> 0xFF1E88E5; "lcn" -> 0xFFF29900
                else -> if (route == "mtb") 0xFF8D6E63 else 0xFF2E9E4F
            }.toInt()
        }
        return TransitMode.of(route)?.colorArgb ?: 0xFF6B7280.toInt()
    }

    private val named = mapOf(
        "red" to 0xFFE53935, "blue" to 0xFF1E63E9, "green" to 0xFF2E9E4F, "yellow" to 0xFFF2C200,
        "orange" to 0xFFF57C00, "purple" to 0xFF8E44AD, "violet" to 0xFF8E44AD, "brown" to 0xFF8D5B3C,
        "pink" to 0xFFE05297, "grey" to 0xFF7A7F87, "gray" to 0xFF7A7F87, "black" to 0xFF2B2B2B,
        "navy" to 0xFF1F3A93, "teal" to 0xFF00897B, "cyan" to 0xFF00ACC1, "lime" to 0xFF7CB342,
        "maroon" to 0xFF8B1E3F, "olive" to 0xFF7D7A1E, "white" to 0xFFE0E0E0, "silver" to 0xFFA0A4AA,
    ).mapValues { it.value.toInt() }

    fun luminance(argb: Int): Double {
        fun ch(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch(argb shr 16 and 0xFF) + 0.7152 * ch(argb shr 8 and 0xFF) + 0.0722 * ch(argb and 0xFF)
    }

    /** "2" < "10" < "M1" < "M10" — the order people expect for line numbers. */
    fun naturalCompare(a: String, b: String): Int {
        val ra = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
        val rb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ra.size, rb.size)) {
            val nx = ra[i].toLongOrNull(); val ny = rb[i].toLongOrNull()
            val c = if (nx != null && ny != null) nx.compareTo(ny) else ra[i].compareTo(rb[i])
            if (c != 0) return c
        }
        return ra.size - rb.size
    }

    fun hex(argb: Int) = String.format("#%06X", argb and 0xFFFFFF)
}

object Overlays {
    val EMPTY = """{"type":"FeatureCollection","features":[]}"""
}
