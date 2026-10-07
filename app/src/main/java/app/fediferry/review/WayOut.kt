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
package app.fediferry.review

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.fediferry.R
import app.fediferry.api.PlanModes
import app.fediferry.api.PlanRequest
import app.fediferry.api.PostDto
import app.fediferry.api.PostStages
import app.fediferry.api.ScheduleDto
import app.fediferry.drafts.ServerMessages
import app.fediferry.ui.SchedulePicker
import app.fediferry.work.ScheduleWords

/**
 * Where a planned, failed or published post stands on its way out, with what
 * can be done about it from here.
 */
@Composable
internal fun WayOut(post: PostDto, state: ReviewPostState, onPlan: () -> Unit, onUnplan: () -> Unit, onRetry: () -> Unit) {
    val publication = post.publication ?: return
    val resources = LocalResources.current
    val context = LocalContext.current
    val failed = post.stage == PostStages.FAILED
    Surface(
        color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val schedule = state.schedules.firstOrNull { it.id == publication.scheduleId }?.name
            Text(
                when (post.stage) {
                    PostStages.SCHEDULED -> stringResource(R.string.post_goes_out, ScheduleWords.fullText(resources, publication.publishAfter))
                    PostStages.PUBLISHING -> stringResource(R.string.publishing_sending)
                    PostStages.PUBLISHED -> stringResource(R.string.post_went_out, ScheduleWords.fullText(resources, publication.publishedAt ?: publication.publishAfter))
                    else -> stringResource(R.string.post_failed_because, ServerMessages.publishFailure(resources, publication.failureCode))
                },
                style = MaterialTheme.typography.titleSmall,
            )
            if (schedule != null && post.stage == PostStages.SCHEDULED) Text(stringResource(R.string.post_in_slot, schedule), style = MaterialTheme.typography.bodySmall)
            if (post.stage == PostStages.SCHEDULED && publication.attempts > 0) {
                Text(
                    pluralStringResource(R.plurals.post_tried, publication.attempts, publication.attempts, ServerMessages.publishFailure(resources, publication.failureCode)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when (post.stage) {
                    PostStages.SCHEDULED -> {
                        TextButton(onClick = onPlan) { Text(stringResource(R.string.post_move)) }
                        TextButton(onClick = onUnplan) { Text(stringResource(R.string.post_unplan)) }
                    }
                    PostStages.FAILED -> {
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.post_retry_now)) }
                        TextButton(onClick = onPlan) { Text(stringResource(R.string.post_plan_again)) }
                        TextButton(onClick = onUnplan) { Text(stringResource(R.string.post_unplan)) }
                    }
                    PostStages.PUBLISHED -> publication.remoteUrl?.let { url ->
                        OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) } }) {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
                            Text(stringResource(R.string.post_open_remote), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Into the queue: the next free slot, a time of one's own, or right away. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlanSheet(post: PostDto, nextSlot: Pair<Long, ScheduleDto>?, onPlan: (PlanRequest) -> Unit, onDismiss: () -> Unit) {
    val resources = LocalResources.current
    var picking by remember { mutableStateOf(false) }
    var confirmingNow by remember { mutableStateOf(false) }

    if (picking) {
        SchedulePicker(
            title = stringResource(R.string.post_pick_time),
            confirmLabel = stringResource(R.string.post_plan),
            initial = post.publication?.publishAfter ?: (System.currentTimeMillis() + 3_600_000),
            onPick = { picking = false; onPlan(PlanRequest(PlanModes.AT, at = it)) },
            onDismiss = { picking = false },
            byServer = true,
        )
        return
    }
    if (confirmingNow) {
        AlertDialog(
            onDismissRequest = { confirmingNow = false },
            title = { Text(stringResource(R.string.post_now_title)) },
            text = { Text(stringResource(R.string.post_now_text)) },
            confirmButton = { TextButton(onClick = { confirmingNow = false; onPlan(PlanRequest(PlanModes.NOW)) }) { Text(stringResource(R.string.post_now)) } },
            dismissButton = { TextButton(onClick = { confirmingNow = false }) { Text(stringResource(R.string.delete_back)) } },
        )
        return
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(stringResource(R.string.post_plan), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            ListItem(
                headlineContent = { Text(stringResource(R.string.post_next_slot)) },
                supportingContent = {
                    Text(
                        nextSlot?.let { (at, schedule) -> "${ScheduleWords.fullText(resources, at)} · ${schedule.name}" }
                            ?: stringResource(R.string.post_no_slot),
                    )
                },
                leadingContent = { Icon(Icons.Outlined.EventAvailable, contentDescription = null) },
                modifier = if (nextSlot != null) Modifier.clickable { onPlan(PlanRequest(PlanModes.NEXT_SLOT, scheduleId = nextSlot.second.id)) } else Modifier,
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.post_pick_time)) },
                leadingContent = { Icon(Icons.Outlined.EditCalendar, contentDescription = null) },
                modifier = Modifier.clickable { picking = true },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.post_now)) },
                supportingContent = { Text(stringResource(R.string.post_now_hint)) },
                leadingContent = { Icon(Icons.Outlined.Bolt, contentDescription = null) },
                modifier = Modifier.clickable { confirmingNow = true },
            )
        }
    }
}
