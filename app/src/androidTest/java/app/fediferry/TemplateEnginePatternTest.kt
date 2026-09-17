package app.fediferry

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.fediferry.data.model.Template
import app.fediferry.template.TemplateEngine
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * Runs on a device, which is the whole point.
 *
 * Android implements `java.util.regex` over ICU; the host JVM uses OpenJDK's
 * engine. The two disagree about patterns like an unmatched `}` — ICU throws
 * where OpenJDK shrugs. A pattern built in a `TemplateEngine` field initialiser
 * therefore takes the whole object's `<clinit>` down on a device while every
 * host-side unit test stays green, which is exactly what happened once.
 */
@RunWith(AndroidJUnit4::class)
class TemplateEnginePatternTest {

    @Test
    fun templateEngineInitialisesOnDevice() {
        // Touching the object at all is the assertion: a bad pattern surfaces
        // here as ExceptionInInitializerError.
        val out = TemplateEngine.render(
            Template.seed().copy(body = "{tags}\n\nvia {link}", tags = "#meme"),
            TemplateEngine.Inputs(link = "https://instagram.com/p/abc"),
        )
        assertEquals("#meme\n\nvia https://instagram.com/p/abc", out)
    }

    @Test
    fun dropsEmptyPlaceholderLinesOnDevice() {
        val out = TemplateEngine.render(
            Template.seed().copy(body = "{tags}\n\nvia {link}", tags = "#meme"),
            TemplateEngine.Inputs(link = null),
        )
        assertEquals("#meme", out)
    }

    @Test
    fun collapsesBlankRunsOnDevice() {
        // Exercises the second pattern, {3,}, which ICU does accept: four
        // newlines collapse to the one blank line the template asked for.
        val out = TemplateEngine.render(
            Template.seed().copy(body = "{tags}\n\n\n\n{date}", tags = "#meme"),
            TemplateEngine.Inputs(now = Instant.parse("2026-01-15T10:00:00Z")),
        )
        assertEquals(listOf("#meme", "", "2026-01-15"), out.lines())
    }
}
