package br.edu.fileshare.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD8E2FF),
    onPrimaryContainer = Color(0xFF001A42),
    secondary = Color(0xFF4F46E5),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0E7FF),
    onSecondaryContainer = Color(0xFF1E1B4B),
    tertiary = Color(0xFF059669),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD1FAE5),
    onTertiaryContainer = Color(0xFF064E3B),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF475569),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainer = Color(0xFFF1F5F9),
    surfaceContainerHigh = Color(0xFFE2E8F0),
    surfaceContainerHighest = Color(0xFFCBD5E1),
    outline = Color(0xFF64748B),
    outlineVariant = Color(0xFFCBD5E1),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFADC6FF),
    onPrimary = Color(0xFF002E6A),
    primaryContainer = Color(0xFF1D4ED8),
    onPrimaryContainer = Color(0xFFD8E2FF),
    secondary = Color(0xFFC0C1FF),
    onSecondary = Color(0xFF1E1B4B),
    secondaryContainer = Color(0xFF3131C0),
    onSecondaryContainer = Color(0xFFE0E7FF),
    tertiary = Color(0xFF4EDEA3),
    onTertiary = Color(0xFF003824),
    tertiaryContainer = Color(0xFF006C49),
    onTertiaryContainer = Color(0xFF6FFBBE),
    background = Color(0xFF0B1326),
    onBackground = Color(0xFFDAE2FD),
    surface = Color(0xFF0B1326),
    onSurface = Color(0xFFDAE2FD),
    surfaceVariant = Color(0xFF222A3D),
    onSurfaceVariant = Color(0xFFC2C6D6),
    surfaceContainerLowest = Color(0xFF060E20),
    surfaceContainerLow = Color(0xFF131B2E),
    surfaceContainer = Color(0xFF171F33),
    surfaceContainerHigh = Color(0xFF222A3D),
    surfaceContainerHighest = Color(0xFF2D3449),
    outline = Color(0xFF8C909F),
    outlineVariant = Color(0xFF424754),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

val FileShareShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
expect fun rememberPlatformDarkTheme(): Boolean

@Composable
fun FileShareTheme(
    darkTheme: Boolean = rememberPlatformDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = FileShareShapes,
        content = content,
    )
}
