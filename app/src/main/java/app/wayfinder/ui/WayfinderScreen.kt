package app.wayfinder.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.material3.toShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import app.wayfinder.data.LatLon
import app.wayfinder.map.MapController
import app.wayfinder.map.MapPalette
import app.wayfinder.map.RouteDrawing
import app.wayfinder.ui.components.LinePills
import app.wayfinder.ui.components.MapHost
import app.wayfinder.ui.components.OverlayPills
import app.wayfinder.ui.components.ModeSelector
import app.wayfinder.ui.components.RouteSheet
import app.wayfinder.ui.components.SearchOverlay
import app.wayfinder.ui.components.SearchPill
import app.wayfinder.ui.components.TripCard

private val SheetPeek = 300.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WayfinderScreen(vm: WayfinderViewModel, requestLocationPermission: () -> Unit) {
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

    val sheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.Hidden,
        skipHiddenState = false,
    )
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)

    LaunchedEffect(vm.planning) {
        if (vm.planning) sheetState.partialExpand() else sheetState.hide()
    }

    // ---------- Keep the map in sync with state ----------
    LaunchedEffect(vm.hasLocationPermission, mapReady) {
        if (vm.hasLocationPermission && mapReady) controller.enableLocation()
    }
    LaunchedEffect(vm.showTransit, mapReady) { controller.setNetworkVisible(vm.showTransit) }
    LaunchedEffect(vm.showBike, mapReady) { controller.setBikeVisible(vm.showBike) }
    LaunchedEffect(vm.transit, mapReady) { controller.setTransit(vm.transit) }
    LaunchedEffect(vm.bike, mapReady) { controller.setBike(vm.bike) }
    LaunchedEffect(vm.highlightedLine, vm.transit, mapReady) { controller.highlightLine(vm.highlightedLine) }

    // Make sure overlays know the current viewport even before the first camera move ends.
    fun syncViewport() {
        val bounds = controller.visibleBounds() ?: return
        vm.onViewport(bounds, controller.zoom())
    }
    LaunchedEffect(vm.itineraries, vm.selected, vm.origin, vm.destination, vm.myLocation, palette, mapReady) {
        val dest = vm.destination
        controller.setRoute(
            if (dest == null) null else RouteDrawing(
                itineraries = vm.itineraries,
                selected = vm.selected,
                origin = vm.origin?.pos ?: vm.myLocation.takeIf { vm.origin == null && vm.itineraries.isNotEmpty() },
                destination = dest.pos,
                accent = palette.accent,
                walk = palette.walk,
            )
        )
    }
    LaunchedEffect(vm.fitRequest, mapReady) {
        if (!mapReady || !vm.planning) return@LaunchedEffect
        val points = vm.itineraries.getOrNull(vm.selected)?.geometry?.takeIf { it.size >= 2 }
            ?: listOfNotNull(vm.origin?.pos ?: vm.myLocation, vm.destination?.pos)
        with(density) {
            controller.fit(
                points,
                left = 48.dp.roundToPx(),
                top = (statusTop + 250.dp).roundToPx(),
                right = 48.dp.roundToPx(),
                bottom = (SheetPeek + 32.dp).roundToPx(),
            )
        }
    }
    // Centre on the user the first time we learn where they are.
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
        sheetPeekHeight = if (vm.planning) SheetPeek + navBottom else 0.dp,
        sheetShape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        sheetShadowElevation = 8.dp,
        sheetSwipeEnabled = vm.planning,
        sheetContent = {
            if (vm.planning) {
                Box(Modifier.navigationBarsPadding()) {
                    RouteSheet(
                        mode = vm.mode,
                        itineraries = vm.itineraries,
                        selected = vm.selected,
                        loading = vm.loading,
                        error = vm.error,
                        onSelect = vm::select,
                        onRetry = vm::plan,
                    )
                }
            } else {
                Spacer(Modifier.height(1.dp))
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            with(density) {
                MapHost(
                    controller = controller,
                    palette = palette,
                    compassTopPx = (statusTop + 260.dp).roundToPx(),
                    attributionBottomPx = (navBottom + 8.dp).roundToPx(),
                    sidePx = 16.dp.roundToPx(),
                    onMapReady = { mapReady = true },
                    onLongPress = { pos: LatLon ->
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        vm.dropPin(pos)
                    },
                    onViewport = vm::onViewport,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // ---------- Top: search pill or trip card ----------
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(vertical = 8.dp)
                    .fillMaxWidth(),
            ) {
              Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
              ) {
                AnimatedContent(
                    targetState = vm.destination != null,
                    transitionSpec = {
                        (fadeIn(motion.defaultEffectsSpec()) +
                            slideInVertically(motion.defaultSpatialSpec()) { -it / 4 })
                            .togetherWith(fadeOut(motion.fastEffectsSpec()))
                    },
                    label = "top",
                ) { planning ->
                    if (planning) {
                        val dest = vm.destination
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (dest != null) {
                                TripCard(
                                    origin = vm.origin,
                                    destination = dest,
                                    onBack = vm::clearTrip,
                                    onEditOrigin = { vm.openSearch(SearchTarget.From) },
                                    onEditDestination = { vm.openSearch(SearchTarget.To) },
                                    onSwap = vm::swap,
                                )
                            }
                            ModeSelector(mode = vm.mode, onSelect = vm::selectMode)
                        }
                    } else {
                        SearchPill(onClick = { vm.openSearch(SearchTarget.To) })
                    }
                }
                OverlayPills(
                    transitOn = vm.showTransit,
                    transitLoading = vm.transitLoading,
                    bikeOn = vm.showBike,
                    bikeLoading = vm.bikeLoading,
                    onTransit = { syncViewport(); vm.toggleTransit() },
                    onBike = { syncViewport(); vm.toggleBike() },
                )
              }
                val lines = vm.transit?.legend.orEmpty()
                AnimatedVisibility(
                    visible = vm.showTransit && lines.isNotEmpty(),
                    enter = fadeIn() + slideInVertically { -it / 2 },
                    exit = fadeOut(),
                ) {
                    LinePills(lines = lines, highlighted = vm.highlightedLine, onClick = vm::highlight)
                }
                AnimatedVisibility(
                    visible = vm.overlayHint != null,
                    enter = fadeIn() + scaleIn(initialScale = 0.9f),
                    exit = fadeOut() + scaleOut(targetScale = 0.9f),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.inverseSurface,
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                        shadowElevation = 4.dp,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        Text(
                            vm.overlayHint.orEmpty(),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
            }

            // ---------- Floating actions ----------
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.End,
                modifier = Modifier
                    .align(if (vm.planning) Alignment.CenterEnd else Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = if (vm.planning) 0.dp else 24.dp)
                    .navigationBarsPadding(),
            ) {
                FloatingActionButton(
                    onClick = {
                        val loc = vm.myLocation
                        when {
                            !vm.hasLocationPermission -> requestLocationPermission()
                            loc != null -> controller.flyTo(loc, 16.0)
                        }
                    },
                    shape = MaterialShapes.Cookie9Sided.toShape(),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(64.dp),
                ) {
                    Icon(
                        if (vm.myLocation != null) Ph.Filled.GpsFix else Ph.Crosshair,
                        contentDescription = "My location",
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        }
    }

    // ---------- Search (covers everything) ----------
    AnimatedVisibility(
        visible = vm.searchTarget != null,
        enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) +
            slideInVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) { -it / 10 },
        exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) +
            slideOutVertically(MaterialTheme.motionScheme.fastSpatialSpec()) { -it / 10 },
    ) {
        val target = vm.searchTarget ?: SearchTarget.To
        SearchOverlay(
            target = target,
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
    BackHandler(enabled = vm.searchTarget == null && vm.planning) { vm.clearTrip() }
}
