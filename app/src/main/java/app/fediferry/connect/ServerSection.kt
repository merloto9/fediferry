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
package app.fediferry.connect

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.LocalContext
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fediferry.R

/**
 * Settings → FediFerry server: the address of the server and the project token,
 * or — once connected — which project this phone works on.
 */
@Composable
fun ServerSection(viewModel: ConnectViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val connection = state.connection

    Text(stringResource(R.string.server_intro), style = MaterialTheme.typography.bodySmall)

    if (connection != null) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.server_connected_to, connection.projectName), style = MaterialTheme.typography.titleMedium)
            Text(connection.address, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.server_device_id, connection.deviceId.take(8)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(stringResource(R.string.server_sync_note), style = MaterialTheme.typography.bodySmall)
        if (state.waitingToImport > 0) {
            Text(
                pluralStringResource(R.plurals.server_import_waiting, state.waitingToImport, state.waitingToImport),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = viewModel::importPosts) { Text(stringResource(R.string.server_import)) }
        }
        state.justImported?.let {
            Text(pluralStringResource(R.plurals.server_imported, it, it), style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = viewModel::disconnect) { Text(stringResource(R.string.server_disconnect)) }
        return
    }

    var address by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    // Asked only for a server in the local network; connecting goes ahead
    // either way, and a refusal shows up in the error message.
    val askLocalNetwork = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.connect(address, token)
    }
    OutlinedTextField(
        value = address,
        onValueChange = { address = it.trim() },
        label = { Text(stringResource(R.string.server_address)) },
        placeholder = { Text("fediferry.example.org") },
        supportingText = { Text(stringResource(R.string.server_address_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = token,
        onValueChange = { token = it.trim() },
        label = { Text(stringResource(R.string.server_token)) },
        placeholder = { Text("ffp_…") },
        supportingText = { Text(stringResource(R.string.server_token_hint)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    state.error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    Button(
        onClick = {
            if (LocalNetwork.mustAsk(context, address)) {
                askLocalNetwork.launch(LocalNetwork.PERMISSION)
            } else {
                viewModel.connect(address, token)
            }
        },
        enabled = !state.busy && address.isNotBlank() && token.isNotBlank(),
    ) {
        Text(stringResource(if (state.busy) R.string.server_connecting else R.string.server_connect))
    }
}
