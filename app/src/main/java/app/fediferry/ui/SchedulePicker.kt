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

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.fediferry.work.ScheduleFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Picks when Mastodon should publish a post: a day, then a time, never less
 * than [ScheduleFormat.MIN_LEAD_MINUTES] away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulePicker(
    title: String,
    confirmLabel: String,
    initial: Long,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(initial).atZone(zone)
    val today = LocalDate.now(zone)
    var pickingTime by remember { mutableStateOf(false) }

    // The date picker counts in UTC midnights, whatever the phone's zone.
    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = start.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isBefore(today)

            override fun isSelectableYear(year: Int): Boolean = year >= today.year
        },
    )
    val timeState = rememberTimePickerState(
        initialHour = start.hour,
        initialMinute = start.minute,
        is24Hour = DateFormat.is24HourFormat(LocalContext.current),
    )
    val date = dateState.selectedDateMillis
        ?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
        ?: start.toLocalDate()

    if (!pickingTime) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = { pickingTime = true }) { Text("Next") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        ) {
            DatePicker(state = dateState, title = { Text(title, modifier = Modifier.padding(start = 24.dp, top = 16.dp)) })
        }
        return
    }

    val at = ScheduleFormat.combine(date, timeState.hour, timeState.minute, zone)
    val tooSoon = at < ScheduleFormat.earliest()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(ScheduleFormat.dayHeading(at), style = MaterialTheme.typography.titleSmall)
                TimePicker(state = timeState)
                if (tooSoon) {
                    Text(
                        "Pick a time at least ${ScheduleFormat.MIN_LEAD_MINUTES} minutes from now. " +
                            "Mastodon needs five, and the picture has to upload first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        "Mastodon keeps the post and publishes it ${ScheduleFormat.whenText(at)}. " +
                            "FediFerry doesn't need to be open then.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(at) }, enabled = !tooSoon) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = { pickingTime = false }) { Text("Back") } },
    )
}
