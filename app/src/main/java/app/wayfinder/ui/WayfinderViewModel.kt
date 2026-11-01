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
import app.wayfinder.data.OverlayData
import app.wayfinder.data.OverlayKind
import app.wayfinder.data.Overlays
import app.wayfinder.data.Place
import app.wayfinder.data.PlaceKind
import app.wayfinder.data.Router
import app.wayfinder.data.TravelMode
import kotlinx.coroutines.CancellationException
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
    var transit by mutableStateOf<OverlayData?>(null)
        private set
    var bike by mutableStateOf<OverlayData?>(null)
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
    private var transitJob: Job? = null
    private var bikeJob: Job? = null
    private var lastViewport: Pair<Bounds, Double>? = null

    /** Train mode narrows the public-transport overlay to rail. */
    val transitKind: OverlayKind
        get() = if (planning && mode == TravelMode.Train) OverlayKind.Rail else OverlayKind.Transit

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
        val kindBefore = transitKind
        mode = m
        plan()
        if (showTransit && transitKind != kindBefore) reloadTransit(force = true)
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
        if (showTransit) reloadTransit(force = false) else {
            transitJob?.cancel(); transitLoading = false; highlightedLine = null
        }
        updateHint()
    }

    fun toggleBike() {
        showBike = !showBike
        if (showBike) reloadBike(force = false) else {
            bikeJob?.cancel(); bikeLoading = false
        }
        updateHint()
    }

    fun highlight(key: String?) {
        highlightedLine = if (highlightedLine == key) null else key
    }

    fun onViewport(bounds: Bounds, zoom: Double) {
        lastViewport = bounds to zoom
        if (showTransit) reloadTransit(force = false)
        if (showBike) reloadBike(force = false)
        updateHint()
    }

    private fun updateHint(error: String? = null) {
        val zoom = lastViewport?.second
        overlayHint = when {
            error != null -> error
            !showTransit && !showBike -> null
            zoom != null && zoom < Overlays.MIN_ZOOM ->
                if (showTransit) "Zoom in to see every line and its number" else "Zoom in to see bike routes"
            else -> null
        }
    }

    private fun reloadTransit(force: Boolean) {
        val kind = transitKind
        transitJob = reload(force, transit, kind, transitJob,
            setLoading = { transitLoading = it },
            setData = { data ->
                if (highlightedLine != null && data.legend.none { it.key == highlightedLine }) highlightedLine = null
                transit = data
            },
            failure = "Couldn't load public transport lines")
    }

    private fun reloadBike(force: Boolean) {
        bikeJob = reload(force, bike, OverlayKind.Bike, bikeJob,
            setLoading = { bikeLoading = it },
            setData = { bike = it },
            failure = "Couldn't load bike routes")
    }

    private fun reload(
        force: Boolean,
        current: OverlayData?,
        kind: OverlayKind,
        running: Job?,
        setLoading: (Boolean) -> Unit,
        setData: (OverlayData) -> Unit,
        failure: String,
    ): Job? {
        val (bounds, zoom) = lastViewport ?: return running
        if (zoom < Overlays.MIN_ZOOM) return running
        val detailed = zoom >= Overlays.DETAIL_ZOOM
        if (!force && current != null && current.kind == kind && current.area.contains(bounds) &&
            (current.detailed || !detailed)
        ) return running
        running?.cancel()
        var self: Job? = null
        self = viewModelScope.launch {
            delay(350)
            setLoading(true)
            try {
                setData(Overlays.load(kind, bounds.expanded(0.3), detailed))
                updateHint()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateHint("$failure (${e.message?.take(60) ?: e.javaClass.simpleName})")
            } finally {
                if (coroutineContext[Job] === self) setLoading(false)
            }
        }
        return self
    }
}
