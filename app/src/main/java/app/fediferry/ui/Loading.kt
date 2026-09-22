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
package app.fediferry.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/**
 * What is being waited for, in words. A bare spinner says only that something
 * is happening; this says what, and from where, so a slow third party reads as
 * slow rather than as a hung app.
 */
@Composable
fun LoadingMessage(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.CloudDownload,
) {
    // After a while the user starts to wonder; say that it is still going and
    // that the wait has an end, since every call here has a timeout.
    var slow by remember(title) { mutableStateOf(false) }
    LaunchedEffect(title) {
        delay(SLOW_AFTER_MS)
        slow = true
    }

    val pulse by rememberInfiniteTransition(label = "loading").animateFloat(
        initialValue = 0.92f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )

    Column(
        modifier = modifier
            .widthIn(max = 360.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                modifier = Modifier.fillMaxSize(),
                strokeWidth = 3.dp,
                trackColor = MaterialTheme.colorScheme.primaryContainer,
            )
            Box(
                Modifier
                    .size(60.dp)
                    .scale(pulse)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        AnimatedVisibility(visible = slow, enter = fadeIn()) {
            Text(
                "Still working — the server is taking its time.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A whole screen given over to one wait, for when there is nothing to show yet. */
@Composable
fun LoadingScreen(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.CloudDownload,
) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        LoadingMessage(title, detail, icon = icon)
    }
}

/**
 * Covers the current screen while a fetch it started runs, so nothing can be
 * tapped twice and the screen underneath stays in view.
 */
@Composable
fun LoadingOverlay(
    title: String,
    detail: String,
    icon: ImageVector = Icons.Outlined.CloudDownload,
) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        LoadingCard(title, detail, icon)
    }
}

/**
 * The overlay for an activity with no screen of its own — the share receiver
 * and the sign-in redirect — which draw over whatever app the user came from.
 */
@Composable
fun LoadingScrim(
    title: String,
    detail: String,
    icon: ImageVector = Icons.Outlined.CloudDownload,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
            // Swallow taps: they would otherwise fall through to nothing.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        LoadingCard(title, detail, icon)
    }
}

@Composable
private fun LoadingCard(title: String, detail: String, icon: ImageVector) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        LoadingMessage(title, detail, icon = icon, modifier = Modifier.padding(28.dp))
    }
}

/** Long enough that a normal fetch never shows it. */
private const val SLOW_AFTER_MS = 8_000L
