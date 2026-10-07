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

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fediferry.R
import app.fediferry.api.ChannelDto
import app.fediferry.api.ScheduleDto
import app.fediferry.api.ScheduleInput
import app.fediferry.api.SlotDto
import app.fediferry.drafts.ServerMessages
import app.fediferry.work.ScheduleWords
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle

/** Upload schedules: weekly slots per channel, which "next free slot" fills in turn. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulesScreen(onBack: () -> Unit, viewModel: SchedulesViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<ScheduleDto?>(null) }
    var creating by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refresh() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(ServerMessages.describe(resources, it))
            viewModel.clearMessage()
        }
    }
    if (creating || editing != null) {
        ScheduleEditor(
            schedule = editing,
            channels = state.channels,
            onSave = { input -> viewModel.save(editing?.id, input); editing = null; creating = false },
            onDelete = editing?.let { s -> { viewModel.delete(s); editing = null } },
            onDismiss = { editing = null; creating = false },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.schedules_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.queue_back))
                    }
                },
                actions = {
                    if (state.channels.isNotEmpty()) {
                        IconButton(onClick = { creating = true }) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.schedules_new))
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(stringResource(R.string.schedules_explain), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.loaded && state.channels.isEmpty()) item { Text(stringResource(R.string.draft_no_channels), color = MaterialTheme.colorScheme.error) }
            if (state.loaded && state.schedules.isEmpty() && state.channels.isNotEmpty()) {
                item {
                    TextButton(onClick = { creating = true }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text(stringResource(R.string.schedules_new), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
            items(state.schedules, key = { it.id }) { schedule ->
                Card(Modifier.fillMaxWidth().clickable { editing = schedule }) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(schedule.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    state.channels.firstOrNull { it.id == schedule.channelId }?.let { "@${it.acct}" } ?: "—",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = schedule.active, onCheckedChange = { viewModel.setActive(schedule, it) })
                        }
                        schedule.slots.forEach { Text(slotText(it), style = MaterialTheme.typography.bodyMedium) }
                        if (schedule.slots.isEmpty()) Text(stringResource(R.string.schedules_no_slots), style = MaterialTheme.typography.bodySmall)
                        schedule.nextFree.firstOrNull()?.let {
                            Text(
                                stringResource(R.string.schedules_next_free, ScheduleWords.whenText(resources, it)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (schedule.timezone != ZoneId.systemDefault().id) {
                            Text(stringResource(R.string.schedules_zone, schedule.timezone), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

/** "Mon, Wed, Fri · 18:00", or "Every day · 18:00". */
@Composable
private fun slotText(slot: SlotDto): String {
    val days = if (slot.days.size == 7) {
        stringResource(R.string.schedules_every_day)
    } else {
        val locale = LocalResources.current.configuration.locales[0]
        slot.days.sorted().joinToString(", ") { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, locale) }
    }
    return "$days · ${slot.time}"
}

/** Creates or changes a schedule: its name, channel and weekly slots. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ScheduleEditor(
    schedule: ScheduleDto?,
    channels: List<ChannelDto>,
    onSave: (ScheduleInput) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(schedule?.name.orEmpty()) }
    var channelId by remember { mutableStateOf(schedule?.channelId ?: channels.firstOrNull { it.isDefault }?.id ?: channels.firstOrNull()?.id) }
    var slots by remember { mutableStateOf(schedule?.slots ?: listOf(SlotDto((1..7).toList(), "18:00"))) }
    var channelMenu by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf<Int?>(null) }
    val locale = LocalResources.current.configuration.locales[0]

    pickingTime?.let { index ->
        val current = LocalTime.parse(slots[index].time)
        val timeState = rememberTimePickerState(current.hour, current.minute, DateFormat.is24HourFormat(LocalContext.current))
        AlertDialog(
            onDismissRequest = { pickingTime = null },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    slots = slots.mapIndexed { i, s -> if (i == index) s.copy(time = "%02d:%02d".format(timeState.hour, timeState.minute)) else s }
                    pickingTime = null
                }) { Text(stringResource(R.string.library_save)) }
            },
            dismissButton = { TextButton(onClick = { pickingTime = null }) { Text(stringResource(R.string.delete_back)) } },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (schedule == null) R.string.schedules_new else R.string.schedules_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.schedules_name)) },
                    placeholder = { Text(stringResource(R.string.schedules_name_example)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Box {
                    TextButton(onClick = { channelMenu = true }) {
                        Text(stringResource(R.string.draft_channel, channels.firstOrNull { it.id == channelId }?.let { "@${it.acct}" } ?: "—"))
                    }
                    DropdownMenu(expanded = channelMenu, onDismissRequest = { channelMenu = false }) {
                        channels.forEach { c -> DropdownMenuItem(text = { Text("${c.name} · @${c.acct}") }, onClick = { channelId = c.id; channelMenu = false }) }
                    }
                }
                slots.forEachIndexed { index, slot ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { pickingTime = index }) { Text(slot.time, style = MaterialTheme.typography.titleLarge) }
                            Box(Modifier.weight(1f))
                            IconButton(onClick = { slots = slots.filterIndexed { i, _ -> i != index } }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.schedules_remove_slot))
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            (1..7).forEach { day ->
                                val on = day in slot.days
                                FilterChip(
                                    selected = on,
                                    onClick = {
                                        slots = slots.mapIndexed { i, s ->
                                            if (i == index) s.copy(days = if (on) s.days - day else (s.days + day).sorted()) else s
                                        }
                                    },
                                    label = { Text(DayOfWeek.of(day).getDisplayName(TextStyle.SHORT, locale)) },
                                )
                            }
                        }
                    }
                }
                TextButton(onClick = { slots = slots + SlotDto((1..5).toList(), "09:00") }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(stringResource(R.string.schedules_add_slot), modifier = Modifier.padding(start = 8.dp))
                }
                Text(
                    stringResource(R.string.schedules_zone, schedule?.timezone ?: ZoneId.systemDefault().id),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.schedules_delete), color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        ScheduleInput(
                            channelId = channelId,
                            name = name.trim().ifEmpty { null },
                            timezone = schedule?.timezone ?: ZoneId.systemDefault().id,
                            slots = slots.filter { it.days.isNotEmpty() },
                        ),
                    )
                },
                enabled = channelId != null,
            ) { Text(stringResource(R.string.library_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.delete_back)) } },
    )
}
