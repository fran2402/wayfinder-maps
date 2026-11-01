package app.wayfinder.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.wayfinder.R

/**
 * Google Sans Flex with the ROND (roundness) axis pinned at 100. The bundled file is an
 * instance of the variable font with ROND=100, keeping the weight axis variable.
 */
@OptIn(ExperimentalTextApi::class)
private fun rounded(weight: Int) = Font(
    resId = R.font.google_sans_flex_rounded,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val GoogleSansFlexRounded = FontFamily(
    rounded(300), rounded(400), rounded(450), rounded(500), rounded(600), rounded(650), rounded(700), rounded(800),
)

private val base = Typography()

private fun TextStyle.flex(weight: Int, letterSpacing: Float? = null) = copy(
    fontFamily = GoogleSansFlexRounded,
    fontWeight = FontWeight(weight),
    letterSpacing = letterSpacing?.sp ?: this.letterSpacing,
)

val WayfinderTypography = Typography(
    displayLarge = base.displayLarge.flex(500, -0.5f),
    displayMedium = base.displayMedium.flex(500, -0.25f),
    displaySmall = base.displaySmall.flex(600),
    headlineLarge = base.headlineLarge.flex(650),
    headlineMedium = base.headlineMedium.flex(650),
    headlineSmall = base.headlineSmall.flex(650),
    titleLarge = base.titleLarge.flex(600),
    titleMedium = base.titleMedium.flex(600, 0f),
    titleSmall = base.titleSmall.flex(600, 0f),
    bodyLarge = base.bodyLarge.flex(400, 0.1f),
    bodyMedium = base.bodyMedium.flex(400, 0.1f),
    bodySmall = base.bodySmall.flex(450, 0.2f),
    labelLarge = base.labelLarge.flex(600, 0.1f),
    labelMedium = base.labelMedium.flex(600, 0.3f),
    labelSmall = base.labelSmall.flex(600, 0.4f),
)
