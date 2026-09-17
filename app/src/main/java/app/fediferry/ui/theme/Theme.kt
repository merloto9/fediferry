package app.fediferry.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Seed = Color(0xFF3E6B5A)

private val LightColors = lightColorScheme(
    primary = Seed,
    secondary = Color(0xFF52796F),
    tertiary = Color(0xFF7A5C3E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FD3B8),
    secondary = Color(0xFFA5C9BC),
    tertiary = Color(0xFFE2BE95),
)

@Composable
fun FediFerryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
