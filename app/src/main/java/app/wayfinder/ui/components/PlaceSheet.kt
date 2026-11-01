@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.wayfinder.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.wayfinder.map.MapStyle
import app.wayfinder.ui.PlaceDetail
import app.wayfinder.ui.Ph

/** A tapped place: what it is, where it is, and how to get there. */
@Composable
fun PlaceSheet(
    detail: PlaceDetail,
    planning: Boolean,
    onDirections: () -> Unit,
    onAddStop: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val group = MapStyle.poiGroupOf(detail.poiClass)
    val tint = Color(android.graphics.Color.parseColor(
        if (detail.poiClass in transportClasses) "#2F3A4A" else MapStyle.poiColorsLight.getValue(group)
    ))
    Column(
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, end = 4.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(56.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(tint),
            ) {
                Icon(placeIcon(detail.poiClass, group), contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    detail.place.name,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight(700)),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                detail.category?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                }
            }
            FilledTonalIconButton(onClick = onClose, shapes = IconButtonDefaults.shapes()) {
                Icon(Ph.X, contentDescription = "Close", modifier = Modifier.size(20.dp))
            }
        }

        detail.place.detail?.let { address ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                Icon(Ph.MapPin, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(address, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onDirections,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.weight(1f).height(56.dp),
            ) {
                Icon(Ph.Filled.NavigationArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Directions", style = MaterialTheme.typography.titleSmall)
            }
            if (planning) {
                FilledTonalButton(
                    onClick = onAddStop,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.weight(1f).height(56.dp),
                ) {
                    Icon(Ph.Strong.Plus, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Add stop", style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

private val transportClasses = setOf("railway", "bus", "aerialway", "ferry_terminal")

private fun placeIcon(poiClass: String?, group: String): ImageVector = when {
    poiClass == "railway" -> Ph.Filled.Train
    poiClass == "bus" -> Ph.Filled.Bus
    poiClass == "ferry_terminal" -> Ph.Filled.Boat
    else -> when (group) {
        "food" -> Ph.Filled.ForkKnife
        "shop" -> Ph.Filled.ShoppingBag
        "nature" -> Ph.Filled.Tree
        "health" -> Ph.Filled.FirstAid
        "culture" -> Ph.Filled.MaskHappy
        "worship" -> Ph.Filled.Church
        "education" -> Ph.Filled.GraduationCap
        "lodging" -> Ph.Filled.Bed
        "money" -> Ph.Filled.Bank
        "fuel" -> Ph.Filled.GasPump
        else -> Ph.Filled.MapPin
    }
}

/** "fast_food" → "Fast food". */
fun humanizeCategory(subclass: String?, poiClass: String?): String? {
    val raw = subclass?.takeIf { it.isNotBlank() } ?: poiClass ?: return null
    return raw.replace('_', ' ').replaceFirstChar { it.uppercase() }
}
