@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.wayfinder.ui.components

import androidx.compose.animation.AnimatedVisibility
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

/** From / To card shown while planning a trip. */
@Composable
fun TripCard(
    origin: Place?,
    destination: Place,
    onBack: () -> Unit,
    onEditOrigin: () -> Unit,
    onEditDestination: () -> Unit,
    onSwap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(6.dp)) {
            IconButton(onClick = onBack) { Icon(Ph.ArrowLeft, contentDescription = "Close directions") }
            Column(Modifier.weight(1f)) {
                Endpoint(
                    marker = {
                        Box(
                            Modifier
                                .size(16.dp)
                                .border(4.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        )
                    },
                    text = origin?.name ?: "Your location",
                    emphasised = origin == null,
                    onClick = onEditOrigin,
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 36.dp, end = 8.dp),
                )
                Endpoint(
                    marker = {
                        Icon(
                            Ph.Filled.MapPin, contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    text = destination.name,
                    emphasised = false,
                    onClick = onEditDestination,
                )
            }
            FilledTonalIconButton(onClick = onSwap, modifier = Modifier.padding(horizontal = 4.dp)) {
                Icon(Ph.ArrowsDownUp, contentDescription = "Swap start and destination")
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
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
    ) {
        Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) { marker() }
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (emphasised) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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

/** Material 3 Expressive connected button group; the selected mode grows to reveal its label. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ModeSelector(mode: TravelMode, onSelect: (TravelMode) -> Unit, modifier: Modifier = Modifier) {
    val options = modeOptions
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            modifier = Modifier.padding(6.dp),
        ) {
            options.forEachIndexed { index, option ->
                val checked = option.mode == mode
                val weight by animateFloatAsState(
                    targetValue = if (checked) 1.9f else 1f,
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
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier
                        .weight(weight)
                        .height(52.dp)
                        .semantics { role = Role.RadioButton },
                ) {
                    Icon(
                        if (checked) option.selectedIcon else option.icon,
                        contentDescription = option.label,
                        modifier = Modifier.size(22.dp),
                    )
                    AnimatedVisibility(visible = checked) {
                        Row {
                            Spacer(Modifier.width(8.dp))
                            Text(option.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
