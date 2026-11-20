package expo.modules.readium

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A saved quote can sit inside another saved quote: "i was hosed" inside a passage that
 * was also saved. Both are searched and painted, so both drew a row of dots over the same
 * letters and the page showed two rows out of step. The rule that fixes it decides, by
 * span alone, which marks a longer mark already covers; this pins that rule down.
 */
class DictionaryNestingTest {

    private data class Span(val id: String, val start: Int, val end: Int)

    // Mirrors coveredByLongerMark in EpubView. Kept in step with it by hand: the
    // production version reaches into the view's state, which needs a device to build.
    private fun covered(spans: List<Span>, id: String): Boolean {
        val span = spans.firstOrNull { it.id == id } ?: return false
        val length = span.end - span.start
        return spans.any { other ->
            if (other.id == id) return@any false
            if (span.start < other.start || span.end > other.end) return@any false
            val otherLength = other.end - other.start
            if (otherLength == length) other.id < id else otherLength > length
        }
    }

    private fun dotted(spans: List<Span>): List<String> =
        spans.filterNot { covered(spans, it.id) }.map { it.id }

    @Test
    fun `a quote inside a longer quote is covered`() {
        val passage = Span("passage", 0, 500)
        val phrase = Span("phrase", 120, 131)

        assertEquals(listOf("passage"), dotted(listOf(passage, phrase)))
    }

    @Test
    fun `a quote that sticks out of a longer one is not covered`() {
        val passage = Span("passage", 0, 500)
        val overlapping = Span("overlapping", 480, 620)

        assertEquals(listOf("passage", "overlapping"), dotted(listOf(passage, overlapping)))
    }

    @Test
    fun `a word repeated elsewhere keeps every copy that is not covered`() {
        // One saved word, seven copies in the chapter, one of them inside a saved
        // passage. Only that copy is covered: matching on quote text would hide all
        // seven, which is what made an earlier attempt drop marks the reader wanted.
        val passage = Span("passage", 100, 200)
        val spans = listOf(passage) + (0 until 7).map { Span("word#$it", 40 + it * 300, 46 + it * 300) }
        val inside = Span("word#9", 150, 156)

        val kept = dotted(spans + inside)

        assertEquals(7, kept.count { it.startsWith("word#") })
        assertEquals(1, kept.count { it == "passage" })
    }

    @Test
    fun `the same quote saved twice draws one row`() {
        // Equal spans have no length to rank by, so the id breaks the tie and exactly
        // one of the two survives.
        val first = Span("a", 10, 20)
        val second = Span("b", 10, 20)

        assertEquals(listOf("a"), dotted(listOf(first, second)))
        assertEquals(listOf("a"), dotted(listOf(second, first)))
    }

    @Test
    fun `nesting three deep keeps only the outermost row`() {
        // passage > phrase > word, which is what a saved passage, a saved phrase and a
        // saved word all over the same text produce.
        val word = Span("word", 150, 156)
        val phrase = Span("phrase", 120, 180)
        val passage = Span("passage", 0, 500)

        assertEquals(listOf("passage"), dotted(listOf(word, phrase, passage)))
    }
}
