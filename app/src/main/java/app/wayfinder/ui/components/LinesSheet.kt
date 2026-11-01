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
import app.wayfinder.ui.Ph

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

/** One line: badge and name, its next departures, and its stops as a timeline in the line colour. */
@Composable
fun LineSheet(
    line: LoadedLine,
    departures: app.wayfinder.ui.WayfinderViewModel.DepartureBoard?,
    onStop: (app.wayfinder.data.StopRef) -> Unit,
    onClose: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val info = line.info
    val lineColor = Color(info.color)
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
            Surface(shape = RoundedCornerShape(18.dp), color = lineColor, contentColor = Color(info.textColor)) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.defaultMinSize(minWidth = 72.dp).height(56.dp).padding(horizontal = 14.dp),
                ) {
                    Text(info.ref, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight(750)), maxLines = 1)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    info.name.ifBlank { "Line ${info.ref}" },
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                info.mode?.let { mode ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(modeIcon(mode), contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            singular(mode) + if (line.stops.isNotEmpty()) " · ${line.stops.size} stops" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
            FilledTonalIconButton(onClick = onClose, shapes = IconButtonDefaults.shapes()) {
                Icon(Ph.X, contentDescription = "Back to all lines", modifier = Modifier.size(20.dp))
            }
        }

        if (departures != null) DepartureCard(departures, lineColor)

        if (line.stops.isNotEmpty()) {
            Surface(shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    val stops = line.stops.take(80)
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

@Composable
private fun DepartureCard(board: app.wayfinder.ui.WayfinderViewModel.DepartureBoard, lineColor: Color) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(24.dp), color = colors.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Next departures", style = MaterialTheme.typography.titleMedium, color = colors.onSecondaryContainer)
                    Text(board.stop.name, style = MaterialTheme.typography.bodyMedium, color = colors.onSecondaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (board.loading) LoadingIndicator(Modifier.size(36.dp))
            }
            board.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSecondaryContainer) }
            val now = System.currentTimeMillis()
            board.items.forEach { d ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(lineColor))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        formatTime(d.timeMs),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight(700),
                            textDecoration = if (d.cancelled) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                        ),
                        color = colors.onSecondaryContainer,
                    )
                    val late = d.scheduledMs?.let { (d.timeMs - it) / 60_000 } ?: 0
                    if (late >= 1 && !d.cancelled) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            formatTime(d.scheduledMs),
                            style = MaterialTheme.typography.bodySmall.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough),
                            color = colors.onSecondaryContainer.copy(alpha = 0.7f),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        d.headsign.ifBlank { "—" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        when {
                            d.cancelled -> "Cancelled"
                            else -> {
                                val mins = ((d.timeMs - now) / 60_000).coerceAtLeast(0)
                                (if (mins == 0L) "Now" else "in $mins min") + if (d.realTime) " · live" else ""
                            }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (d.cancelled) colors.error else colors.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

internal fun singular(mode: TransitMode) = when (mode) {
    TransitMode.Train -> "Train"
    TransitMode.Metro -> "Metro"
    TransitMode.Tram -> "Tram"
    TransitMode.Bus -> "Bus"
    TransitMode.Ferry -> "Ferry"
    TransitMode.Other -> "Transit"
}

internal fun modeIcon(mode: TransitMode): ImageVector = when (mode) {
    TransitMode.Train -> Ph.Filled.Train
    TransitMode.Metro -> Ph.Filled.Subway
    TransitMode.Tram -> Ph.Filled.Tram
    TransitMode.Bus -> Ph.Filled.Bus
    TransitMode.Ferry -> Ph.Filled.Boat
    TransitMode.Other -> Ph.Filled.TrainSimple
}
