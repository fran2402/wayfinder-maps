package app.wayfinder.ui.components

import android.view.Gravity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.wayfinder.data.Bounds
import app.wayfinder.data.LatLon
import app.wayfinder.map.MapController
import app.wayfinder.map.MapPalette
import org.maplibre.android.maps.MapView

/** Hosts a MapLibre [MapView] and forwards lifecycle and gestures. */
@Composable
fun MapHost(
    controller: MapController,
    palette: MapPalette,
    compassTopPx: Int,
    attributionBottomPx: Int,
    sidePx: Int,
    onMapReady: () -> Unit,
    onLongPress: (LatLon) -> Unit,
    onViewport: (Bounds, Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val longPress by rememberUpdatedState(onLongPress)
    val viewport by rememberUpdatedState(onViewport)
    val ready by rememberUpdatedState(onMapReady)
    val currentPalette by rememberUpdatedState(palette)

    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
            getMapAsync { map ->
                controller.attach(map)
                map.uiSettings.apply {
                    setCompassGravity(Gravity.TOP or Gravity.END)
                    setCompassMargins(0, compassTopPx, sidePx, 0)
                    setAttributionGravity(Gravity.BOTTOM or Gravity.START)
                    setAttributionMargins(sidePx, 0, 0, attributionBottomPx)
                }
                map.addOnCameraIdleListener {
                    controller.visibleBounds()?.let { viewport(it, controller.zoom()) }
                }
                map.addOnMapLongClickListener { latLng ->
                    longPress(LatLon(latLng.latitude, latLng.longitude))
                    true
                }
                controller.applyStyle(currentPalette) { ready() }
            }
        }
    }

    DisposableEffect(lifecycle, mapView) {
        var destroyed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> { destroyed = true; mapView.onDestroy() }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (!destroyed) {
                mapView.onPause(); mapView.onStop(); mapView.onDestroy()
            }
        }
    }

    // Re-style when the colour scheme changes (light/dark, wallpaper colours).
    androidx.compose.runtime.LaunchedEffect(palette) {
        controller.applyStyle(palette)
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}
