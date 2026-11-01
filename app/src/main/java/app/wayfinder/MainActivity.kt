package app.wayfinder

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import app.wayfinder.data.LatLon
import app.wayfinder.ui.WayfinderScreen
import app.wayfinder.ui.WayfinderViewModel
import app.wayfinder.ui.theme.WayfinderTheme

class MainActivity : ComponentActivity() {
    private val vm: WayfinderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val permissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        setContent {
            WayfinderTheme {
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { result -> vm.onPermission(result.values.any { it }) }

                LaunchedEffect(Unit) {
                    val granted = permissions.any {
                        ContextCompat.checkSelfPermission(this@MainActivity, it) == PackageManager.PERMISSION_GRANTED
                    }
                    vm.onPermission(granted)
                    if (!granted) launcher.launch(permissions)
                }

                LocationUpdates(enabled = vm.hasLocationPermission, onLocation = vm::onLocation)
                WayfinderScreen(vm = vm, requestLocationPermission = { launcher.launch(permissions) })
            }
        }
    }
}

/** Streams the device position from the platform location providers while [enabled]. */
@SuppressLint("MissingPermission")
@Composable
private fun LocationUpdates(enabled: Boolean, onLocation: (LatLon) -> Unit) {
    val context = LocalContext.current
    val callback by rememberUpdatedState(onLocation)
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val manager = context.getSystemService(LocationManager::class.java)
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = callback(LatLon(location.latitude, location.longitude))
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }

        providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { callback(LatLon(it.latitude, it.longitude)) }
        providers.forEach {
            runCatching { manager.requestLocationUpdates(it, 4000L, 10f, listener, Looper.getMainLooper()) }
        }
        onDispose { manager.removeUpdates(listener) }
    }
}
