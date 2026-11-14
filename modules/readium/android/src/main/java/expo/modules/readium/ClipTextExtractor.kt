package expo.modules.readium

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser

/** The text and enclosing selector needed by Readium's quote-based range finder. */
data class ClipText(
    val highlight: String,
    val before: String?,
    val after: String?,
    val cssSelector: String?,
)

/** Extracts clip text without relying on the shape or attributes of its span tags. */
object ClipTextExtractor {
    /** Parse once; source offsets are used only for reading back the original text. */
    fun parseDocument(xhtml: String): Document =
        Jsoup.parse(xhtml, "", Parser.xmlParser().setTrackPosition(true))

    fun extract(xhtml: String, fragment: String, sameResourceFragments: List<String>): ClipText? =
        extract(parseDocument(xhtml), xhtml, fragment, sameResourceFragments)

    fun extract(document: Document, xhtml: String, fragment: String, sameResourceFragments: List<String>): ClipText? {
        val anchor = document.getElementById(fragment) ?: return null
        val anchorRange = anchor.sourceRange()
        if (anchorRange.isImplicit()) return null

        // A media-overlay anchor can also wrap its own text. In that case the
        // anchor is already the narrowest element for Readium to search within.
        val ownText = anchor.wholeText()
        if (ownText.isNotEmpty()) {
            return ClipText(ownText, null, null, anchor.cssSelector())
        }

        val parent = anchor.parent() ?: return null
        val parentRange = parent.sourceRange()
        val parentEnd = parent.endSourceRange()
        if (parentRange.isImplicit() || parentEnd.isImplicit()) return null
        val anchorEnd = anchor.endSourceRange()
        if (anchorEnd.isImplicit()) return null
        // sourceRange includes only the opening tag for an empty XML anchor;
        // endSourceRange puts its closing tag at the sentence's actual start.
        val textStart = anchorEnd.endPos()
        if (textStart < anchorRange.startPos() || textStart > parentEnd.startPos()) return null

        val nextAnchor = sameResourceFragments.asSequence()
            .drop(sameResourceFragments.indexOf(fragment).coerceAtLeast(0) + 1)
            .filter { it != fragment }
            .mapNotNull { id -> document.getElementById(id) }
            .firstOrNull { next -> next.sourceRange().startPos() >= textStart }
        val clipEnd = nextAnchor?.sourceRange()?.startPos() ?: parentEnd.startPos()
        if (anchorRange.endPos() > clipEnd) return null

        // Empty media-overlay spans mark a sentence boundary, rather than wrap
        // the sentence. Take text through the next marker, or to the end of the
        // containing block for the final marker in that block.
        val highlight = htmlText(xhtml, textStart, clipEnd) ?: return null
        if (highlight.isEmpty()) return null

        val before = htmlText(xhtml, parentRange.startPos(), anchorRange.startPos())
        val after = nextAnchor?.endSourceRange()?.endPos()?.let { afterStart ->
            htmlText(xhtml, afterStart, parentEnd.startPos())
        }
        val parentContext = parent.wholeText()
        val otherContext = parentContext.takeUnless { containsNormalizedText(it, highlight) }

        return ClipText(
            highlight = highlight,
            before = before ?: otherContext,
            after = after,
            cssSelector = parent.cssSelector(),
        )
    }

    private fun htmlText(xhtml: String, start: Int, end: Int): String? {
        if (start < 0 || start > end || end > xhtml.length) return null
        return Jsoup.parse(xhtml.substring(start, end), "", Parser.xmlParser()).wholeText().takeIf { it.isNotBlank() }
    }

    private fun containsNormalizedText(text: String, needle: String): Boolean =
        text.replace(Regex("\\s+"), " ").trim().contains(needle.replace(Regex("\\s+"), " ").trim())
}
