@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.wayfinder.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.wayfinder.ui.Ph
import kotlin.math.roundToInt

/** The few settings that don't belong on the layers sheet. */
@Composable
fun SettingsPage(
    minLineZoom: Float,
    onMinLineZoom: (Float) -> Unit,
    shading: Boolean,
    onShading: (Boolean) -> Unit,
    relief: Boolean,
    onRelief: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surface, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
                    Icon(Ph.ArrowLeft, contentDescription = "Back", modifier = Modifier.size(22.dp))
                }
                Text("Settings", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight(700)))
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Label("Lines on the map")
                Surface(shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerLow) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Show lines from", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Text("Zoom ${minLineZoom.toInt()} · ${zoomName(minLineZoom)}", style = MaterialTheme.typography.labelLarge, color = colors.primary)
                        }
                        Slider(
                            value = minLineZoom,
                            onValueChange = { onMinLineZoom(it.roundToInt().toFloat()) },
                            valueRange = 0f..18f,
                            steps = 17,
                        )
                        Text(
                            "Lower shows lines on a zoomed-out map. Line numbers appear when zoomed in close, " +
                                "and lines sharing a street sit side by side only when zoomed in very close.",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        )
                    }
                }
                Label("Terrain")
                Surface(shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerLow) {
                    Column {
                        SwitchRow("Hill shading", "Shadows that show the shape of the land", shading, onShading)
                        HorizontalDivider(color = colors.outlineVariant, modifier = Modifier.padding(horizontal = 18.dp))
                        SwitchRow("Elevation and depth", "Colour land by height and the sea by depth", relief, onRelief)
                    }
                }
                Label("About")
                Text(
                    "Map labels are in English where OpenStreetMap has them. Times use the 24-hour clock. " +
                        "Lines and departures from Transitous, map data © OpenStreetMap contributors.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, top = 4.dp))
}

@Composable
private fun SwitchRow(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = null)
    }
}

private fun zoomName(z: Float) = when {
    z < 3 -> "World"
    z < 6 -> "Continent"
    z < 8 -> "Country"
    z < 11 -> "Region"
    z < 14 -> "City"
    else -> "Streets"
}
