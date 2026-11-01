package app.wayfinder.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.wayfinder.data.Itinerary
import app.wayfinder.data.Leg
import app.wayfinder.data.LegMode
import app.wayfinder.data.Step
import app.wayfinder.data.TravelMode
import app.wayfinder.ui.Ph

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RouteSheet(
    mode: TravelMode,
    itineraries: List<Itinerary>,
    selected: Int,
    loading: Boolean,
    error: String?,
    onSelect: (Int) -> Unit,
    onRetry: () -> Unit,
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.82f).dp
    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, bottom = 8.dp),
        ) {
            Text(
                when (mode) {
                    TravelMode.Transit -> "Public transport"
                    TravelMode.Train -> "Trains"
                    TravelMode.Walk -> "Walking"
                    TravelMode.Cycle -> "Cycling"
                },
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
        }

        when {
            loading -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth().height(200.dp),
            ) {
                ContainedLoadingIndicator(Modifier.size(64.dp))
                Spacer(Modifier.height(16.dp))
                Text(
                    "Finding the best routes…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            error != null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth().height(200.dp).padding(horizontal = 32.dp),
            ) {
                Icon(
                    Ph.WarningCircle, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(36.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(error, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRetry) {
                    Icon(Ph.ArrowClockwise, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Try again")
                }
            }

            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(itineraries) { index, itinerary ->
                    ItineraryCard(
                        itinerary = itinerary,
                        mode = mode,
                        selected = index == selected,
                        onClick = { onSelect(index) },
                    )
                }
                item {
                    Text(
                        if (mode == TravelMode.Walk || mode == TravelMode.Cycle) {
                            "Routing by FOSSGIS OSRM · Map data © OpenStreetMap contributors"
                        } else {
                            "Timetables via Transitous · Map data © OpenStreetMap contributors"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ItineraryCard(itinerary: Itinerary, mode: TravelMode, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (selected) colors.secondaryContainer else colors.surfaceContainerHighest,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "cardColor",
    )
    val corner by animateDpAsState(
        if (selected) 32.dp else 20.dp,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "cardCorner",
    )
    val isTransit = mode == TravelMode.Transit || mode == TravelMode.Train
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(corner),
        color = container,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    formatDuration(itinerary.durationS),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f),
                )
                if (isTransit && itinerary.startMs != null) {
                    Text(
                        "${formatTime(itinerary.startMs)} – ${formatTime(itinerary.endMs)}",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                summary(itinerary, isTransit),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            if (isTransit) {
                Spacer(Modifier.height(12.dp))
                LegStrip(itinerary.legs)
            }
            AnimatedVisibility(visible = selected) {
                Column {
                    Spacer(Modifier.height(16.dp))
                    if (isTransit) Timeline(itinerary) else Steps(itinerary.legs.firstOrNull()?.steps.orEmpty())
                }
            }
        }
    }
}

private fun summary(itinerary: Itinerary, isTransit: Boolean): String = if (isTransit) {
    buildList {
        add(
            when (itinerary.transfers) {
                0 -> "Direct"
                1 -> "1 change"
                else -> "${itinerary.transfers} changes"
            }
        )
        val walk = itinerary.walkSeconds
        if (walk >= 60) add("${formatDuration(walk)} walk")
    }.joinToString(" · ")
} else {
    listOfNotNull(
        itinerary.distanceM?.let { formatDistance(it) },
        "arrive ${formatTime(itinerary.endMs)}",
    ).joinToString(" · ")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LegStrip(legs: List<Leg>) {
    val visible = legs.filter { it.mode.isTransit || it.durationS >= 60 }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        visible.forEachIndexed { i, leg ->
            if (i > 0) {
                Icon(
                    Ph.CaretRight, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp).align(Alignment.CenterVertically),
                )
            }
            if (leg.mode.isTransit) {
                LineBadge(leg, Modifier.align(Alignment.CenterVertically))
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.align(Alignment.CenterVertically),
                ) {
                    Icon(
                        modeIcon(leg.mode, filled = true), contentDescription = modeName(leg.mode),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        "${((leg.durationS + 30) / 60).coerceAtLeast(1)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun LineBadge(leg: Leg, modifier: Modifier = Modifier) {
    val bg = legColor(leg)
    val fg = legTextColor(leg)
    Surface(shape = RoundedCornerShape(10.dp), color = bg, modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Icon(modeIcon(leg.mode, filled = true), contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                leg.line ?: modeName(leg.mode),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight(700)),
                color = fg,
                maxLines = 1,
            )
        }
    }
}

/** Vertical trip timeline: stations, lines and walking connections. */
@Composable
private fun Timeline(itinerary: Itinerary) {
    val colors = MaterialTheme.colorScheme
    Column {
        itinerary.legs.forEachIndexed { index, leg ->
            if (!leg.mode.isTransit) {
                if (leg.durationS < 30 && index != 0) return@forEachIndexed
                TimelineRow(
                    time = if (index == 0) formatTime(leg.startMs) else "",
                    rail = { DottedRail(colors.outline) },
                ) {
                    if (index == 0 && leg.fromName.isNotBlank()) {
                        Text(leg.fromName.let { if (it == "START") "Start" else it }, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                        Icon(
                            modeIcon(leg.mode, filled = false), contentDescription = null,
                            tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${modeName(leg.mode)} ${formatDuration(leg.durationS)}" +
                                (leg.distanceM?.takeIf { it > 0 }?.let { " · ${formatDistance(it)}" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            } else {
                val line = legColor(leg)
                TimelineRow(
                    time = formatTime(leg.startMs),
                    endTime = formatTime(leg.endMs),
                    rail = { SolidRail(line, colors.surface) },
                ) {
                    Text(leg.fromName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LineBadge(leg)
                        leg.headsign?.let {
                            Spacer(Modifier.width(8.dp))
                            Icon(Ph.ArrowRight, contentDescription = null, modifier = Modifier.size(14.dp), tint = colors.onSurfaceVariant)
                            Spacer(Modifier.width(4.dp))
                            Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        buildList {
                            val stops = leg.intermediateStops + 1
                            add(if (stops == 1) "1 stop" else "$stops stops")
                            add(formatDuration(leg.durationS))
                            leg.agency?.let { add(it) }
                            if (leg.realTime) add("live")
                        }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(leg.toName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        TimelineRow(time = formatTime(itinerary.endMs), rail = {
            Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                Icon(Ph.Filled.FlagCheckered, contentDescription = null, tint = colors.tertiary, modifier = Modifier.size(18.dp))
            }
        }) {
            Text("Arrive", style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun TimelineRow(
    time: String,
    endTime: String? = null,
    rail: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(Modifier.width(52.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Text(time, style = MaterialTheme.typography.labelLarge)
            if (endTime != null) Text(endTime, style = MaterialTheme.typography.labelLarge)
        }
        Box(Modifier.width(28.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) { rail() }
        Column(Modifier.weight(1f).padding(start = 8.dp, bottom = 12.dp)) { content() }
    }
}

@Composable
private fun SolidRail(color: Color, surface: Color) {
    Canvas(Modifier.width(16.dp).fillMaxHeight()) {
        val x = size.width / 2
        val r = 6.dp.toPx()
        drawLine(color, Offset(x, r), Offset(x, size.height - r), strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
        listOf(r, size.height - r).forEach { y ->
            drawCircle(color, radius = r, center = Offset(x, y))
            drawCircle(surface, radius = r - 2.5.dp.toPx(), center = Offset(x, y))
        }
    }
}

@Composable
private fun DottedRail(color: Color) {
    Canvas(Modifier.width(16.dp).fillMaxHeight()) {
        val x = size.width / 2
        val gap = 7.dp.toPx()
        var y = gap / 2
        while (y < size.height) {
            drawCircle(color, radius = 2.dp.toPx(), center = Offset(x, y))
            y += gap
        }
    }
}

@Composable
private fun Steps(steps: List<Step>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        steps.take(40).forEach { step ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                val icon = when {
                    step.text.startsWith("Arrive") -> Ph.Filled.FlagCheckered
                    step.text.contains("left", ignoreCase = true) || step.text.contains("right", ignoreCase = true) -> Ph.Signpost
                    else -> Ph.ArrowRight
                }
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(14.dp))
                Text(step.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (step.distanceM >= 1) {
                    Text(
                        formatDistance(step.distanceM),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

