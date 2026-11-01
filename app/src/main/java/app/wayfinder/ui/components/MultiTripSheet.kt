@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.wayfinder.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.wayfinder.data.Itinerary
import app.wayfinder.data.TravelMode
import app.wayfinder.ui.TripSegment

/**
 * A trip with stops: the mode for every leg, the total, then one card per leg with its own
 * mode group and options.
 */
@Composable
fun MultiTripSheet(
    mode: TravelMode,
    segments: List<TripSegment>,
    combined: Itinerary?,
    loading: Boolean,
    onMode: (TravelMode) -> Unit,
    onSegmentMode: (Int, TravelMode) -> Unit,
    onOption: (Int, Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.82f).dp
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
    ) {
        ModeSelector(mode = mode, onSelect = onMode)

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    combined?.let { formatDuration(it.durationS) } ?: "Planning…",
                    style = MaterialTheme.typography.displaySmall,
                )
                Text(
                    buildList {
                        add("${segments.size} legs")
                        combined?.endMs?.let { add("arrive ${formatTime(it)}") }
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurfaceVariant,
                )
            }
            if (loading) LoadingIndicator(Modifier.size(44.dp))
        }

        segments.forEachIndexed { i, seg -> SegmentCard(i, seg, onSegmentMode, onOption) }
    }
}

@Composable
private fun SegmentCard(
    index: Int,
    seg: TripSegment,
    onSegmentMode: (Int, TravelMode) -> Unit,
    onOption: (Int, Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val transit = seg.mode == TravelMode.Transit || seg.mode == TravelMode.Train
    Surface(shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(28.dp).clip(CircleShape).background(colors.primary),
                ) {
                    Text("${index + 1}", style = MaterialTheme.typography.labelLarge, color = colors.onPrimary)
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    "To ${seg.to.name}",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            CompactModeSelector(mode = seg.mode, onSelect = { onSegmentMode(index, it) }, modifier = Modifier.fillMaxWidth())

            val chosen = seg.chosen
            when {
                seg.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(Modifier.size(32.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Finding routes…", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                seg.error != null -> Text(seg.error, style = MaterialTheme.typography.bodyMedium, color = colors.error)
                chosen != null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            formatDuration(chosen.durationS),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight(650)),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (transit) "${formatTime(chosen.startMs)} – ${formatTime(chosen.endMs)}"
                            else listOfNotNull(chosen.distanceM?.let { formatDistance(it) }, "arrive ${formatTime(chosen.endMs)}").joinToString(" · "),
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    if (transit) LegStrip(chosen.legs)
                    if (seg.options.size > 1) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            seg.options.forEachIndexed { k, option ->
                                FilterChip(
                                    selected = k == seg.selected,
                                    onClick = { onOption(index, k) },
                                    label = {
                                        Text(
                                            if (transit && option.startMs != null) "${formatTime(option.startMs)} · ${formatDuration(option.durationS)}"
                                            else formatDuration(option.durationS)
                                        )
                                    },
                                    shape = CircleShape,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
