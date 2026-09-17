package app.fediferry.share

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.lifecycle.lifecycleScope
import app.fediferry.MainActivity
import app.fediferry.data.model.Status
import app.fediferry.di.ServiceLocator
import app.fediferry.work.Notifications
import app.fediferry.work.PostScheduler
import kotlinx.coroutines.launch

/**
 * The single entry point for every share. Ingests and persists first, then
 * branches on the mode — that is the only thing the three modes disagree about.
 *
 * Invisible: it has no UI of its own and finishes as soon as the pipeline has
 * been handed off, so the share sheet dismisses immediately.
 */
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = intent
        val mode = resolveMode(intent)
        val payload = SharePayload.from(intent)

        if (payload.isEmpty) {
            toast("Nothing shareable in that")
            finish()
            return
        }

        ShortcutManagerCompat.reportShortcutUsed(this, mode.shortcutId)

        lifecycleScope.launch {
            val repo = ServiceLocator.items(this@ShareReceiverActivity)
            repo.seedIfEmpty()

            val result = repo.ingest(
                payload = payload,
                status = if (mode == ShareMode.POST_NOW) Status.QUEUED else Status.DRAFT,
            )

            result.fold(
                onSuccess = { item -> handle(mode, item.id) },
                onFailure = { error ->
                    // The whole throwable goes to the log: a bare `message` is
                    // useless for the errors that actually occur here (a
                    // NoClassDefFoundError's message is just the mangled class
                    // name). The payload itself is never logged.
                    Log.e(TAG, "Ingest failed for a $mode share", error)
                    toast("Could not read the share: ${error.describe()}")
                    finish()
                },
            )
        }
    }

    private suspend fun handle(mode: ShareMode, itemId: String) {
        when (mode) {
            ShareMode.POST_NOW -> {
                val delay = ServiceLocator.settings(this).current().undoDelaySeconds
                if (delay > 0) Notifications.showUndo(this, itemId, delay)
                PostScheduler.enqueue(this, itemId, delay * 1000L)
                toast(if (delay > 0) "Posting in ${delay}s" else "Posting…")
                finish()
            }

            ShareMode.COMPOSE -> {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .setAction(MainActivity.ACTION_EDIT)
                        .putExtra(MainActivity.EXTRA_ITEM_ID, itemId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
                finish()
            }

            ShareMode.SAVE_FOR_LATER -> {
                toast("Saved to the inbox")
                finish()
            }
        }
    }

    /**
     * Direct-share taps carry the mode as an extra. A plain "Open with FediFerry"
     * tap carries nothing, and defaults to the editor: the mode was not chosen,
     * so do not assume the irreversible one.
     */
    private fun resolveMode(intent: Intent): ShareMode =
        ShareMode.fromName(intent.getStringExtra(ShareMode.EXTRA))
            ?: ShareMode.fromShortcutId(intent.getStringExtra(EXTRA_SHORTCUT_ID))
            ?: ShareMode.COMPOSE

    private fun toast(message: String) =
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()

    /**
     * Class name plus message. Errors thrown by the class loader carry only a
     * mangled name as their message, so the message alone says nothing.
     */
    private fun Throwable.describe(): String {
        val root = generateSequence(this) { it.cause }.last()
        val name = root::class.java.simpleName
        return root.message?.takeIf { it.isNotBlank() }?.let { "$name: $it" } ?: name
    }

    private companion object {
        const val TAG = "ShareReceiver"

        /** Set by the launcher when a Sharing Shortcut is tapped. */
        const val EXTRA_SHORTCUT_ID = "android.intent.extra.shortcut.ID"
    }
}
