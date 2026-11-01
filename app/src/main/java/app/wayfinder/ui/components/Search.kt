package app.wayfinder.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.wayfinder.data.Place
import app.wayfinder.data.PlaceKind
import app.wayfinder.ui.Ph
import app.wayfinder.ui.SearchTarget

/** The resting "Where to?" pill. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SearchPill(onClick: () -> Unit, settingsOn: Boolean, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = colors.surfaceContainerHigh,
        shadowElevation = 6.dp,
        modifier = modifier.fillMaxWidth().height(64.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 22.dp, end = 10.dp),
        ) {
            Icon(Ph.MagnifyingGlass, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Text(
                "Where to?",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight(500)),
                color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            // Map settings live in the search bar's corner.
            androidx.compose.material3.FilledIconToggleButton(
                checked = settingsOn,
                onCheckedChange = { onSettings() },
                shapes = androidx.compose.material3.IconButtonDefaults.toggleableShapes(),
                colors = androidx.compose.material3.IconButtonDefaults.filledIconToggleButtonColors(
                    containerColor = colors.secondaryContainer,
                    contentColor = colors.onSecondaryContainer,
                ),
                modifier = Modifier.size(46.dp),
            ) {
                Icon(Ph.SlidersHorizontal, contentDescription = "Map settings", modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** Full-screen search with live suggestions. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SearchOverlay(
    target: SearchTarget,
    query: String,
    suggestions: List<Place>,
    searching: Boolean,
    hasLocation: Boolean,
    onQueryChange: (String) -> Unit,
    onChoose: (Place?) -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(target) { focus.requestFocus() }

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().imePadding()) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(64.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp)) {
                    IconButton(onClick = onClose) { Icon(Ph.ArrowLeft, contentDescription = "Back") }
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight(500),
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            keyboard?.hide()
                            suggestions.firstOrNull()?.let(onChoose)
                        }),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (query.isEmpty()) {
                                    Text(
                                        if (target == SearchTarget.From) "Choose a starting point" else "Search places, stops, addresses",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight(500)),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                inner()
                            }
                        },
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp).focusRequester(focus),
                    )
                    when {
                        searching -> LoadingIndicator(Modifier.size(40.dp))
                        query.isNotEmpty() -> IconButton(onClick = { onQueryChange("") }) {
                            Icon(Ph.X, contentDescription = "Clear")
                        }
                    }
                }
            }

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
            ) {
                if (target == SearchTarget.From && hasLocation) {
                    item {
                        SuggestionRow(
                            icon = Ph.Filled.GpsFix,
                            title = "Your location",
                            subtitle = null,
                            highlight = true,
                            onClick = { onChoose(null) },
                        )
                    }
                }
                items(suggestions) { place ->
                    SuggestionRow(
                        icon = if (place.kind == PlaceKind.Station) Ph.Filled.Train else Ph.Filled.MapPin,
                        title = place.name,
                        subtitle = place.detail,
                        highlight = false,
                        onClick = { onChoose(place) },
                    )
                }
                if (query.isBlank() && target == SearchTarget.To) {
                    item {
                        Text(
                            "Tip: long-press anywhere on the map to drop a pin.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    highlight: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    if (highlight) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHighest
                ),
        ) {
            Icon(
                icon, contentDescription = null, modifier = Modifier.size(22.dp),
                tint = if (highlight) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
