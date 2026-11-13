package expo.modules.readium

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipTextExtractorTest {
    @Test
    fun `empty anchors use text through the next clip and surrounding context`() {
        val text = ClipTextExtractor.extract(
            """
            <html xmlns="http://www.w3.org/1999/xhtml"><body>
              <p id="paragraph"><span id="one"></span>Before this sentence.
                <span id="two"></span>Listen to <em>this &amp; that</em>.
                <span id="three"></span>After this sentence.</p>
            </body></html>
            """.trimIndent(),
            "two",
            listOf("one", "two", "three"),
        )

        assertEquals("Listen to this & that.", text?.highlight)
        assertEquals("Before this sentence.", text?.before)
        assertEquals("After this sentence.", text?.after)
        assertEquals("#paragraph", text?.cssSelector)
    }

    @Test
    fun `last anchor ends at its block instead of taking the whole chapter`() {
        val text = ClipTextExtractor.extract(
            """
            <html><body><div><p id="paragraph">Before. <span id="three"></span>Finis &#8220;now.&#8221;</p>
              <p>This belongs to the next paragraph.</p></div></body></html>
            """.trimIndent(),
            "three",
            listOf("one", "two", "three"),
        )

        assertEquals("Finis “now.”", text?.highlight)
        assertEquals("Before.", text?.before)
        assertNull(text?.after)
        assertEquals("#paragraph", text?.cssSelector)
    }

    @Test
    fun `quoted or both forms of attributes are matched by parsed ids`() {
        val text = ClipTextExtractor.extract(
            """
            <html><body><p id='paragraph'><SPAN data-name='clip' id='sentence:one'></SPAN>
              Sentence with &apos;quotes&apos;. <span id="next"></span>Next.</p></body></html>
            """.trimIndent(),
            "sentence:one",
            listOf("sentence:one", "next"),
        )

        assertEquals("Sentence with 'quotes'.", text?.highlight)
        // The element exists but needs CSS escaping; a unique quote is a safer scope.
        assertNotEquals("#sentence:one", text?.cssSelector)
    }

    @Test
    fun `an anchor that wraps text uses itself and its own contents`() {
        val text = ClipTextExtractor.extract(
            "<html><body><p><span id=\"clip\">Only <b>this.</b></span> Other text.</p></body></html>",
            "clip",
            listOf("clip"),
        )

        assertEquals("Only this.", text?.highlight)
        assertEquals("#clip", text?.cssSelector)
        assertNull(text?.before)
        assertNull(text?.after)
    }

    @Test
    fun `a missing clip cannot yield an accidental text match`() {
        assertNull(ClipTextExtractor.extract("<p>Only text</p>", "missing", listOf("missing")))
        assertNull(ClipTextExtractor.extract("<p><span\">text</span></p>", "clip", listOf("clip")))
    }
}
