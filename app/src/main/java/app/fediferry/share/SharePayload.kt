package app.fediferry.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Parcelable

/**
 * What a share sheet actually handed us.
 *
 * Instagram sends `text/plain` with a permalink and nothing else; the image
 * always arrives from a separate screenshot share. Both shapes land here.
 */
data class SharePayload(
    val imageUris: List<Uri>,
    val text: String?,
) {
    val link: String? = text?.let { LINK.find(it)?.value }

    val isEmpty: Boolean get() = imageUris.isEmpty() && text.isNullOrBlank()

    companion object {
        private val LINK = Regex("""https?://\S+""")

        fun from(intent: Intent): SharePayload {
            val uris = when (intent.action) {
                Intent.ACTION_SEND -> listOfNotNull(intent.parcelable<Uri>(Intent.EXTRA_STREAM))
                Intent.ACTION_SEND_MULTIPLE ->
                    intent.parcelableList<Uri>(Intent.EXTRA_STREAM).orEmpty()
                else -> emptyList()
            }
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            return SharePayload(uris, text?.trim()?.ifBlank { null })
        }

        @Suppress("DEPRECATION")
        private inline fun <reified T : Parcelable> Intent.parcelable(key: String): T? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getParcelableExtra(key, T::class.java)
            } else {
                getParcelableExtra(key) as? T
            }

        @Suppress("DEPRECATION")
        private inline fun <reified T : Parcelable> Intent.parcelableList(key: String): List<T>? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getParcelableArrayListExtra(key, T::class.java)
            } else {
                getParcelableArrayListExtra<T>(key)
            }
    }
}
