package app.wayfinder.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** A rectangular area in degrees. */
data class Bounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    fun contains(o: Bounds) = o.south >= south && o.north <= north && o.west >= west && o.east <= east
    fun expanded(f: Double): Bounds {
        val dLat = (north - south) * f
        val dLon = (east - west) * f
        return Bounds(
            (south - dLat).coerceAtLeast(-85.0), (west - dLon).coerceAtLeast(-180.0),
            (north + dLat).coerceAtMost(85.0), (east + dLon).coerceAtMost(180.0),
        )
    }
}

/** What an overlay shows. */
enum class OverlayKind { Transit, Rail, Bike }

/** One line, as shown in the line-number pills. */
data class LineInfo(
    val key: String,
    val ref: String,
    val name: String,
    val route: String,
    val color: Int,
    val textColor: Int,
)

/** GeoJSON for every line (and stop) of one overlay in an area. */
data class OverlayData(
    val kind: OverlayKind,
    val lines: String,
    val stops: String,
    val legend: List<LineInfo>,
    val area: Bounds,
    /** Whether the dense tier (buses / local bike routes) is included. */
    val detailed: Boolean,
)

/**
 * Loads OpenStreetMap route relations (public transport lines or cycle routes) from the
 * Overpass API, so lines can be drawn in their real colours with their line numbers.
 */
object Overlays {
    private val endpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    )

    /** Below this zoom, overlays rely only on what the vector tiles carry. */
    const val MIN_ZOOM = 10.0
    /** Buses and local cycle routes are dense: only fetch them closer in. */
    const val DETAIL_ZOOM = 12.5

    private const val RAIL = "train|subway|light_rail|monorail|funicular"
    private const val FIXED = "$RAIL|tram|ferry"
    private const val ALL = "$FIXED|bus|trolleybus"

    val EMPTY = """{"type":"FeatureCollection","features":[]}"""

    suspend fun load(kind: OverlayKind, area: Bounds, detailed: Boolean): OverlayData {
        val bbox = String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f", area.south, area.west, area.north, area.east)
        val relationFilter = when (kind) {
            OverlayKind.Rail -> """["type"="route"]["route"~"^($RAIL)$"]"""
            OverlayKind.Transit -> """["type"="route"]["route"~"^(${if (detailed) ALL else FIXED})$"]"""
            OverlayKind.Bike -> if (detailed) {
                """["type"="route"]["route"~"^(bicycle|mtb)$"]"""
            } else {
                """["type"="route"]["route"="bicycle"]["network"~"^(icn|ncn|rcn)$"]"""
            }
        }
        // Relations with their member lists, then only the member ways/stops inside the area.
        val query = buildString {
            append("[out:json][timeout:40];\n")
            append("relation$relationFilter($bbox)->.r;\n")
            append(".r out body;\n")
            append("way(r.r)($bbox);\n")
            append("out geom;\n")
            if (kind != OverlayKind.Bike) {
                append("node(r.r)($bbox);\n")
                append("out skel;\n")
            }
        }
        var last: Exception? = null
        for (endpoint in endpoints) {
            try {
                val body = Http.postForm(endpoint, mapOf("data" to query))
                return withContext(Dispatchers.Default) { parse(kind, JSONObject(body), area, detailed) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("Overpass unavailable")
    }

    private fun parse(kind: OverlayKind, json: JSONObject, area: Bounds, detailed: Boolean): OverlayData {
        val elements = json.optJSONArray("elements") ?: JSONArray()
        val relations = ArrayList<JSONObject>()
        val ways = HashMap<Long, JSONArray>() // id -> coordinates [[lon,lat],…]
        val nodes = HashMap<Long, JSONArray>() // id -> [lon,lat]
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            when (el.optString("type")) {
                "relation" -> relations += el
                "way" -> el.optJSONArray("geometry")?.let { geom ->
                    val coords = JSONArray()
                    for (g in 0 until geom.length()) {
                        val p = geom.optJSONObject(g) ?: continue
                        coords.put(JSONArray().put(p.getDouble("lon")).put(p.getDouble("lat")))
                    }
                    if (coords.length() >= 2) ways[el.getLong("id")] = coords
                }
                "node" -> if (el.has("lat")) {
                    nodes[el.getLong("id")] = JSONArray().put(el.getDouble("lon")).put(el.getDouble("lat"))
                }
            }
        }

        val lineFeatures = JSONArray()
        val stops = HashMap<Long, JSONObject>()
        val legend = LinkedHashMap<String, LineInfo>()

        for (rel in relations) {
            val tags = rel.optJSONObject("tags") ?: JSONObject()
            val route = tags.optString("route")
            val members = rel.optJSONArray("members") ?: continue
            val color = colorFor(kind, tags, route)
            val text = if (luminance(color) > 0.6) 0xFF1B1B1F.toInt() else 0xFFFFFFFF.toInt()
            val ref = tags.optString("ref").ifBlank {
                if (kind == OverlayKind.Bike) tags.optString("name") else ""
            }.take(24)
            val name = tags.optString("name")
            val rank = rank(route, tags.optString("network"))
            val key = "$route|${ref.ifBlank { name }}|${hex(color)}"

            val parts = JSONArray()
            for (m in 0 until members.length()) {
                val member = members.getJSONObject(m)
                val id = member.optLong("ref")
                val role = member.optString("role")
                when (member.optString("type")) {
                    "way" -> if (!role.startsWith("platform")) ways[id]?.let { parts.put(it) }
                    "node" -> if (role.startsWith("stop") || role.startsWith("platform")) {
                        val pos = nodes[id] ?: continue
                        val existing = stops[id]
                        if (existing == null || existing.getJSONObject("properties").getInt("rank") < rank) {
                            stops[id] = feature(
                                JSONObject().put("type", "Point").put("coordinates", pos),
                                JSONObject().put("rank", rank).put("color", hex(color)).put("key", key),
                            )
                        }
                    }
                }
            }
            if (parts.length() == 0) continue
            lineFeatures.put(
                feature(
                    JSONObject().put("type", "MultiLineString").put("coordinates", parts),
                    JSONObject()
                        .put("key", key)
                        .put("route", route)
                        .put("ref", ref)
                        .put("name", name)
                        .put("color", hex(color))
                        .put("text", hex(text))
                        .put("rank", rank),
                )
            )
            if (ref.isNotBlank() && key !in legend) {
                legend[key] = LineInfo(key, ref, name, route, color, text)
            }
        }

        return OverlayData(
            kind = kind,
            lines = collection(lineFeatures),
            stops = collection(JSONArray(stops.values)),
            legend = legend.values.sortedWith(
                compareByDescending<LineInfo> { rank(it.route, "") }.thenComparator { a, b -> naturalCompare(a.ref, b.ref) }
            ),
            area = area,
            detailed = detailed,
        )
    }

    /** Draw order: higher ranks paint on top. */
    private fun rank(route: String, network: String) = when (route) {
        "bus", "trolleybus" -> 1
        "ferry" -> 2
        "tram" -> 3
        "light_rail", "monorail", "funicular" -> 4
        "subway" -> 5
        "train" -> 6
        "bicycle", "mtb" -> when (network) {
            "icn" -> 4; "ncn" -> 3; "rcn" -> 2; else -> 1
        }
        else -> 0
    }

    private fun colorFor(kind: OverlayKind, tags: JSONObject, route: String): Int {
        val raw = tags.optString("colour").ifBlank { tags.optString("color") }.trim()
        parseHex(raw)?.let { return it }
        named[raw.lowercase()]?.let { return it }
        if (kind == OverlayKind.Bike) {
            return when (tags.optString("network")) {
                "icn" -> 0xFF8E24AA
                "ncn" -> 0xFFE53935
                "rcn" -> 0xFF1E88E5
                "lcn" -> 0xFFF29900
                else -> if (route == "mtb") 0xFF8D6E63 else 0xFF2E9E4F
            }.toInt()
        }
        return defaultColor(route)
    }

    private val named = mapOf(
        "red" to 0xFFE53935, "blue" to 0xFF1E63E9, "green" to 0xFF2E9E4F, "yellow" to 0xFFF2C200,
        "orange" to 0xFFF57C00, "purple" to 0xFF8E44AD, "violet" to 0xFF8E44AD, "brown" to 0xFF8D5B3C,
        "pink" to 0xFFE05297, "grey" to 0xFF7A7F87, "gray" to 0xFF7A7F87, "black" to 0xFF2B2B2B,
        "navy" to 0xFF1F3A93, "teal" to 0xFF00897B, "cyan" to 0xFF00ACC1, "lime" to 0xFF7CB342,
        "maroon" to 0xFF8B1E3F, "olive" to 0xFF7D7A1E, "white" to 0xFFE0E0E0, "silver" to 0xFFA0A4AA,
    ).mapValues { it.value.toInt() }

    fun defaultColor(route: String): Int = when (route) {
        "bus", "trolleybus" -> 0xFF7E57C2
        "tram" -> 0xFFE8473F
        "subway" -> 0xFF1E63E9
        "light_rail", "monorail" -> 0xFF00A3A3
        "train" -> 0xFF3D4A5C
        "ferry" -> 0xFF0091D5
        "funicular" -> 0xFFB0761E
        else -> 0xFF6B7280
    }.toInt()

    private fun luminance(argb: Int): Double {
        fun ch(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch(argb shr 16 and 0xFF) + 0.7152 * ch(argb shr 8 and 0xFF) + 0.0722 * ch(argb and 0xFF)
    }

    /** "2" < "10" < "M1" < "M10" — the order people expect for line numbers. */
    private fun naturalCompare(a: String, b: String): Int {
        val ra = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
        val rb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ra.size, rb.size)) {
            val x = ra[i]; val y = rb[i]
            val nx = x.toLongOrNull(); val ny = y.toLongOrNull()
            val c = if (nx != null && ny != null) nx.compareTo(ny) else x.compareTo(y)
            if (c != 0) return c
        }
        return ra.size - rb.size
    }

    fun hex(argb: Int) = String.format("#%06X", argb and 0xFFFFFF)

    private fun feature(geometry: JSONObject, props: JSONObject) =
        JSONObject().put("type", "Feature").put("geometry", geometry).put("properties", props)

    private fun collection(features: JSONArray) =
        JSONObject().put("type", "FeatureCollection").put("features", features).toString()
}
