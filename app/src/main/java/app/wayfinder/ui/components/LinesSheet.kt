@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.wayfinder.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import app.wayfinder.ui.Ph
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.wayfinder.data.LineInfo
import app.wayfinder.data.LoadedLine
import app.wayfinder.data.TransitMode

/** "Lines nearby": mode filter as a connected button group, then lines wrapped by mode. */
@Composable
fun LinesSheet(
    lines: List<LineInfo>,
    modes: Set<TransitMode>,
    loading: Boolean,
    hint: String?,
    onToggleMode: (TransitMode) -> Unit,
    onLine: (String) -> Unit,
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
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(
                "Lines nearby",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight(700)),
                modifier = Modifier.weight(1f),
            )
            if (loading) {
                LoadingIndicator(Modifier.size(36.dp))
            } else {
                Text("${lines.size} lines", style = MaterialTheme.typography.titleSmall, color = colors.onSurfaceVariant)
            }
        }

        ModeFilterGroup(modes, onToggleMode)

        if (lines.isEmpty()) {
            Text(
                if (loading) "Loading lines…" else "No lines here. Move or zoom the map.",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }

        lines.groupBy { it.mode }.forEach { (mode, group) ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                if (mode != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(modeIcon(mode), contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("${mode.label} · ${group.size}", style = MaterialTheme.typography.titleSmall, color = colors.onSurfaceVariant)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    group.take(120).forEach { line -> LinePill(line, onClick = { onLine(line.key) }) }
                }
            }
        }

        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
        }
    }
}

/** Full-width connected button group with an icon over each label. */
@Composable
private fun ModeFilterGroup(modes: Set<TransitMode>, onToggle: (TransitMode) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val all = TransitMode.entries
    Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween), modifier = Modifier.fillMaxWidth()) {
        all.forEachIndexed { index, mode ->
            ToggleButton(
                checked = mode in modes,
                onCheckedChange = { onToggle(mode) },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    all.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                colors = ToggleButtonDefaults.toggleButtonColors(
                    containerColor = colors.surfaceContainerHighest,
                    contentColor = colors.onSurfaceVariant,
                    checkedContainerColor = Color(mode.colorArgb),
                    checkedContentColor = Color.White,
                ),
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.weight(1f).height(56.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(modeIcon(mode), contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(mode.label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun LinePill(line: LineInfo, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(10.dp), color = Color(line.color), contentColor = Color(line.textColor)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.defaultMinSize(minWidth = 48.dp).height(36.dp).padding(horizontal = 12.dp),
        ) {
            Text(
                line.ref,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight(750)),
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

/** One line: badge and name, next departures as chips, and its stops behind "All stops". */
@Composable
fun LineSheet(
    line: LoadedLine,
    departures: app.wayfinder.ui.WayfinderViewModel.DepartureBoard?,
    onStop: (app.wayfinder.data.StopRef) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val info = line.info
    val lineColor = Color(info.color)
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.82f).dp
    var showStops by androidx.compose.runtime.saveable.rememberSaveable(info.key) { androidx.compose.runtime.mutableStateOf(false) }
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = lineColor, contentColor = Color(info.textColor)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.defaultMinSize(minWidth = 44.dp).height(44.dp).padding(horizontal = 10.dp)) {
                    Text(info.ref.ifBlank { "•" }, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight(750)), maxLines = 1)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    info.name.ifBlank { line.stops.firstOrNull()?.let { a -> line.stops.last().let { b -> "${a.name} – ${b.name}" } } ?: "Line ${info.ref}" },
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight(700)),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(info.mode?.let(::singular), line.stops.size.takeIf { it > 0 }?.let { "$it stops" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }

        if (departures != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${departures.stop.name} · next",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (departures.loading) LoadingIndicator(Modifier.size(28.dp))
            }
            departures.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) }
            val now = System.currentTimeMillis()
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                departures.items.take(12).forEachIndexed { i, d ->
                    val mins = ((d.timeMs - now) / 60_000).coerceAtLeast(0)
                    val first = i == 0
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = if (first) colors.primaryContainer else colors.surfaceContainerHigh,
                        contentColor = if (first) colors.onPrimaryContainer else colors.onSurface,
                    ) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(
                                when { d.cancelled -> "Cancelled"; mins == 0L -> "Now"; else -> "$mins min" },
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight(700),
                                    textDecoration = if (d.cancelled) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                                ),
                            )
                            Text(
                                formatTime(d.timeMs) + if (d.realTime) " · live" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = (if (first) colors.onPrimaryContainer else colors.onSurfaceVariant),
                            )
                            if (d.headsign.isNotBlank()) Text(
                                d.headsign,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(120.dp),
                            )
                        }
                    }
                }
            }
        }

        if (line.stops.isNotEmpty()) {
            androidx.compose.material3.TextButton(onClick = { showStops = !showStops }) {
                Text(if (showStops) "Hide stops" else "All ${line.stops.size} stops")
                Spacer(Modifier.width(6.dp))
                Icon(if (showStops) Ph.CaretDown else Ph.CaretRight, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
        if (showStops && line.stops.isNotEmpty()) {
            Surface(shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    val stops = line.stops.take(120)
                    stops.forEachIndexed { i, stop ->
                        val terminal = i == 0 || i == stops.lastIndex
                        val selected = departures?.stop?.id == stop.id && stop.id.isNotBlank()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clickable { onStop(stop) }
                                .background(if (selected) colors.secondaryContainer else Color.Transparent)
                                .padding(horizontal = 18.dp),
                        ) {
                            Canvas(Modifier.width(24.dp).fillMaxHeight()) {
                                val x = size.width / 2
                                val mid = size.height / 2
                                val track = 6.dp.toPx()
                                if (i > 0) drawLine(lineColor, Offset(x, 0f), Offset(x, mid), strokeWidth = track)
                                if (i < stops.lastIndex) drawLine(lineColor, Offset(x, mid), Offset(x, size.height), strokeWidth = track)
                                val r = (if (terminal) 7 else 5).dp.toPx()
                                drawCircle(Color.White, radius = r, center = Offset(x, mid))
                                drawCircle(lineColor, radius = r, center = Offset(x, mid), style = Stroke(width = (if (terminal) 4 else 3).dp.toPx()))
                            }
                            Spacer(Modifier.width(14.dp))
                            Text(
                                stop.name,
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight(if (terminal || selected) 650 else 450)),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (selected) Icon(Ph.Clock, contentDescription = "Showing departures", tint = colors.onSecondaryContainer, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            Text(
                "Tap a stop to see its departures",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

internal fun singular(mode: TransitMode) = when (mode) {
    TransitMode.Train -> "Train"
    TransitMode.Metro -> "Metro"
    TransitMode.Tram -> "Tram"
    TransitMode.Bus -> "Bus"
    TransitMode.Coach -> "Coach"
    TransitMode.Ferry -> "Ferry"
    TransitMode.Other -> "Transit"
}

internal fun modeIcon(mode: TransitMode): ImageVector = when (mode) {
    TransitMode.Train -> Ph.Filled.Train
    TransitMode.Metro -> Ph.Filled.Subway
    TransitMode.Tram -> Ph.Filled.Tram
    TransitMode.Bus -> Ph.Filled.Bus
    TransitMode.Coach -> Ph.Filled.Bus
    TransitMode.Ferry -> Ph.Filled.Boat
    TransitMode.Other -> Ph.Filled.TrainSimple
}
