package app.wayfinder.data

data class LatLon(val lat: Double, val lon: Double)

enum class TravelMode { Transit, Train, Walk, Cycle }

enum class PlaceKind { Place, Station, MyLocation, DroppedPin }

data class Place(
    val name: String,
    val detail: String?,
    val pos: LatLon,
    val kind: PlaceKind = PlaceKind.Place,
)

/** Simplified vehicle categories used for colours and icons. */
enum class LegMode { Walk, Bike, Bus, Tram, Subway, Rail, Ferry, Air, Other;

    val isTransit get() = this != Walk && this != Bike
}

data class Step(val text: String, val distanceM: Double)

data class Leg(
    val mode: LegMode,
    val fromName: String,
    val toName: String,
    val startMs: Long?,
    val endMs: Long?,
    val durationS: Long,
    val distanceM: Double?,
    val line: String?,
    val headsign: String?,
    val agency: String?,
    /** ARGB, or null to use the default colour for [mode]. */
    val color: Int?,
    val textColor: Int?,
    val intermediateStops: Int,
    val realTime: Boolean,
    val geometry: List<LatLon>,
    val steps: List<Step> = emptyList(),
)

data class Itinerary(
    val legs: List<Leg>,
    val durationS: Long,
    val startMs: Long?,
    val endMs: Long?,
    val transfers: Int,
    val distanceM: Double?,
) {
    val walkSeconds: Long get() = legs.filter { it.mode == LegMode.Walk }.sumOf { it.durationS }
    val geometry: List<LatLon> get() = legs.flatMap { it.geometry }
}
