package app.fediferry.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class AltTextMode {
    /** Emit no `description` at all. */
    NONE,

    /** Always use [Template.staticAltText]. */
    STATIC,

    /** Ask the configured vision endpoint; fall back to nothing on failure. */
    VISION,
}

/**
 * A named bundle of posting defaults. Body text carries `{link}`, `{tags}` and
 * `{date}` placeholders; see [app.fediferry.template.TemplateEngine].
 */
@Entity(tableName = "templates")
data class Template(
    @PrimaryKey val id: String,
    val name: String,
    val body: String,
    val tags: String = "",
    val visibility: Visibility = Visibility.PUBLIC,
    val contentWarning: String? = null,
    val altTextMode: AltTextMode = AltTextMode.NONE,
    val staticAltText: String? = null,
    val accountId: String? = null,
    val isDefault: Boolean = false,
    val sortOrder: Int = 0,
) {
    companion object {
        const val DEFAULT_ID = "default"

        /** Seeded on first run so a fresh install can post immediately. */
        fun seed() = Template(
            id = DEFAULT_ID,
            name = "Meme",
            body = "{tags}\n\nvia {link}",
            tags = "#meme",
            visibility = Visibility.PUBLIC,
            altTextMode = AltTextMode.NONE,
            isDefault = true,
        )
    }
}
