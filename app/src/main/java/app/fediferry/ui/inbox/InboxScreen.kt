package app.fediferry.ui.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fediferry.data.model.Item
import app.fediferry.data.model.Status
import coil3.compose.AsyncImage
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    onOpenItem: (String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: InboxViewModel = viewModel(),
) {
    val items by viewModel.items.collectAsState()
    var selection by remember { mutableStateOf(emptySet<String>()) }
    val selecting = selection.isNotEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selecting) "${selection.size} selected" else "Inbox") },
                navigationIcon = {
                    if (selecting) {
                        IconButton(onClick = { selection = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear selection")
                        }
                    }
                },
                actions = {
                    if (selecting) {
                        IconButton(onClick = {
                            viewModel.post(selection)
                            selection = emptySet()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Post selected")
                        }
                        IconButton(onClick = {
                            // Spaced out so a saved batch does not flood a timeline.
                            viewModel.post(selection, spacingMinutes = 30)
                            selection = emptySet()
                        }) {
                            Icon(Icons.Default.Schedule, contentDescription = "Post spaced out")
                        }
                        IconButton(onClick = {
                            viewModel.delete(selection)
                            selection = emptySet()
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete selected")
                        }
                    } else {
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            EmptyInbox(Modifier.padding(padding))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    ItemCard(
                        item = item,
                        selected = item.id in selection,
                        onClick = {
                            if (selecting) {
                                selection = selection.toggle(item.id)
                            } else {
                                onOpenItem(item.id)
                            }
                        },
                        onLongClick = { selection = selection.toggle(item.id) },
                    )
                }
            }
        }
    }
}

private fun Set<String>.toggle(id: String) = if (id in this) this - id else this + id

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ItemCard(
    item: Item,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .background(Color.Black.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center,
        ) {
            if (item.mediaPath != null) {
                AsyncImage(
                    model = File(item.mediaPath),
                    contentDescription = item.altText ?: "Shared image",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text("Text only", style = MaterialTheme.typography.labelMedium)
            }
        }
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(item.status)
            }
            Text(
                text = item.bodyText.ifBlank { "(no text)" },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            item.failureReason?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusChip(status: Status) {
    val label = when (status) {
        Status.DRAFT -> "Draft"
        Status.QUEUED -> "Queued"
        Status.POSTING -> "Sending"
        Status.POSTED -> "Posted"
        Status.FAILED -> "Failed"
    }
    AssistChip(onClick = {}, enabled = false, label = { Text(label) })
}

@Composable
private fun EmptyInbox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Nothing saved yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Screenshot a meme, share it here, and pick Post now, Compose, " +
                    "or Save for later.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
