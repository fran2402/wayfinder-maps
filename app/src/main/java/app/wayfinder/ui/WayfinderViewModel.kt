package app.wayfinder.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.wayfinder.data.Bounds
import app.wayfinder.data.Geocoder
import app.wayfinder.data.Itinerary
import app.wayfinder.data.LatLon
import app.wayfinder.data.LineInfo
import app.wayfinder.data.Overlays
import app.wayfinder.data.Overpass
import app.wayfinder.data.RouteStore
import app.wayfinder.data.Tier
import app.wayfinder.data.TileId
import app.wayfinder.data.Tiles
import app.wayfinder.data.TransitData
import app.wayfinder.data.TransitMode
import app.wayfinder.data.TransitRoutes
import app.wayfinder.data.Place
import app.wayfinder.data.PlaceKind
import app.wayfinder.data.Router
import app.wayfinder.data.TravelMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

enum class SearchTarget { From, To, Stop }

/** One leg of a multi-stop trip, planned with its own travel mode. */
data class TripSegment(
    val from: Place?, // null = my location
    val to: Place,
    val mode: TravelMode,
    val options: List<Itinerary> = emptyList(),
    val selected: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val chosen: Itinerary? get() = options.getOrNull(selected)
}

/** A place the user tapped on the map (restaurant, shop, station…). */
data class PlaceDetail(val place: Place, val category: String?, val poiClass: String?)

class WayfinderViewModel : ViewModel() {

    // ---------- Search ----------
    var searchTarget by mutableStateOf<SearchTarget?>(null)
        private set
    var query by mutableStateOf("")
        private set
    var suggestions by mutableStateOf<List<Place>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set
    private var searchJob: Job? = null

    // ---------- Trip ----------
    /** null means "my location". */
    var origin by mutableStateOf<Place?>(null)
        private set
    var destination by mutableStateOf<Place?>(null)
        private set
    var mode by mutableStateOf(TravelMode.Transit)
        private set
    var itineraries by mutableStateOf<List<Itinerary>>(emptyList())
        private set
    var selected by mutableStateOf(0)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** Bumped whenever the camera should frame the current route. */
    var fitRequest by mutableStateOf(0)
        private set
    private var planJob: Job? = null

    val planning: Boolean get() = destination != null

    // ---------- Stops in between ----------
    /** Places visited between the start and the destination, in order. */
    var stops by mutableStateOf<List<Place>>(emptyList())
        private set
    /** Travel mode per segment (stops.size + 1 entries) while the trip has stops. */
    var segmentModes by mutableStateOf<List<TravelMode>>(emptyList())
        private set
    var segments by mutableStateOf<List<TripSegment>>(emptyList())
        private set
    val multiStop: Boolean get() = stops.isNotEmpty()

    /** The chosen option of every segment, stitched into one trip. */
    val combined: Itinerary?
        get() {
            val chosen = segments.map { it.chosen ?: return null }
            if (chosen.isEmpty()) return null
            val start = chosen.first().startMs
            val end = chosen.last().endMs
            return Itinerary(
                legs = chosen.flatMap { it.legs },
                durationS = if (start != null && end != null) (end - start) / 1000 else chosen.sumOf { it.durationS },
                startMs = start,
                endMs = end,
                transfers = chosen.sumOf { it.transfers },
                distanceM = chosen.mapNotNull { it.distanceM }.takeIf { it.size == chosen.size }?.sum(),
            )
        }

    // ---------- Tapped place ----------
    var tappedPlace by mutableStateOf<PlaceDetail?>(null)
        private set

    // ---------- Location ----------
    var myLocation by mutableStateOf<LatLon?>(null)
        private set
    var hasLocationPermission by mutableStateOf(false)
        private set

    // ---------- Map overlays (the pills on top) ----------
    var showTransit by mutableStateOf(false)
        private set
    var showBike by mutableStateOf(false)
        private set
    /** Which kinds of public transport are drawn. */
    var transitModes by mutableStateOf(TransitMode.entries.toSet())
        private set
    var transitLines by mutableStateOf(Overlays.EMPTY)
        private set
    var transitStops by mutableStateOf(Overlays.EMPTY)
        private set
    /** Every loaded line with a number, for the badges drawn on the map. */
    var transitPills by mutableStateOf<List<LineInfo>>(emptyList())
        private set
    var bikeLines by mutableStateOf(Overlays.EMPTY)
        private set
    /** Lines in view, for the line-number pills. */
    var legend by mutableStateOf<List<LineInfo>>(emptyList())
        private set
    var transitLoading by mutableStateOf(false)
        private set
    var bikeLoading by mutableStateOf(false)
        private set
    var overlayHint by mutableStateOf<String?>(null)
        private set
    /** Key of the line picked in the line pills, or null for all lines. */
    var highlightedLine by mutableStateOf<String?>(null)
        private set
    /** The picked line with its stops, for the line sheet. */
    val highlightedLoaded: app.wayfinder.data.LoadedLine?
        get() = highlightedLine?.let { key -> transitData?.routes?.firstOrNull { it.info.key == key } }
    /** The picked line's details, for the panel. */
    val highlightedInfo: LineInfo?
        get() = highlightedLine?.let { key -> transitData?.routes?.firstOrNull { it.info.key == key }?.info }
    /** Area the camera should frame after a line is picked; bumped with [focusRequest]. */
    var focusBounds by mutableStateOf<Bounds?>(null)
        private set
    var focusRequest by mutableStateOf(0)
        private set

    /** Overlays only make sense for the travel mode being planned. */
    val transitAvailable: Boolean
        get() = !planning || mode == TravelMode.Transit || mode == TravelMode.Train
    val bikeAvailable: Boolean
        get() = !planning || mode == TravelMode.Cycle
    val transitVisible: Boolean get() = showTransit && transitAvailable
    val bikeVisible: Boolean get() = showBike && bikeAvailable

    /** Whether the map key sheet is open. */
    var showKey by mutableStateOf(false)
        private set

    fun toggleKey() { showKey = !showKey }

    private var transitData: TransitData? = null
    private var transitJob: Job? = null
    private var transitPending = false
    private var transitRetryAt = 0L
    private val bikeStore = RouteStore()
    private val loadedTiles = HashSet<TileId>()
    private val retryAfter = HashMap<Tier, Long>()
    private var overlayError: String? = null
    private var loaderJob: Job? = null
    private var legendJob: Job? = null
    private var lastViewport: Pair<Bounds, Double>? = null

    /** All store access happens on this one thread, so merges and reads never overlap. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val storeThread = Dispatchers.Default.limitedParallelism(1)

    // ---------- Search actions ----------

    fun openSearch(target: SearchTarget) {
        searchTarget = target
        query = ""
        suggestions = emptyList()
    }

    fun closeSearch() {
        searchTarget = null
        searchJob?.cancel()
        searching = false
    }

    fun onQueryChange(text: String, near: LatLon?) {
        query = text
        searchJob?.cancel()
        if (text.isBlank()) {
            suggestions = emptyList(); searching = false; return
        }
        searchJob = viewModelScope.launch {
            delay(280)
            searching = true
            try {
                suggestions = Geocoder.search(text.trim(), near ?: myLocation)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                suggestions = emptyList()
            } finally {
                searching = false
            }
        }
    }

    fun choose(place: Place?) {
        when (searchTarget) {
            SearchTarget.From -> origin = place?.takeUnless { it.kind == PlaceKind.MyLocation }
            SearchTarget.To, null -> destination = place
            SearchTarget.Stop -> if (place != null) {
                closeSearch()
                addStop(place)
                return
            }
        }
        closeSearch()
        plan()
        overlaysChanged()
    }

    fun dropPin(pos: LatLon) {
        val pin = Place("Dropped pin", null, pos, PlaceKind.DroppedPin)
        destination = pin
        plan()
        overlaysChanged()
        viewModelScope.launch {
            val named = runCatching { Geocoder.reverse(pos) }.getOrNull() ?: return@launch
            if (destination == pin) {
                destination = pin.copy(name = named.name, detail = named.detail)
            }
        }
    }

    fun swap() {
        val from = origin ?: myLocation?.let { Place("Your location", null, it, PlaceKind.MyLocation) }
        val to = destination ?: return
        origin = to
        destination = from
        stops = stops.reversed()
        segmentModes = segmentModes.reversed()
        if (destination?.kind == PlaceKind.MyLocation) {
            // Keep "my location" live on the destination side as a fixed point.
            destination = destination?.copy(name = "Your location")
        }
        plan()
    }

    fun clearTrip() {
        planJob?.cancel()
        destination = null
        origin = null
        stops = emptyList()
        segmentModes = emptyList()
        segments = emptyList()
        itineraries = emptyList()
        error = null
        loading = false
        selected = 0
        overlaysChanged()
    }

    /** Sets the travel mode; with stops, it applies to every segment. */
    fun selectMode(m: TravelMode) {
        if (m == mode && (!multiStop || segmentModes.all { it == m })) return
        mode = m
        if (multiStop) segmentModes = List(stops.size + 1) { m }
        plan()
        overlaysChanged()
    }

    // ---------- Stops ----------

    /** Adds a stop just before the destination; the new segment inherits the last one's mode. */
    fun addStop(place: Place) {
        if (destination == null) return
        val modes = segmentModes.takeIf { it.size == stops.size + 1 } ?: List(stops.size + 1) { mode }
        stops = stops + place
        segmentModes = modes + modes.last()
        segments = emptyList()
        plan()
        overlaysChanged()
    }

    fun removeStop(index: Int) {
        if (index !in stops.indices) return
        val modes = segmentModes.toMutableList()
        if (index + 1 < modes.size) modes.removeAt(index + 1)
        stops = stops.filterIndexed { i, _ -> i != index }
        segmentModes = if (stops.isEmpty()) emptyList() else modes
        if (stops.isEmpty()) {
            mode = modes.firstOrNull() ?: mode
            segments = emptyList()
        }
        plan()
        overlaysChanged()
    }

    /** Changes the travel mode of one segment and re-plans from there on. */
    fun setSegmentMode(index: Int, m: TravelMode) {
        if (index !in segmentModes.indices || segmentModes[index] == m) return
        segmentModes = segmentModes.toMutableList().also { it[index] = m }
        planSegments(index)
    }

    /** Picks another option for one segment; later segments are re-timed. */
    fun selectSegmentOption(index: Int, option: Int) {
        val seg = segments.getOrNull(index) ?: return
        if (seg.selected == option || option !in seg.options.indices) return
        segments = segments.toMutableList().also { it[index] = seg.copy(selected = option) }
        if (index < segments.lastIndex) planSegments(index + 1) else fitRequest++
    }

    // ---------- Tapped places ----------

    fun onPlaceTapped(place: Place, category: String?, poiClass: String?) {
        val detail = PlaceDetail(place, category, poiClass)
        tappedPlace = detail
        if (place.detail == null) {
            viewModelScope.launch {
                val found = runCatching { Geocoder.reverse(place.pos) }.getOrNull() ?: return@launch
                val address = listOfNotNull(found.name.takeIf { it != place.name }, found.detail)
                    .joinToString(", ").ifBlank { null }
                if (tappedPlace == detail) tappedPlace = detail.copy(place = place.copy(detail = address))
            }
        }
    }

    fun closePlace() { tappedPlace = null }

    fun directionsTo(place: Place) {
        tappedPlace = null
        showKey = false
        stops = emptyList()
        segmentModes = emptyList()
        segments = emptyList()
        origin = null
        destination = place
        plan()
        overlaysChanged()
    }

    fun addStopFromPlace(place: Place) {
        tappedPlace = null
        addStop(place)
    }

    fun select(index: Int) {
        if (index == selected) return
        selected = index
        fitRequest++
    }

    // ---------- Planning ----------

    fun plan() {
        if (multiStop) {
            planSegments(0)
            return
        }
        val to = destination ?: return
        val from = origin?.pos ?: myLocation
        planJob?.cancel()
        itineraries = emptyList()
        selected = 0
        error = null
        if (from == null) {
            loading = false
            error = if (hasLocationPermission) "Waiting for your location…" else "Choose a starting point or turn on location"
            fitRequest++
            return
        }
        loading = true
        fitRequest++
        val requestMode = mode
        planJob = viewModelScope.launch {
            try {
                val result = Router.plan(requestMode, from, to.pos)
                itineraries = result
                error = if (result.isEmpty()) noResultsMessage(requestMode) else null
                fitRequest++
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                error = "Can't reach the routing service. Check your connection."
            } catch (e: Exception) {
                error = noResultsMessage(requestMode)
            } finally {
                if (planJob === coroutineContext[Job]) loading = false
            }
        }
    }

    /** Plans segments [start]..end in order; each departs when the previous one arrives. */
    private fun planSegments(start: Int) {
        val to = destination ?: return
        val startPos = origin?.pos ?: myLocation
        planJob?.cancel()
        if (startPos == null) {
            loading = false
            error = if (hasLocationPermission) "Waiting for your location…" else "Choose a starting point or turn on location"
            fitRequest++
            return
        }
        val points: List<Place?> = listOf(origin) + stops + listOf(to)
        val count = points.size - 1
        if (segmentModes.size != count) segmentModes = List(count) { segmentModes.getOrNull(it) ?: mode }
        val kept = segments.take(start).takeIf { it.size == start } ?: emptyList()
        val from = kept.size
        segments = kept + (from until count).map { TripSegment(points[it], points[it + 1]!!, segmentModes[it], loading = true) }
        error = null
        loading = true
        planJob = viewModelScope.launch {
            try {
                var departAt = kept.lastOrNull()?.chosen?.endMs
                for (i in from until count) {
                    val seg = segments[i]
                    val origin = points[i]?.pos ?: startPos
                    val updated = try {
                        val result = Router.plan(seg.mode, origin, seg.to.pos, departAt)
                        seg.copy(options = result, selected = 0, loading = false,
                            error = if (result.isEmpty()) noResultsMessage(seg.mode) else null)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: IOException) {
                        seg.copy(loading = false, error = "Can't reach the routing service")
                    } catch (e: Exception) {
                        seg.copy(loading = false, error = noResultsMessage(seg.mode))
                    }
                    segments = segments.toMutableList().also { it[i] = updated }
                    departAt = updated.chosen?.endMs ?: departAt
                }
                fitRequest++
            } finally {
                if (planJob === coroutineContext[Job]) loading = false
            }
        }
    }

    private fun noResultsMessage(m: TravelMode) = when (m) {
        TravelMode.Transit -> "No public transport connections found"
        TravelMode.Train -> "No train connections found"
        TravelMode.Walk -> "No walking route found"
        TravelMode.Cycle -> "No cycling route found"
    }

    // ---------- Location ----------

    fun onPermission(granted: Boolean) {
        hasLocationPermission = granted
        if (granted && planning && itineraries.isEmpty() && origin == null) plan()
    }

    fun onLocation(pos: LatLon) {
        val first = myLocation == null
        myLocation = pos
        if (first && planning && origin == null && itineraries.isEmpty() && !loading) plan()
    }

    // ---------- Overlays ----------

    fun toggleTransit() {
        showTransit = !showTransit
        if (!showTransit) highlightedLine = null
        overlayError = null
        transitRetryAt = 0L
        ensureTransit()
        updateHint()
    }

    fun toggleBike() {
        showBike = !showBike
        overlayError = null
        retryAfter.clear()
        ensureBike()
        updateHint()
    }

    /**
     * With every mode shown, tapping one focuses on it; further taps add or remove modes;
     * removing the last one shows everything again.
     */
    fun toggleMode(mode: TransitMode) {
        val all = TransitMode.entries.toSet()
        transitModes = when {
            transitModes == all -> setOf(mode)
            mode in transitModes -> (transitModes - mode).ifEmpty { all }
            else -> transitModes + mode
        }
        refreshLegend()
    }

    fun highlight(key: String?) {
        highlightedLine = if (highlightedLine == key) null else key
        val picked = highlightedLine ?: return
        transitData?.routes?.firstOrNull { it.info.key == picked }?.let {
            focusBounds = it.bounds
            focusRequest++
        }
    }

    /** Re-evaluates overlays after the trip or travel mode changed. */
    private fun overlaysChanged() {
        if (!transitAvailable) highlightedLine = null
        ensureTransit()
        ensureBike()
        updateHint()
    }

    fun onViewport(bounds: Bounds, zoom: Double) {
        lastViewport = bounds to zoom
        refreshLegend()
        ensureTransit()
        ensureBike()
        updateHint()
    }

    private fun updateHint() {
        val zoom = lastViewport?.second
        val data = transitData
        overlayHint = when {
            !transitVisible && !bikeVisible -> null
            overlayError != null -> overlayError
            zoom != null && bikeVisible && !transitVisible && zoom < Tier.Bike.minViewZoom -> "Zoom in to see bike routes"
            transitVisible && data != null && data.zoomFiltered && legend.none { it.mode == TransitMode.Bus } &&
                TransitMode.Bus in transitModes -> "Zoom in to see trams and buses"
            else -> null
        }
    }

    // ---------- Public transport (Transitous) ----------

    /** Fetches routes for the view unless what we have already covers it. One request at a time. */
    private fun ensureTransit() {
        if (!transitVisible) return
        val (view, zoom) = lastViewport ?: return
        val have = transitData
        val covered = have != null && have.area.contains(view) &&
            (!have.zoomFiltered || kotlin.math.floor(zoom) <= kotlin.math.floor(have.zoom))
        if (covered) return
        if (System.currentTimeMillis() < transitRetryAt) return
        if (transitJob?.isActive == true) {
            transitPending = true
            return
        }
        transitJob = viewModelScope.launch {
            transitLoading = true
            try {
                val data = TransitRoutes.load(view.expanded(0.25), zoom)
                transitData = data
                transitPills = data.routes.map { it.info }
                transitLines = data.lines
                transitStops = data.stops
                overlayError = null
                refreshLegend()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                transitRetryAt = System.currentTimeMillis() + 5_000
                overlayError = "Couldn't load transit lines: ${e.message ?: e.javaClass.simpleName}. Move the map to retry."
            } finally {
                transitLoading = false
            }
            updateHint()
            if (transitPending) {
                transitPending = false
                ensureTransit()
            }
        }
    }

    private fun refreshLegend() {
        val view = lastViewport?.first ?: return
        val routes = transitData?.routes ?: return
        val lines = TransitRoutes.legend(routes, transitModes, view)
        legend = lines
        if (highlightedLine != null && lines.none { it.key == highlightedLine }) highlightedLine = null
        updateHint()
    }

    // ---------- Bike routes (Overpass, tiled) ----------

    private fun nextBikeBatch(): List<TileId>? {
        val (view, zoom) = lastViewport ?: return null
        if (!bikeVisible || zoom < Tier.Bike.minViewZoom) return null
        if ((retryAfter[Tier.Bike] ?: 0L) > System.currentTimeMillis()) return null
        return Tiles.covering(Tier.Bike, view.expanded(0.1)).filter { it !in loadedTiles }.take(2).ifEmpty { null }
    }

    /**
     * Loads missing bike tiles one request at a time. Requests are never cancelled: Overpass keeps
     * running abandoned queries and limits each client's concurrent queries.
     */
    private fun ensureBike() {
        if (loaderJob?.isActive == true) return
        loaderJob = viewModelScope.launch {
            while (true) {
                if (loadedTiles.size > 40) {
                    // Keep memory bounded: start over once a lot of area has been cached.
                    withContext(storeThread) { bikeStore.clear() }
                    loadedTiles.clear()
                }
                val tiles = nextBikeBatch() ?: break
                bikeLoading = true
                val area = tiles.map(Tiles::bounds).reduce { a, b ->
                    Bounds(minOf(a.south, b.south), minOf(a.west, b.west), maxOf(a.north, b.north), maxOf(a.east, b.east))
                }
                try {
                    val json = Overpass.fetch(Tier.Bike, area)
                    val lines = withContext(storeThread) {
                        bikeStore.merge(json, bike = true)
                        bikeStore.geoJson(null).first
                    }
                    loadedTiles += tiles
                    bikeLines = lines
                    overlayError = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    retryAfter[Tier.Bike] = System.currentTimeMillis() + 20_000
                    overlayError = "Couldn't load bike routes: ${e.message ?: e.javaClass.simpleName}. Move the map to retry."
                }
                updateHint()
            }
            bikeLoading = false
        }
    }
}
