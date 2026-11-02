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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.rotate
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
    spread: Boolean = false,
    onSpread: (Boolean) -> Unit = {},
    minLineZoom: Float,
    onMinLineZoom: (Float) -> Unit,
    presets: List<OfflineTransit.Region>,
    saved: List<OfflineTransit.Saved>,
    download: app.wayfinder.ui.DownloadState?,
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
        val savedById = saved.associateBy { it.region.id }
        val custom = saved.map { it.region }.filter { r -> presets.none { it.id == r.id } }
        RegionGroup(
            regions = OfflineTransit.continents + custom,
            savedById = savedById, download = download, tileCount = tileCount,
            onDownload = onDownload, onCancelDownload = onCancelDownload, onDelete = onDelete,
        ) {
            HorizontalDivider(color = colors.outlineVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Visible area", style = MaterialTheme.typography.titleMedium)
                    Text("Everything around what's on screen", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                FilledTonalButton(
                    onClick = { visibleArea()?.let(onDownload) },
                    enabled = download == null,
                    shapes = ButtonDefaults.shapes(),
                ) { Text("Download") }
            }
        }
        var countriesOpen by rememberSaveable { mutableStateOf(false) }
        var citiesOpen by rememberSaveable { mutableStateOf(false) }
        val activeId = download?.region?.id
        ExpandableGroup(
            title = "Countries and areas",
            subtitle = "${OfflineTransit.countries.size} regions",
            open = countriesOpen || OfflineTransit.countries.any { it.id == activeId },
            onToggle = { countriesOpen = !countriesOpen },
        ) {
            RegionGroup(OfflineTransit.countries, savedById, download, tileCount, onDownload, onCancelDownload, onDelete)
        }
        ExpandableGroup(
            title = "Cities",
            subtitle = "${OfflineTransit.cities.size} cities, with the area around them",
            open = citiesOpen || OfflineTransit.cities.any { it.id == activeId },
            onToggle = { citiesOpen = !citiesOpen },
        ) {
            RegionGroup(OfflineTransit.cities, savedById, download, tileCount, onDownload, onCancelDownload, onDelete)
        }
        Text(
            "Downloaded regions include every line, buses too, at every zoom, and draw from your phone " +
                "with no network. Departures and trip planning stay live. A continent takes a while; keep the app open.",
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
                HorizontalDivider(color = colors.outlineVariant, modifier = Modifier.padding(start = 72.dp, end = 16.dp))
                SettingRow(Ph.Bus, "Lines side by side", "Spread lines sharing a street when zoomed in very close", spread, onSpread)
            }
        }
    }
}

@Composable
private fun RegionGroup(
    regions: List<OfflineTransit.Region>,
    savedById: Map<String, OfflineTransit.Saved>,
    download: app.wayfinder.ui.DownloadState?,
    tileCount: (OfflineTransit.Region) -> Int,
    onDownload: (OfflineTransit.Region) -> Unit,
    onCancelDownload: () -> Unit,
    onDelete: (String) -> Unit,
    footer: @Composable () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerHigh) {
        Column(Modifier.padding(vertical = 6.dp)) {
            regions.forEachIndexed { i, region ->
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
            footer()
        }
    }
}

@Composable
private fun ExpandableGroup(
    title: String,
    subtitle: String,
    open: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val turn by animateFloatAsState(if (open) 180f else 0f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "caret")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            onClick = onToggle,
            shape = RoundedCornerShape(24.dp),
            color = if (open) colors.secondaryContainer else colors.surfaceContainerHigh,
            contentColor = if (open) colors.onSecondaryContainer else colors.onSurface,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                Icon(Ph.CaretDown, contentDescription = if (open) "Collapse" else "Expand", modifier = Modifier.size(20.dp).rotate(turn))
            }
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) { content() }
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
    download: app.wayfinder.ui.DownloadState?,
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
                        download != null && download.pack -> "Downloading · ${download.done} of ${download.total} MB"
                        download != null && download.finishing -> "Preparing zoomed-out views…"
                        download != null && download.retrying -> "Second try · ${download.failed} map squares left"
                        download != null -> "Lines ${download.done} of ${download.total} · bike routes ${download.bikesDone}" +
                            if (download.failed > 0) " · ${download.failed} failed" else ""
                        saved != null && saved.complete -> "Downloaded · ${formatBytes(saved.bytes)}"
                        saved != null -> "Partly downloaded · ${formatBytes(saved.bytes)}"
                        else -> "$tiles map squares · every line, buses included"
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
            val note = listOfNotNull(
                if (download.retrying) "Retrying the ${download.failed} that failed" else null,
                download.problem?.let { "Last issue: $it" },
            ).joinToString(" · ")
            if (note.isNotEmpty()) {
                Text(note, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
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
