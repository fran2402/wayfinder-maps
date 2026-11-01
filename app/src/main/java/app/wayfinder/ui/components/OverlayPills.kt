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

/** Every line in view as a pill in its own colour showing its line number. */
@Composable
fun LinePills(
    lines: List<LineInfo>,
    highlighted: String?,
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(lines.take(120), key = { it.key }) { line ->
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                ) {
                    Icon(routeIcon(line.route), contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        line.ref,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight(750)),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun routeIcon(route: String): ImageVector = when (route) {
    "bus", "trolleybus" -> Ph.Filled.Bus
    "tram" -> Ph.Filled.Tram
    "subway" -> Ph.Filled.Subway
    "train", "light_rail", "monorail" -> Ph.Filled.Train
    "ferry" -> Ph.Filled.Boat
    else -> Ph.Filled.TrainSimple
}
