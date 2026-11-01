package app.wayfinder.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.wayfinder.data.LineInfo
import app.wayfinder.data.TransitMode
import app.wayfinder.ui.Ph

/** The two overlay toggles shown at the top of the map. */
@Composable
fun OverlayPills(
    transitOn: Boolean,
    transitLoading: Boolean,
    bikeOn: Boolean,
    bikeLoading: Boolean,
    onTransit: () -> Unit,
    onBike: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
        OverlayPill(
            label = "Public transport",
            icon = if (transitOn) Ph.Filled.Bus else Ph.Bus,
            on = transitOn,
            loading = transitLoading,
            onClick = onTransit,
        )
        OverlayPill(
            label = "Bike routes",
            icon = if (bikeOn) Ph.Filled.Bicycle else Ph.Bicycle,
            on = bikeOn,
            loading = bikeLoading,
            onClick = onBike,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OverlayPill(label: String, icon: ImageVector, on: Boolean, loading: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (on) colors.primary else colors.surfaceContainerHigh,
        MaterialTheme.motionScheme.defaultEffectsSpec(), label = "pillBg",
    )
    val content by animateColorAsState(
        if (on) colors.onPrimary else colors.onSurface,
        MaterialTheme.motionScheme.defaultEffectsSpec(), label = "pillFg",
    )
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = container,
        contentColor = content,
        shadowElevation = 4.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(40.dp).padding(start = 12.dp, end = 16.dp),
        ) {
            if (loading) {
                LoadingIndicator(Modifier.size(22.dp), color = content)
            } else {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Filter chips for each kind of public transport, each in its mode colour. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ModeChips(
    enabled: Set<TransitMode>,
    onToggle: (TransitMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(TransitMode.entries.toList(), key = { it.name }) { mode ->
            val on = mode in enabled
            val tint = Color(mode.colorArgb)
            val container by animateColorAsState(
                if (on) tint else MaterialTheme.colorScheme.surfaceContainerHigh,
                MaterialTheme.motionScheme.defaultEffectsSpec(), label = "modeBg",
            )
            val content by animateColorAsState(
                if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                MaterialTheme.motionScheme.defaultEffectsSpec(), label = "modeFg",
            )
            Surface(
                onClick = { onToggle(mode) },
                shape = CircleShape,
                color = container,
                contentColor = content,
                shadowElevation = 3.dp,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(34.dp).padding(start = 10.dp, end = 14.dp),
                ) {
                    Icon(modeIcon(mode), contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(mode.label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** Every line in view as a pill in its own colour with its number, grouped by mode. */
@Composable
fun LinePills(
    lines: List<LineInfo>,
    highlighted: String?,
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups = lines.take(150).groupBy { it.mode }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        groups.forEach { (mode, group) ->
            if (mode != null) {
                item(key = "group-${mode.name}") {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        shadowElevation = 2.dp,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        ) {
                            Icon(modeIcon(mode), contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(mode.label, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            items(group, key = { it.key }) { line -> LinePill(line, highlighted, onClick) }
        }
    }
}

@Composable
private fun LinePill(line: LineInfo, highlighted: String?, onClick: (String) -> Unit) {
    val selected = line.key == highlighted
    Surface(
        onClick = { onClick(line.key) },
        shape = RoundedCornerShape(10.dp),
        color = Color(line.color),
        contentColor = Color(line.textColor),
        shadowElevation = if (selected) 6.dp else 2.dp,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface) else null,
        modifier = Modifier.alpha(if (highlighted == null || selected) 1f else 0.45f),
    ) {
        Text(
            line.ref,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight(750)),
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

private fun modeIcon(mode: TransitMode): ImageVector = when (mode) {
    TransitMode.Train -> Ph.Filled.Train
    TransitMode.Metro -> Ph.Filled.Subway
    TransitMode.Tram -> Ph.Filled.Tram
    TransitMode.Bus -> Ph.Filled.Bus
    TransitMode.Ferry -> Ph.Filled.Boat
    TransitMode.Other -> Ph.Filled.TrainSimple
}
