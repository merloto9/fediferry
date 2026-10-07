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
package app.fediferry.publishing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fediferry.R
import app.fediferry.api.PostDto
import app.fediferry.api.PostStages
import app.fediferry.drafts.ServerMessages
import app.fediferry.ui.LoadingScreen
import app.fediferry.ui.Space
import app.fediferry.ui.SpaceBar
import app.fediferry.work.ScheduleWords
import coil3.compose.AsyncImage

/** What goes out when, what failed, and what went out. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublishingScreen(
    onOpenPost: (String) -> Unit,
    onOpenSchedules: () -> Unit,
    onOpenSettings: () -> Unit,
    onSwitchSpace: (Space) -> Unit,
    viewModel: PublishingViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val resources = LocalResources.current

    LifecycleResumeEffect(Unit) {
        viewModel.start()
        onPauseOrDispose { viewModel.stop() }
    }

    Scaffold(
        bottomBar = { SpaceBar(Space.PUBLISHING, onSwitchSpace) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.publishing_title)) },
                actions = {
                    IconButton(onClick = onOpenSchedules) {
                        Icon(Icons.Outlined.CalendarMonth, contentDescription = stringResource(R.string.schedules_title))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.queue_settings))
                    }
                },
            )
        },
    ) { padding ->
        if (!state.loadedOnce) {
            LoadingScreen(
                title = stringResource(R.string.publishing_loading),
                detail = stringResource(R.string.library_loading_detail),
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        val channelOf = { post: PostDto -> state.channels.firstOrNull { it.id == post.channelId }?.let { "@${it.acct}" } }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.error?.let { code -> item { Notice(ServerMessages.describe(resources, code)) } }

            if (state.failed.isNotEmpty()) {
                item { Heading(stringResource(R.string.publishing_failed)) }
                items(state.failed, key = { it.id }) { post ->
                    PostRow(
                        post, thumbnail = post.media.firstOrNull()?.let { viewModel.thumbnailUrl(it.asset.id) },
                        headline = ServerMessages.publishFailure(resources, post.publication?.failureCode),
                        detail = channelOf(post),
                        warning = true,
                        onClick = { onOpenPost(post.id) },
                    )
                }
            }

            item { Heading(stringResource(R.string.publishing_queue)) }
            if (state.queue.isEmpty()) item { Notice(stringResource(R.string.publishing_queue_empty)) }
            var lastDay: String? = null
            state.queue.forEach { post ->
                val at = post.publication?.publishAfter ?: return@forEach
                val day = ScheduleWords.dayHeading(resources, at)
                if (day != lastDay) {
                    lastDay = day
                    item(key = "day-$day") { Text(day, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp)) }
                }
                item(key = post.id) {
                    val publication = post.publication
                    val schedule = state.schedules.firstOrNull { it.id == publication?.scheduleId }?.name
                    PostRow(
                        post, thumbnail = post.media.firstOrNull()?.let { viewModel.thumbnailUrl(it.asset.id) },
                        headline = when {
                            post.stage == PostStages.PUBLISHING -> stringResource(R.string.publishing_sending)
                            (publication?.attempts ?: 0) > 0 -> stringResource(R.string.publishing_retry_at, ScheduleWords.timeText(resources, at))
                            else -> ScheduleWords.timeText(resources, at)
                        },
                        detail = listOfNotNull(channelOf(post), schedule).joinToString(" · "),
                        onClick = { onOpenPost(post.id) },
                    )
                }
            }

            if (state.published.isNotEmpty()) {
                item { Heading(stringResource(R.string.publishing_published)) }
                items(state.published, key = { it.id }) { post ->
                    PostRow(
                        post, thumbnail = post.media.firstOrNull()?.let { viewModel.thumbnailUrl(it.asset.id) },
                        headline = post.publication?.publishedAt?.let { ScheduleWords.fullText(resources, it) }.orEmpty(),
                        detail = channelOf(post),
                        onClick = { onOpenPost(post.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Heading(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun PostRow(
    post: PostDto,
    thumbnail: String?,
    headline: String,
    detail: String?,
    onClick: () -> Unit,
    warning: Boolean = false,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (thumbnail != null) {
                    AsyncImage(model = thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Outlined.Notes, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (warning) Icons.Outlined.Warning else Icons.Outlined.Schedule,
                        contentDescription = null,
                        tint = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        headline,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                Text(post.finalText.orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                detail?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Notice(text: String) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp))
