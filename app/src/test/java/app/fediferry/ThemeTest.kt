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
package app.fediferry

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import app.fediferry.ui.theme.DarkColors
import app.fediferry.ui.theme.DarkHighContrastColors
import app.fediferry.ui.theme.LightColors
import app.fediferry.ui.theme.LightHighContrastColors
import app.fediferry.ui.theme.isRed
import app.fediferry.ui.theme.withoutRed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contrast promise the Appearance settings make, checked against the
 * schemes themselves so a colour tweak cannot quietly break it.
 */
class ThemeTest {

    private fun ratio(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance().toDouble(), b.luminance().toDouble()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    /** Every text colour against every background it is drawn on in the app. */
    private fun textPairs(s: ColorScheme) = listOf(
        "onPrimary/primary" to (s.onPrimary to s.primary),
        "onPrimaryContainer/primaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
        "onSecondary/secondary" to (s.onSecondary to s.secondary),
        "onSecondaryContainer/secondaryContainer" to (s.onSecondaryContainer to s.secondaryContainer),
        "onTertiaryContainer/tertiaryContainer" to (s.onTertiaryContainer to s.tertiaryContainer),
        "onErrorContainer/errorContainer" to (s.onErrorContainer to s.errorContainer),
        "onSurface/surface" to (s.onSurface to s.surface),
        "onSurfaceVariant/surface" to (s.onSurfaceVariant to s.surface),
        "onSurfaceVariant/surfaceVariant" to (s.onSurfaceVariant to s.surfaceVariant),
        "onSurfaceVariant/surfaceContainerHigh" to (s.onSurfaceVariant to s.surfaceContainerHigh),
        "onSurfaceVariant/surfaceContainerHighest" to (s.onSurfaceVariant to s.surfaceContainerHighest),
        "primary/surface" to (s.primary to s.surface),
        "primary/surfaceContainerHighest" to (s.primary to s.surfaceContainerHighest),
        "error/surface" to (s.error to s.surface),
        "error/surfaceContainerHighest" to (s.error to s.surfaceContainerHighest),
    )

    private fun assertContrast(name: String, scheme: ColorScheme, minimum: Double) {
        textPairs(scheme).forEach { (pair, colors) ->
            val r = ratio(colors.first, colors.second)
            assertTrue("$name $pair is ${"%.2f".format(r)}:1, needs $minimum:1", r >= minimum)
        }
    }

    @Test
    fun `standard schemes meet WCAG AA for text`() {
        assertContrast("light", LightColors, 4.5)
        assertContrast("dark", DarkColors, 4.5)
    }

    @Test
    fun `high contrast schemes meet WCAG AAA for text`() {
        assertContrast("light high", LightHighContrastColors, 7.0)
        assertContrast("dark high", DarkHighContrastColors, 7.0)
    }

    @Test
    fun `outlines stand out from the surface`() {
        listOf(LightColors, DarkColors).forEach { assertTrue(ratio(it.outline, it.surface) >= 3.0) }
        listOf(LightHighContrastColors, DarkHighContrastColors).forEach { assertTrue(ratio(it.outline, it.surface) >= 4.5) }
    }

    @Test
    fun `no accent in the app's own schemes is red`() {
        listOf(LightColors, DarkColors, LightHighContrastColors, DarkHighContrastColors).forEach { s ->
            listOf(s.primary, s.primaryContainer, s.secondary, s.secondaryContainer, s.tertiary, s.tertiaryContainer)
                .forEach { assertFalse("$it", isRed(it)) }
            assertTrue("the error container is the red one", isRed(s.errorContainer))
        }
    }

    @Test
    fun `recognises red and pink but not green, blue or grey`() {
        assertTrue(isRed(Color(0xFFBA1A1A)))
        assertTrue(isRed(Color(0xFFFFD9E2))) // a wallpaper's pink container
        assertTrue(isRed(Color(0xFF8C4A60)))
        assertFalse(isRed(Color(0xFF2F6A55)))
        assertFalse(isRed(Color(0xFF3F6374)))
        assertFalse(isRed(Color(0xFFE0E0E0)))
        assertFalse(isRed(Color(0xFF7A5C3E))) // brown sits outside the band
    }

    @Test
    fun `a red wallpaper accent is swapped for the app's own, the rest kept`() {
        val wallpaper = lightColorScheme(
            primary = Color(0xFF8F4C38), // terracotta
            primaryContainer = Color(0xFFFFDBD1),
            secondary = Color(0xFF6B5E10), // olive — kept
            secondaryContainer = Color(0xFFF5E388),
            tertiary = Color(0xFF904A43), // red
            tertiaryContainer = Color(0xFFFFDAD5),
            surface = Color(0xFFFFF8F6),
        )

        val guarded = wallpaper.withoutRed(LightColors)

        assertEquals(LightColors.primary, guarded.primary)
        assertEquals(LightColors.primaryContainer, guarded.primaryContainer)
        assertEquals(LightColors.tertiary, guarded.tertiary)
        assertEquals(wallpaper.secondary, guarded.secondary)
        assertEquals(wallpaper.surface, guarded.surface)
    }
}
