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
import app.wayfinder.data.TransitMode
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

enum class SearchTarget { From, To }

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

    private val transitStore = RouteStore()
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
        }
        closeSearch()
        plan()
    }

    fun dropPin(pos: LatLon) {
        val pin = Place("Dropped pin", null, pos, PlaceKind.DroppedPin)
        destination = pin
        plan()
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
        itineraries = emptyList()
        error = null
        loading = false
        selected = 0
    }

    fun selectMode(m: TravelMode) {
        if (m == mode) return
        mode = m
        plan()
    }

    fun select(index: Int) {
        if (index == selected) return
        selected = index
        fitRequest++
    }

    // ---------- Planning ----------

    fun plan() {
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
        afterOverlayChange()
    }

    fun toggleBike() {
        showBike = !showBike
        afterOverlayChange()
    }

    fun toggleMode(mode: TransitMode) {
        transitModes = if (mode in transitModes) transitModes - mode else transitModes + mode
        refreshTransit()
        ensureLoading()
    }

    fun highlight(key: String?) {
        highlightedLine = if (highlightedLine == key) null else key
    }

    fun onViewport(bounds: Bounds, zoom: Double) {
        lastViewport = bounds to zoom
        if (showTransit) refreshLegend()
        ensureLoading()
        updateHint()
    }

    private fun afterOverlayChange() {
        overlayError = null
        retryAfter.clear()
        ensureLoading()
        updateHint()
    }

    private fun updateHint() {
        val zoom = lastViewport?.second
        overlayHint = when {
            !showTransit && !showBike -> null
            overlayError != null -> overlayError
            zoom != null && showTransit && zoom < Tier.Rail.minViewZoom -> "Zoom in to see every line"
            zoom != null && showBike && !showTransit && zoom < Tier.Bike.minViewZoom -> "Zoom in to see bike routes"
            zoom != null && showTransit && TransitMode.Bus in transitModes && zoom < Tier.Bus.minViewZoom &&
                zoom >= Tier.Rail.minViewZoom -> "Zoom in further for bus lines"
            else -> null
        }
    }

    private fun tiersNeeded(zoom: Double): List<Tier> = buildList {
        if (showTransit) {
            add(Tier.Rail)
            if (TransitMode.Bus in transitModes) add(Tier.Bus)
        }
        if (showBike) add(Tier.Bike)
    }.filter { zoom >= it.minViewZoom }

    private fun nextBatch(): Pair<Tier, List<TileId>>? {
        val (view, zoom) = lastViewport ?: return null
        val now = System.currentTimeMillis()
        for (tier in tiersNeeded(zoom)) {
            if ((retryAfter[tier] ?: 0L) > now) continue
            val missing = Tiles.covering(tier, view.expanded(0.1)).filter { it !in loadedTiles }
            if (missing.isNotEmpty()) return tier to missing.take(if (tier == Tier.Bus) 2 else 4)
        }
        return null
    }

    /**
     * Loads missing tiles one request at a time. Requests are never cancelled: Overpass keeps
     * running abandoned queries and limits each client's concurrent queries, so cancelling and
     * re-requesting on every map move is what kept the old loader spinning forever.
     */
    private fun ensureLoading() {
        if (loaderJob?.isActive == true) return
        loaderJob = viewModelScope.launch {
            while (true) {
                val (tier, tiles) = nextBatch() ?: break
                if (tier == Tier.Bike) bikeLoading = true else transitLoading = true
                val area = tiles.map(Tiles::bounds).reduce { a, b ->
                    Bounds(minOf(a.south, b.south), minOf(a.west, b.west), maxOf(a.north, b.north), maxOf(a.east, b.east))
                }
                try {
                    val json = Overpass.fetch(tier, area)
                    withContext(storeThread) {
                        (if (tier == Tier.Bike) bikeStore else transitStore).merge(json, bike = tier == Tier.Bike)
                    }
                    loadedTiles += tiles
                    overlayError = null
                    if (tier == Tier.Bike) refreshBike() else refreshTransit()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    retryAfter[tier] = System.currentTimeMillis() + 20_000
                    val what = if (tier == Tier.Bike) "bike routes" else "transit lines"
                    overlayError = "Couldn't load $what: ${e.message ?: e.javaClass.simpleName}. Move the map to retry."
                }
                if (tier == Tier.Bike) bikeLoading = false else transitLoading = false
                updateHint()
            }
            transitLoading = false
            bikeLoading = false
        }
    }

    private fun refreshTransit() {
        val modes = transitModes
        viewModelScope.launch {
            val (lines, stops) = withContext(storeThread) { transitStore.geoJson(modes) }
            transitLines = lines
            transitStops = stops
            refreshLegend()
        }
    }

    private fun refreshBike() {
        viewModelScope.launch {
            bikeLines = withContext(storeThread) { bikeStore.geoJson(null).first }
        }
    }

    private fun refreshLegend() {
        val view = lastViewport?.first ?: return
        val modes = transitModes
        legendJob?.cancel()
        legendJob = viewModelScope.launch {
            val lines = withContext(storeThread) { transitStore.legend(modes, view) }
            legend = lines
            if (highlightedLine != null && lines.none { it.key == highlightedLine }) highlightedLine = null
        }
    }
}
