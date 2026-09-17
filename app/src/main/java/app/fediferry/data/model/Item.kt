package app.fediferry.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class Visibility { PUBLIC, UNLISTED, PRIVATE, DIRECT;

    /** The wire value Mastodon expects for `visibility`. */
    val api: String get() = name.lowercase()
}

enum class Status { DRAFT, QUEUED, POSTING, POSTED, FAILED }

/**
 * One shared item. Written before anything is acted on, in every mode — a
 * zero-touch post that fails still lands in the inbox instead of vanishing.
 */
@Entity(
    tableName = "items",
    indices = [Index("mediaHash"), Index("status"), Index("createdAt")],
)
data class Item(
    @PrimaryKey val id: String,
    val mediaPath: String? = null,
    val mediaHash: String? = null,
    val mimeType: String? = null,
    val sourceUrl: String? = null,
    val bodyText: String = "",
    val altText: String? = null,
    /** Set when alt-text resolution failed, so the editor can prompt for it. */
    val altTextFailed: Boolean = false,
    val contentWarning: String? = null,
    val visibility: Visibility = Visibility.PUBLIC,
    val templateId: String,
    val accountId: String? = null,
    val status: Status = Status.DRAFT,
    val failureReason: String? = null,
    @ColumnInfo(name = "createdAt") val createdAt: Long = System.currentTimeMillis(),
    val postedAt: Long? = null,
    val statusUrl: String? = null,
    /** Epoch millis; when set the worker is delayed until then. */
    val scheduledAt: Long? = null,
)
