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
package app.fediferry.analyze

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fediferry.R
import app.fediferry.api.ChannelInsightsDto
import app.fediferry.api.FollowerCountDto
import app.fediferry.api.InteractionsAccess
import app.fediferry.api.PostStatsDto
import app.fediferry.insights.Analysis
import app.fediferry.ui.Space
import app.fediferry.ui.SpaceBar
import coil3.compose.AsyncImage
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * How the project's channels are doing: followers, what posts got, when and
 * with which hashtags posting works best, and who interacts. It shows what
 * [AnalyzeViewModel] reads, and knows no platform.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyzeScreen(
    onOpenSettings: () -> Unit,
    onOpenChannelSettings: (String) -> Unit,
    onSwitchSpace: (Space) -> Unit = {},
    viewModel: AnalyzeViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.start() }

    Scaffold(
        bottomBar = { SpaceBar(Space.ANALYZE, onSwitchSpace) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.analyze_title)) },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.loading) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.analyze_refresh))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.sources_settings))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.loading && state.page != null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            when {
                state.page == null && state.loading -> Centered {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.analyze_loading), modifier = Modifier.padding(top = 12.dp))
                }
                state.page == null && state.problem != null -> Centered {
                    Text(
                        stringResource(
                            if (state.problem == AnalyzeProblem.SERVER_TOO_OLD) R.string.analyze_server_too_old else R.string.analyze_unreadable,
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(onClick = viewModel::refresh, modifier = Modifier.padding(top = 12.dp)) {
                        Text(stringResource(R.string.analyze_retry))
                    }
                }
                state.page?.let { it.channels.isEmpty() && it.unreachable.isEmpty() } == true -> Centered {
                    Text(stringResource(R.string.analyze_no_channels), style = MaterialTheme.typography.bodyLarge)
                }
                else -> Content(state, viewModel, onOpenChannelSettings)
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

@Composable
private fun Content(state: AnalyzeState, viewModel: AnalyzeViewModel, onOpenChannelSettings: (String) -> Unit) {
    val page = state.page ?: return
    val shown = state.shown
    val zone = remember { ZoneId.systemDefault() }
    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Filters(state, viewModel) }

        if (page.unreachable.isNotEmpty()) item {
            Notice(stringResource(R.string.analyze_unreachable, page.unreachable.joinToString { state.nameOf(it) }))
        }
        val reconnect = page.channels.filter { it.interactionsAccess == InteractionsAccess.RECONNECT }
            .filter { state.channelId == null || it.channelId == state.channelId }
        reconnect.forEach { channel ->
            item(key = "reconnect-${channel.channelId}") {
                Notice(stringResource(R.string.analyze_reconnect, state.nameOf(channel.channelId))) {
                    TextButton(onClick = { onOpenChannelSettings(channel.channelId) }) {
                        Text(stringResource(R.string.analyze_reconnect_action))
                    }
                }
            }
        }
        if (shown == null) return@LazyColumn

        item { Tiles(shown, page.since) }
        item { Section(stringResource(R.string.analyze_growth_title)) { Growth(shown.followerHistory) } }
        if (shown.interactionsAccess == InteractionsAccess.OK) {
            item { Section(stringResource(R.string.analyze_activity_title)) { Activity(shown, zone) } }
        }
        item {
            Section(stringResource(R.string.analyze_best_times_title), stringResource(R.string.analyze_best_times_detail)) {
                BestTimes(shown.recent, zone)
            }
        }
        item { Section(stringResource(R.string.analyze_top_posts_title)) { TopPosts(shown.recent) } }
        item {
            Section(stringResource(R.string.analyze_hashtags_title), stringResource(R.string.analyze_hashtags_detail)) {
                HashtagStats(shown.recent)
            }
        }
        if (shown.interactionsAccess == InteractionsAccess.OK) {
            item { Section(stringResource(R.string.analyze_fans_title)) { Fans(shown) } }
        }
        item {
            Text(
                stringResource(R.string.analyze_no_reach),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Filters(state: AnalyzeState, viewModel: AnalyzeViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(7, 30, 90).forEach { days ->
                FilterChip(
                    selected = state.days == days,
                    onClick = { viewModel.setDays(days) },
                    label = { Text(stringResource(R.string.analyze_days, days)) },
                )
            }
        }
        val channels = state.page?.channels.orEmpty()
        if (channels.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.channelId == null,
                    onClick = { viewModel.setChannel(null) },
                    label = { Text(stringResource(R.string.analyze_all_channels)) },
                )
                channels.forEach { c ->
                    FilterChip(
                        selected = state.channelId == c.channelId,
                        onClick = { viewModel.setChannel(c.channelId) },
                        label = { Text(state.nameOf(c.channelId)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Notice(text: String, action: (@Composable () -> Unit)? = null) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(12.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            action?.invoke()
        }
    }
}

@Composable
private fun Section(title: String, detail: String? = null, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            content()
        }
    }
}

@Composable
private fun Empty(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun oneDecimal(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)

// --- the numbers at a glance --------------------------------------------------------------

@Composable
private fun Tiles(shown: ChannelInsightsDto, since: Long) {
    val totals = Analysis.totals(shown.recent)
    val from = Instant.ofEpochMilli(since).atZone(java.time.ZoneOffset.UTC).toLocalDate()
    val change = Analysis.followerChange(shown.followerHistory, from, shown.followers)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile(
                stringResource(R.string.analyze_followers), shown.followers.toString(),
                change?.let { stringResource(R.string.analyze_followers_change, if (it > 0) "+$it" else "$it") },
                Modifier.weight(1f),
            )
            Tile(stringResource(R.string.analyze_posts), totals.posts.toString(), stringResource(R.string.analyze_posts_detail), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile(stringResource(R.string.analyze_per_post), oneDecimal(totals.perPost), stringResource(R.string.analyze_per_post_detail), Modifier.weight(1f))
            if (shown.interactionsAccess == InteractionsAccess.OK) {
                Tile(
                    stringResource(R.string.analyze_interactions), shown.interactions.size.toString(),
                    stringResource(R.string.analyze_interactions_detail), Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Tile(label: String, value: String, detail: String?, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

// --- charts -----------------------------------------------------------------------------------

/** The follower count per recorded day as a line. */
@Composable
private fun Growth(history: List<FollowerCountDto>) {
    if (history.size < 2) return Empty(stringResource(R.string.analyze_growth_empty))
    val line = MaterialTheme.colorScheme.primary
    val low = history.minOf { it.followers }
    val high = history.maxOf { it.followers }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(40.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(high.toString(), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(72.dp))
            Text(low.toString(), style = MaterialTheme.typography.labelSmall)
        }
        Canvas(Modifier.weight(1f).height(110.dp)) {
            val span = (high - low).coerceAtLeast(1)
            val step = size.width / (history.size - 1)
            val path = Path()
            history.forEachIndexed { i, point ->
                val x = i * step
                val y = size.height - (point.followers - low).toFloat() / span * size.height
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, line, style = Stroke(width = 3.dp.toPx()))
        }
    }
    Row {
        Text(history.first().day, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
        Text(history.last().day, style = MaterialTheme.typography.labelSmall)
    }
}

/** Interactions per day, as bars. */
@Composable
private fun Activity(shown: ChannelInsightsDto, zone: ZoneId) {
    val days = Analysis.perDay(shown.interactions, zone)
    if (days.isEmpty()) return Empty(stringResource(R.string.analyze_nothing))
    val bar = MaterialTheme.colorScheme.primary
    val totals = days.map { it.second.values.sum() }
    val most = totals.max()
    Text(most.toString(), style = MaterialTheme.typography.labelSmall)
    Canvas(Modifier.fillMaxWidth().height(90.dp)) {
        val slot = size.width / totals.size
        val width = (slot * 0.7f).coerceAtLeast(2f)
        totals.forEachIndexed { i, total ->
            val h = total.toFloat() / most * size.height
            drawRoundRect(bar, Offset(i * slot + (slot - width) / 2, size.height - h), Size(width, h), CornerRadius(2.dp.toPx()))
        }
    }
    Row {
        Text(days.first().first.toString(), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
        Text(days.last().first.toString(), style = MaterialTheme.typography.labelSmall)
    }
}

/** The week as a grid, Monday on top, hour 0 to 23 across; a stronger cell got more on average. */
@Composable
private fun BestTimes(posts: List<PostStatsDto>, zone: ZoneId) {
    val slots = Analysis.bestTimes(posts, zone)
    if (posts.size < 3 || slots.isEmpty()) return Empty(stringResource(R.string.analyze_best_times_empty))
    val best = slots.maxOf { it.perPost }.coerceAtLeast(0.1)
    val byCell = slots.associateBy { it.dayOfWeek to it.hour }
    val strong = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val locale = Locale.getDefault()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        DayOfWeek.values().forEach { day ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(day.getDisplayName(TextStyle.SHORT, locale), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(32.dp))
                Canvas(Modifier.weight(1f).height(14.dp)) {
                    val cell = size.width / 24
                    for (hour in 0 until 24) {
                        val slot = byCell[day.value to hour]
                        val color = if (slot == null) empty else strong.copy(alpha = (0.2 + 0.8 * slot.perPost / best).toFloat().coerceIn(0.2f, 1f))
                        drawRoundRect(color, Offset(hour * cell + 1, 0f), Size(cell - 2, size.height), CornerRadius(2.dp.toPx()))
                    }
                }
            }
        }
        Row {
            Spacer(Modifier.width(32.dp))
            listOf("0", "6", "12", "18", "23").forEachIndexed { i, label ->
                Text(label, style = MaterialTheme.typography.labelSmall, modifier = if (i < 4) Modifier.weight(1f) else Modifier)
            }
        }
    }
}

// --- lists ------------------------------------------------------------------------------------

@Composable
private fun TopPosts(posts: List<PostStatsDto>) {
    val top = Analysis.topPosts(posts)
    if (top.isEmpty()) return Empty(stringResource(R.string.analyze_nothing))
    val uri = LocalUriHandler.current
    top.forEach { post ->
        Row(
            Modifier.fillMaxWidth().clickable(enabled = post.url != null) { post.url?.let { uri.openUri(it) } },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))) {
                post.previewUrl?.let {
                    AsyncImage(model = it, contentDescription = stringResource(R.string.analyze_open_post), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            Column(Modifier.weight(1f)) {
                Text(post.text.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.analyze_post_counts, post.favourites, post.boosts, post.replies),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HashtagStats(posts: List<PostStatsDto>) {
    val tags = Analysis.hashtags(posts).take(10)
    if (tags.isEmpty()) return Empty(stringResource(R.string.analyze_hashtags_empty))
    tags.forEach { t ->
        Row(Modifier.fillMaxWidth()) {
            Text(t.tag, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.analyze_hashtag_row, t.posts, oneDecimal(t.perPost)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Fans(shown: ChannelInsightsDto) {
    val fans = Analysis.topAccounts(shown.interactions)
    if (fans.isEmpty()) return Empty(stringResource(R.string.analyze_nothing))
    fans.forEach { fan ->
        Row(Modifier.fillMaxWidth()) {
            Text(fan.account, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.analyze_fans_row, fan.interactions), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
