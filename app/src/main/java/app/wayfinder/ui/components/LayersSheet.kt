@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.wayfinder.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.wayfinder.data.TransitMode
import app.wayfinder.ui.Ph
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box

/** Everything the map can show, in one place: big layer tiles, line types, and the way to offline maps. */
@Composable
fun LayersSheet(
    linesOn: Boolean,
    linesLoading: Boolean,
    bikesOn: Boolean,
    bikesLoading: Boolean,
    terrainOn: Boolean,
    ferriesOn: Boolean,
    onLines: () -> Unit,
    onBikes: () -> Unit,
    onTerrain: () -> Unit,
    onFerries: () -> Unit,
    modes: Set<TransitMode>,
    onToggleMode: (TransitMode) -> Unit,
    offlineSubtitle: String,
    onOffline: () -> Unit,
    onSettings: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
    ) {
        Text(
            "Layers",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight(700)),
            modifier = Modifier.padding(start = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LayerTile("Transit lines", if (linesLoading) "Saving lines here…" else "Every line, on phone", Ph.Bus, linesOn, onLines, Modifier.weight(1f))
            LayerTile("Bike routes", if (bikesLoading) "Loading…" else "Signed routes", Ph.Bicycle, bikesOn, onBikes, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LayerTile("Terrain", "Hills and depth", Ph.Mountains, terrainOn, onTerrain, Modifier.weight(1f))
            LayerTile("Ferries", "Crossings", Ph.Boat, ferriesOn, onFerries, Modifier.weight(1f))
        }
        if (linesOn) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TransitMode.entries.forEach { mode ->
                    val on = mode in modes
                    FilterChip(
                        selected = on,
                        onClick = { onToggleMode(mode) },
                        label = { Text(mode.label) },
                        leadingIcon = {
                            Box(Modifier.size(12.dp).clip(RoundedCornerShape(4.dp)).background(Color(mode.colorArgb)))
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.secondaryContainer),
                    )
                }
            }
        }
        Surface(onClick = onOffline, shape = RoundedCornerShape(20.dp), color = colors.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(64.dp).padding(horizontal = 16.dp)) {
                Icon(Ph.DownloadSimple, contentDescription = null, tint = colors.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Offline maps", style = MaterialTheme.typography.titleMedium)
                    Text(offlineSubtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Ph.CaretRight, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
        TextButton(onClick = onSettings) {
            Icon(Ph.GearSix, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("More settings")
        }
    }
}

@Composable
private fun LayerTile(label: String, sub: String, icon: ImageVector, on: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    // Expressive: a picked tile rounds out further.
    val radius by animateDpAsState(if (on) 28.dp else 18.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "tile")
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(radius),
        color = if (on) colors.primaryContainer else colors.surfaceContainerHigh,
        contentColor = if (on) colors.onPrimaryContainer else colors.onSurface,
        modifier = modifier.height(96.dp),
    ) {
        Column(verticalArrangement = Arrangement.SpaceBetween, modifier = Modifier.padding(16.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Column {
                Text(label, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight(650)))
                Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (on) colors.onPrimaryContainer.copy(alpha = 0.8f) else colors.onSurfaceVariant)
            }
        }
    }
}
