package app.wayfinder.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import app.wayfinder.data.Leg
import app.wayfinder.data.LegMode
import app.wayfinder.map.MapController
import app.wayfinder.ui.Ph
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

fun formatDuration(seconds: Long): String {
    val minutes = ((seconds + 30) / 60).coerceAtLeast(1)
    if (minutes < 60) return "$minutes min"
    val h = minutes / 60
    val m = minutes % 60
    return if (m == 0L) "$h h" else "$h h $m min"
}

fun formatDistance(meters: Double): String = when {
    meters < 1000 -> "${((meters / 10).roundToInt() * 10).coerceAtLeast(10)} m"
    meters < 10_000 -> String.format(Locale.getDefault(), "%.1f km", meters / 1000)
    else -> "${(meters / 1000).roundToInt()} km"
}

private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

fun formatTime(ms: Long?): String = ms?.let { timeFormat.format(Instant.ofEpochMilli(it)) } ?: ""

fun modeIcon(mode: LegMode, filled: Boolean): ImageVector = when (mode) {
    LegMode.Walk -> if (filled) Ph.Filled.PersonSimpleWalk else Ph.PersonSimpleWalk
    LegMode.Bike -> if (filled) Ph.Filled.PersonSimpleBike else Ph.PersonSimpleBike
    LegMode.Bus -> if (filled) Ph.Filled.Bus else Ph.Bus
    LegMode.Tram -> if (filled) Ph.Filled.Tram else Ph.Tram
    LegMode.Subway -> if (filled) Ph.Filled.Subway else Ph.Subway
    LegMode.Rail -> if (filled) Ph.Filled.Train else Ph.Train
    LegMode.Ferry -> if (filled) Ph.Filled.Boat else Ph.Boat
    LegMode.Air -> if (filled) Ph.Filled.Airplane else Ph.Airplane
    LegMode.Other -> if (filled) Ph.Filled.TrainSimple else Ph.TrainSimple
}

fun modeName(mode: LegMode) = when (mode) {
    LegMode.Walk -> "Walk"
    LegMode.Bike -> "Cycle"
    LegMode.Bus -> "Bus"
    LegMode.Tram -> "Tram"
    LegMode.Subway -> "Metro"
    LegMode.Rail -> "Train"
    LegMode.Ferry -> "Ferry"
    LegMode.Air -> "Flight"
    LegMode.Other -> "Transit"
}

fun legColor(leg: Leg): Color = Color(leg.color ?: MapController.defaultLegColor(leg.mode))

fun legTextColor(leg: Leg): Color {
    leg.textColor?.let { tc ->
        // Feeds sometimes ship identical route and text colours; guard against invisible text.
        if (tc != leg.color) return Color(tc)
    }
    return if (legColor(leg).luminance() > 0.55f) Color(0xFF1B1B1F) else Color.White
}
