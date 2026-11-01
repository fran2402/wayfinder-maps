package app.wayfinder.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.wayfinder.data.LineInfo
import app.wayfinder.data.TransitMode
import app.wayfinder.ui.Ph

/**
 * Map layer toggles. Material 3 Expressive toggle buttons: the shape morphs from a pill to a
 * rounded square when switched on.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LayerToggles(
    showTransitToggle: Boolean,
    transitLabel: String,
    transitOn: Boolean,
    transitLoading: Boolean,
    showBikeToggle: Boolean,
    bikeOn: Boolean,
    bikeLoading: Boolean,
    onTransit: () -> Unit,
    onBike: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
        if (showTransitToggle) {
            LayerToggle(transitLabel, if (transitOn) Ph.Filled.Bus else Ph.Bus, transitOn, transitLoading, onTransit)
        }
        if (showBikeToggle) {
            LayerToggle("Bike routes", if (bikeOn) Ph.Filled.Bicycle else Ph.Bicycle, bikeOn, bikeLoading, onBike)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LayerToggle(label: String, icon: ImageVector, on: Boolean, loading: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    ToggleButton(
        checked = on,
        onCheckedChange = { onClick() },
        colors = ToggleButtonDefaults.toggleButtonColors(
            containerColor = colors.surfaceContainerHigh,
            contentColor = colors.onSurface,
            checkedContainerColor = colors.primary,
            checkedContentColor = colors.onPrimary,
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp, pressedElevation = 1.dp),
        contentPadding = PaddingValues(start = 14.dp, end = 18.dp),
        modifier = Modifier.height(44.dp),
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (loading) LoadingIndicator(Modifier.size(22.dp)) else Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Public transport panel: a connected button group to filter modes, then the lines in view
 * grouped by mode — or, once a line is picked, that line's details.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TransitPanel(
    modes: Set<TransitMode>,
    onToggleMode: (TransitMode) -> Unit,
    lines: List<LineInfo>,
    selected: LineInfo?,
    onLine: (String) -> Unit,
    loading: Boolean,
    hint: String?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = colors.surfaceContainerHigh,
        shadowElevation = 6.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        AnimatedContent(
            targetState = selected,
            transitionSpec = {
                (fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()))
                    .using(SizeTransform(clip = false))
            },
            label = "panel",
        ) { picked ->
            if (picked != null) {
                SelectedLine(picked, stops = null, onClose = { onLine(picked.key) })
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(top = 16.dp, bottom = 14.dp),
                ) {
                    // Header: title + count capsule (or the expressive loading indicator)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 20.dp),
                    ) {
                        Text("Lines in view", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (loading) {
                            LoadingIndicator(Modifier.size(28.dp))
                        } else {
                            Text(
                                "${lines.size}",
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(colors.surfaceContainerHighest)
                                    .padding(horizontal = 10.dp, vertical = 3.dp),
                            )
                        }
                    }

                    // Mode filter — connected button group
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    ) {
                        val all = TransitMode.entries
                        all.forEachIndexed { index, mode ->
                            ToggleButton(
                                checked = mode in modes,
                                onCheckedChange = { onToggleMode(mode) },
                                shapes = when (index) {
                                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                    all.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                                },
                                colors = ToggleButtonDefaults.toggleButtonColors(
                                    containerColor = colors.surfaceContainerHighest,
                                    contentColor = colors.onSurfaceVariant,
                                    checkedContainerColor = Color(mode.colorArgb),
                                    checkedContentColor = Color.White,
                                ),
                                contentPadding = PaddingValues(start = 12.dp, end = 14.dp),
                                modifier = Modifier.height(40.dp),
                            ) {
                                Icon(modeIcon(mode), contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(mode.label, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }

                    if (lines.isEmpty()) {
                        Text(
                            if (loading) "Loading lines…" else "No lines here. Move or zoom the map.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    } else {
                        LineRow(lines, onLine)
                    }

                    Text(
                        hint ?: "Tap a line to follow it on the map",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LineRow(lines: List<LineInfo>, onLine: (String) -> Unit) {
    val groups = lines.take(200).groupBy { it.mode }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = Modifier.fillMaxWidth().height(32.dp),
    ) {
        var first = true
        groups.forEach { (mode, group) ->
            val isFirst = first
            first = false
            if (mode != null) {
                item(key = "group-${mode.name}") { GroupLabel(mode, divider = !isFirst) }
            }
            items(group, key = { it.key }) { line -> LinePill(line, onClick = { onLine(line.key) }) }
        }
    }
}

@Composable
private fun GroupLabel(mode: TransitMode, divider: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(32.dp)) {
        if (divider) {
            Box(Modifier.width(1.dp).height(20.dp).background(colors.outlineVariant))
            Spacer(Modifier.width(10.dp))
        }
        Icon(modeIcon(mode), contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(mode.label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        Spacer(Modifier.width(2.dp))
    }
}

@Composable
private fun LinePill(line: LineInfo, onClick: (() -> Unit)?, outlined: Boolean = false) {
    val shape = RoundedCornerShape(8.dp)
    val border = if (outlined) BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface) else null
    val content: @Composable () -> Unit = {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.defaultMinSize(minWidth = 40.dp).height(32.dp).padding(horizontal = 10.dp),
        ) {
            Text(
                line.ref,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight(750)),
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = Color(line.color), contentColor = Color(line.textColor), border = border) { content() }
    } else {
        Surface(shape = shape, color = Color(line.color), contentColor = Color(line.textColor), border = border) { content() }
    }
}

@Composable
private fun SelectedLine(line: LineInfo, stops: Int?, onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .defaultMinSize(minWidth = 64.dp)
                .height(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(line.color))
                .padding(horizontal = 12.dp),
        ) {
            Text(
                line.ref,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight(750)),
                color = Color(line.textColor),
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                line.name.ifBlank { "Line ${line.ref}" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            line.mode?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(modeIcon(it), contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        singular(it) + (stops?.let { n -> " · $n stops in view" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
        FilledTonalIconButton(onClick = onClose, shapes = IconButtonDefaults.shapes()) {
            Icon(Ph.X, contentDescription = "Show all lines", modifier = Modifier.size(20.dp))
        }
    }
}

private fun singular(mode: TransitMode) = when (mode) {
    TransitMode.Train -> "Train"
    TransitMode.Metro -> "Metro"
    TransitMode.Tram -> "Tram"
    TransitMode.Bus -> "Bus"
    TransitMode.Ferry -> "Ferry"
    TransitMode.Other -> "Transit"
}

private fun modeIcon(mode: TransitMode): ImageVector = when (mode) {
    TransitMode.Train -> Ph.Filled.Train
    TransitMode.Metro -> Ph.Filled.Subway
    TransitMode.Tram -> Ph.Filled.Tram
    TransitMode.Bus -> Ph.Filled.Bus
    TransitMode.Ferry -> Ph.Filled.Boat
    TransitMode.Other -> Ph.Filled.TrainSimple
}
