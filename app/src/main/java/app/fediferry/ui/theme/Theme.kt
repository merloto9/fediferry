/*
 * FediFerry — share a meme screenshot straight to Mastodon.
 * Copyright (C) 2026 Jasper Ramthun
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package app.fediferry.ui.theme

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import app.fediferry.data.Settings
import app.fediferry.di.ServiceLocator

/** Light, dark, or whatever the phone is set to. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** FediFerry's own green, or colours taken from the wallpaper (Android 12+). */
enum class ColorSource { APP, WALLPAPER }

/** How far apart text and background are pushed. SYSTEM follows Android 14+'s own setting. */
enum class ContrastLevel { SYSTEM, STANDARD, HIGH }

/*
 * Every role is set, for every scheme. Leaving one out falls back to
 * Material's baseline purple, which is how containers and outlines used to
 * clash with the green they sat next to.
 *
 * Checked, pair by pair, for the text each role carries: at least 4.5:1 in the
 * standard schemes (WCAG AA) and 7:1 in the high-contrast ones (WCAG AAA).
 * Red is the error role and nothing else — the accents are green and blue, so
 * a warning is the only red thing on screen.
 */
internal val LightColors = lightColorScheme(
    primary = Color(0xFF2F6A55),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB4F0D6),
    onPrimaryContainer = Color(0xFF002117),
    secondary = Color(0xFF4C6359),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCEE9DB),
    onSecondaryContainer = Color(0xFF082018),
    tertiary = Color(0xFF3F6374),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC3E8FC),
    onTertiaryContainer = Color(0xFF001F2A),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF6FBF7),
    onBackground = Color(0xFF171D1A),
    surface = Color(0xFFF6FBF7),
    onSurface = Color(0xFF171D1A),
    surfaceVariant = Color(0xFFDBE5DE),
    onSurfaceVariant = Color(0xFF3F4944),
    outline = Color(0xFF6F7973),
    outlineVariant = Color(0xFFBFC9C2),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F5F1),
    surfaceContainer = Color(0xFFEAEFEB),
    surfaceContainerHigh = Color(0xFFE4EAE5),
    surfaceContainerHighest = Color(0xFFDFE4E0),
    surfaceDim = Color(0xFFD6DBD7),
    surfaceBright = Color(0xFFF6FBF7),
    inverseSurface = Color(0xFF2C322F),
    inverseOnSurface = Color(0xFFEDF2EE),
    inversePrimary = Color(0xFF99D4BB),
    scrim = Color(0xFF000000),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFF99D4BB),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF11513F),
    onPrimaryContainer = Color(0xFFB4F0D6),
    secondary = Color(0xFFB3CCBF),
    onSecondary = Color(0xFF1E352C),
    secondaryContainer = Color(0xFF354B42),
    onSecondaryContainer = Color(0xFFCEE9DB),
    tertiary = Color(0xFFA7CCDF),
    onTertiary = Color(0xFF0B3444),
    tertiaryContainer = Color(0xFF264B5B),
    onTertiaryContainer = Color(0xFFC3E8FC),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0F1512),
    onBackground = Color(0xFFDEE4DF),
    surface = Color(0xFF0F1512),
    onSurface = Color(0xFFDEE4DF),
    surfaceVariant = Color(0xFF3F4944),
    onSurfaceVariant = Color(0xFFBFC9C2),
    outline = Color(0xFF89938D),
    outlineVariant = Color(0xFF3F4944),
    surfaceContainerLowest = Color(0xFF0A0F0D),
    surfaceContainerLow = Color(0xFF171D1A),
    surfaceContainer = Color(0xFF1B211E),
    surfaceContainerHigh = Color(0xFF252B28),
    surfaceContainerHighest = Color(0xFF303633),
    surfaceDim = Color(0xFF0F1512),
    surfaceBright = Color(0xFF353B38),
    inverseSurface = Color(0xFFDEE4DF),
    inverseOnSurface = Color(0xFF2C322F),
    inversePrimary = Color(0xFF2F6A55),
    scrim = Color(0xFF000000),
)

internal val LightHighContrastColors = lightColorScheme(
    primary = Color(0xFF00402F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF1E5543),
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = Color(0xFF253830),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF3F554B),
    onSecondaryContainer = Color(0xFFFFFFFF),
    tertiary = Color(0xFF173A4A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF365A6B),
    onTertiaryContainer = Color(0xFFFFFFFF),
    error = Color(0xFF600004),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF98000A),
    onErrorContainer = Color(0xFFFFFFFF),
    background = Color(0xFFF6FBF7),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFF6FBF7),
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFDBE5DE),
    onSurfaceVariant = Color(0xFF1C2621),
    outline = Color(0xFF38423D),
    outlineVariant = Color(0xFF38423D),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F5F1),
    surfaceContainer = Color(0xFFE4EAE5),
    surfaceContainerHigh = Color(0xFFDFE4E0),
    surfaceContainerHighest = Color(0xFFD6DBD7),
    surfaceDim = Color(0xFFCACFCB),
    surfaceBright = Color(0xFFF6FBF7),
    inverseSurface = Color(0xFF2C322F),
    inverseOnSurface = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFF99D4BB),
    scrim = Color(0xFF000000),
)

internal val DarkHighContrastColors = darkColorScheme(
    primary = Color(0xFFC2FFE4),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF95D0B7),
    onPrimaryContainer = Color(0xFF000000),
    secondary = Color(0xFFE0F9EB),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFFAFC8BB),
    onSecondaryContainer = Color(0xFF000000),
    tertiary = Color(0xFFE0F4FF),
    onTertiary = Color(0xFF000000),
    tertiaryContainer = Color(0xFFA3C8DB),
    onTertiaryContainer = Color(0xFF000000),
    error = Color(0xFFFFECE9),
    onError = Color(0xFF000000),
    errorContainer = Color(0xFFFFAEA4),
    onErrorContainer = Color(0xFF000000),
    background = Color(0xFF0F1512),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF0F1512),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF3F4944),
    onSurfaceVariant = Color(0xFFFFFFFF),
    outline = Color(0xFFE9F2EB),
    outlineVariant = Color(0xFFBBC5BE),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF1B211E),
    surfaceContainer = Color(0xFF2C322F),
    surfaceContainerHigh = Color(0xFF373D3A),
    surfaceContainerHighest = Color(0xFF424845),
    surfaceDim = Color(0xFF0F1512),
    surfaceBright = Color(0xFF4C5250),
    inverseSurface = Color(0xFFDEE4DF),
    inverseOnSurface = Color(0xFF000000),
    inversePrimary = Color(0xFF12523F),
    scrim = Color(0xFF000000),
)


/**
 * The app's theme, as the user set it under Settings → Appearance. Reads the
 * setting itself, so every screen — the share card over another app too —
 * looks the same without each caller passing it in.
 */
@Composable
fun FediFerryTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val settings by ServiceLocator.settings(context).settings.collectAsState(initial = Settings())
    FediFerryTheme(
        mode = settings.themeMode,
        colors = settings.colorSource,
        contrast = settings.contrastLevel,
        content = content,
    )
}

@Composable
fun FediFerryTheme(
    mode: ThemeMode,
    colors: ColorSource,
    contrast: ContrastLevel,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val high = when (contrast) {
        ContrastLevel.HIGH -> true
        ContrastLevel.STANDARD -> false
        ContrastLevel.SYSTEM -> systemAsksForContrast(context)
    }
    val own = when {
        high && dark -> DarkHighContrastColors
        high -> LightHighContrastColors
        dark -> DarkColors
        else -> LightColors
    }
    val scheme = when {
        // A wallpaper palette carries no contrast guarantee, so high contrast
        // always uses the checked schemes above.
        high -> own
        colors == ColorSource.WALLPAPER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            (if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)).withoutRed(own)
        else -> own
    }

    // Since Android 15 the app draws behind the status bar, so its icons must
    // follow the app's theme — not the system's, which a Light or Dark choice
    // here can contradict, leaving white icons on a white bar.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    MaterialTheme(colorScheme = scheme, content = content)
}

/** Android 14's contrast setting: medium and high both count. */
private fun systemAsksForContrast(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
    val ui = context.getSystemService(UiModeManager::class.java) ?: return false
    return ui.contrast >= 0.5f
}

/**
 * Keeps red for warnings in a wallpaper palette. A warm wallpaper can make any
 * accent red or pink, and then a menu looks like an error. Each accent family
 * that came out red is replaced by the app's own, and the rest of the
 * wallpaper's colours are kept.
 */
internal fun ColorScheme.withoutRed(own: ColorScheme): ColorScheme {
    var s = this
    if (isRed(primary) || isRed(primaryContainer)) {
        s = s.copy(
            primary = own.primary, onPrimary = own.onPrimary,
            primaryContainer = own.primaryContainer, onPrimaryContainer = own.onPrimaryContainer,
            inversePrimary = own.inversePrimary,
        )
    }
    if (isRed(secondary) || isRed(secondaryContainer)) {
        s = s.copy(
            secondary = own.secondary, onSecondary = own.onSecondary,
            secondaryContainer = own.secondaryContainer, onSecondaryContainer = own.onSecondaryContainer,
        )
    }
    if (isRed(tertiary) || isRed(tertiaryContainer)) {
        s = s.copy(
            tertiary = own.tertiary, onTertiary = own.onTertiary,
            tertiaryContainer = own.tertiaryContainer, onTertiaryContainer = own.onTertiaryContainer,
        )
    }
    return s
}

/**
 * Whether a colour reads as red or pink: its hue within about 25 degrees of
 * red, and saturated enough to look coloured rather than grey. The bar is low
 * on purpose — a wallpaper's pale pink container is barely saturated, and is
 * exactly the colour that made a menu look like a warning.
 */
internal fun isRed(color: Color): Boolean {
    val r = color.red
    val g = color.green
    val b = color.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    if (max < 0.15f || delta == 0f) return false
    val saturation = delta / max
    val hue = when (max) {
        r -> 60f * (((g - b) / delta) % 6f)
        g -> 60f * ((b - r) / delta + 2f)
        else -> 60f * ((r - g) / delta + 4f)
    }.let { if (it < 0f) it + 360f else it }
    return (hue < 25f || hue > 330f) && saturation > 0.08f
}
