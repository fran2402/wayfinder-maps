package app.wayfinder.data

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.io.Reader
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Signed bike routes (OpenStreetMap route relations) from Overpass, stream-parsed straight into
 * the compact [TileData] form the transit lines use: no JSON trees or giant strings in memory.
 */
object BikeRoutes {
    private val endpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )

    private val quickClient = Http.client.newBuilder().callTimeout(35, TimeUnit.SECONDS).readTimeout(35, TimeUnit.SECONDS).build()
    private val largeClient = Http.client.newBuilder().callTimeout(170, TimeUnit.SECONDS).readTimeout(150, TimeUnit.SECONDS).build()

    /** Bike routes in [area]; [large] allows a bigger area and a longer server time limit (downloads). */
    suspend fun fetch(area: Bounds, large: Boolean): TileData {
        val bbox = String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f", area.south, area.west, area.north, area.east)
        val query = "[out:json][timeout:${if (large) 140 else 25}];\n" +
            "relation[\"type\"=\"route\"][\"route\"~\"^(${Tier.Bike.routes})$\"]($bbox)->.r;\n" +
            ".r out body;\nway(r.r)($bbox);\nout geom;\n"
        var last: Exception? = null
        for (endpoint in endpoints) {
            try {
                return withContext(Dispatchers.IO) {
                    val call = (if (large) largeClient else quickClient).newCall(
                        Request.Builder().url(endpoint).post(FormBody.Builder().add("data", query).build()).build()
                    )
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            throw HttpException(response.code, if (response.code == 429 || response.code == 504) "server busy (${response.code})" else "HTTP ${response.code}")
                        }
                        parse(response.body!!.charStream())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("Overpass unavailable")
    }

    private class Rel(val route: String, val ref: String, val name: String, val color: Int, val rank: Int, val ways: LongArray)

    /** Reads an Overpass JSON answer (relations, then ways with geometry). */
    fun parse(reader: Reader): TileData {
        val rels = ArrayList<Rel>()
        val geometry = HashMap<Long, FloatArray>()
        var remark: String? = null
        // Simplified to street detail: about a metre.
        val tolerance = (360.0 / (256.0 * Math.pow(2.0, 16.0)) / 3.0).toFloat()
        JsonReader(reader).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "remark" -> remark = r.nextString()
                    "elements" -> {
                        r.beginArray()
                        while (r.hasNext()) readElement(r, rels, geometry, tolerance)
                        r.endArray()
                    }
                    else -> r.skipValue()
                }
            }
            r.endObject()
        }
        // Overpass reports its own time-outs inside a normal answer.
        if (remark?.contains("runtime error") == true) throw IOException("server busy")
        val out = ArrayList<TileRoute>(rels.size)
        for (rel in rels) {
            val parts = ArrayList<FloatArray>(rel.ways.size)
            for (w in rel.ways) geometry[w]?.let { parts += it }
            if (parts.isEmpty()) continue
            var s = 90f; var n = -90f; var w = 180f; var e = -180f
            for (p in parts) {
                var k = 0
                while (k < p.size) {
                    val lat = p[k]; val lon = p[k + 1]
                    if (lat < s) s = lat; if (lat > n) n = lat; if (lon < w) w = lon; if (lon > e) e = lon
                    k += 2
                }
            }
            val text = if (Palette.luminance(rel.color) > 0.6) 0xFF1B1B1F.toInt() else 0xFFFFFFFF.toInt()
            out += TileRoute(
                key = "${rel.route}|${rel.ref.ifBlank { rel.name }}|${Palette.hex(rel.color)}",
                mode = TransitMode.Other, osmRoute = rel.route, ref = rel.ref, name = rel.name,
                color = rel.color, text = text, rank = rel.rank,
                parts = parts, partIds = IntArray(parts.size) { parts[it].contentHashCode() }, stops = emptyList(),
                bounds = Bounds(s.toDouble(), w.toDouble(), n.toDouble(), e.toDouble()),
            )
        }
        return TileData(out, false)
    }

    private fun readElement(r: JsonReader, rels: MutableList<Rel>, ways: MutableMap<Long, FloatArray>, tolerance: Float) {
        var type = ""
        var id = 0L
        val tags = HashMap<String, String>()
        val wayIds = ArrayList<Long>()
        var geom: FloatArray? = null
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "type" -> type = r.nextString()
                "id" -> id = r.nextLong()
                "tags" -> {
                    r.beginObject()
                    while (r.hasNext()) {
                        val k = r.nextName()
                        if (r.peek() == JsonToken.STRING) tags[k] = r.nextString() else r.skipValue()
                    }
                    r.endObject()
                }
                "members" -> {
                    r.beginArray()
                    while (r.hasNext()) {
                        var mType = ""; var ref = 0L; var role = ""
                        r.beginObject()
                        while (r.hasNext()) {
                            when (r.nextName()) {
                                "type" -> mType = r.nextString()
                                "ref" -> ref = r.nextLong()
                                "role" -> role = r.nextString()
                                else -> r.skipValue()
                            }
                        }
                        r.endObject()
                        if (mType == "way" && !role.startsWith("platform")) wayIds += ref
                    }
                    r.endArray()
                }
                "geometry" -> {
                    var buf = FloatArray(64)
                    var n = 0
                    r.beginArray()
                    while (r.hasNext()) {
                        if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
                        var lat = Double.NaN; var lon = Double.NaN
                        r.beginObject()
                        while (r.hasNext()) {
                            when (r.nextName()) {
                                "lat" -> lat = r.nextDouble()
                                "lon" -> lon = r.nextDouble()
                                else -> r.skipValue()
                            }
                        }
                        r.endObject()
                        if (lat.isNaN() || lon.isNaN()) continue
                        if (n + 2 > buf.size) buf = buf.copyOf(buf.size * 2)
                        buf[n++] = lat.toFloat(); buf[n++] = lon.toFloat()
                    }
                    r.endArray()
                    if (n >= 4) geom = TransitRoutes.simplify(buf.copyOf(n), tolerance)
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        when (type) {
            "relation" -> {
                val route = tags["route"].orEmpty()
                val json = JSONObject(tags as Map<*, *>)
                val color = Palette.colorFor(json, route, true)
                val name = tags["name"].orEmpty()
                val ref = tags["ref"].orEmpty().ifBlank { name }.trim().take(24)
                rels += Rel(route, ref, name, color, Palette.rank(route, tags["network"].orEmpty()), wayIds.toLongArray())
            }
            "way" -> geom?.let { ways[id] = it }
        }
    }
}
