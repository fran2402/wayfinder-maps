@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.wayfinder.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.wayfinder.ui.Ph

/**
 * Bottom floating toolbar (vibrant, primary container) holding the map layer toggles, paired
 * with the location FAB — the Material 3 Expressive "floating toolbar + FAB" arrangement.
 */
@Composable
fun MapToolbar(
    showLayers: Boolean,
    linesOn: Boolean,
    linesLoading: Boolean,
    bikesOn: Boolean,
    bikesLoading: Boolean,
    hasLocation: Boolean,
    onLines: () -> Unit,
    onBikes: () -> Unit,
    settingsOn: Boolean,
    onSettings: () -> Unit,
    onLocate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = modifier) {
        if (showLayers) {
            Surface(
                shape = CircleShape,
                color = colors.primaryContainer,
                contentColor = colors.onPrimaryContainer,
                shadowElevation = 6.dp,
                modifier = Modifier.weight(1f).height(64.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(8.dp),
                ) {
                    ToolbarToggle("Lines", if (linesOn) Ph.Filled.Bus else Ph.Bus, linesOn, linesLoading, onLines, Modifier.weight(1f))
                    ToolbarToggle("Bikes", if (bikesOn) Ph.Filled.Bicycle else Ph.Bicycle, bikesOn, bikesLoading, onBikes, Modifier.weight(1f))
                    androidx.compose.material3.FilledIconToggleButton(
                        checked = settingsOn,
                        onCheckedChange = { onSettings() },
                        colors = androidx.compose.material3.IconButtonDefaults.filledIconToggleButtonColors(
                            containerColor = Color.Transparent,
                            contentColor = colors.onPrimaryContainer,
                            checkedContainerColor = colors.primary,
                            checkedContentColor = colors.onPrimary,
                        ),
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Ph.SlidersHorizontal, contentDescription = "Map settings", modifier = Modifier.size(22.dp))
                    }
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        FloatingActionButton(
            onClick = onLocate,
            shape = RoundedCornerShape(22.dp),
            containerColor = colors.primary,
            contentColor = colors.onPrimary,
            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
            modifier = Modifier.size(64.dp),
        ) {
            Icon(
                if (hasLocation) Ph.Filled.GpsFix else Ph.Crosshair,
                contentDescription = "My location",
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

@Composable
private fun ToolbarToggle(
    label: String,
    icon: ImageVector,
    on: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    ToggleButton(
        checked = on,
        onCheckedChange = { onClick() },
        colors = ToggleButtonDefaults.toggleButtonColors(
            containerColor = Color.Transparent,
            contentColor = colors.onPrimaryContainer,
            checkedContainerColor = colors.primary,
            checkedContentColor = colors.onPrimary,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = modifier.height(48.dp),
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            if (loading) LoadingIndicator(Modifier.size(24.dp), color = androidx.compose.material3.LocalContentColor.current) else Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
