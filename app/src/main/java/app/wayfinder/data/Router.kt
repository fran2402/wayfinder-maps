package app.wayfinder.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime

object Router {

    /** [departAt] (epoch ms) chains legs of a multi-stop trip; null means now. */
    suspend fun plan(mode: TravelMode, from: LatLon, to: LatLon, departAt: Long? = null): List<Itinerary> = when (mode) {
        TravelMode.Walk -> Osrm.route("routed-foot", LegMode.Walk, from, to, departAt)
        TravelMode.Cycle -> Osrm.route("routed-bike", LegMode.Bike, from, to, departAt)
        TravelMode.Transit -> Motis.plan(from, to, listOf("TRANSIT"), departAt)
        TravelMode.Train -> Motis.plan(from, to, listOf("RAIL,SUBURBAN", "RAIL"), departAt)
    }
}

/** Walking and cycling directions from the FOSSGIS OSRM instances (https://routing.openstreetmap.de). */
private object Osrm {
    suspend fun route(profile: String, legMode: LegMode, from: LatLon, to: LatLon, departAt: Long?): List<Itinerary> {
        val url = ("https://routing.openstreetmap.de/$profile/route/v1/driving/" +
            "${from.lon},${from.lat};${to.lon},${to.lat}").toHttpUrl().newBuilder()
            .addQueryParameter("overview", "full")
            .addQueryParameter("geometries", "geojson")
            .addQueryParameter("steps", "true")
            .addQueryParameter("alternatives", "true")
            .build()
        val json = JSONObject(Http.get(url))
        if (json.optString("code") != "Ok") error(json.optString("message", "No route found"))
        val routes = json.getJSONArray("routes")
        val now = departAt ?: System.currentTimeMillis()
        return (0 until routes.length()).map { i ->
            val r = routes.getJSONObject(i)
            val coords = r.getJSONObject("geometry").getJSONArray("coordinates")
            val geometry = (0 until coords.length()).map {
                val c = coords.getJSONArray(it)
                LatLon(c.getDouble(1), c.getDouble(0))
            }
            val steps = buildList {
                val legs = r.getJSONArray("legs")
                for (l in 0 until legs.length()) {
                    val s = legs.getJSONObject(l).getJSONArray("steps")
                    for (k in 0 until s.length()) {
                        val step = s.getJSONObject(k)
                        add(Step(instruction(step), step.optDouble("distance", 0.0)))
                    }
                }
            }
            val duration = r.getDouble("duration").toLong()
            Itinerary(
                legs = listOf(
                    Leg(
                        mode = legMode, fromName = "", toName = "", startMs = now,
                        endMs = now + duration * 1000, durationS = duration,
                        distanceM = r.getDouble("distance"), line = null, headsign = null, agency = null,
                        color = null, textColor = null, intermediateStops = 0, realTime = false,
                        geometry = geometry, steps = steps,
                    )
                ),
                durationS = duration, startMs = now, endMs = now + duration * 1000,
                transfers = 0, distanceM = r.getDouble("distance"),
            )
        }
    }

    private fun instruction(step: JSONObject): String {
        val m = step.getJSONObject("maneuver")
        val type = m.optString("type")
        val modifier = m.optString("modifier").takeIf { it.isNotBlank() }
        val name = step.optString("name").takeIf { it.isNotBlank() }
        val onto = name?.let { " onto $it" } ?: ""
        val dir = when (modifier) {
            "uturn" -> "Make a U-turn"
            "sharp right" -> "Turn sharp right"
            "sharp left" -> "Turn sharp left"
            "slight right" -> "Bear right"
            "slight left" -> "Bear left"
            "right" -> "Turn right"
            "left" -> "Turn left"
            else -> "Continue"
        }
        return when (type) {
            "depart" -> "Head out" + (name?.let { " on $it" } ?: "")
            "arrive" -> "Arrive at your destination"
            "roundabout", "rotary" -> "At the roundabout, take exit ${m.optInt("exit", 1)}$onto"
            "new name", "continue" -> if (modifier == null || modifier == "straight") "Continue$onto" else "$dir$onto"
            "end of road", "fork", "turn", "merge", "on ramp", "off ramp" -> "$dir$onto"
            else -> "$dir$onto"
        }
    }
}

/** Public transport routing via Transitous (MOTIS, https://transitous.org). */
private object Motis {
    private val apiVersions = listOf("v6", "v5", "v4", "v3", "v2", "v1")
    @Volatile private var workingVersion: String? = null

    suspend fun plan(from: LatLon, to: LatLon, modeVariants: List<String>, departAt: Long?): List<Itinerary> {
        var lastError: Exception? = null
        val versions = workingVersion?.let { listOf(it) } ?: apiVersions
        for (version in versions) {
            for (modes in modeVariants) {
                try {
                    val url = "https://api.transitous.org/api/$version/plan".toHttpUrl().newBuilder()
                        .addQueryParameter("fromPlace", "${from.lat},${from.lon}")
                        .addQueryParameter("toPlace", "${to.lat},${to.lon}")
                        .addQueryParameter("transitModes", modes)
                        .apply { departAt?.let { addQueryParameter("time", java.time.Instant.ofEpochMilli(it).toString()) } }
                        .build()
                    val result = parse(JSONObject(Http.get(url)))
                    workingVersion = version
                    return result
                } catch (e: HttpException) {
                    lastError = e
                    // 404 → try an older API version; 400 → try the next transit-mode spelling.
                    if (e.code == 404) break
                    if (e.code != 400) throw e
                }
            }
        }
        throw lastError ?: IllegalStateException("No connections found")
    }

    private fun parse(json: JSONObject): List<Itinerary> {
        val list = json.optJSONArray("itineraries") ?: JSONArray()
        return (0 until list.length()).mapNotNull { i ->
            val itin = list.getJSONObject(i)
            val legsJson = itin.optJSONArray("legs") ?: return@mapNotNull null
            val legs = (0 until legsJson.length()).map { k -> leg(legsJson.getJSONObject(k)) }
            val start = time(itin.optString("startTime"))
            val end = time(itin.optString("endTime"))
            Itinerary(
                legs = legs,
                durationS = itin.optLong("duration", if (start != null && end != null) (end - start) / 1000 else 0),
                startMs = start, endMs = end,
                transfers = itin.optInt("transfers", (legs.count { l -> l.mode.isTransit } - 1).coerceAtLeast(0)),
                distanceM = null,
            )
        }
    }

    private fun leg(j: JSONObject): Leg {
        val from = j.optJSONObject("from") ?: JSONObject()
        val to = j.optJSONObject("to") ?: JSONObject()
        val geo = j.optJSONObject("legGeometry")
        val geometry = geo?.optString("points")?.takeIf { it.isNotEmpty() }
            ?.let { decodePolyline(it, geo.optInt("precision", 7)) }
            ?: listOf(
                LatLon(from.optDouble("lat"), from.optDouble("lon")),
                LatLon(to.optDouble("lat"), to.optDouble("lon")),
            )
        val start = time(j.optString("startTime"))
        val end = time(j.optString("endTime"))
        val mode = mode(j.optString("mode"))
        val steps = j.optJSONArray("steps")?.let { arr ->
            (0 until arr.length()).map { k ->
                val s = arr.getJSONObject(k)
                val street = s.optString("streetName").takeIf { it.isNotBlank() }
                val dir = when (s.optString("relativeDirection")) {
                    "LEFT" -> "Turn left"; "RIGHT" -> "Turn right"
                    "SLIGHTLY_LEFT" -> "Bear left"; "SLIGHTLY_RIGHT" -> "Bear right"
                    "HARD_LEFT" -> "Turn sharp left"; "HARD_RIGHT" -> "Turn sharp right"
                    "UTURN_LEFT", "UTURN_RIGHT" -> "Make a U-turn"
                    "DEPART" -> "Head out"; "ELEVATOR" -> "Take the elevator"
                    "CIRCLE_CLOCKWISE", "CIRCLE_COUNTERCLOCKWISE" -> "Take the roundabout"
                    else -> "Continue"
                }
                Step(dir + (street?.let { " on $it" } ?: ""), s.optDouble("distance", 0.0))
            }
        }.orEmpty()
        return Leg(
            mode = mode,
            fromName = from.optString("name"),
            toName = to.optString("name"),
            startMs = start, endMs = end,
            durationS = j.optLong("duration", if (start != null && end != null) (end - start) / 1000 else 0),
            distanceM = j.optDouble("distance").takeUnless { it.isNaN() },
            line = j.optString("routeShortName").takeIf { it.isNotBlank() }
                ?: j.optString("displayName").takeIf { it.isNotBlank() },
            headsign = j.optString("headsign").takeIf { it.isNotBlank() },
            agency = j.optString("agencyName").takeIf { it.isNotBlank() },
            color = parseHex(j.optString("routeColor")),
            textColor = parseHex(j.optString("routeTextColor")),
            intermediateStops = j.optJSONArray("intermediateStops")?.length() ?: 0,
            realTime = j.optBoolean("realTime", false),
            geometry = geometry,
            steps = steps,
        )
    }

    private fun mode(m: String): LegMode = when {
        m == "WALK" -> LegMode.Walk
        m == "BIKE" || m == "RENTAL" -> LegMode.Bike
        m == "BUS" || m == "COACH" || m == "TROLLEYBUS" -> LegMode.Bus
        m == "TRAM" || m == "CABLE_CAR" -> LegMode.Tram
        m == "SUBWAY" || m == "METRO" -> LegMode.Subway
        m == "FERRY" -> LegMode.Ferry
        m == "AIRPLANE" -> LegMode.Air
        "RAIL" in m || m == "SUBURBAN" || m == "LONG_DISTANCE" || m == "MONORAIL" -> LegMode.Rail
        else -> LegMode.Other
    }

    private fun time(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }
            .recoverCatching { Instant.parse(s).toEpochMilli() }
            .getOrNull()
    }
}

internal fun parseHex(raw: String?): Int? {
    val s = raw?.trim()?.removePrefix("#") ?: return null
    if (s.length != 6 && s.length != 3) return null
    val full = if (s.length == 3) s.map { "$it$it" }.joinToString("") else s
    return full.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
}
