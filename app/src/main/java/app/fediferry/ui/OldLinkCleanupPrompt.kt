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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
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
        data class Offer(val count: Int, val services: String) : Phase
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
                        _phase.value = Phase.Offer(candidates.size, services.joinToString(" and "))
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
                title = { Text("Links in the inbox") },
                text = { Text("No post in the inbox has a Pinterest or Reddit link to check.") },
                confirmButton = { TextButton(onClick = { viewModel.decline() }) { Text("OK") } },
            )
        } else {
            AlertDialog(
                onDismissRequest = { viewModel.decline() },
                title = { Text("Clean older links?") },
                text = {
                    Text(
                        "${plural(p.count, "post")} in the inbox ${if (p.count == 1) "has" else "have"} " +
                            "${p.services} links, in the shared link or the text. Links from before " +
                            "FediFerry 0.17.1, or added by hand, may still say who shared them. " +
                            "Check them now? Each link is tested, and one that can't be " +
                            "confirmed is kept as it is.\n\n" +
                            "You can do this later in Settings → Sharing & posting.",
                    )
                },
                confirmButton = { TextButton(onClick = { viewModel.run() }) { Text("Clean links") } },
                dismissButton = { TextButton(onClick = { viewModel.decline() }) { Text("Not now") } },
            )
        }
        is OldLinkCleanupViewModel.Phase.Running -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text("Cleaning links") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (p.total == 0) {
                        Text("Looking at the inbox…")
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        Text("Checking post ${minOf(p.done + 1, p.total)} of ${p.total}")
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
            title = { Text("Links checked") },
            text = { Text(summary(p.result)) },
            confirmButton = { TextButton(onClick = { viewModel.close() }) { Text("OK") } },
        )
    }
}

private fun plural(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"

/** What a run did, in the words the done dialog uses. */
internal fun summary(r: OldLinkCleanup.Result): String = buildList {
    if (r.cleaned > 0) add("${plural(r.cleaned, "link")} cleaned.")
    if (r.kept > 0) {
        val one = r.kept == 1
        add(
            "${plural(r.kept, "link")} couldn't be confirmed and ${if (one) "was" else "were"} " +
                "kept as shared, so ${if (one) "it" else "they"} may still say who shared ${if (one) "it" else "them"}.",
        )
    }
    if (r.alreadyClean > 0) add("${plural(r.alreadyClean, "link")} already clean.")
}.ifEmpty { listOf("Nothing needed changing.") }.joinToString("\n")
