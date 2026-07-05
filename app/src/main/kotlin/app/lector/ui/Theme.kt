package app.lector.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

/**
 * "Reading room" identity: warm paper tones, serif body text, generous line
 * height. Deliberately not template-Material — this app is a place, not a form.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF7A4A21),            // leather brown
    onPrimary = Color(0xFFFFF6EA),
    primaryContainer = Color(0xFFF3E2CB),
    onPrimaryContainer = Color(0xFF3E2408),
    secondaryContainer = Color(0xFFFAE9C8), // active-sentence lamplight
    onSecondaryContainer = Color(0xFF4A3416),
    background = Color(0xFFFBF6EE),         // warm paper
    onBackground = Color(0xFF241D14),
    surface = Color(0xFFFBF6EE),
    onSurface = Color(0xFF241D14),
    surfaceVariant = Color(0xFFF0E6D6),
    onSurfaceVariant = Color(0xFF52483A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE8C093),
    onPrimary = Color(0xFF44280C),
    primaryContainer = Color(0xFF5D3F1E),
    onPrimaryContainer = Color(0xFFF7E3C8),
    secondaryContainer = Color(0xFF4E3D20), // lamplight, dimmed
    onSecondaryContainer = Color(0xFFF2E1BC),
    background = Color(0xFF1A160F),         // dim study
    onBackground = Color(0xFFEBE2D4),
    surface = Color(0xFF1A160F),
    onSurface = Color(0xFFEBE2D4),
    surfaceVariant = Color(0xFF2C2619),
    onSurfaceVariant = Color(0xFFCFC2AC),
)

private val ReadingTypography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 19.sp,
        lineHeight = 32.sp, // generous leading — dyslexia-friendly default
        letterSpacing = 0.2.sp,
    ),
)

@Composable
fun LectorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = ReadingTypography,
        content = content,
    )
}
