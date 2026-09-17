package app.fediferry.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import app.fediferry.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Cancels a post still inside its undo window and returns the item to the inbox
 * as a draft. The work request is cancelled by its unique name, so a worker that
 * has already started sending is not interrupted mid-flight.
 */
class UndoReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_UNDO) return
        val itemId = intent.getStringExtra(EXTRA_ITEM_ID) ?: return
        val appContext = context.applicationContext
        val pending = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                PostScheduler.cancel(appContext, itemId)
                ServiceLocator.items(appContext).markDraft(itemId)
                Notifications.cancel(appContext, itemId)
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "Post cancelled — kept as a draft", Toast.LENGTH_SHORT)
                        .show()
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_UNDO = "app.fediferry.action.UNDO"
        const val EXTRA_ITEM_ID = "app.fediferry.extra.ITEM_ID"
    }
}
