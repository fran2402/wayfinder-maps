@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.wayfinder.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wayfinder.data.LineInfo
import app.wayfinder.data.LoadedLine
import app.wayfinder.data.StopRef
import app.wayfinder.data.TransitMode
import app.wayfinder.data.TransitRoutes
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A line drawn the way a metro diagram shows it: its track running down the stops, with
 *  - thin strands beside the track for every other line sharing that stretch,
 *  - short stubs for lines that only meet it at a stop,
 *  - a capsule at stops where you can change, a dot where you can't,
 *  - the badges of the lines to change to, and minutes from the first stop.
 */
@Composable
fun LineDiagram(
    line: LoadedLine,
    network: List<LoadedLine>,
    selectedStopId: String?,
    onStop: (StopRef) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var reversed by rememberSaveable(line.info.key) { mutableStateOf(false) }
    val stops = if (reversed) line.stops.reversed() else line.stops
    if (stops.size < 2) return

    // Who meets the line where: computed once per line and direction.
    val model = remember(line.info.key, reversed, network.size) { buildModel(line, stops, network) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Direction: a connected button pair.
        Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween), modifier = Modifier.fillMaxWidth()) {
            listOf(false to line.stops.last().name, true to line.stops.first().name).forEachIndexed { i, (rev, to) ->
                ToggleButton(
                    checked = reversed == rev,
                    onCheckedChange = { reversed = rev },
                    shapes = if (i == 0) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes(),
                    modifier = Modifier.weight(1f).height(44.dp),
                ) { Text("To $to", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge) }
            }
        }

        Box(Modifier.fillMaxWidth()) {
            val lineColor = Color(line.info.color)
            val ring = if (colors.surface.luminanceApprox() < 0.5f) Color(0xFF0B0D10) else Color(0xFF23272B)
            val fill = if (colors.surface.luminanceApprox() < 0.5f) Color(0xFFEEF1F4) else Color.White
            Canvas(Modifier.matchParentSize()) {
                val row = ROW.toPx()
                val x = TRACK_X.toPx()
                val y = { i: Int -> row * i + row / 2 }
                // Strands of lines sharing each stretch, to the left of the track.
                model.shared.forEachIndexed { i, others ->
                    others.forEachIndexed { k, o ->
                        val sx = x - 12.dp.toPx() - k * 7.dp.toPx()
                        drawLine(Color(o.color), Offset(sx, y(i)), Offset(sx, y(i + 1)),
                            strokeWidth = (if (o.mode == TransitMode.Bus) 2 else 3).dp.toPx(), cap = StrokeCap.Round)
                    }
                }
                // Stubs of lines that only cross at a stop.
                model.crossing.forEachIndexed { i, others ->
                    others.take(4).forEachIndexed { k, o ->
                        val yy = y(i) - 5.dp.toPx() + k * 4.dp.toPx()
                        drawLine(Color(o.color), Offset(x - 40.dp.toPx() - k * 3.dp.toPx(), yy), Offset(x - 12.dp.toPx(), yy),
                            strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
                    }
                }
                // The line itself.
                drawLine(lineColor, Offset(x, y(0)), Offset(x, y(stops.lastIndex)), strokeWidth = 7.dp.toPx(), cap = StrokeCap.Round)
                // Stops: a capsule where lines meet, a dot elsewhere (bigger at the ends).
                stops.indices.forEach { i ->
                    val n = model.meet[i].size
                    val c = Offset(x, y(i))
                    if (n > 0) {
                        val h = (14 + minOf(n, 6) * 3).dp.toPx(); val w = 20.dp.toPx()
                        val tl = Offset(c.x - w / 2, c.y - h / 2)
                        drawRoundRect(fill, tl, Size(w, h), CornerRadius(w / 2))
                        drawRoundRect(ring, tl, Size(w, h), CornerRadius(w / 2), style = Stroke(2.dp.toPx()))
                    } else {
                        val r = (if (i == 0 || i == stops.lastIndex) 7 else 5.5f).dp.toPx()
                        drawCircle(fill, r, c)
                        drawCircle(ring, r, c, style = Stroke(2.dp.toPx()))
                    }
                }
            }
            Column {
                stops.forEachIndexed { i, stop ->
                    val end = i == 0 || i == stops.lastIndex
                    val selected = selectedStopId != null && selectedStopId == stop.id
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ROW)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selected) colors.secondaryContainer.copy(alpha = 0.7f) else Color.Transparent)
                            .clickable(role = Role.Button, onClickLabel = "Show departures") { onStop(stop) }
                            .padding(start = TRACK_X + 26.dp, end = 4.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stop.name,
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight(if (end || selected) 700 else 550)),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                if (i == 0) "start" else "≈ ${model.minutes[i]} min",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                            val meet = model.meet[i]
                            meet.take(4).forEach { MiniBadge(it) }
                            if (meet.size > 4) Text("+${meet.size - 4}", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        Text(
            "Tap a stop for its departures. Times are estimates from the distance between stops.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** A small line-number badge in the line's colour; the shape tells the mode. */
@Composable
fun MiniBadge(info: LineInfo) {
    val shape = when (info.mode) {
        TransitMode.Bus, TransitMode.Coach -> RoundedCornerShape(9.dp)
        TransitMode.Train -> RoundedCornerShape(3.dp)
        else -> RoundedCornerShape(6.dp)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .heightIn(min = 18.dp)
            .clip(shape)
            .background(Color(info.color))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(info.ref, color = Color(info.textColor), fontSize = 11.sp, fontWeight = FontWeight(750), maxLines = 1)
    }
}

private val ROW = 50.dp
private val TRACK_X = 66.dp

private class DiagramModel(
    /** Per stretch between stop i and i+1: other lines running along it. */
    val shared: List<List<LineInfo>>,
    /** Per stop: other lines that stop there but don't run alongside. */
    val crossing: List<List<LineInfo>>,
    /** Per stop: every other line to change to there. */
    val meet: List<List<LineInfo>>,
    /** Per stop: minutes from the first stop (estimated). */
    val minutes: List<Int>,
)

private fun buildModel(line: LoadedLine, stops: List<StopRef>, network: List<LoadedLine>): DiagramModel {
    val keys = stops.map(TransitRoutes::stationKey)
    val others = network.filter { it.info.key != line.info.key && it.stops.isNotEmpty() }
        .map { o -> o to o.stops.map(TransitRoutes::stationKey) }
    val order = compareBy<LineInfo> { it.mode?.ordinal ?: 99 }.thenComparator { a, b -> app.wayfinder.data.Palette.naturalCompare(a.ref, b.ref) }
    val meet = keys.map { k -> others.filter { (_, ks) -> k in ks }.map { it.first.info }.distinctBy { it.key }.sortedWith(order) }
    val shared = (0 until keys.size - 1).map { i ->
        val a = keys[i]; val b = keys[i + 1]
        others.filter { (_, ks) -> (0 until ks.size - 1).any { j -> (ks[j] == a && ks[j + 1] == b) || (ks[j] == b && ks[j + 1] == a) } }
            .map { it.first.info }.distinctBy { it.key }.sortedWith(order)
    }
    val crossing = keys.indices.map { i ->
        val along = (if (i > 0) shared[i - 1] else emptyList()) + (if (i < shared.size) shared[i] else emptyList())
        meet[i].filter { m -> along.none { it.key == m.key } }
    }
    // Rough running time: average speed by mode, plus a short stop at each station.
    val speed = when (line.info.mode) { TransitMode.Train -> 55.0; TransitMode.Metro -> 32.0; TransitMode.Bus -> 20.0; else -> 17.0 }
    var t = 0.0
    val minutes = stops.indices.map { i ->
        if (i > 0) t += metres(stops[i - 1], stops[i]) / 1000.0 / speed * 60.0 + 0.4
        t.roundToInt()
    }
    return DiagramModel(shared, crossing, meet, minutes)
}

private fun metres(a: StopRef, b: StopRef): Double {
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon) * cos(Math.toRadians(a.lat))
    return 6_371_000.0 * sqrt(dLat * dLat + dLon * dLon)
}

private fun Color.luminanceApprox() = 0.2126f * red + 0.7152f * green + 0.0722f * blue
