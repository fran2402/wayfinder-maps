package app.wayfinder.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.wayfinder.Downloader
import app.wayfinder.data.Bounds
import app.wayfinder.data.Departure
import app.wayfinder.data.Departures
import app.wayfinder.data.Geocoder
import app.wayfinder.data.Itinerary
import app.wayfinder.data.LatLon
import app.wayfinder.data.LineInfo
import app.wayfinder.data.LoadedLine
import app.wayfinder.data.OfflineTransit
import app.wayfinder.data.Overlays
import app.wayfinder.data.Overpass
import app.wayfinder.data.Place
import app.wayfinder.data.PlaceKind
import app.wayfinder.data.BikeRoutes
import app.wayfinder.data.Router
import app.wayfinder.data.StopRef
import app.wayfinder.data.Tier
import app.wayfinder.data.TileData
import app.wayfinder.data.TileRoute
import app.wayfinder.data.TileId
import app.wayfinder.data.Tiles
import app.wayfinder.data.TransitData
import app.wayfinder.data.TransitMode
import app.wayfinder.data.TransitRoutes
import app.wayfinder.data.TravelMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
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

typealias DownloadState = Downloader.State

class WayfinderViewModel(application: Application) : AndroidViewModel(application) {

    // ---------- Map settings (persisted) ----------
    private val prefs = application.getSharedPreferences("map_settings", android.content.Context.MODE_PRIVATE)
    var showFerries by mutableStateOf(prefs.getBoolean("ferries", false))
        private set
    var showTerrain by mutableStateOf(prefs.getBoolean("terrain", true))
        private set
    var showRelief by mutableStateOf(prefs.getBoolean("relief", true))
        private set
    var settingsOpen by mutableStateOf(false)
        private set

    fun setFerries(on: Boolean) { showFerries = on; prefs.edit().putBoolean("ferries", on).apply() }
    fun setTerrain(on: Boolean) { showTerrain = on; prefs.edit().putBoolean("terrain", on).apply() }
    fun setRelief(on: Boolean) { showRelief = on; prefs.edit().putBoolean("relief", on).apply() }

    /** Lines sharing a street pushed apart side by side when zoomed in very close (off by default: it moves them off the road). */
    var spreadLines by mutableStateOf(prefs.getBoolean("spreadLines", false))
        private set
    fun changeSpreadLines(on: Boolean) {
        spreadLines = on
        prefs.edit().putBoolean("spreadLines", on).apply()
        rebuildTransit()
    }
    /** The layers sheet (it holds what used to be the settings sheet). */
    fun toggleSettings() { settingsOpen = !settingsOpen }

    enum class Page { None, Offline, Settings }
    /** A full-screen page over the map. */
    var page by mutableStateOf(Page.None)
        private set
    fun openPage(p: Page) {
        page = p
        if (p == Page.Offline) refreshStorage()
    }
    fun toggleTerrain() {
        val on = !(showTerrain || showRelief)
        setTerrain(on); setRelief(on)
    }

    /** Transit lines are drawn (and loaded) from this zoom level up. */
    var minLineZoom by mutableStateOf(prefs.getFloat("minLineZoom", 4f))
        private set

    fun changeMinLineZoom(z: Float) {
        minLineZoom = z
        prefs.edit().putFloat("minLineZoom", z).apply()
        linesInZoom = (lastViewport?.second ?: 99.0) >= z
        ensureTransit()
    }

    /** False below [minLineZoom]: every line layer is hidden (a picked line still shows). */
    var linesInZoom by mutableStateOf(true)
        private set

    // ---------- Offline transit data ----------
    private val offlineStore: OfflineTransit = Downloader.store
    var savedRegions by mutableStateOf(offlineStore.saved())
        private set

    var download by mutableStateOf<DownloadState?>(Downloader.state.value)
        private set

    /** Squares saved while browsing (outside any saved region), with their size. */
    var browsing by mutableStateOf(0 to 0L)
        private set

    /** Bytes of all saved lines and bike routes, and free space on the phone. */
    var storage by mutableStateOf(0L to 0L)
        private set

    init {
        viewModelScope.launch { Downloader.state.collect { download = it } }
        viewModelScope.launch { Downloader.arrived.collect { unitArrived(it) } }
        viewModelScope.launch {
            Downloader.finished.collect {
                savedRegions = offlineStore.saved()
                tileCache.clear()
                shownTiles = emptySet()
                ensureTransit()
                refreshStorage()
            }
        }
        refreshStorage()
    }

    fun refreshStorage() {
        viewModelScope.launch {
            browsing = offlineStore.browsingStats()
            storage = offlineStore.totalBytes() to offlineStore.freeBytes()
        }
    }

    fun clearBrowsing() {
        viewModelScope.launch {
            offlineStore.clearBrowsing()
            tileCache.clear()
            shownTiles = emptySet()
            ensureTransit()
            refreshStorage()
        }
    }

    /** The map's visible area, last reported. */
    val viewBounds: Bounds? get() = lastViewport?.first

    fun tileCount(region: OfflineTransit.Region) = offlineStore.unitsOf(region).size

    fun visibleAreaRegion(): OfflineTransit.Region? =
        lastViewport?.first?.let { OfflineTransit.visibleArea(it) }

    /** Starts (or resumes, or for a finished region refreshes) a download that keeps going in the background. */
    fun downloadRegion(region: OfflineTransit.Region) {
        val refresh = savedRegions.any { it.region.id == region.id && it.complete }
        val center = lastViewport?.first?.let { Tiles.lonToX((it.west + it.east) / 2, 8) to Tiles.latToY((it.south + it.north) / 2, 8) }
        Downloader.start(getApplication(), region, refresh, center)
        savedRegions = offlineStore.saved()
    }

    fun cancelDownload() = Downloader.cancel()

    fun deleteRegion(id: String) {
        viewModelScope.launch {
            offlineStore.delete(id)
            savedRegions = offlineStore.saved()
            refreshStorage()
            tileCache.clear()
            shownTiles = emptySet()
            ensureTransit()
        }
    }


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
    var transitModes by mutableStateOf(TransitMode.entries.toSet() - TransitMode.Coach)
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
    /** Every line loaded on the map, for the line diagram's connections. */
    val network: List<LoadedLine> get() = transitData?.routes.orEmpty()

    /** The picked line with its stops, for the line sheet. */
    val highlightedLoaded: LoadedLine?
        get() = if (highlightedLine == null) null else selectedLine
    /** The picked line's details, for the panel. */
    val highlightedInfo: LineInfo?
        get() = highlightedLoaded?.info
    /** Snapshot of the picked line, kept whatever the map loads or how far it is zoomed out. */
    private var selectedLine by mutableStateOf<LoadedLine?>(null)
    /** The picked line's whole route as GeoJSON, drawn at every zoom. */
    var selectedGeo by mutableStateOf<String?>(null)
        private set
    private var selectionJob: Job? = null

    private fun clearSelection() {
        highlightedLine = null
        selectedLine = null
        selectedGeo = null
        selectionJob?.cancel()
    }

    /** Draws what is loaded of [line] at once, then fetches its whole route and frames it. */
    private fun selectWhole(line: LoadedLine) {
        val key = line.info.key
        selectedLine = line
        val cached = tileCache.values.flatMap { t -> t.routes.filter { it.key == key } }
        selectedGeo = TransitRoutes.lineGeoJson(key, cached)
        selectionJob?.cancel()
        selectionJob = viewModelScope.launch {
            // Full resolution: the line's shapes from the saved street tiles under it, growing the
            // area until the whole line is covered.
            val found = ArrayList<TileRoute>()
            val read = HashSet<Pair<Int, Int>>()
            var area = line.bounds
            repeat(4) {
                val z = OfflineTransit.Level.Street.z
                val a = area.expanded(0.05)
                val xs = Tiles.lonToX(a.west, z)..Tiles.lonToX(a.east, z)
                val ys = Tiles.latToY(a.north, z)..Tiles.latToY(a.south, z)
                val todo = xs.flatMap { x -> ys.map { y -> x to y } }
                    .filter { it !in read && offlineStore.has(OfflineTransit.Level.Street, it.first, it.second) }
                if (todo.isEmpty() || read.size + todo.size > 400) return@repeat
                read += todo
                todo.chunked(8).forEach { chunk ->
                    chunk.map { (x, y) -> async(Dispatchers.IO) { offlineStore.read(OfflineTransit.Level.Street, x, y) } }
                        .awaitAll().filterNotNull().forEach { t -> found += t.routes.filter { it.key == key } }
                }
                val grown = found.fold(area) { acc, r ->
                    Bounds(minOf(acc.south, r.bounds.south), minOf(acc.west, r.bounds.west), maxOf(acc.north, r.bounds.north), maxOf(acc.east, r.bounds.east))
                }
                if (grown == area) return@repeat
                area = grown
            }
            var parts: List<TileRoute> = found
            if (parts.isEmpty()) {
                // Not saved: ask Transitous for the route. Its route numbers change whenever it
                // reloads timetables, so only routes that really are this line are kept.
                val ids = cached.flatMap { it.routeIdx.toList() }.distinct().take(8)
                parts = ids.mapNotNull { id ->
                    try { TransitRoutes.loadRouteDetails(id) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                }.flatMap { it.routes }.filter { it.key == key }
            }
            if (parts.isEmpty() || highlightedLine != key) return@launch
            val whole = withContext(storeThread) {
                TransitRoutes.TileBuilder().apply { parts.forEach { add(it) } }.build().routes
            }
            val r = whole.first()
            selectedGeo = withContext(storeThread) { TransitRoutes.lineGeoJson(key, whole) }
            val stops = parts.maxByOrNull { it.stops.size }?.stops.orEmpty()
            selectedLine = line.copy(bounds = r.bounds, stops = if (stops.size > line.stops.size) stops else line.stops)
            focusBounds = r.bounds
            focusRequest++
        }
    }

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

    private var transitData: TransitData? = null
    private var transitJob: Job? = null
    private var rebuildJob: Job? = null
    private var retryJob: Job? = null

    /** Transit map tiles: fetched one at a time, cached (LRU), merged for the view. */
    /** [offline] tiles come from downloaded data (a fixed level), others from the network. */
    /** [lod] is the zoom a saved tile was simplified for on loading (zoomed-out views need less). */
    private data class TKey(val z: Int, val x: Int, val y: Int, val offline: Boolean = false, val lod: Int = 0)
    private val tileCache = object : LinkedHashMap<TKey, TileData>(256, 0.75f, true) {
        // Always room for every tile in view (continent views can need hundreds of small ones).
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TKey, TileData>?) = size > maxOf(90, neededTiles.size + 12)
    }
    private val tileFailedAt = HashMap<TKey, Long>()
    private var neededTiles: List<TKey> = emptyList()
    private var shownTiles: Set<TKey> = emptySet()

    /** Upcoming departures for the picked line at one of its stops. */
    data class DepartureBoard(
        val lineKey: String,
        val stop: StopRef,
        val loading: Boolean,
        val items: List<Departure>,
        val error: String?,
    )
    var departures by mutableStateOf<DepartureBoard?>(null)
        private set
    private var departuresJob: Job? = null
    /** Bike routes loaded so far: online tiles and saved squares, in the compact line form. */
    private val bikeTiles = LinkedHashMap<Any, TileData>()
    private val loadedTiles = HashSet<TileId>()
    /** Download squares whose saved bike routes are in [bikeTiles]. */
    private val loadedBikeUnits = HashSet<Pair<Int, Int>>()

    private suspend fun redrawBikes() {
        val tiles = bikeTiles.values.toList()
        bikeLines = withContext(storeThread) { TransitRoutes.merge(tiles, withStops = false).lines }
    }
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
        if (!showTransit) clearSelection()
        overlayError = null
        tileFailedAt.clear()
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
        val all = TransitMode.entries.toSet() - TransitMode.Coach
        transitModes = when {
            transitModes == all -> setOf(mode)
            mode in transitModes -> (transitModes - mode).ifEmpty { all }
            else -> transitModes + mode
        }
        refreshLegend()
        rebuildTransit() // hidden types are left out of the drawing (and of shared-track merging)
    }

    fun highlight(key: String?) {
        if (key == null || highlightedLine == key) { clearSelection(); departures = null; rebuildTransit(); return }
        highlightedLine = key
        rebuildTransit()
        departures = null
        val picked = key
        transitData?.routes?.firstOrNull { it.info.key == picked }?.let { line ->
            selectWhole(line)
            focusBounds = line.bounds
            focusRequest++
            // Show departures from the stop nearest to the user (or the map centre).
            val ref = myLocation ?: lastViewport?.first?.let { LatLon((it.south + it.north) / 2, (it.west + it.east) / 2) }
            val nearest = if (ref == null) line.stops.firstOrNull() else line.stops.minByOrNull {
                val dLat = it.lat - ref.lat; val dLon = (it.lon - ref.lon) * Math.cos(Math.toRadians(ref.lat))
                dLat * dLat + dLon * dLon
            }
            nearest?.let { showDepartures(it) }
        }
    }

    /** Opens a line picked on the map (never toggles it off). */
    fun openLine(key: String) {
        if (highlightedLine != key) highlight(key)
    }

    fun showDepartures(stop: StopRef) {
        val line = highlightedLoaded ?: return
        departuresJob?.cancel()
        val board = DepartureBoard(line.info.key, stop, loading = true, items = emptyList(), error = null)
        departures = board
        if (stop.id.isBlank()) {
            departures = board.copy(loading = false, error = "No timetable for this stop")
            return
        }
        departuresJob = viewModelScope.launch {
            departures = try {
                val now = System.currentTimeMillis() - 60_000
                val items = Departures.load(stop.id, line.info.ref).filter { it.timeMs >= now }.take(12)
                board.copy(loading = false, items = items, error = if (items.isEmpty()) "No upcoming departures of this line here" else null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                board.copy(loading = false, error = "Couldn't load departures")
            }
        }
    }

    /** Re-evaluates overlays after the trip or travel mode changed. */
    private fun overlaysChanged() {
        if (!transitAvailable) clearSelection()
        ensureTransit()
        ensureBike()
        updateHint()
    }

    /** Zoom bands that change what a rebuild produces (stops, line numbers, side-by-side). */
    private var detailBand = -1

    fun onViewport(bounds: Bounds, zoom: Double) {
        lastViewport = bounds to zoom
        linesInZoom = zoom >= minLineZoom
        // Overlap removal depends on the zoom, so redraw on each whole zoom step below 13.
        val band = when { zoom >= SPREAD_ZOOM -> 30; zoom >= 13.5 -> 20; zoom >= 13.0 -> 15; zoom >= 11.0 -> 10 + zoom.toInt(); else -> zoom.toInt() }
        if (band != detailBand) {
            detailBand = band
            if (shownTiles.isNotEmpty()) rebuildTransit()
        }
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
            transitVisible && zoom != null && zoom >= minLineZoom && zoom < AUTO_SAVE_ZOOM && neededTiles.isEmpty() ->
                "Zoom in to load lines here"
            else -> null
        }
    }

    // ---------- Public transport (Transitous) ----------

    /** Tiles one zoom level coarser than the view, so a few requests cover the screen. */
    private fun tilesFor(area: Bounds, zoom: Double): List<TKey> {
        val z = (kotlin.math.floor(zoom).toInt() - 1).coerceIn(2, 13)
        val x0 = Tiles.lonToX(area.west, z); val x1 = Tiles.lonToX(area.east, z)
        val y0 = Tiles.latToY(area.north, z); val y1 = Tiles.latToY(area.south, z)
        val cx = (x0 + x1) / 2.0; val cy = (y0 + y1) / 2.0
        val out = ArrayList<TKey>()
        for (x in x0..x1) for (y in y0..y1) out += TKey(z, x, y)
        return out.sortedBy { (it.x - cx) * (it.x - cx) + (it.y - cy) * (it.y - cy) }.take(12)
    }

    // Lines always come from the phone. Wherever the map is looked at (zoomed in past
    // [AUTO_SAVE_ZOOM]), missing download squares are fetched once in the background and kept.

    private fun levelFor(zoom: Double) = when {
        zoom < 8.0 -> OfflineTransit.Level.Rail
        zoom < 11.5 -> OfflineTransit.Level.City
        else -> OfflineTransit.Level.Street
    }

    /** Lines sharing a street are spread side by side only this close in (it costs work). */
    private val SPREAD_ZOOM = 15.5

    /** Below this zoom a view spans too many squares to save on the fly. */
    private val AUTO_SAVE_ZOOM = 7.0

    private val autoInFlight = HashSet<Pair<Int, Int>>()
    private val autoFailedAt = HashMap<Pair<Int, Int>, Long>()
    private var wantedUnits: List<Pair<Int, Int>> = emptyList()

    /** Forgets cached tiles that a newly saved square changes, and redraws if it is in view. */
    private fun unitArrived(u: Pair<Int, Int>) {
        tileCache.keys.removeAll { k -> offlineStore.unitOf(k.z, k.x, k.y).let { (ux, uy) ->
            if (k.z >= OfflineTransit.Level.City.z) ux == u.first && uy == u.second
            else (u.first shr (OfflineTransit.Level.City.z - k.z)) == k.x && (u.second shr (OfflineTransit.Level.City.z - k.z)) == k.y
        } }
        tileFailedAt.clear()
        ensureTransit()
    }

    private fun pumpAutoSave() {
        val now = System.currentTimeMillis()
        while (autoInFlight.size < 6) {
            val u = wantedUnits.firstOrNull {
                it !in autoInFlight && !offlineStore.hasLines(it.first, it.second) &&
                    (autoFailedAt[it]?.let { t -> now - t > 30_000 } ?: true)
            } ?: break
            autoInFlight += u
            transitLoading = true
            viewModelScope.launch {
                try {
                    offlineStore.downloadUnit(u.first, u.second) { }
                    offlineStore.rebuildRail(u.first shr 2, u.second shr 2)
                    autoFailedAt.remove(u)
                    overlayError = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    autoFailedAt[u] = System.currentTimeMillis()
                    overlayError = "Couldn't load lines here (${e.message ?: e.javaClass.simpleName}). Retrying…"
                    retryJob?.cancel()
                    retryJob = viewModelScope.launch { delay(31_000); ensureTransit() }
                } finally {
                    autoInFlight -= u
                    transitLoading = autoInFlight.isNotEmpty()
                    updateHint()
                    unitArrived(u)
                }
            }
        }
    }

    /** Reads one saved tile at the detail its key asks for. */
    private suspend fun loadSaved(next: TKey): TileData? {
        val lvl = offlineStore.levelForZ(next.z) ?: return null
        val t = offlineStore.read(lvl, next.x, next.y) ?: return null
        return when (lvl) {
            OfflineTransit.Level.Rail -> {
                // Far out, only the rail networks (trains, metro, trams) and ferries.
                val far = next.lod < 9
                val kept = if (far) TileData(t.routes.filter {
                    it.mode == TransitMode.Train || it.mode == TransitMode.Metro || it.mode == TransitMode.Tram || it.mode == TransitMode.Ferry
                }, t.zoomFiltered) else t
                TransitRoutes.reduce(kept, next.lod, if (far) 400 else 1_500)
            }
            OfflineTransit.Level.City -> TransitRoutes.reduce(t, next.lod, 4_000)
            OfflineTransit.Level.Street -> t
        }
    }

    /** Shows the saved tiles for the view and saves any missing squares around it. */
    private fun ensureTransit() {
        if (!transitVisible) return
        val (view, zoom) = lastViewport ?: return
        if (zoom < minLineZoom) return
        val area = view.expanded(0.15)
        val level = levelFor(zoom)
        val x0 = Tiles.lonToX(area.west, level.z); val x1 = Tiles.lonToX(area.east, level.z)
        val y0 = Tiles.latToY(area.north, level.z); val y1 = Tiles.latToY(area.south, level.z)
        val cx = (x0 + x1) / 2.0; val cy = (y0 + y1) / 2.0
        // Detail to load saved tiles at: continent views get coarse shapes and long-distance modes only.
        val lod = when (level) {
            OfflineTransit.Level.Rail -> when { zoom < 4.5 -> 5; zoom < 5.5 -> 6; zoom < 6.5 -> 7; else -> 9 }
            // Simplified for a zoom two levels closer than shown: within a fraction of a pixel,
            // so lines keep following their roads and tracks.
            OfflineTransit.Level.City -> if (zoom < 9.5) 11 else 12
            OfflineTransit.Level.Street -> 14
        }
        val keys = ArrayList<TKey>()
        for (x in x0..x1) for (y in y0..y1) if (offlineStore.has(level, x, y)) keys += TKey(level.z, x, y, offline = true, lod = lod)
        // Continent views may need many tiles, but each holds only a light train network.
        val cap = if (level == OfflineTransit.Level.Rail) 500 else 80
        neededTiles = keys.sortedBy { (it.x - cx) * (it.x - cx) + (it.y - cy) * (it.y - cy) }.take(cap)

        val ux0 = Tiles.lonToX(area.west, 8); val ux1 = Tiles.lonToX(area.east, 8)
        val uy0 = Tiles.latToY(area.north, 8); val uy1 = Tiles.latToY(area.south, 8)
        val ucx = (ux0 + ux1) / 2.0; val ucy = (uy0 + uy1) / 2.0
        wantedUnits = if (zoom < AUTO_SAVE_ZOOM || (ux1 - ux0 + 1) * (uy1 - uy0 + 1) > 40) emptyList() else
            (ux0..ux1).flatMap { x -> (uy0..uy1).map { y -> x to y } }
                .sortedBy { (it.first - ucx) * (it.first - ucx) + (it.second - ucy) * (it.second - ucy) }
        pumpAutoSave()

        val available = neededTiles.filter { it in tileCache }.toSet()
        // Keep the previous lines on screen until at least one tile of the new view is ready.
        if (available != shownTiles && (available.isNotEmpty() || neededTiles.isEmpty())) rebuildTransit()
        if (transitJob?.isActive == true) return
        transitJob = viewModelScope.launch {
            while (true) {
                // Several tiles at once: reading and simplifying run in parallel off the main thread.
                val batch = neededTiles.filter { it !in tileCache && it !in tileFailedAt }.take(6)
                if (batch.isEmpty()) break
                val loaded = batch.map { next -> async(Dispatchers.Default) { next to loadSaved(next) } }.awaitAll()
                for ((next, data) in loaded) {
                    if (data != null) tileCache[next] = data else tileFailedAt[next] = System.currentTimeMillis()
                }
                if (loaded.any { it.second != null && it.first in neededTiles }) rebuildTransit()
            }
            updateHint()
        }
    }

    /** Coalesces bursts (several tiles arriving, camera moves) into one merge + map update. */
    private fun rebuildTransit() {
        rebuildJob?.cancel()
        rebuildJob = viewModelScope.launch {
            delay(250)
            val keys = neededTiles
            val tiles = keys.mapNotNull { tileCache[it] }
            shownTiles = keys.filter { it in tileCache }.toSet()
            val zoom = lastViewport?.second ?: 12.0
            // Stops and line badges only show close in; leave them out further away.
            val data = withContext(storeThread) { TransitRoutes.merge(
                    tiles, withStops = zoom >= 11.0, spread = spreadLines && zoom >= SPREAD_ZOOM,
                    // Below street level, lines sharing a track are drawn once.
                    // Not while one line is picked: shared stretches would show as holes in it.
                    overlapZoom = if (zoom < 13.0 && highlightedLine == null) zoom else null,
                    modes = transitModes,
                ) }
            transitData = data
            transitPills = if (zoom >= 13.5) data.routes.map { it.info } else emptyList()
            transitLines = data.lines
            transitStops = data.stops
            refreshLegend()
            updateHint()
        }
    }

    private fun refreshLegend() {
        val view = lastViewport?.first ?: return
        val routes = transitData?.routes ?: return
        val lines = TransitRoutes.legend(routes, transitModes, view)
        legend = lines
        updateHint()
    }

    // ---------- Bike routes (Overpass, tiled) ----------

    private fun nextBikeBatch(): List<TileId>? {
        val (view, zoom) = lastViewport ?: return null
        if (!bikeVisible || zoom < Tier.Bike.minViewZoom) return null
        if ((retryAfter[Tier.Bike] ?: 0L) > System.currentTimeMillis()) return null
        return Tiles.covering(Tier.Bike, view.expanded(0.1))
            .filter { it !in loadedTiles && bikeUnitOf(it) !in loadedBikeUnits }.take(2).ifEmpty { null }
    }

    /**
     * Loads missing bike tiles one request at a time. Requests are never cancelled: Overpass keeps
     * running abandoned queries and limits each client's concurrent queries.
     */
    private fun bikeUnitOf(t: TileId): Pair<Int, Int> {
        val shift = t.tier.tileZoom - OfflineTransit.Level.City.z
        return (t.x shr shift) to (t.y shr shift)
    }

    private fun ensureBike() {
        if (loaderJob?.isActive == true) return
        loaderJob = viewModelScope.launch {
            while (true) {
                if (loadedTiles.size > 40 || loadedBikeUnits.size > 8) {
                    // Keep memory bounded: start over once a lot of area has been cached.
                    bikeTiles.clear()
                    loadedTiles.clear()
                    loadedBikeUnits.clear()
                }
                // Downloaded squares first: straight from disk, no Overpass.
                val view = lastViewport
                if (bikeVisible && view != null && view.second >= Tier.Bike.minViewZoom) {
                    val units = Tiles.covering(Tier.Bike, view.first.expanded(0.1)).map(::bikeUnitOf).distinct()
                        .filter { it !in loadedBikeUnits && offlineStore.hasBike(it.first, it.second) }
                    if (units.isNotEmpty()) {
                        for (u in units) {
                            loadedBikeUnits += u
                            offlineStore.readBike(u.first, u.second)?.let { bikeTiles[u] = it }
                        }
                        redrawBikes()
                        continue
                    }
                }
                val tiles = nextBikeBatch() ?: break
                bikeLoading = true
                val area = tiles.map(Tiles::bounds).reduce { a, b ->
                    Bounds(minOf(a.south, b.south), minOf(a.west, b.west), maxOf(a.north, b.north), maxOf(a.east, b.east))
                }
                try {
                    bikeTiles[tiles.first()] = BikeRoutes.fetch(area, large = false)
                    loadedTiles += tiles
                    redrawBikes()
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
