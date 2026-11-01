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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import android.content.Intent
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

                // Download progress shows as a notification while the app is in the background.
                val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

                LocationUpdates(enabled = vm.hasLocationPermission, onLocation = vm::onLocation)
                WayfinderScreen(
                    vm = vm,
                    requestLocationPermission = { launcher.launch(permissions) },
                    requestNotificationPermission = {
                        if (android.os.Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                )
                CrashDialog()
            }
        }
    }
}

/** Shows the previous crash, if the app crashed last time, with copy and share actions. */
@Composable
private fun CrashDialog() {
    val context = LocalContext.current
    var report by remember { mutableStateOf(CrashReporter.takeLastCrash(context)) }
    val text = report ?: return
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = { report = null },
        title = { Text("Wayfinder crashed last time") },
        text = {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Wayfinder crash report")
                    .putExtra(Intent.EXTRA_TEXT, text)
                context.startActivity(Intent.createChooser(send, "Share crash report"))
                report = null
            }) { Text("Share") }
        },
        dismissButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(text))
                report = null
            }) { Text("Copy") }
        },
    )
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
