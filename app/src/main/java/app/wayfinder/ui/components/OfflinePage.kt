@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.wayfinder.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.wayfinder.data.Bounds
import app.wayfinder.data.OfflineTransit
import app.wayfinder.ui.DownloadState
import app.wayfinder.ui.Ph

/** Offline maps: the running download, a region finder, saved regions and what was saved while browsing. */
@Composable
fun OfflinePage(
    saved: List<OfflineTransit.Saved>,
    download: DownloadState?,
    view: Bounds?,
    browsing: Pair<Int, Long>,
    storage: Pair<Long, Long>,
    tileCount: (OfflineTransit.Region) -> Int,
    visibleArea: () -> OfflineTransit.Region?,
    onDownload: (OfflineTransit.Region) -> Unit,
    onCancel: () -> Unit,
    onDelete: (String) -> Unit,
    onClearBrowsing: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var query by rememberSaveable { mutableStateOf("") }
    val savedById = saved.associateBy { it.region.id }
    val busy = download != null
    Surface(color = colors.surface, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
                    Icon(Ph.ArrowLeft, contentDescription = "Back", modifier = Modifier.size(22.dp))
                }
                Text("Offline maps", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight(700)))
            }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            ) {
                download?.let { d ->
                    item("active") { ActiveDownload(d, onCancel) }
                }
                item("search") {
                    Surface(shape = CircleShape, color = colors.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 18.dp)) {
                            Icon(Ph.MagnifyingGlass, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Box(Modifier.weight(1f)) {
                                if (query.isEmpty()) Text("Find a country or city", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                                BasicTextField(
                                    value = query,
                                    onValueChange = { query = it },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                                    cursorBrush = SolidColor(colors.primary),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
                val q = query.trim()
                if (q.isNotEmpty()) {
                    val found = (OfflineTransit.presets + saved.map { it.region }).distinctBy { it.id }
                        .filter { it.name.contains(q, ignoreCase = true) }
                    item("found") {
                        Group(found.ifEmpty { null }, savedById, download, busy, tileCount, onDownload, onCancel, onDelete)
                        if (found.isEmpty()) Text("No region by that name", color = colors.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                    }
                } else {
                    if (saved.isNotEmpty()) {
                        item("savedT") { SectionLabel("Saved") }
                        item("saved") { Group(saved.map { it.region }, savedById, download, busy, tileCount, onDownload, onCancel, onDelete) }
                    }
                    item("nearT") { SectionLabel("Suggested near you") }
                    item("near") {
                        val near = nearest(view).filter { it.id !in savedById }.take(4)
                        Group(near, savedById, download, busy, tileCount, onDownload, onCancel, onDelete) {
                            HorizontalDivider(color = colors.outlineVariant, modifier = Modifier.padding(horizontal = 16.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text("Area on screen", style = MaterialTheme.typography.titleMedium)
                                    Text("Everything around what you see", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                }
                                FilledTonalButton(onClick = { visibleArea()?.let(onDownload) }, enabled = !busy, shapes = ButtonDefaults.shapes()) { Text("Save") }
                            }
                        }
                    }
                    item("browseT") { SectionLabel("Saved while browsing") }
                    item("browse") {
                        Surface(shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerLow) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text("Areas you looked at", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        if (browsing.first == 0) "Nothing yet" else "${browsing.first} squares · ${formatBytes(browsing.second)}",
                                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                                    )
                                }
                                if (browsing.first > 0) TextButton(onClick = onClearBrowsing) { Text("Clear") }
                            }
                        }
                    }
                    item("allT") { SectionLabel("All regions") }
                    item("cont") { Group(OfflineTransit.continents, savedById, download, busy, tileCount, onDownload, onCancel, onDelete) }
                    item("countries") { Group(OfflineTransit.countries, savedById, download, busy, tileCount, onDownload, onCancel, onDelete) }
                    item("cities") { Group(OfflineTransit.cities, savedById, download, busy, tileCount, onDownload, onCancel, onDelete) }
                }
                item("storage") {
                    val (used, free) = storage
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                        Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(colors.surfaceContainerHighest)) {
                            val f = if (used + free > 0) (used.toFloat() / (used + free)).coerceIn(0.02f, 1f) else 0f
                            Box(Modifier.fillMaxHeight().fillMaxWidth(f).clip(CircleShape).background(colors.primary))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("${formatBytes(used)} used · ${formatBytes(free)} free", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun nearest(view: Bounds?): List<OfflineTransit.Region> {
    if (view == null) return OfflineTransit.countries
    val lat = (view.south + view.north) / 2; val lon = (view.west + view.east) / 2
    return (OfflineTransit.countries + OfflineTransit.cities).sortedBy { r ->
        val inside = lat in r.area.south..r.area.north && lon in r.area.west..r.area.east
        val dLat = (r.area.south + r.area.north) / 2 - lat
        val dLon = ((r.area.west + r.area.east) / 2 - lon) * Math.cos(Math.toRadians(lat))
        (if (inside) 0.0 else 1000.0) + dLat * dLat + dLon * dLon
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, top = 6.dp),
    )
}

@Composable
private fun ActiveDownload(d: DownloadState, onCancel: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(28.dp), color = colors.primaryContainer, contentColor = colors.onPrimaryContainer) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(d.region.name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight(700)))
                    Text(
                        when {
                            d.finishing -> "Preparing zoomed-out views…"
                            d.retrying -> "Second try · ${d.failed} squares left"
                            else -> "Saving · ${d.done} of ${d.total} squares" + if (d.failed > 0) " · ${d.failed} failed" else ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedButton(onClick = onCancel, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
            }
            LinearWavyProgressIndicator(
                progress = { if (d.total == 0) 0f else d.done.toFloat() / d.total },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Lines and bike routes (${d.bikesDone} squares of bike routes so far). Keeps going in the background." +
                    (d.problem?.let { "\nLast issue: $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Group(
    regions: List<OfflineTransit.Region>?,
    savedById: Map<String, OfflineTransit.Saved>,
    download: DownloadState?,
    busy: Boolean,
    tileCount: (OfflineTransit.Region) -> Int,
    onDownload: (OfflineTransit.Region) -> Unit,
    onCancel: () -> Unit,
    onDelete: (String) -> Unit,
    footer: @Composable () -> Unit = {},
) {
    if (regions == null) return
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerLow) {
        Column(Modifier.padding(vertical = 4.dp)) {
            regions.forEachIndexed { i, region ->
                if (i > 0) HorizontalDivider(color = colors.outlineVariant, modifier = Modifier.padding(horizontal = 16.dp))
                val s = savedById[region.id]
                val active = download?.region?.id == region.id
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(region.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            when {
                                active -> "Saving…"
                                s != null && s.complete -> "Saved · ${formatBytes(s.bytes)}"
                                s != null -> "Partly saved · ${formatBytes(s.bytes)}"
                                else -> "${tileCount(region)} squares"
                            },
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        )
                    }
                    when {
                        active -> TextButton(onClick = onCancel) { Text("Cancel") }
                        s != null -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { onDownload(region) }, enabled = !busy) { Text(if (s.complete) "Update" else "Resume") }
                            IconButton(onClick = { onDelete(region.id) }, enabled = !busy, shapes = IconButtonDefaults.shapes()) {
                                Icon(Ph.Trash, contentDescription = "Delete ${region.name}", modifier = Modifier.size(20.dp))
                            }
                        }
                        else -> Button(onClick = { onDownload(region) }, enabled = !busy, shapes = ButtonDefaults.shapes()) { Text("Save") }
                    }
                }
            }
            footer()
        }
    }
}

internal fun formatBytes(b: Long) = when {
    b >= 1_000_000_000 -> String.format(java.util.Locale.getDefault(), "%.1f GB", b / 1e9)
    b >= 1_000_000 -> String.format(java.util.Locale.getDefault(), "%.0f MB", b / 1e6)
    else -> String.format(java.util.Locale.getDefault(), "%.0f kB", b / 1e3)
}
