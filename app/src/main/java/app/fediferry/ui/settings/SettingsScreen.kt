package app.fediferry.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fediferry.data.model.AltTextMode
import app.fediferry.data.model.Template
import app.fediferry.data.model.Visibility

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

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
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AccountsSection(state, viewModel)
            HorizontalDivider()
            TemplatesSection(state, viewModel)
            HorizontalDivider()
            PostingSection(state, viewModel)
            HorizontalDivider()
            VisionSection(state, viewModel)
        }
    }
}

@Composable
private fun AccountsSection(state: SettingsState, viewModel: SettingsViewModel) {
    var instance by remember { mutableStateOf("") }

    SectionTitle("Accounts")

    state.accounts.forEach { account ->
        Card(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("@${account.acct}", style = MaterialTheme.typography.titleSmall)
                    Text(account.instance, style = MaterialTheme.typography.bodySmall)
                }
                if (account.isDefault) {
                    Text("Default", style = MaterialTheme.typography.labelSmall)
                } else {
                    TextButton(onClick = { viewModel.makeDefaultAccount(account.id) }) {
                        Text("Make default")
                    }
                }
                IconButton(onClick = { viewModel.disconnect(account.id) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Disconnect")
                }
            }
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = instance,
            onValueChange = { instance = it },
            label = { Text("Instance") },
            placeholder = { Text("mastodon.social") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = { viewModel.connect(instance) },
            enabled = !state.connecting,
        ) { Text("Connect") }
    }
}

@Composable
private fun TemplatesSection(state: SettingsState, viewModel: SettingsViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionTitle("Templates", Modifier.weight(1f))
        IconButton(onClick = viewModel::newTemplate) {
            Icon(Icons.Default.Add, contentDescription = "New template")
        }
    }
    Text(
        "Placeholders: {link}, {tags}, {date}. A placeholder with nothing to fill " +
            "it resolves to an empty string and its line is dropped.",
        style = MaterialTheme.typography.bodySmall,
    )

    state.templates.forEach { template ->
        TemplateCard(template, viewModel)
    }
}

@Composable
private fun TemplateCard(template: Template, viewModel: SettingsViewModel) {
    var draft by remember(template.id) { mutableStateOf(template) }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.body,
                onValueChange = { draft = draft.copy(body = it) },
                label = { Text("Body") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.tags,
                onValueChange = { draft = draft.copy(tags = it) },
                label = { Text("Tags") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Visibility", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Visibility.entries.forEach { visibility ->
                    FilterChip(
                        selected = draft.visibility == visibility,
                        onClick = { draft = draft.copy(visibility = visibility) },
                        label = { Text(visibility.name.lowercase()) },
                    )
                }
            }

            Text("Alt text", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AltTextMode.entries.forEach { mode ->
                    FilterChip(
                        selected = draft.altTextMode == mode,
                        onClick = { draft = draft.copy(altTextMode = mode) },
                        label = { Text(mode.name.lowercase()) },
                    )
                }
            }

            if (draft.altTextMode == AltTextMode.STATIC) {
                OutlinedTextField(
                    value = draft.staticAltText.orEmpty(),
                    onValueChange = { draft = draft.copy(staticAltText = it) },
                    label = { Text("Static description") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = { viewModel.saveTemplate(draft) }) { Text("Save") }
                if (!template.isDefault) {
                    TextButton(onClick = { viewModel.makeDefaultTemplate(template.id) }) {
                        Text("Make default")
                    }
                    TextButton(onClick = { viewModel.deleteTemplate(template.id) }) {
                        Text("Delete")
                    }
                } else {
                    Text("Default", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun PostingSection(state: SettingsState, viewModel: SettingsViewModel) {
    SectionTitle("Posting")

    Text("Undo window: ${state.settings.undoDelaySeconds}s")
    Text(
        "How long Post now waits before sending. Zero sends immediately.",
        style = MaterialTheme.typography.bodySmall,
    )
    Slider(
        value = state.settings.undoDelaySeconds.toFloat(),
        onValueChange = { viewModel.setUndoDelay(it.toInt()) },
        valueRange = 0f..30f,
        steps = 29,
    )

    Text(
        if (state.settings.purgePostedAfterDays == 0) {
            "Posted items: kept forever"
        } else {
            "Posted items: purged after ${state.settings.purgePostedAfterDays} days"
        },
    )
    Slider(
        value = state.settings.purgePostedAfterDays.toFloat(),
        onValueChange = { viewModel.setPurgeAfterDays(it.toInt()) },
        valueRange = 0f..90f,
        steps = 89,
    )
}

@Composable
private fun VisionSection(state: SettingsState, viewModel: SettingsViewModel) {
    SectionTitle("Alt-text provider")
    Text(
        "Any OpenAI-compatible chat-completions endpoint. Used only by templates " +
            "set to VISION; a failure never blocks a post.",
        style = MaterialTheme.typography.bodySmall,
    )

    OutlinedTextField(
        value = state.settings.visionEndpoint,
        onValueChange = viewModel::setVisionEndpoint,
        label = { Text("Endpoint URL") },
        placeholder = { Text("https://api.example.com/v1/chat/completions") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.settings.visionModel,
        onValueChange = viewModel::setVisionModel,
        label = { Text("Model") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.settings.visionApiKey,
        onValueChange = viewModel::setVisionApiKey,
        label = { Text("API key") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.settings.visionPrompt,
        onValueChange = viewModel::setVisionPrompt,
        label = { Text("Prompt") },
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier)
}
