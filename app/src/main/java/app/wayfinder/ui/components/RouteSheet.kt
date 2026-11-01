package app.wayfinder.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
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
    val isTransit = mode == TravelMode.Transit || mode == TravelMode.Train
    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
        when {
            loading -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth().height(220.dp),
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
                modifier = Modifier.fillMaxWidth().height(220.dp).padding(horizontal = 32.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(56.dp).clip(MaterialShapes.Cookie6Sided.toShape())
                        .background(MaterialTheme.colorScheme.errorContainer),
                ) {
                    Icon(
                        Ph.WarningCircle, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    error,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRetry, shapes = ButtonDefaults.shapes()) {
                    Icon(Ph.ArrowClockwise, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Try again")
                }
            }

            isTransit -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        if (mode == TravelMode.Train) "Train connections" else "Routes",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 4.dp),
                    )
                }
                itemsIndexed(itineraries) { index, itinerary ->
                    TransitCard(itinerary, selected = index == selected, onClick = { onSelect(index) })
                }
                item { Credit("Timetables via Transitous · Map data © OpenStreetMap contributors") }
            }

            else -> {
                val current = itineraries.getOrNull(selected)
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (current != null) {
                        item { ActiveSummary(current, mode) }
                        item { Steps(current.legs.firstOrNull()?.steps.orEmpty()) }
                    }
                    if (itineraries.size > 1) {
                        item {
                            Text(
                                "Other routes",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                            )
                        }
                        itemsIndexed(itineraries) { index, itinerary ->
                            if (index != selected) AlternativeCard(itinerary, onClick = { onSelect(index) })
                        }
                    }
                    item { Credit("Routing by FOSSGIS OSRM · Map data © OpenStreetMap contributors") }
                }
            }
        }
    }
}

@Composable
private fun Credit(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
    )
}

/** Transit option. The chosen one expands (shape and colour morph) to show the full timeline. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TransitCard(itinerary: Itinerary, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (selected) colors.secondaryContainer else colors.surfaceContainerHighest,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "cardColor",
    )
    val corner by animateDpAsState(
        if (selected) 28.dp else 20.dp,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "cardCorner",
    )
    Surface(onClick = onClick, shape = RoundedCornerShape(corner), color = container, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = if (selected) 18.dp else 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatDuration(itinerary.durationS),
                    style = if (selected) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                if (itinerary.startMs != null) {
                    Text(
                        "${formatTime(itinerary.startMs)} – ${formatTime(itinerary.endMs)}",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            LegStrip(itinerary.legs)
            Spacer(Modifier.height(8.dp))
            Text(summary(itinerary, isTransit = true), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            AnimatedVisibility(visible = selected) {
                Column {
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = colors.outlineVariant)
                    Spacer(Modifier.height(14.dp))
                    Timeline(itinerary)
                }
            }
        }
    }
}

/** Walking / cycling: the active route's headline numbers and an expressive mode badge. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ActiveSummary(itinerary: Itinerary, mode: TravelMode) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(formatDuration(itinerary.durationS), style = MaterialTheme.typography.displaySmall)
            Text(
                summary(itinerary, isTransit = false),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(60.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(colors.primaryContainer),
        ) {
            Icon(
                if (mode == TravelMode.Cycle) Ph.Filled.PersonSimpleBike else Ph.Filled.PersonSimpleWalk,
                contentDescription = null,
                tint = colors.onPrimaryContainer,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun AlternativeCard(itinerary: Itinerary, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text(formatDuration(itinerary.durationS), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Text(
                itinerary.distanceM?.let { formatDistance(it) } ?: "",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

/** Turn-by-turn list: one grouped surface, round tonal icons, distances right-aligned. */
@Composable
private fun Steps(steps: List<Step>) {
    val colors = MaterialTheme.colorScheme
    if (steps.isEmpty()) return
    Surface(shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 6.dp)) {
            steps.take(40).forEachIndexed { index, step ->
                if (index > 0) HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.6f), modifier = Modifier.padding(start = 70.dp, end = 16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(40.dp).clip(CircleShape).background(colors.secondaryContainer),
                    ) {
                        Icon(stepIcon(step.text), contentDescription = null, tint = colors.onSecondaryContainer, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(step.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    if (step.distanceM >= 1) {
                        Spacer(Modifier.width(8.dp))
                        Text(formatDistance(step.distanceM), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun stepIcon(text: String) = when {
    text.startsWith("Arrive") -> Ph.Filled.FlagCheckered
    text.contains("U-turn") -> Ph.ArrowUUpLeft
    text.contains("roundabout", ignoreCase = true) -> Ph.ArrowsClockwise
    text.contains("left", ignoreCase = true) -> Ph.ArrowBendUpLeft
    text.contains("right", ignoreCase = true) -> Ph.ArrowBendUpRight
    else -> Ph.ArrowUp
}
