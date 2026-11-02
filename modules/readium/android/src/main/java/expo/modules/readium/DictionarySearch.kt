@file:OptIn(ExperimentalReadiumApi::class)

package expo.modules.readium

import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.indexOfFirstWithHref
import org.readium.r2.shared.publication.services.search.SearchService
import org.readium.r2.shared.publication.services.search.StringSearchService
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.content.HtmlResourceContentExtractor

/** A saved word, and the book its stored locator belongs to, if it came from one. */
data class DictionaryWord(val uuid: String, val word: String, val bookUuid: String?)

/** One copy of a saved word in a chapter, numbered within that word. */
data class DictionaryOccurrence(val uuid: String, val index: Int, val locator: Locator)

/**
 * Finds a saved word wherever it appears in a book's text.
 *
 * A saved word is not tied to the book it came from any more, so its stored
 * locator only describes where the word sat in that one book. Every other book
 * has to be searched for the word instead. The chapter is reduced to plain text
 * once and searched with ICU, which understands word boundaries in scripts that
 * do not separate words with spaces.
 */
object DictionarySearch {

    // A chapter longer than this is skipped. No chapter of a novel comes close,
    // and searching a huge one would cost more than the marks are worth.
    private const val MAX_TEXT_LENGTH = 2 * 1024 * 1024

    // A word repeated more often than this in one chapter is left to the saved
    // locator alone, rather than painting a mark on every copy.
    private const val MAX_OCCURRENCES_PER_WORD = 100

    // The web view's range finder only needs enough text either side of the word
    // to tell one copy from another. A short window keeps the decoration payload
    // small, which matters when a chapter holds many marks.
    private const val SNIPPET_LENGTH = 50

    private val extractor = HtmlResourceContentExtractor()

    /**
     * The plain text of one chapter, or null if the resource could not be read.
     * The resource is closed again either way.
     */
    suspend fun extractChapterText(bookUuid: String, href: Url): String? =
        withContext(Dispatchers.IO) {
            val publication = BookService.getPublication(bookUuid) ?: return@withContext null
            val link = publication.linkWithHref(href) ?: return@withContext null
            val resource = publication.get(link) ?: return@withContext null
            try {
                extractor.extractText(resource).getOrNull()
            } catch (e: Exception) {
                null
            } finally {
                try {
                    resource.close()
                } catch (e: Exception) {
                    // Nothing left to do with the resource if it will not close.
                }
            }
        }

    /**
     * Every copy of [words] in [text], which must be the text of the chapter at
     * [href]. Words that do not occur at all are skipped before ICU runs.
     */
    suspend fun findOccurrences(
        bookUuid: String,
        href: Url,
        text: String,
        words: List<DictionaryWord>,
    ): List<DictionaryOccurrence> {
        if (text.isEmpty() || text.length > MAX_TEXT_LENGTH) return emptyList()

        val publication = BookService.getPublication(bookUuid) ?: return emptyList()
        val chapterIndex = publication.readingOrder.indexOfFirstWithHref(href)
            ?: return emptyList()
        val chapterCount = publication.readingOrder.size
        if (chapterCount == 0) return emptyList()

        return withContext(Dispatchers.IO) {
            val locale = Locale.getDefault()
            val options = SearchService.Options(
                caseSensitive = false,
                diacriticSensitive = false,
                wholeWord = true,
            )
            val algorithm = StringSearchService.IcuAlgorithm()
            val lowercaseText = text.lowercase(locale)
            val occurrences = mutableListOf<DictionaryOccurrence>()

            for (word in words) {
                val needle = word.word.lowercase(locale)
                if (needle.isEmpty() || !lowercaseText.contains(needle)) continue

                val ranges = algorithm.findRanges(word.word, options, text, locale)

                ranges.take(MAX_OCCURRENCES_PER_WORD).forEachIndexed { index, range ->
                    if (range.isEmpty()) return@forEachIndexed
                    val start = range.first
                    val end = range.last + 1
                    val progression = start.toDouble() / text.length

                    occurrences.add(
                        DictionaryOccurrence(
                            uuid = word.uuid,
                            index = index,
                            locator = Locator(
                                href = href,
                                mediaType = MediaType.XHTML,
                                locations = Locator.Locations(
                                    progression = progression,
                                    totalProgression =
                                        (chapterIndex + progression) / chapterCount,
                                ),
                                text = Locator.Text(
                                    before = snippetBefore(text, start),
                                    highlight = text.substring(start, end),
                                    after = snippetAfter(text, end),
                                ),
                            ),
                        )
                    )
                }
            }

            occurrences
        }
    }

    // A snippet must not start or end inside a surrogate pair, or the lone half
    // cannot be written back out as text.
    private fun snippetBefore(text: String, start: Int): String {
        var from = (start - SNIPPET_LENGTH).coerceAtLeast(0)
        if (from > 0 && Character.isLowSurrogate(text[from])) from -= 1
        return text.substring(from, start)
    }

    private fun snippetAfter(text: String, end: Int): String {
        var to = (end + SNIPPET_LENGTH).coerceAtMost(text.length)
        if (to < text.length && Character.isLowSurrogate(text[to])) to += 1
        return text.substring(end, to)
    }
}
