package app.fediferry.data

import app.fediferry.data.db.AccountDao
import app.fediferry.data.db.ItemDao
import app.fediferry.data.db.TemplateDao
import app.fediferry.data.model.Item
import app.fediferry.data.model.Status
import app.fediferry.data.model.Template
import app.fediferry.share.SharePayload
import app.fediferry.template.TemplateEngine
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * The single ingest path. Every share, in every mode, is persisted here before
 * anything else happens — that is what makes the zero-touch path recoverable
 * rather than silently lossy.
 */
class ItemRepository(
    private val items: ItemDao,
    private val templates: TemplateDao,
    private val accounts: AccountDao,
    private val media: MediaVault,
) {

    fun observeInbox(): Flow<List<Item>> = items.observeInbox()
    fun observeHistory(): Flow<List<Item>> = items.observeHistory()
    fun observe(id: String): Flow<Item?> = items.observe(id)

    suspend fun byId(id: String): Item? = items.byId(id)

    /**
     * Ingests one shared payload into a persisted [Item].
     *
     * If the same screenshot was already ingested and is still pending, that item
     * is returned instead of a duplicate — sharing a screenshot twice by accident
     * should not produce two posts.
     */
    suspend fun ingest(
        payload: SharePayload,
        templateId: String? = null,
        status: Status = Status.DRAFT,
    ): Result<Item> = runCatching {
        val template = resolveTemplate(templateId)

        val stored = payload.imageUris.firstOrNull()
            ?.let { uri -> media.ingest(uri).getOrThrow() }

        stored?.sha256?.let { hash ->
            items.byMediaHash(hash)?.let { existing ->
                // Same bytes, still pending: fold the new share into it rather
                // than creating a second copy.
                val merged = existing.copy(
                    sourceUrl = existing.sourceUrl ?: payload.link,
                )
                if (merged != existing) items.update(merged)
                return@runCatching merged
            }
        }

        val body = TemplateEngine.render(
            template,
            TemplateEngine.Inputs(link = payload.link),
        )

        val item = Item(
            id = UUID.randomUUID().toString(),
            mediaPath = stored?.file?.absolutePath,
            mediaHash = stored?.sha256,
            mimeType = stored?.mimeType,
            sourceUrl = payload.link,
            bodyText = body,
            altText = null,
            contentWarning = template.contentWarning,
            visibility = template.visibility,
            templateId = template.id,
            accountId = template.accountId ?: accounts.defaultAccount()?.id,
            status = status,
        )
        items.upsert(item)
        item
    }

    suspend fun update(item: Item) = items.update(item)

    suspend fun markQueued(id: String) = items.setStatus(id, Status.QUEUED)

    suspend fun markDraft(id: String) = items.setStatus(id, Status.DRAFT)

    suspend fun delete(id: String) {
        val item = items.byId(id) ?: return
        items.delete(id)
        val hash = item.mediaHash
        val stillUsed = hash != null && items.byMediaHash(hash) != null
        media.deleteIfUnreferenced(item.mediaPath, stillUsed)
    }

    suspend fun mediaBytes(item: Item): ByteArray? = item.mediaPath?.let { media.bytes(it) }

    /** Re-arms anything the process died mid-post. Called once on app start. */
    suspend fun recoverStalePosting() = items.requeueStalePosting()

    suspend fun purgePosted(olderThanDays: Int): Int {
        if (olderThanDays <= 0) return 0
        val cutoff = System.currentTimeMillis() - olderThanDays * 86_400_000L
        return items.purgePostedBefore(cutoff)
    }

    suspend fun templates(): List<Template> = templates.all()

    suspend fun resolveTemplate(id: String?): Template =
        (id?.let { templates.byId(it) } ?: templates.defaultTemplate() ?: templates.all().firstOrNull())
            ?: Template.seed().also { templates.upsert(it) }

    /** Seeds the default template so a fresh install can post straight away. */
    suspend fun seedIfEmpty() {
        if (templates.count() == 0) templates.upsert(Template.seed())
    }

    companion object {
        /** A share that carried neither media nor text is not worth persisting. */
        fun isActionable(payload: SharePayload): Boolean = !payload.isEmpty
    }
}
