@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.wayfinder.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import app.wayfinder.data.OfflineTransit
import app.wayfinder.ui.WayfinderViewModel
import kotlin.math.roundToInt
import app.wayfinder.ui.Ph

/** Map settings: when transit lines appear, offline transit data, and optional base-map layers. */
@Composable
fun SettingsSheet(
    ferries: Boolean,
    terrain: Boolean,
    relief: Boolean,
    onFerries: (Boolean) -> Unit,
    onTerrain: (Boolean) -> Unit,
    onRelief: (Boolean) -> Unit,
    minLineZoom: Float,
    onMinLineZoom: (Float) -> Unit,
    presets: List<OfflineTransit.Region>,
    saved: List<OfflineTransit.Saved>,
    download: WayfinderViewModel.DownloadState?,
    tileCount: (OfflineTransit.Region) -> Int,
    visibleArea: () -> OfflineTransit.Region?,
    onDownload: (OfflineTransit.Region) -> Unit,
    onCancelDownload: () -> Unit,
    onDelete: (String) -> Unit,
    onClose: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.82f).dp
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, end = 4.dp)) {
            Text(
                "Map settings",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight(700)),
                modifier = Modifier.weight(1f),
            )
            FilledTonalIconButton(onClick = onClose, shapes = IconButtonDefaults.shapes()) {
                Icon(Ph.X, contentDescription = "Close map settings", modifier = Modifier.size(20.dp))
            }
        }

        // ---------- Transit lines ----------
        SectionTitle("Transit lines")
        Surface(shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerHigh) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show lines from", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(
                        "Zoom ${minLineZoom.toInt()} · ${zoomName(minLineZoom)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.primary,
                    )
                }
                Slider(
                    value = minLineZoom,
                    onValueChange = { onMinLineZoom(it.roundToInt().toFloat()) },
                    valueRange = 0f..18f,
                    steps = 17,
                )
                Text(
                    "Lower shows lines on a zoomed-out map (heavier); higher only on city streets.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }

        // ---------- Offline data ----------
        SectionTitle("Offline transit data")
        Surface(shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerHigh) {
            Column(Modifier.padding(vertical = 6.dp)) {
                val savedById = saved.associateBy { it.region.id }
                val rows = presets + saved.map { it.region }.filter { r -> presets.none { it.id == r.id } }
                rows.forEachIndexed { i, region ->
                    if (i > 0) HorizontalDivider(color = colors.outlineVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp))
                    RegionRow(
                        region = region,
                        saved = savedById[region.id],
                        download = download?.takeIf { it.region.id == region.id },
                        busy = download != null,
                        tiles = tileCount(region),
                        onDownload = { onDownload(region) },
                        onCancel = onCancelDownload,
                        onDelete = { onDelete(region.id) },
                    )
                }
                HorizontalDivider(color = colors.outlineVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Visible area", style = MaterialTheme.typography.titleMedium)
                        Text("Everything on screen, including buses", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                    FilledTonalButton(
                        onClick = { visibleArea()?.let(onDownload) },
                        enabled = download == null,
                        shapes = ButtonDefaults.shapes(),
                    ) { Text("Download") }
                }
            }
        }
        Text(
            "Lines in downloaded regions draw from your phone without waiting for the network. " +
                "Downloads go one request at a time to be kind to Transitous, so a continent takes a while; keep the app open.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )

        // ---------- Base map ----------
        SectionTitle("Base map")
        Surface(shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerHigh) {
            Column(Modifier.padding(vertical = 6.dp)) {
                SettingRow(Ph.Boat, "Ferry routes", "Crossings from OpenStreetMap", ferries, onFerries)
                HorizontalDivider(color = colors.outlineVariant, modifier = Modifier.padding(start = 72.dp, end = 16.dp))
                SettingRow(Ph.Mountains, "Terrain shading", "Hill shading on the map", terrain, onTerrain)
                HorizontalDivider(color = colors.outlineVariant, modifier = Modifier.padding(start = 72.dp, end = 16.dp))
                SettingRow(Ph.Path, "Elevation and depth", "Colour land by height and the sea by depth", relief, onRelief)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, top = 4.dp),
    )
}

@Composable
private fun RegionRow(
    region: OfflineTransit.Region,
    saved: OfflineTransit.Saved?,
    download: WayfinderViewModel.DownloadState?,
    busy: Boolean,
    tiles: Int,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(region.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        download != null -> "Downloading ${download.done} of ${download.total} map squares" +
                            if (download.failed > 0) " · ${download.failed} failed" else ""
                        saved != null && saved.complete -> "Downloaded · ${formatBytes(saved.bytes)}"
                        saved != null -> "Partly downloaded · ${formatBytes(saved.bytes)}"
                        else -> "$tiles map squares · " +
                            if (OfflineTransit.Level.Street in region.levels) "lines incl. buses" else "trains, metro and trams"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            when {
                download != null -> OutlinedButton(onClick = onCancel, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
                saved != null -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!saved.complete) {
                        FilledTonalButton(onClick = onDownload, enabled = !busy, shapes = ButtonDefaults.shapes()) { Text("Resume") }
                    } else {
                        OutlinedButton(onClick = onDownload, enabled = !busy, shapes = ButtonDefaults.shapes()) { Text("Update") }
                    }
                    FilledTonalIconButton(onClick = onDelete, enabled = !busy, shapes = IconButtonDefaults.shapes()) {
                        Icon(Ph.X, contentDescription = "Delete ${region.name}", modifier = Modifier.size(18.dp))
                    }
                }
                else -> Button(onClick = onDownload, enabled = !busy, shapes = ButtonDefaults.shapes()) { Text("Download") }
            }
        }
        if (download != null) {
            LinearWavyProgressIndicator(
                progress = { if (download.total == 0) 0f else download.done.toFloat() / download.total },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun zoomName(z: Float) = when {
    z < 3 -> "World"
    z < 6 -> "Continent"
    z < 8 -> "Country"
    z < 11 -> "Region"
    z < 14 -> "City"
    else -> "Streets"
}

private fun formatBytes(b: Long) = when {
    b >= 1_000_000_000 -> String.format(java.util.Locale.getDefault(), "%.1f GB", b / 1e9)
    b >= 1_000_000 -> String.format(java.util.Locale.getDefault(), "%.0f MB", b / 1e6)
    else -> String.format(java.util.Locale.getDefault(), "%.0f kB", b / 1e3)
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(40.dp).clip(CircleShape).background(colors.secondaryContainer),
        ) {
            Icon(icon, contentDescription = null, tint = colors.onSecondaryContainer, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            thumbContent = if (checked) {
                { Icon(Ph.Strong.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
            } else null,
        )
    }
}
