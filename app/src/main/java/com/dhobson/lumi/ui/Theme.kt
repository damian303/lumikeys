package com.dhobson.lumi.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The app's own look: always dark, deep blue-violet surfaces with a violet accent and
 * teal for selected things. Dark because the falling notes, layer colours and the
 * rainbow keys read best against it, as they do on the keyboard itself.
 */
private val LumiColorScheme = darkColorScheme(
    primary = Color(0xFFA596FF),
    onPrimary = Color(0xFF15102F),
    primaryContainer = Color(0xFF3B3183),
    onPrimaryContainer = Color(0xFFE8E3FF),
    secondary = Color(0xFF44D9C9),
    onSecondary = Color(0xFF00201C),
    secondaryContainer = Color(0xFF175A5C),
    onSecondaryContainer = Color(0xFFD6FFF9),
    tertiary = Color(0xFFFF8FC4),
    onTertiary = Color(0xFF3B0B23),
    background = Color(0xFF0B0C16),
    onBackground = Color(0xFFEEEEF8),
    surface = Color(0xFF121424),
    onSurface = Color(0xFFEEEEF8),
    surfaceVariant = Color(0xFF262A45),
    onSurfaceVariant = Color(0xFFB9BDD8),
    surfaceContainerLowest = Color(0xFF0D0E1B),
    surfaceContainerLow = Color(0xFF151728),
    surfaceContainer = Color(0xFF1A1D32),
    surfaceContainerHigh = Color(0xFF21243D),
    surfaceContainerHighest = Color(0xFF2A2E4B),
    outline = Color(0xFF747AA3),
    outlineVariant = Color(0xFF3A3F60),
    error = Color(0xFFFF7483),
    onError = Color(0xFF3B0710),
    errorContainer = Color(0xFF6B1622),
    onErrorContainer = Color(0xFFFFDADD),
    inverseSurface = Color(0xFFEAEAF6),
    inverseOnSurface = Color(0xFF1A1B2C),
    inversePrimary = Color(0xFF5546C8),
)

private val LumiTypography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

private val LumiShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun LumiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LumiColorScheme, typography = LumiTypography, shapes = LumiShapes, content = content)
}

/** Colours and brushes that are not part of the Material scheme. */
object LumiStyle {
    /** Behind everything: violet-tinted at the top, near black at the bottom. */
    val backdrop = Brush.verticalGradient(listOf(Color(0xFF1B1640), Color(0xFF0E0F1E), Color(0xFF090A12)))

    /** For the things that should catch the eye: count-ins and results. */
    val accent = Brush.linearGradient(listOf(Color(0xFF6F5FE8), Color(0xFFB54FC4)))

    val record = Color(0xFFFF6471)
    val onRecord = Color(0xFF2A0509)
    val gold = Color(0xFFFFD166)

    /** Connection status dots. Each state is also spelled out in the button's text. */
    val connected = Color(0xFF5BE49B)
    val connecting = Color(0xFFFFC857)

    /** One colour per layer, to tell layers apart at a glance. Each layer also has its name. */
    val layerPalette = listOf(
        Color(0xFFA596FF), Color(0xFF44D9C9), Color(0xFFFFB454), Color(0xFFFF8FC4),
        Color(0xFF5CB8FF), Color(0xFF8BE28E), Color(0xFFE2A77F), Color(0xFFFF7483),
    )
}

// Small drawn symbols, so they look the same on every device (text symbols such as a
// triangle can be replaced by emoji-style pictures on some).

/** A right-pointing triangle: play. */
@Composable
fun PlayGlyph(modifier: Modifier = Modifier.size(14.dp)) {
    val color = LocalContentColor.current
    Canvas(modifier) {
        val path = Path().apply {
            moveTo(size.width * 0.12f, 0f)
            lineTo(size.width, size.height / 2)
            lineTo(size.width * 0.12f, size.height)
            close()
        }
        drawPath(path, color)
    }
}

/** A square: stop. */
@Composable
fun StopGlyph(modifier: Modifier = Modifier.size(13.dp)) {
    val color = LocalContentColor.current
    Canvas(modifier) { drawRoundRect(color, cornerRadius = CornerRadius(size.minDimension * 0.18f)) }
}

/** A filled circle: record, or a status light. */
@Composable
fun DotGlyph(modifier: Modifier = Modifier.size(12.dp), color: Color = LocalContentColor.current) {
    Canvas(modifier) { drawCircle(color) }
}
