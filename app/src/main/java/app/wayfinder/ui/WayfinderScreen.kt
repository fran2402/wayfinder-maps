@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.wayfinder.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.wayfinder.data.LatLon
import app.wayfinder.data.TravelMode
import app.wayfinder.map.MapController
import app.wayfinder.map.MapPalette
import app.wayfinder.map.RouteDrawing
import app.wayfinder.ui.components.LineSheet
import app.wayfinder.ui.components.LinesSheet
import app.wayfinder.ui.components.MapHost
import app.wayfinder.ui.components.MultiTripSheet
import app.wayfinder.ui.components.PlaceSheet
import app.wayfinder.ui.components.humanizeCategory
import app.wayfinder.data.Place
import app.wayfinder.data.PlaceKind
import app.wayfinder.ui.components.MapToolbar
import app.wayfinder.ui.components.ModeSelector
import app.wayfinder.ui.components.RouteSheet
import app.wayfinder.ui.components.SearchOverlay
import app.wayfinder.ui.components.SearchPill
import app.wayfinder.ui.components.SettingsSheet
import app.wayfinder.ui.components.TripCard
import kotlin.math.roundToInt

/** What the bottom sheet is showing. */
private enum class SheetMode { None, Place, Settings, Trip, Lines, Line }

private fun peekFor(mode: SheetMode): Dp = when (mode) {
    SheetMode.Trip -> 440.dp
    SheetMode.Place -> 260.dp
    SheetMode.Settings -> 520.dp
    SheetMode.Lines -> 400.dp
    SheetMode.Line -> 380.dp
    SheetMode.None -> 0.dp
}

/**
 * Bottom-first layout: search (or the trip) on top, everything else in one bottom sheet,
 * and a floating toolbar + location FAB riding just above the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WayfinderScreen(
    vm: WayfinderViewModel,
    requestLocationPermission: () -> Unit,
    requestNotificationPermission: () -> Unit = {},
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val dark = isSystemInDarkTheme()
    val scheme = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme

    val palette = remember(dark, scheme) {
        fun hex(c: Color) = MapController.hex(c.toArgb())
        if (dark) {
            MapPalette.dark(hex(scheme.primary), hex(scheme.primaryContainer), hex(scheme.tertiary), hex(scheme.onSurfaceVariant))
        } else {
            MapPalette.light(hex(scheme.primary), hex(scheme.primaryContainer), hex(scheme.tertiary), hex(scheme.onSurfaceVariant))
        }
    }
    val controller = remember { MapController(context.applicationContext) }
    var mapReady by remember { mutableStateOf(false) }

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val picked = vm.highlightedLoaded
    val sheetMode = when {
        vm.tappedPlace != null -> SheetMode.Place
        vm.settingsOpen && !vm.planning -> SheetMode.Settings
        vm.planning -> SheetMode.Trip
        vm.transitVisible && picked != null -> SheetMode.Line
        vm.transitVisible -> SheetMode.Lines
        else -> SheetMode.None
    }
    val peek = peekFor(sheetMode)

    // A sheet that is showing something can be pulled down to its peek, never off screen.
    val currentMode by androidx.compose.runtime.rememberUpdatedState(sheetMode)
    val sheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.Hidden,
        skipHiddenState = false,
        confirmValueChange = { it != SheetValue.Hidden || currentMode == SheetMode.None },
    )
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)
    LaunchedEffect(sheetMode) {
        if (sheetMode == SheetMode.None) sheetState.hide() else sheetState.partialExpand()
    }

    // ---------- Keep the map in sync with state ----------
    LaunchedEffect(vm.hasLocationPermission, mapReady) {
        if (vm.hasLocationPermission && mapReady) controller.enableLocation()
    }
    LaunchedEffect(vm.transitVisible, vm.linesInZoom, mapReady) { controller.setNetworkVisible(vm.transitVisible && vm.linesInZoom) }
    LaunchedEffect(vm.selectedGeo, mapReady) { controller.setSelectedLine(vm.selectedGeo) }
    LaunchedEffect(vm.bikeVisible, mapReady) { controller.setBikeVisible(vm.bikeVisible) }
    LaunchedEffect(vm.minLineZoom, mapReady) { controller.setLinesMinZoom(vm.minLineZoom) }
    LaunchedEffect(vm.showFerries, vm.showTerrain, vm.showRelief, mapReady) {
        controller.setBaseLayers(MapController.BaseLayers(vm.showFerries, vm.showTerrain, vm.showRelief))
    }
    LaunchedEffect(vm.transitLines, vm.transitStops, mapReady) {
        controller.setTransit(vm.transitLines, vm.transitStops, vm.transitPills)
    }
    LaunchedEffect(vm.bikeLines, mapReady) { controller.setBike(vm.bikeLines) }
    LaunchedEffect(vm.transitModes, mapReady) { controller.setTransitModes(vm.transitModes) }
    LaunchedEffect(vm.highlightedLine, vm.transitLines, mapReady) { controller.highlightLine(vm.highlightedLine) }

    fun syncViewport() {
        val bounds = controller.visibleBounds() ?: return
        vm.onViewport(bounds, controller.zoom())
    }

    // Frame a line when it is picked.
    LaunchedEffect(vm.focusRequest) {
        val b = vm.focusBounds ?: return@LaunchedEffect
        with(density) {
            controller.fit(
                listOf(LatLon(b.south, b.west), LatLon(b.north, b.east)),
                left = 40.dp.roundToPx(),
                top = (statusTop + 96.dp).roundToPx(),
                right = 40.dp.roundToPx(),
                bottom = (peekFor(SheetMode.Line) + navBottom + 24.dp).roundToPx(),
            )
        }
    }
    LaunchedEffect(vm.tappedPlace, mapReady) { controller.setSelectedPlace(vm.tappedPlace?.place?.pos) }
    LaunchedEffect(vm.itineraries, vm.selected, vm.segments, vm.stops, vm.origin, vm.destination, vm.myLocation, palette, mapReady) {
        val dest = vm.destination
        val multi = vm.multiStop
        controller.setRoute(
            if (dest == null) null else RouteDrawing(
                itineraries = if (multi) listOfNotNull(vm.combined) else vm.itineraries,
                selected = if (multi) 0 else vm.selected,
                stops = vm.stops.map { it.pos },
                origin = vm.origin?.pos ?: vm.myLocation.takeIf { vm.origin == null && (vm.itineraries.isNotEmpty() || multi) },
                destination = dest.pos,
                accent = palette.accent,
                walk = palette.walk,
            )
        )
    }
    LaunchedEffect(vm.fitRequest, mapReady) {
        if (!mapReady || !vm.planning) return@LaunchedEffect
        val points = (if (vm.multiStop) vm.combined else vm.itineraries.getOrNull(vm.selected))
            ?.geometry?.takeIf { it.size >= 2 }
            ?: (listOfNotNull(vm.origin?.pos ?: vm.myLocation) + vm.stops.map { it.pos } + listOfNotNull(vm.destination?.pos))
        with(density) {
            controller.fit(
                points,
                left = 48.dp.roundToPx(),
                top = (statusTop + 140.dp).roundToPx(),
                right = 48.dp.roundToPx(),
                bottom = (peekFor(SheetMode.Trip) + navBottom + 24.dp).roundToPx(),
            )
        }
    }
    var centredOnUser by remember { mutableStateOf(false) }
    LaunchedEffect(vm.myLocation, mapReady) {
        val loc = vm.myLocation ?: return@LaunchedEffect
        if (mapReady && !centredOnUser && !vm.planning) {
            centredOnUser = true
            controller.flyTo(loc, 14.5)
        }
    }

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = if (sheetMode == SheetMode.None) 0.dp else peek + navBottom,
        sheetShape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        sheetShadowElevation = 8.dp,
        sheetSwipeEnabled = sheetMode != SheetMode.None,
        sheetContent = {
            Box(Modifier.navigationBarsPadding()) {
                when (sheetMode) {
                    SheetMode.Place -> vm.tappedPlace?.let { detail ->
                        PlaceSheet(
                            detail = detail,
                            planning = vm.planning,
                            onDirections = { vm.directionsTo(detail.place) },
                            onAddStop = { vm.addStopFromPlace(detail.place) },
                            onClose = vm::closePlace,
                        )
                    }
                    SheetMode.Settings -> SettingsSheet(
                        ferries = vm.showFerries,
                        terrain = vm.showTerrain,
                        relief = vm.showRelief,
                        onFerries = vm::setFerries,
                        onTerrain = vm::setTerrain,
                        onRelief = vm::setRelief,
                        minLineZoom = vm.minLineZoom,
                        onMinLineZoom = vm::changeMinLineZoom,
                        presets = app.wayfinder.data.OfflineTransit.presets,
                        saved = vm.savedRegions,
                        download = vm.download,
                        tileCount = vm::tileCount,
                        visibleArea = vm::visibleAreaRegion,
                        onDownload = { requestNotificationPermission(); vm.downloadRegion(it) },
                        onCancelDownload = vm::cancelDownload,
                        onDelete = vm::deleteRegion,
                        onClose = vm::toggleSettings,
                    )
                    SheetMode.Trip -> if (vm.multiStop) MultiTripSheet(
                        mode = vm.mode,
                        segments = vm.segments,
                        combined = vm.combined,
                        loading = vm.loading,
                        onMode = vm::selectMode,
                        onSegmentMode = vm::setSegmentMode,
                        onOption = vm::selectSegmentOption,
                    ) else RouteSheet(
                        mode = vm.mode,
                        itineraries = vm.itineraries,
                        selected = vm.selected,
                        loading = vm.loading,
                        error = vm.error,
                        onSelect = vm::select,
                        onRetry = vm::plan,
                        header = {
                            TripSheetHeader(
                                mode = vm.mode,
                                onMode = vm::selectMode,
                                linesOn = vm.showTransit,
                                bikesOn = vm.showBike,
                                onLines = { syncViewport(); vm.toggleTransit() },
                                onBikes = { syncViewport(); vm.toggleBike() },
                            )
                        },
                    )
                    SheetMode.Lines -> LinesSheet(
                        lines = vm.legend,
                        modes = vm.transitModes,
                        loading = vm.transitLoading,
                        hint = vm.overlayHint,
                        onToggleMode = vm::toggleMode,
                        onLine = vm::highlight,
                    )
                    SheetMode.Line -> picked?.let {
                        LineSheet(
                            line = it,
                            departures = vm.departures?.takeIf { d -> d.lineKey == it.info.key },
                            onStop = vm::showDepartures,
                            onClose = { vm.highlight(it.info.key) },
                        )
                    }
                    SheetMode.None -> Spacer(Modifier.height(1.dp))
                }
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            with(density) {
                MapHost(
                    controller = controller,
                    palette = palette,
                    compassTopPx = (statusTop + 140.dp).roundToPx(),
                    attributionBottomPx = (navBottom + 8.dp).roundToPx(),
                    sidePx = 16.dp.roundToPx(),
                    onMapReady = { mapReady = true },
                    onLongPress = { pos: LatLon ->
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        vm.dropPin(pos)
                    },
                    onTap = { pos: LatLon ->
                        val f = controller.placeAt(pos)
                        if (f != null) {
                            vm.onPlaceTapped(
                                Place(f.name, null, f.pos, if (f.poiClass in setOf("railway", "bus", "ferry_terminal")) PlaceKind.Station else PlaceKind.Place),
                                humanizeCategory(f.subclass, f.poiClass),
                                f.poiClass,
                            )
                        } else {
                            val line = if (vm.transitVisible) controller.lineAt(pos) else null
                            when {
                                line != null -> vm.openLine(line)
                                vm.tappedPlace != null -> vm.closePlace()
                            }
                        }
                    },
                    onViewport = vm::onViewport,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // ---------- Top: search, or the trip ----------
            AnimatedContent(
                targetState = vm.destination != null,
                transitionSpec = {
                    (fadeIn(motion.defaultEffectsSpec()) + slideInVertically(motion.defaultSpatialSpec()) { -it / 4 })
                        .togetherWith(fadeOut(motion.fastEffectsSpec()))
                },
                label = "top",
                modifier = Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
            ) { planning ->
                val dest = vm.destination
                if (planning && dest != null) {
                    TripCard(
                        origin = vm.origin,
                        destination = dest,
                        stops = vm.stops,
                        onBack = vm::clearTrip,
                        onEditOrigin = { vm.openSearch(SearchTarget.From) },
                        onEditDestination = { vm.openSearch(SearchTarget.To) },
                        onSwap = vm::swap,
                        onAddStop = { vm.openSearch(SearchTarget.Stop) },
                        onRemoveStop = vm::removeStop,
                    )
                } else {
                    SearchPill(
                        onClick = { vm.openSearch(SearchTarget.To) },
                        settingsOn = vm.settingsOpen,
                        onSettings = vm::toggleSettings,
                    )
                }
            }

            // ---------- Bottom: hint + floating toolbar + FAB, riding above the sheet ----------
            val navBottomPx = with(density) { navBottom.roundToPx() }
            val gapPx = with(density) { 16.dp.roundToPx() }
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints.copy(minHeight = 0))
                        val sheetTop = runCatching { sheetState.requireOffset() }.getOrNull()
                            ?.takeIf { sheetMode != SheetMode.None }
                            ?: Float.MAX_VALUE
                        val bottom = minOf(sheetTop.toInt(), constraints.maxHeight - navBottomPx) - gapPx
                        val y = bottom - placeable.height
                        // Fade the toolbar out as a tall sheet pushes it towards the search bar,
                        // and leave it unplaced (so it takes no taps) once it would overlap.
                        val hideAt = constraints.maxHeight * 0.22f
                        val fadeOver = constraints.maxHeight * 0.12f
                        val alpha = ((y - hideAt) / fadeOver).coerceIn(0f, 1f)
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            if (alpha > 0f) placeable.placeWithLayer(0, y) { this.alpha = alpha }
                        }
                    }
                    .padding(horizontal = 16.dp),
            ) {
                AnimatedVisibility(
                    visible = !vm.transitVisible && vm.overlayHint != null,
                    enter = fadeIn() + scaleIn(initialScale = 0.9f),
                    exit = fadeOut() + scaleOut(targetScale = 0.9f),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.inverseSurface,
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                        shadowElevation = 4.dp,
                    ) {
                        Text(
                            vm.overlayHint.orEmpty(),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
                MapToolbar(
                    showLayers = !vm.planning,
                    linesOn = vm.showTransit,
                    linesLoading = vm.transitLoading,
                    bikesOn = vm.showBike,
                    bikesLoading = vm.bikeLoading,
                    hasLocation = vm.myLocation != null,
                    onLines = { syncViewport(); vm.toggleTransit() },
                    onBikes = { syncViewport(); vm.toggleBike() },
                    onLocate = {
                        val loc = vm.myLocation
                        when {
                            !vm.hasLocationPermission -> requestLocationPermission()
                            loc != null -> controller.flyTo(loc, 16.0)
                        }
                    },
                )
            }
        }
    }

    // ---------- Search (covers everything) ----------
    AnimatedVisibility(
        visible = vm.searchTarget != null,
        enter = fadeIn(motion.defaultEffectsSpec()) + slideInVertically(motion.defaultSpatialSpec()) { -it / 10 },
        exit = fadeOut(motion.fastEffectsSpec()) + slideOutVertically(motion.fastSpatialSpec()) { -it / 10 },
    ) {
        SearchOverlay(
            target = vm.searchTarget ?: SearchTarget.To,
            query = vm.query,
            suggestions = vm.suggestions,
            searching = vm.searching,
            hasLocation = vm.hasLocationPermission,
            onQueryChange = { vm.onQueryChange(it, controller.center()) },
            onChoose = vm::choose,
            onClose = vm::closeSearch,
        )
    }

    BackHandler(enabled = vm.searchTarget != null) { vm.closeSearch() }
    BackHandler(enabled = vm.searchTarget == null && vm.tappedPlace != null) { vm.closePlace() }
    BackHandler(enabled = vm.searchTarget == null && vm.tappedPlace == null && vm.settingsOpen && !vm.planning) { vm.toggleSettings() }
    BackHandler(enabled = vm.searchTarget == null && vm.tappedPlace == null && !vm.settingsOpen && vm.highlightedLine != null) { vm.highlight(vm.highlightedLine) }
    BackHandler(enabled = vm.searchTarget == null && vm.tappedPlace == null && vm.highlightedLine == null && vm.planning) { vm.clearTrip() }
}

/** Trip sheet header: travel modes as a connected group, then the section title and layer chip. */
@Composable
private fun TripSheetHeader(
    mode: TravelMode,
    onMode: (TravelMode) -> Unit,
    linesOn: Boolean,
    bikesOn: Boolean,
    onLines: () -> Unit,
    onBikes: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ModeSelector(mode = mode, onSelect = onMode)
        val transit = mode == TravelMode.Transit || mode == TravelMode.Train
        if (transit || mode == TravelMode.Cycle) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
                Text(
                    if (transit) "Routes" else "Cycling",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight(700)),
                    modifier = Modifier.weight(1f),
                )
                val on = if (transit) linesOn else bikesOn
                FilterChip(
                    selected = on,
                    onClick = if (transit) onLines else onBikes,
                    label = { Text(if (transit) "Show lines" else "Bike routes") },
                    leadingIcon = {
                        Icon(
                            if (transit) (if (on) Ph.Filled.Bus else Ph.Bus) else (if (on) Ph.Filled.Bicycle else Ph.Bicycle),
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    },
                    shape = CircleShape,
                )
            }
        }
    }
}
