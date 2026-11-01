package app.wayfinder.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime

/** One upcoming departure of a line from a stop. */
data class Departure(
    val timeMs: Long,
    val scheduledMs: Long?,
    val headsign: String,
    val realTime: Boolean,
    val cancelled: Boolean,
)

/** Upcoming departures at a stop from Transitous (MOTIS `stoptimes`). */
object Departures {
    private val versions = listOf("v6", "v5", "v4", "v3", "v2", "v1")
    @Volatile private var working: String? = null

    /** Departures of the line numbered [ref] (all lines when blank) from [stopId]. */
    suspend fun load(stopId: String, ref: String): List<Departure> {
        var last: Exception? = null
        for (v in working?.let { listOf(it) } ?: versions) {
            try {
                val url = "https://api.transitous.org/api/$v/stoptimes".toHttpUrl().newBuilder()
                    .addQueryParameter("stopId", stopId)
                    .addQueryParameter("n", "60")
                    .build()
                val json = JSONObject(Http.get(url))
                working = v
                return parse(json, ref)
            } catch (e: HttpException) {
                last = e
                if (e.code != 404) throw e
            }
        }
        throw last ?: IllegalStateException("No departures")
    }

    private fun parse(json: JSONObject, ref: String): List<Departure> {
        val list = json.optJSONArray("stopTimes") ?: return emptyList()
        val out = ArrayList<Departure>()
        for (i in 0 until list.length()) {
            val st = list.getJSONObject(i)
            val line = st.optString("routeShortName").ifBlank { st.optString("displayName") }.trim()
            if (ref.isNotBlank() && !line.equals(ref, ignoreCase = true)) continue
            val place = st.optJSONObject("place") ?: continue
            val time = time(place.optString("departure")) ?: time(place.optString("arrival")) ?: continue
            out += Departure(
                timeMs = time,
                scheduledMs = time(place.optString("scheduledDeparture")),
                headsign = st.optString("headsign"),
                realTime = st.optBoolean("realTime", false),
                cancelled = st.optBoolean("cancelled", false) || st.optBoolean("tripCancelled", false),
            )
        }
        return out.sortedBy { it.timeMs }
    }

    private fun time(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }
            .recoverCatching { Instant.parse(s).toEpochMilli() }
            .getOrNull()
    }
}
