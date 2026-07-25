import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val GraphiteDarkColors = darkColorScheme(
    primary = Color(0xFFFFB454),
    onPrimary = Color(0xFF2B1800),
    primaryContainer = Color(0xFF5B390C),
    onPrimaryContainer = Color(0xFFFFDDB0),
    secondary = Color(0xFFB9C7D8),
    onSecondary = Color(0xFF1D2733),
    secondaryContainer = Color(0xFF344252),
    onSecondaryContainer = Color(0xFFD9E7F7),
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFE6EAF0),
    surface = Color(0xFF151A21),
    onSurface = Color(0xFFE6EAF0),
    surfaceVariant = Color(0xFF242B35),
    onSurfaceVariant = Color(0xFFB7C0CC),
    outline = Color(0xFF424C5A),
    outlineVariant = Color(0xFF2C3541),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF5C1717),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val GraphiteLightColors = lightColorScheme(
    primary = Color(0xFF9A5B00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDDB0),
    onPrimaryContainer = Color(0xFF321A00),
    secondary = Color(0xFF4E6072),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9E7F7),
    onSecondaryContainer = Color(0xFF0B1D2E),
    background = Color(0xFFF6F7F9),
    onBackground = Color(0xFF171B21),
    surface = Color.White,
    onSurface = Color(0xFF171B21),
    surfaceVariant = Color(0xFFE9EDF2),
    onSurfaceVariant = Color(0xFF46515E),
    outline = Color(0xFF747F8C),
    outlineVariant = Color(0xFFD4DAE2),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val AniyomiShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun AniyomiDesktopTheme(
    settings: AppSettings,
    content: @Composable () -> Unit,
) {
    val isLight = settings.themeMode == "light"
    MaterialTheme(
        colorScheme = if (isLight) GraphiteLightColors else GraphiteDarkColors,
        typography = Typography(),
        shapes = AniyomiShapes,
        content = content,
    )
}
