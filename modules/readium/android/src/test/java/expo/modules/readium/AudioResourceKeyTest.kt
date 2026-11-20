package expo.modules.readium

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioResourceKeyTest {
    @Test
    fun identicalHrefsStayEqual() {
        assertEquals("OEBPS/Audio/00001.mp3", audioResourceKey("OEBPS/Audio/00001.mp3"))
    }

    @Test
    fun percentEncodingAndSpacesMatch() {
        assertEquals(
            audioResourceKey("OEBPS/Audio/Chapter 1.mp3"),
            audioResourceKey("OEBPS/Audio/Chapter%201.mp3")
        )
        assertEquals(
            audioResourceKey("OEBPS/Audio/caf\u00e9.mp3"),
            audioResourceKey("OEBPS/Audio/caf%C3%A9.mp3")
        )
    }

    @Test
    fun leadingSlashAndDotSegmentsMatch() {
        val key = audioResourceKey("OEBPS/Audio/a.mp3")
        assertEquals(key, audioResourceKey("/OEBPS/Audio/a.mp3"))
        assertEquals(key, audioResourceKey("./OEBPS/Audio/a.mp3"))
        assertEquals(key, audioResourceKey("OEBPS/Text/../Audio/a.mp3"))
    }

    @Test
    fun fragmentAndQueryAreIgnored() {
        assertEquals(
            audioResourceKey("OEBPS/Audio/a.mp3"),
            audioResourceKey("OEBPS/Audio/a.mp3#t=1.5,3.0")
        )
        assertEquals(
            audioResourceKey("OEBPS/Audio/a.mp3"),
            audioResourceKey("OEBPS/Audio/a.mp3?x=1")
        )
    }

    @Test
    fun differentFilesStayDifferent() {
        assert(audioResourceKey("OEBPS/Audio/a.mp3") != audioResourceKey("OEBPS/Audio/b.mp3"))
        assert(audioResourceKey("Audio/a.mp3") != audioResourceKey("OEBPS/Audio/a.mp3"))
    }

    @Test
    fun malformedPercentIsKeptLiterally() {
        assertEquals("a%zz.mp3", audioResourceKey("a%zz.mp3"))
        assertEquals("a%.mp3", audioResourceKey("a%.mp3"))
        assertEquals("a%2", audioResourceKey("a%2"))
    }
}
