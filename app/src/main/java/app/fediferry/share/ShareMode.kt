package app.fediferry.share

import androidx.annotation.StringRes
import app.fediferry.R

/**
 * Where the shared-item pipeline pauses. Every mode ingests and persists first;
 * they differ only in what happens next.
 */
enum class ShareMode(val shortcutId: String, @StringRes val labelRes: Int) {
    /** Template applied, enqueued immediately, held for the undo window. */
    POST_NOW("post_now", R.string.share_post_now),

    /** Editor opens prefilled; the user sends. */
    COMPOSE("compose", R.string.share_compose),

    /** Persisted as a draft and left in the inbox. */
    SAVE_FOR_LATER("save_later", R.string.share_save_later);

    companion object {
        const val EXTRA = "app.fediferry.extra.SHARE_MODE"

        fun fromShortcutId(id: String?): ShareMode? = entries.firstOrNull { it.shortcutId == id }

        fun fromName(name: String?): ShareMode? =
            entries.firstOrNull { it.name == name }
    }
}
