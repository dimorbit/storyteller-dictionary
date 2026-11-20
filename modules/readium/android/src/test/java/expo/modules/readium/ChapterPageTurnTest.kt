package expo.modules.readium

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterPageTurnTest {
    @Test
    fun stillLoadingPageInViewportDefersTurn() {
        // Outgoing page shown, incoming single-page chapter still hidden while loading.
        val pages = listOf(
            ChapterPageTurn.PageView(inViewport = true, shown = true),
            ChapterPageTurn.PageView(inViewport = true, shown = false),
        )
        assertTrue(ChapterPageTurn.hasLoadingPage(pages))
    }

    @Test
    fun offscreenHiddenPageDoesNotDeferTurn() {
        val pages = listOf(
            ChapterPageTurn.PageView(inViewport = true, shown = true),
            ChapterPageTurn.PageView(inViewport = false, shown = false),
        )
        assertFalse(ChapterPageTurn.hasLoadingPage(pages))
    }
}
