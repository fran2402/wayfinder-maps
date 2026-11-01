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
import app.wayfinder.data.NetworkData
import app.wayfinder.data.NetworkScope
import app.wayfinder.data.Place
import app.wayfinder.data.PlaceKind
import app.wayfinder.data.Router
import app.wayfinder.data.TransitNetwork
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

    // ---------- Network ----------
    var showNetwork by mutableStateOf(false)
        private set
    var network by mutableStateOf<NetworkData?>(null)
        private set
    var networkLoading by mutableStateOf(false)
        private set
    var networkHint by mutableStateOf<String?>(null)
        private set
    private var networkJob: Job? = null
    private var lastViewport: Pair<Bounds, Double>? = null

    val networkScope: NetworkScope
        get() = if (planning && mode == TravelMode.Train) NetworkScope.Rail else NetworkScope.All

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
        val scopeBefore = networkScope
        mode = m
        plan()
        if (showNetwork && networkScope != scopeBefore) reloadNetwork(force = true)
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

    // ---------- Network ----------

    fun toggleNetwork() {
        showNetwork = !showNetwork
        if (showNetwork) reloadNetwork(force = false) else {
            networkJob?.cancel(); networkLoading = false; networkHint = null
        }
    }

    fun onViewport(bounds: Bounds, zoom: Double) {
        lastViewport = bounds to zoom
        if (showNetwork) reloadNetwork(force = false)
    }

    private fun reloadNetwork(force: Boolean) {
        val (bounds, zoom) = lastViewport ?: return
        if (zoom < TransitNetwork.MIN_ZOOM) {
            networkHint = "Zoom in to see every line"
            return
        }
        networkHint = null
        val scope = networkScope
        val includeBus = scope == NetworkScope.All && zoom >= TransitNetwork.BUS_ZOOM
        val current = network
        if (!force && current != null && current.scope == scope && current.area.contains(bounds) &&
            (current.includesBus || !includeBus)
        ) return
        networkJob?.cancel()
        networkJob = viewModelScope.launch {
            delay(450)
            networkLoading = true
            try {
                network = TransitNetwork.load(bounds.expanded(0.4), scope, includeBus)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                networkHint = "Couldn't load the transit network"
            } finally {
                if (networkJob === coroutineContext[Job]) networkLoading = false
            }
        }
    }
}
