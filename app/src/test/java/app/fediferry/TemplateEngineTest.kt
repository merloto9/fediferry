package app.fediferry

import app.fediferry.data.model.Template
import app.fediferry.template.TemplateEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TemplateEngineTest {

    private fun template(body: String, tags: String = "#meme") =
        Template.seed().copy(body = body, tags = tags)

    @Test
    fun `fills link and tags`() {
        val out = TemplateEngine.render(
            template("{tags}\n\nvia {link}"),
            TemplateEngine.Inputs(link = "https://instagram.com/p/abc"),
        )
        assertEquals("#meme\n\nvia https://instagram.com/p/abc", out)
    }

    @Test
    fun `drops the attribution line when no link was shared`() {
        // Instagram often sends only an image; a dangling "via" must not ship.
        val out = TemplateEngine.render(
            template("{tags}\n\nvia {link}"),
            TemplateEngine.Inputs(link = null),
        )
        assertEquals("#meme", out)
    }

    @Test
    fun `caption degrades to empty rather than leaking the placeholder`() {
        val out = TemplateEngine.render(
            template("{caption}", tags = ""),
            TemplateEngine.Inputs(caption = null),
        )
        assertEquals("", out)
        assertTrue("{caption}" !in out)
    }

    @Test
    fun `renders the date placeholder`() {
        val out = TemplateEngine.render(
            template("{date}", tags = ""),
            TemplateEngine.Inputs(now = Instant.parse("2026-01-15T10:00:00Z")),
        )
        assertTrue(out.startsWith("2026-01-1"))
    }

    @Test
    fun `leaves unknown placeholders alone`() {
        val out = TemplateEngine.render(
            template("{nope}", tags = ""),
            TemplateEngine.Inputs(),
        )
        assertEquals("{nope}", out)
    }
}
