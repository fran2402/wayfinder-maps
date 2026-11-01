@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.wayfinder.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.wayfinder.data.TransitMode
import app.wayfinder.map.MapPalette
import app.wayfinder.ui.Ph

/** "Map key": what each line style, route style and map colour means. */
@Composable
fun MapKeySheet(palette: MapPalette, onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.82f).dp
    val surface = Color(android.graphics.Color.parseColor(palette.surface))
    fun c(hex: String) = Color(android.graphics.Color.parseColor(hex))
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, end = 4.dp)) {
            Text(
                "Map key",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight(700)),
                modifier = Modifier.weight(1f),
            )
            FilledTonalIconButton(onClick = onClose, shapes = IconButtonDefaults.shapes()) {
                Icon(Ph.X, contentDescription = "Close map key", modifier = Modifier.size(20.dp))
            }
        }

        KeySection("Public transport", footnote = "Lines use their operator's colour where known. The style shows the type.") {
            KeyGrid(
                listOf(
                    "Train" to { s: DrawScope -> s.railway(Color(TransitMode.Train.colorArgb), surface) },
                    "Metro" to { s: DrawScope -> s.solid(Color(TransitMode.Metro.colorArgb), 7f) },
                    "Tram" to { s: DrawScope -> s.tram(Color(TransitMode.Tram.colorArgb), surface) },
                    "Bus" to { s: DrawScope -> s.solid(Color(TransitMode.Bus.colorArgb), 3f) },
                    "Ferry" to { s: DrawScope -> s.dashed(Color(TransitMode.Ferry.colorArgb), 3f, 9f, 6f) },
                    "Cable car" to { s: DrawScope -> s.dotted(Color(TransitMode.Other.colorArgb)) },
                )
            )
        }

        KeySection("Your trip") {
            KeyGrid(
                listOf(
                    "Ride" to { s: DrawScope -> s.solid(Color(TransitMode.Bus.colorArgb), 6f, casing = surface) },
                    "Walk" to { s: DrawScope -> s.dotted(colors.primary) },
                    "Cycle" to { s: DrawScope -> s.solid(colors.primary, 6f) },
                    "Other option" to { s: DrawScope -> s.solid(c(palette.alternative), 5f) },
                )
            )
        }

        KeySection("Map") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    Triple("Park", c(palette.park), null),
                    Triple("Water", c(palette.water), null),
                    Triple("Building", c(palette.building), c(palette.buildingLine)),
                    Triple("Motorway", c(palette.motorway), c(palette.motorwayCasing)),
                    Triple("Cycleway", c(palette.cycleway), null),
                    Triple("Railway", c(palette.rail), null),
                ).chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { (label, fill, outline) ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Box(
                                    Modifier
                                        .size(width = 28.dp, height = 18.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(fill)
                                        .then(if (outline != null) Modifier.border(1.5.dp, outline, RoundedCornerShape(5.dp)) else Modifier)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(label, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeySection(title: String, footnote: String? = null, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
            if (footnote != null) {
                Text(footnote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun KeyGrid(items: List<Pair<String, (DrawScope) -> Unit>>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (label, draw) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Canvas(Modifier.size(width = 56.dp, height = 16.dp)) { draw(this) }
                        Spacer(Modifier.width(10.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

// ---------- Swatches, matching the map's line styles ----------

private fun DrawScope.ends(inset: Float) = Offset(inset, size.height / 2) to Offset(size.width - inset, size.height / 2)

private fun DrawScope.solid(color: Color, widthDp: Float, casing: Color? = null) {
    val (a, b) = ends(4.dp.toPx())
    if (casing != null) drawLine(casing, a, b, strokeWidth = (widthDp + 4).dp.toPx(), cap = StrokeCap.Round)
    drawLine(color, a, b, strokeWidth = widthDp.dp.toPx(), cap = StrokeCap.Round)
}

private fun DrawScope.railway(color: Color, ties: Color) {
    solid(color, 6f)
    val (a, b) = ends(7.dp.toPx())
    drawLine(ties, a, b, strokeWidth = 1.6.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx())))
}

private fun DrawScope.tram(color: Color, track: Color) {
    solid(color, 5f)
    val (a, b) = ends(4.dp.toPx())
    drawLine(track, a, b, strokeWidth = 1.2.dp.toPx())
}

private fun DrawScope.dashed(color: Color, widthDp: Float, on: Float, off: Float) {
    val (a, b) = ends(4.dp.toPx())
    drawLine(
        color, a, b, strokeWidth = widthDp.dp.toPx(), cap = StrokeCap.Round,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(on.dp.toPx(), off.dp.toPx())),
    )
}

private fun DrawScope.dotted(color: Color) {
    val (a, b) = ends(5.dp.toPx())
    drawLine(
        color, a, b, strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.1f, 9.dp.toPx())),
    )
}
