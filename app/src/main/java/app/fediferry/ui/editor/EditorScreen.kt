package app.fediferry.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fediferry.data.model.Visibility
import coil3.compose.AsyncImage
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    itemId: String,
    onDone: () -> Unit,
    viewModel: EditorViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(itemId) { viewModel.load(itemId) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Edit post") },
                navigationIcon = {
                    IconButton(onClick = { viewModel.saveDraft(onDone) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Save and go back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.discard(onDone) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Discard")
                    }
                },
            )
        },
    ) { padding ->
        val item = state.item
        if (item == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (state.loaded) Text("That item is gone") else CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item.mediaPath?.let { path ->
                AsyncImage(
                    model = File(path),
                    contentDescription = item.altText ?: "Shared image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
            }

            TemplatePicker(state, viewModel)

            OutlinedTextField(
                value = item.bodyText,
                onValueChange = viewModel::setBody,
                label = { Text("Post text") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = item.altText.orEmpty(),
                onValueChange = viewModel::setAltText,
                label = { Text("Alt text") },
                supportingText = {
                    if (item.altTextFailed) {
                        Text(
                            "Automatic description failed — the post will go out without one.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                minLines = 2,
                trailingIcon = {
                    if (state.altTextBusy) {
                        CircularProgressIndicator(Modifier.padding(12.dp))
                    } else {
                        IconButton(onClick = viewModel::regenerateAltText) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = "Regenerate alt text")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = item.contentWarning.orEmpty(),
                onValueChange = viewModel::setContentWarning,
                label = { Text("Content warning") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Visibility", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Visibility.entries.forEach { visibility ->
                    FilterChip(
                        selected = item.visibility == visibility,
                        onClick = { viewModel.setVisibility(visibility) },
                        label = { Text(visibility.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
            }

            AccountPicker(state, viewModel)

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { viewModel.saveDraft(onDone) },
                    modifier = Modifier.weight(1f),
                ) { Text("Save draft") }

                Button(
                    onClick = { viewModel.send(onDone) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                    Text("Post", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun TemplatePicker(state: EditorState, viewModel: EditorViewModel) {
    var open by remember { mutableStateOf(false) }
    val current = state.templates.firstOrNull { it.id == state.item?.templateId }

    Box {
        TextButton(onClick = { open = true }) {
            Text("Template: ${current?.name ?: "—"}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.templates.forEach { template ->
                DropdownMenuItem(
                    text = { Text(template.name) },
                    onClick = {
                        viewModel.applyTemplate(template)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun AccountPicker(state: EditorState, viewModel: EditorViewModel) {
    if (state.accounts.isEmpty()) {
        Text(
            "No account connected — add one in Settings before posting.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        return
    }

    var open by remember { mutableStateOf(false) }
    val current = state.accounts.firstOrNull { it.id == state.item?.accountId }
        ?: state.accounts.firstOrNull { it.isDefault }

    Box {
        TextButton(onClick = { open = true }) {
            Text("Account: @${current?.acct ?: "—"}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.accounts.forEach { account ->
                DropdownMenuItem(
                    text = { Text("@${account.acct}") },
                    onClick = {
                        viewModel.setAccount(account.id)
                        open = false
                    },
                )
            }
        }
    }
}
