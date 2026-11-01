package app.wayfinder.data

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

/** Which slice of the network to draw: everything, or rail only (Train mode). */
enum class NetworkScope { All, Rail }

/** GeoJSON for every public-transport line and stop in an area. */
data class NetworkData(
    val lines: String,
    val stops: String,
    val area: Bounds,
    val scope: NetworkScope,
    val includesBus: Boolean,
)

/**
 * Loads the public-transport network (OSM `type=route` relations) from the Overpass API,
 * so the whole network can be drawn with each line in its official colour.
 */
object TransitNetwork {
    private val endpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )

    /** Below this zoom, only the rail network baked into the vector tiles is drawn. */
    const val MIN_ZOOM = 9.5
    /** Bus routes are dense: only fetch them once the user is looking at a neighbourhood. */
    const val BUS_ZOOM = 12.5

    private const val RAIL = "train|subway|light_rail|monorail|funicular"
    private const val FIXED = "$RAIL|tram|ferry"
    private const val ALL = "$FIXED|bus|trolleybus"

    suspend fun load(area: Bounds, scope: NetworkScope, includeBus: Boolean): NetworkData {
        val bbox = String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f", area.south, area.west, area.north, area.east)
        val routes = when {
            scope == NetworkScope.Rail -> RAIL
            includeBus -> ALL
            else -> FIXED
        }
        // `out geom(bbox)` clips member geometry to the area, which keeps long-distance lines small.
        val query = """
            [out:json][timeout:30];
            relation["type"="route"]["route"~"^($routes)$"]($bbox);
            out geom($bbox);
        """.trimIndent()
        var last: Exception? = null
        for (endpoint in endpoints) {
            try {
                return toGeoJson(JSONObject(Http.postForm(endpoint, mapOf("data" to query))), area, scope, includeBus)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                last = e
            }
        }
        throw last ?: IllegalStateException("Network unavailable")
    }

    private fun toGeoJson(json: JSONObject, area: Bounds, scope: NetworkScope, includeBus: Boolean): NetworkData {
        val elements = json.optJSONArray("elements") ?: JSONArray()
        val lines = JSONArray()
        val stops = HashMap<Long, JSONObject>()
        for (i in 0 until elements.length()) {
            val rel = elements.getJSONObject(i)
            if (rel.optString("type") != "relation") continue
            val tags = rel.optJSONObject("tags") ?: JSONObject()
            val route = tags.optString("route")
            val members = rel.optJSONArray("members") ?: continue
            val parts = JSONArray()
            for (m in 0 until members.length()) {
                val member = members.getJSONObject(m)
                val role = member.optString("role")
                when (member.optString("type")) {
                    "way" -> {
                        if (role.startsWith("platform")) continue
                        val geom = member.optJSONArray("geometry") ?: continue
                        // Points outside the clip area come back as null: split the way there.
                        var coords = JSONArray()
                        for (g in 0 until geom.length()) {
                            val p = geom.optJSONObject(g)
                            if (p == null) {
                                if (coords.length() >= 2) parts.put(coords)
                                coords = JSONArray()
                                continue
                            }
                            coords.put(JSONArray().put(p.getDouble("lon")).put(p.getDouble("lat")))
                        }
                        if (coords.length() >= 2) parts.put(coords)
                    }
                    "node" -> if (role.startsWith("stop") || role.startsWith("platform")) {
                        val id = member.getLong("ref")
                        if (!member.has("lat")) continue
                        val existing = stops[id]
                        val rank = rank(route)
                        if (existing == null || existing.getJSONObject("properties").getInt("rank") < rank) {
                            stops[id] = feature(
                                JSONObject().put("type", "Point").put(
                                    "coordinates",
                                    JSONArray().put(member.getDouble("lon")).put(member.getDouble("lat")),
                                ),
                                JSONObject().put("route", route).put("rank", rank)
                                    .put("color", colorFor(tags, route)),
                            )
                        }
                    }
                }
            }
            if (parts.length() == 0) continue
            val ref = tags.optString("ref").ifBlank { tags.optString("name") }
            lines.put(
                feature(
                    JSONObject().put("type", "MultiLineString").put("coordinates", parts),
                    JSONObject()
                        .put("route", route)
                        .put("ref", ref)
                        .put("name", tags.optString("name"))
                        .put("color", colorFor(tags, route))
                        .put("rank", rank(route)),
                )
            )
        }
        return NetworkData(
            lines = collection(lines),
            stops = collection(JSONArray(stops.values)),
            area = area,
            scope = scope,
            includesBus = includeBus,
        )
    }

    /** Draw order: higher ranks paint on top. */
    private fun rank(route: String) = when (route) {
        "bus", "trolleybus" -> 1
        "ferry" -> 2
        "tram" -> 3
        "light_rail", "monorail", "funicular" -> 4
        "subway" -> 5
        "train" -> 6
        else -> 0
    }

    private fun colorFor(tags: JSONObject, route: String): String {
        val raw = tags.optString("colour").ifBlank { tags.optString("color") }
        parseHex(raw)?.let { return String.format("#%06X", it and 0xFFFFFF) }
        // Named CSS colours are common in OSM; let a few through, else fall back to a mode colour.
        val named = mapOf(
            "red" to "#E53935", "blue" to "#1E63E9", "green" to "#2E9E4F", "yellow" to "#F2C200",
            "orange" to "#F57C00", "purple" to "#8E44AD", "brown" to "#8D5B3C", "pink" to "#E05297",
            "grey" to "#7A7F87", "gray" to "#7A7F87", "black" to "#2B2B2B", "navy" to "#1F3A93",
        )
        named[raw.lowercase()]?.let { return it }
        return defaultColor(route)
    }

    fun defaultColor(route: String) = when (route) {
        "bus", "trolleybus" -> "#7E57C2"
        "tram" -> "#E8473F"
        "subway" -> "#1E63E9"
        "light_rail", "monorail" -> "#00A3A3"
        "train" -> "#3D4A5C"
        "ferry" -> "#0091D5"
        "funicular" -> "#B0761E"
        else -> "#6B7280"
    }

    private fun feature(geometry: JSONObject, props: JSONObject) =
        JSONObject().put("type", "Feature").put("geometry", geometry).put("properties", props)

    private fun collection(features: JSONArray) =
        JSONObject().put("type", "FeatureCollection").put("features", features).toString()

    val EMPTY = """{"type":"FeatureCollection","features":[]}"""
}
