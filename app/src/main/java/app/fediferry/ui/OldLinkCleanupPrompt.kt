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

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fediferry.R
import app.fediferry.data.OldLinkCleanup
import app.fediferry.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Offers, once, to clean the links of posts that were shared before links were
 * cleaned on arrival. Settings → Sharing & posting can ask again.
 */
class OldLinkCleanupViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface Phase {
        data object Hidden : Phase
        data class Offer(val count: Int, val services: List<String>) : Phase
        data class Running(val done: Int, val total: Int) : Phase
        data class Done(val result: OldLinkCleanup.Result) : Phase
    }

    private val repo = ServiceLocator.items(app)
    private val settings = ServiceLocator.settings(app)

    private val _phase = MutableStateFlow<Phase>(Phase.Hidden)
    val phase: StateFlow<Phase> = _phase

    init {
        viewModelScope.launch {
            var firstLook = true
            settings.settings.map { it.oldLinkCleanupOffered }.distinctUntilChanged().collect { offered ->
                if (!offered) {
                    val candidates = repo.oldLinkCandidates()
                    if (candidates.isEmpty() && firstLook) {
                        // A new install, or an inbox without such links: nothing to ask.
                        settings.setOldLinkCleanupOffered(true)
                    } else {
                        val services = candidates.flatMap { repo.cleaningServices(it) }.distinct()
                        _phase.value = Phase.Offer(candidates.size, services)
                    }
                }
                firstLook = false
            }
        }
    }

    fun decline() = viewModelScope.launch {
        _phase.value = Phase.Hidden
        settings.setOldLinkCleanupOffered(true)
    }

    fun run() = viewModelScope.launch {
        settings.setOldLinkCleanupOffered(true)
        _phase.value = Phase.Running(0, 0)
        val result = repo.cleanOldLinks { done, total -> _phase.value = Phase.Running(done, total) }
        _phase.value = Phase.Done(result)
    }

    fun close() {
        _phase.value = Phase.Hidden
    }
}

@Composable
fun OldLinkCleanupPrompt(viewModel: OldLinkCleanupViewModel = viewModel()) {
    val phase by viewModel.phase.collectAsState()
    when (val p = phase) {
        OldLinkCleanupViewModel.Phase.Hidden -> Unit
        is OldLinkCleanupViewModel.Phase.Offer -> if (p.count == 0) {
            AlertDialog(
                onDismissRequest = { viewModel.decline() },
                title = { Text(stringResource(R.string.oldlinks_none_title)) },
                text = { Text(stringResource(R.string.oldlinks_none_body)) },
                confirmButton = { TextButton(onClick = { viewModel.decline() }) { Text(stringResource(R.string.oldlinks_ok)) } },
            )
        } else {
            AlertDialog(
                onDismissRequest = { viewModel.decline() },
                title = { Text(stringResource(R.string.oldlinks_offer_title)) },
                text = {
                    val pair = stringResource(R.string.oldlinks_services_pair)
                    val services = p.services.reduceOrNull { a, b -> String.format(pair, a, b) }.orEmpty()
                    Text(pluralStringResource(R.plurals.oldlinks_offer_body, p.count, p.count, services))
                },
                confirmButton = { TextButton(onClick = { viewModel.run() }) { Text(stringResource(R.string.oldlinks_clean)) } },
                dismissButton = { TextButton(onClick = { viewModel.decline() }) { Text(stringResource(R.string.oldlinks_not_now)) } },
            )
        }
        is OldLinkCleanupViewModel.Phase.Running -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(stringResource(R.string.oldlinks_running_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (p.total == 0) {
                        Text(stringResource(R.string.oldlinks_looking))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        Text(stringResource(R.string.oldlinks_checking, minOf(p.done + 1, p.total), p.total))
                        LinearProgressIndicator(
                            progress = { p.done / p.total.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {},
        )
        is OldLinkCleanupViewModel.Phase.Done -> AlertDialog(
            onDismissRequest = { viewModel.close() },
            title = { Text(stringResource(R.string.oldlinks_done_title)) },
            text = { Text(summary(LocalContext.current, p.result)) },
            confirmButton = { TextButton(onClick = { viewModel.close() }) { Text(stringResource(R.string.oldlinks_ok)) } },
        )
    }
}

/** What a run did, in the words the done dialog uses. */
internal fun summary(context: Context, r: OldLinkCleanup.Result): String {
    val res = context.resources
    return buildList {
        if (r.cleaned > 0) add(res.getQuantityString(R.plurals.oldlinks_cleaned, r.cleaned, r.cleaned))
        if (r.kept > 0) add(res.getQuantityString(R.plurals.oldlinks_kept, r.kept, r.kept))
        if (r.alreadyClean > 0) add(res.getQuantityString(R.plurals.oldlinks_already_clean, r.alreadyClean, r.alreadyClean))
    }.ifEmpty { listOf(context.getString(R.string.oldlinks_nothing)) }.joinToString("\n")
}
