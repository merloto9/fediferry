package app.fediferry.ui.inbox

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.fediferry.data.model.Item
import app.fediferry.data.model.Status
import app.fediferry.di.ServiceLocator
import app.fediferry.work.PostScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class InboxViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ServiceLocator.items(app)

    val items: StateFlow<List<Item>> = repo.observeInbox()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Posts the given items. [spacingMinutes] staggers them so a batch of six
     * saved memes does not arrive on followers' timelines as one wall.
     */
    fun post(ids: Collection<String>, spacingMinutes: Int = 0) = viewModelScope.launch {
        ids.forEachIndexed { index, id ->
            repo.markQueued(id)
            PostScheduler.enqueue(
                getApplication(),
                id,
                delayMillis = index * spacingMinutes * 60_000L,
            )
        }
    }

    fun retry(id: String) = post(listOf(id))

    fun delete(ids: Collection<String>) = viewModelScope.launch {
        ids.forEach { id ->
            PostScheduler.cancel(getApplication(), id)
            repo.delete(id)
        }
    }

    fun cancelQueued(id: String) = viewModelScope.launch {
        PostScheduler.cancel(getApplication(), id)
        repo.markDraft(id)
    }

    companion object {
        fun canPost(item: Item) = item.status == Status.DRAFT || item.status == Status.FAILED
    }
}
