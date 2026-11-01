package app.wayfinder.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/** Place search backed by Photon (OpenStreetMap data, https://photon.komoot.io). */
object Geocoder {
    private const val BASE = "https://photon.komoot.io"

    suspend fun search(query: String, near: LatLon?): List<Place> {
        val url = "$BASE/api/".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("limit", "10")
            .apply {
                if (near != null) {
                    addQueryParameter("lat", near.lat.toString())
                    addQueryParameter("lon", near.lon.toString())
                }
            }
            .build()
        return parse(Http.get(url))
    }

    suspend fun reverse(pos: LatLon): Place? {
        val url = "$BASE/reverse".toHttpUrl().newBuilder()
            .addQueryParameter("lat", pos.lat.toString())
            .addQueryParameter("lon", pos.lon.toString())
            .build()
        return parse(Http.get(url)).firstOrNull()
    }

    private fun parse(body: String): List<Place> {
        val features = JSONObject(body).optJSONArray("features") ?: return emptyList()
        val seen = HashSet<String>()
        return buildList {
            for (i in 0 until features.length()) {
                val f = features.getJSONObject(i)
                val coords = f.getJSONObject("geometry").getJSONArray("coordinates")
                val p = f.getJSONObject("properties")
                val street = p.optString("street").takeIf { it.isNotBlank() }
                val number = p.optString("housenumber").takeIf { it.isNotBlank() }
                val name = p.optString("name").takeIf { it.isNotBlank() }
                    ?: listOfNotNull(street, number).joinToString(" ").takeIf { it.isNotBlank() }
                    ?: continue
                val detail = listOfNotNull(
                    if (p.optString("name").isNotBlank()) street?.let { s -> listOfNotNull(s, number).joinToString(" ") } else null,
                    p.optString("city").takeIf { it.isNotBlank() && it != name },
                    p.optString("country").takeIf { it.isNotBlank() },
                ).joinToString(", ").ifBlank { null }
                val key = p.optString("osm_key")
                val value = p.optString("osm_value")
                val kind = if (key == "railway" || key == "public_transport" ||
                    (key == "highway" && value == "bus_stop") || value == "station"
                ) PlaceKind.Station else PlaceKind.Place
                if (!seen.add("$name|$detail")) continue
                add(Place(name, detail, LatLon(coords.getDouble(1), coords.getDouble(0)), kind))
            }
        }
    }
}
