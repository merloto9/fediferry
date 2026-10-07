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
package app.fediferry.ui.queue

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import app.fediferry.R
import app.fediferry.ui.LoadingOverlay
import app.fediferry.ui.SchedulePicker
import app.fediferry.work.ScheduleFormat

/** The dialogs a queued post's actions open, shared by the Queue and the post's own screen. */

@Composable
internal fun ChangeTimeDialog(post: QueuedPost, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    SchedulePicker(
        title = stringResource(R.string.queue_move_title),
        confirmLabel = stringResource(R.string.queue_move_confirm),
        initial = post.at.coerceAtLeast(ScheduleFormat.suggested()),
        onPick = onPick,
        onDismiss = onDismiss,
    )
}

@Composable
internal fun CancelPostDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.queue_cancel_title)) },
        text = { Text(stringResource(R.string.queue_cancel_text)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.queue_cancel_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.queue_cancel_keep)) } },
    )
}

@Composable
internal fun MakeDraftDialog(onMake: (cancelAfter: Boolean) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.queue_draft_title)) },
        text = { Text(stringResource(R.string.queue_draft_text)) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { onMake(true) }) { Text(stringResource(R.string.queue_draft_and_cancel)) }
                TextButton(onClick = { onMake(false) }) { Text(stringResource(R.string.queue_draft_and_keep)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.schedule_back)) }
            }
        },
    )
}

@Composable
internal fun DraftingOverlay() {
    LoadingOverlay(
        title = stringResource(R.string.queue_drafting_title),
        detail = stringResource(R.string.queue_drafting_detail),
    )
}
