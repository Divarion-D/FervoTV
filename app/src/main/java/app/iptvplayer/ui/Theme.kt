package app.iptvplayer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Dark Material 3 scheme, "vivid" variant. Tones come from Google's material-color-utilities (TonalPalette, seed #FF8C1A):
 * - primary = the seed itself (tone 70, chroma 63) instead of M3's default pastel tone 80, so accents are bright;
 *   onPrimary = tone 15 of the same palette (contrast 6.5:1);
 * - surfaces = near-neutral palette (chroma 3) so the orange pops instead of blending into brown.
 * Selected chips / drawer items / segmented buttons use `primary` too (set in the components).
 */
private val Scheme = darkColorScheme(
    primary = Color(0xFFFF8C1A),
    onPrimary = Color(0xFF3E1D00),
    primaryContainer = Color(0xFF6F3800),
    onPrimaryContainer = Color(0xFFFFDCC4),
    inversePrimary = Color(0xFF924C00),
    secondary = Color(0xFFFCB886),
    onSecondary = Color(0xFF4E2601),
    secondaryContainer = Color(0xFF4A2A10),
    onSecondaryContainer = Color(0xFFFFDCC4),
    tertiary = Color(0xFF7FD0FF),
    onTertiary = Color(0xFF00344A),
    tertiaryContainer = Color(0xFF00B6F7),
    onTertiaryContainer = Color(0xFF00445E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF161310),
    onBackground = Color(0xFFEAE1DC),
    surface = Color(0xFF161310),
    onSurface = Color(0xFFEAE1DC),
    surfaceVariant = Color(0xFF52443B),
    onSurfaceVariant = Color(0xFFD6C3B7),
    outline = Color(0xFF9F8D82),
    outlineVariant = Color(0xFF52443B),
    inverseSurface = Color(0xFFEAE1DC),
    inverseOnSurface = Color(0xFF393431),
    surfaceDim = Color(0xFF161310),
    surfaceBright = Color(0xFF3D3835),
    surfaceContainerLowest = Color(0xFF110D0B),
    surfaceContainerLow = Color(0xFF1F1B18),
    surfaceContainer = Color(0xFF231F1C),
    surfaceContainerHigh = Color(0xFF2E2926),
    surfaceContainerHighest = Color(0xFF393431),
    scrim = Color(0xFF000000),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Scheme, content = content)
