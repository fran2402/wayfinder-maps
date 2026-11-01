@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.wayfinder.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.wayfinder.data.Place
import app.wayfinder.data.TravelMode
import app.wayfinder.ui.Ph

/** Start, stops and destination, shown while planning a trip. */
@Composable
fun TripCard(
    origin: Place?,
    destination: Place,
    stops: List<Place>,
    onBack: () -> Unit,
    onEditOrigin: () -> Unit,
    onEditDestination: () -> Unit,
    onSwap: () -> Unit,
    onAddStop: () -> Unit,
    onRemoveStop: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = colors.surfaceContainerHigh,
        shadowElevation = 6.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) { Icon(Ph.ArrowLeft, contentDescription = "Close directions") }
            Column(Modifier.weight(1f)) {
                Endpoint(
                    marker = { Box(Modifier.size(14.dp).border(4.dp, colors.primary, CircleShape)) },
                    text = origin?.name ?: "Your location",
                    emphasised = origin == null,
                    onClick = onEditOrigin,
                )
                stops.forEachIndexed { i, stop ->
                    Divider()
                    Endpoint(
                        marker = {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(20.dp).clip(CircleShape).background(colors.primary),
                            ) {
                                Text("${i + 1}", style = MaterialTheme.typography.labelSmall, color = colors.onPrimary)
                            }
                        },
                        text = stop.name,
                        emphasised = false,
                        onClick = {},
                        trailing = {
                            IconButton(onClick = { onRemoveStop(i) }, modifier = Modifier.size(40.dp)) {
                                Icon(Ph.X, contentDescription = "Remove stop ${stop.name}", modifier = Modifier.size(18.dp))
                            }
                        },
                    )
                }
                Divider()
                Endpoint(
                    marker = { Icon(Ph.Filled.MapPin, contentDescription = null, tint = colors.tertiary, modifier = Modifier.size(18.dp)) },
                    text = destination.name,
                    emphasised = false,
                    onClick = onEditDestination,
                )
                Divider()
                Endpoint(
                    marker = { Icon(Ph.Strong.Plus, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp)) },
                    text = "Add stop",
                    emphasised = true,
                    onClick = onAddStop,
                )
            }
            if (stops.isEmpty()) {
                FilledTonalIconButton(onClick = onSwap, modifier = Modifier.padding(horizontal = 4.dp)) {
                    Icon(Ph.ArrowsDownUp, contentDescription = "Swap start and destination")
                }
            }
        }
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 36.dp, end = 8.dp))
}

/** Icon-only connected mode group for one leg of a multi-stop trip. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CompactModeSelector(mode: TravelMode, onSelect: (TravelMode) -> Unit, modifier: Modifier = Modifier) {
    val options = modeOptions
    Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween), modifier = modifier) {
        options.forEachIndexed { index, option ->
            val checked = option.mode == mode
            ToggleButton(
                checked = checked,
                onCheckedChange = { onSelect(option.mode) },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                colors = ToggleButtonDefaults.toggleButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    checkedContainerColor = MaterialTheme.colorScheme.primary,
                    checkedContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.weight(1f).height(40.dp).semantics { role = Role.RadioButton },
            ) {
                Icon(if (checked) option.selectedIcon else option.icon, contentDescription = option.label, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun Endpoint(
    marker: @Composable () -> Unit,
    text: String,
    emphasised: Boolean,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = if (trailing != null) 0.dp else 8.dp),
    ) {
        Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) { marker() }
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (emphasised) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

private data class ModeOption(val mode: TravelMode, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val modeOptions
    get() = listOf(
        ModeOption(TravelMode.Transit, "Transit", Ph.Bus, Ph.Filled.Bus),
        ModeOption(TravelMode.Train, "Train", Ph.TrainSimple, Ph.Filled.TrainSimple),
        ModeOption(TravelMode.Walk, "Walk", Ph.PersonSimpleWalk, Ph.Filled.PersonSimpleWalk),
        ModeOption(TravelMode.Cycle, "Cycle", Ph.PersonSimpleBike, Ph.Filled.PersonSimpleBike),
    )

/** Travel modes as a connected button group (icon over label); the selected one widens and morphs. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ModeSelector(mode: TravelMode, onSelect: (TravelMode) -> Unit, modifier: Modifier = Modifier) {
    val options = modeOptions
    Row(
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        modifier = modifier.fillMaxWidth(),
    ) {
        options.forEachIndexed { index, option ->
            val checked = option.mode == mode
            val weight by animateFloatAsState(
                targetValue = if (checked) 1.3f else 1f,
                animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                label = "modeWeight",
            )
            ToggleButton(
                checked = checked,
                onCheckedChange = { onSelect(option.mode) },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                colors = ToggleButtonDefaults.toggleButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    checkedContainerColor = MaterialTheme.colorScheme.primary,
                    checkedContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier
                    .weight(weight)
                    .height(56.dp)
                    .semantics { role = Role.RadioButton },
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        if (checked) option.selectedIcon else option.icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(option.label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
    }
}
