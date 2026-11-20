package expo.modules.readium

import android.graphics.Color
import android.os.Parcel
import android.os.Parcelable
import androidx.annotation.ColorInt
import java.util.Locale
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.html.HtmlDecorationTemplate
import org.readium.r2.navigator.html.HtmlDecorationTemplates

// Dictionary words get a fine dotted underline in the text colour.
//
// Readium anchors both this decoration and the readaloud "underline" style to the
// bottom of the text's content box, and the readaloud rule is a 2px solid border
// on that same edge. Only ~3 CSS px of room is left above it, so any second rule
// -- however thin -- merges into one thick double line. The mark is therefore a
// row of round dots rather than a line: dots read as texture, not as a thinner
// underline, and a highlight wash tints them exactly as much as the text.
//
// `round` fits a whole number of tiles across the word and stretches them to
// fill it, so the end dots sit just inside the word ends and short words still
// get three dots instead of two widely spaced ones.
// `active` marks the word whose dictionary menu is open. It gets the same light
// blue wash the reader draws over a text selection, which is how ReadEra shows
// which word a tap landed on.
//
// The wash has to be an inline `!important` background colour, and it has to sit
// behind the glyphs (z-index: -1). Both are forced by the reading CSS:
// ReadiumCSS-after.css ships `:root[style*="--USER__backgroundColor"] * {
// background-color: transparent !important }`, and Storyteller always sets a
// reading background, so a background colour set from a stylesheet rule on this
// element is silently dropped. Readium's own highlight template solves it the
// same way, with an inline `background-color: ... !important`. An opaque box
// painted over the text would hide the word, so it is put under the glyphs
// instead of being blended over them.
data class DictionaryUnderlineStyle(
    @ColorInt val tint: Int,
    @ColorInt val wash: Int = 0,
    val active: Boolean = false,
    // The one copy of a word the word was saved from draws the same dots at twice
    // the pitch, so the row reads sparser without changing the dot itself.
    val sparse: Boolean = false,
    // Painted with no dots at all. Used for a word that sits inside a saved sentence:
    // the sentence draws the row, but the word keeps a decoration of its own so that
    // tapping it still opens that word's menu rather than the sentence's.
    val hidden: Boolean = false,
) : Decoration.Style {
    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(tint)
        dest.writeInt(wash)
        dest.writeInt(if (active) 1 else 0)
        dest.writeInt(if (sparse) 1 else 0)
        dest.writeInt(if (hidden) 1 else 0)
    }

    companion object CREATOR : Parcelable.Creator<DictionaryUnderlineStyle> {
        override fun createFromParcel(source: Parcel) =
            DictionaryUnderlineStyle(
                source.readInt(),
                source.readInt(),
                source.readInt() == 1,
                source.readInt() == 1,
                source.readInt() == 1
            )

        override fun newArray(size: Int) = arrayOfNulls<DictionaryUnderlineStyle>(size)
    }
}

private const val dictionaryUnderlineClass = "storyteller-dictionary-underline"

private const val dictionarySparseClass = "storyteller-dictionary-sparse"

internal const val dictionaryActiveClass = "storyteller-dictionary-active"

// Present until the clamp has corrected the box's geometry.
private const val dictionaryPendingClass = "storyteller-dictionary-pending"

// A word kept only as a tap target, with no dots of its own.
private const val dictionaryHiddenClass = "storyteller-dictionary-hidden"

// Dot and pitch are in em so they scale with the reader's font size. The dot is
// 1.76px at 16px (~4.6 device px at 2.625x): large enough to read at a glance now
// that the row paints above the readaloud mark, still a dot rather than a line.
private const val dictionaryDotEm = 0.11
private const val dictionaryPitchEm = 0.25

// Distance from the bottom edge of the box to the dot row, positive upwards.
// Measured against the approved preview on a 25px line box: the glyph baseline sits
// about 1.5px below the box bottom and descenders reach ~3.9px, so a negative value
// is required to put the row in the interline gap, clear of both.
// The dot row lives in its own strip: a background cannot paint outside its own
// element, so the row is drawn in a child box rather than by shifting a background
// off the line box. The offset is measured from the box bottom, and was set from a
// device screenshot rather than from font metrics: the geometry predicts where the
// baseline and descenders fall, but only the rendered page shows where the row
// actually lands. A negative value puts it below the box bottom.
// The strip is only as tall as its ink; extra height just enlarges the box.
private const val dictionaryRow = "2px"
private const val dictionaryRowOffset = "1.6px"

fun storytellerDecorationTemplates(): HtmlDecorationTemplates {
    val templates = HtmlDecorationTemplates.defaultTemplates()
    templates.set(
        DictionaryUnderlineStyle::class,
        HtmlDecorationTemplate(
            layout = HtmlDecorationTemplate.Layout.BOXES,
            width = HtmlDecorationTemplate.Width.WRAP,
            element = { decoration ->
                val style = decoration.style as? DictionaryUnderlineStyle
                val tint = style?.tint ?: Color.BLACK
                val color = "rgb(${Color.red(tint)}, ${Color.green(tint)}, ${Color.blue(tint)})"
                val wash = style?.wash ?: 0
                val alpha = String.format(Locale.US, "%.3f", Color.alpha(wash) / 255f)
                val washColor = "rgba(${Color.red(wash)}, ${Color.green(wash)}, ${Color.blue(wash)}, $alpha)"
                val active = style?.active == true
                val sparse = style?.sparse == true
                val hidden = style?.hidden == true
                // A hidden mark keeps only its box: the box is the tap target, so it
                // must stay where it is, but it must draw no dots of its own. The
                // colour is written into this element's own inline style, which every
                // recreated box inherits from the template; the class alone is not
                // enough, because Readium rebuilds the boxes on reflow. The wash is
                // kept: the active mark is this very box, and a tapped word has to
                // look selected even though the sentence draws the row.
                val dotColor = if (hidden) "transparent" else color
                // Held back until the clamp has sized this box: Readium sizes it from
                // full line-box rects, so the first paint is wrong. The class is removed
                // rather than an inline style written, so the writing-mode and sparse
                // variants keep coming from the cascade. Each name is its own list entry
                // so a missing space cannot fuse two names into one unknown token.
                val classes = listOfNotNull(
                    dictionaryUnderlineClass,
                    dictionaryPendingClass,
                    if (sparse) dictionarySparseClass else null,
                    if (active) dictionaryActiveClass else null,
                    if (hidden) dictionaryHiddenClass else null,
                ).joinToString(" ")
                // The wash is drawn by a ::before pseudo-element, not by the box:
                // the box sits above the readaloud overlay so its dots stay visible,
                // and one element cannot be both above the text and behind it.
                """<div class="$classes" style="--dictionary-dot-color: $dotColor; --dictionary-active-wash: $washColor"/>"""
            },
            stylesheet = """
                /* The box itself carries no background and no z-index, so it never
                   becomes a stacking context. Its two pseudo-elements do the drawing:
                   the dot row above the readaloud mark, and the wash behind the
                   glyphs. Putting either on the box would force one of them onto the
                   wrong side of the text. */
                /* Readium sets position:absolute and never z-index, and appends the
                   decoration groups in first-creation order, so the readaloud
                   overlay can end up painted above the dots. The lifted z-index has
                   to live on this pseudo-element: on the box it would make a
                   stacking context and trap the wash behind it. */
                .$dictionaryUnderlineClass::after {
                    content: "";
                    position: absolute;
                    left: 0;
                    right: 0;
                    /* Measured up from the bottom of the line box, so the row lands
                       under the baseline and clear of the descenders. top:auto is
                       required: with top, bottom and height all set, top and height
                       win and bottom is ignored. */
                    top: calc(100% - $dictionaryRowOffset);
                    bottom: auto;
                    height: $dictionaryRow;
                    pointer-events: none;
                    z-index: 1;
                    border-radius: 0;
                    background-image: radial-gradient(
                        circle closest-side,
                        var(--dictionary-dot-color) 78%,
                        transparent 100%
                    );
                    background-size: ${dictionaryPitchEm}em ${dictionaryDotEm}em;
                    background-repeat: round no-repeat;
                    background-position: 0 0;
                }
                /* Vertical writing modes mirror the strip: the row runs down the
                   page, so the strip is narrow and offset on the inline axis. The
                   same rule applies — a background cannot paint outside its own box,
                   so the strip moves rather than the background being shifted. */
                [data-writing-mode="vertical-rl"].$dictionaryUnderlineClass::after,
                [data-writing-mode="sideways-rl"].$dictionaryUnderlineClass::after,
                [data-writing-mode="vertical-lr"].$dictionaryUnderlineClass::after {
                    top: 0;
                    bottom: 0;
                    right: auto;
                    left: calc(100% - $dictionaryRowOffset);
                    width: $dictionaryRow;
                    height: auto;
                    background-size: ${dictionaryDotEm}em ${dictionaryPitchEm}em;
                    background-repeat: no-repeat round;
                    background-position: 0 0;
                }
                [data-writing-mode="sideways-lr"].$dictionaryUnderlineClass::after {
                    left: auto;
                    right: calc(100% - $dictionaryRowOffset);
                    background-position: 0 0;
                }
                /* The copy the word was saved from keeps the same dot and colour,
                   with the pitch doubled so the row reads as dots with wider gaps.
                   These rules follow the writing-mode ones above so that a sparse
                   mark overrides the pitch in every direction. */
                .$dictionaryUnderlineClass.$dictionarySparseClass::after {
                    background-size: calc(2 * ${dictionaryPitchEm}em) ${dictionaryDotEm}em;
                }
                [data-writing-mode="vertical-rl"].$dictionaryUnderlineClass.$dictionarySparseClass::after,
                [data-writing-mode="sideways-rl"].$dictionaryUnderlineClass.$dictionarySparseClass::after,
                [data-writing-mode="vertical-lr"].$dictionaryUnderlineClass.$dictionarySparseClass::after {
                    background-size: ${dictionaryDotEm}em calc(2 * ${dictionaryPitchEm}em);
                }
                [data-writing-mode="sideways-lr"].$dictionaryUnderlineClass.$dictionarySparseClass::after {
                    background-size: ${dictionaryDotEm}em calc(2 * ${dictionaryPitchEm}em);
                }
                /* The tapped word carries the same wash the reader paints over a
                   text selection, so it reads as "this is the word you tapped".
                   The colour itself is inline (see DictionaryUnderlineStyle); a
                   background colour from a rule here never paints, because the
                   reading CSS makes every element's background transparent.
                   z-index:-1 puts the box under the glyphs, so the word stays
                   readable on top of it the way a text selection looks. */
                /* The tapped word keeps its dots in the normal layer and paints its
                   wash behind the glyphs, so a tap highlights the word rather than
                   covering it. */
                .$dictionaryUnderlineClass.$dictionaryActiveClass {
                    border-radius: 2px;
                }
                .$dictionaryUnderlineClass.$dictionaryActiveClass::before {
                    content: "";
                    position: absolute;
                    left: 0;
                    right: 0;
                    top: 0;
                    bottom: 0;
                    border-radius: 2px;
                    background-color: var(--dictionary-active-wash);
                    z-index: -1;
                }
                /* Dots and the tapped-word wash are both withheld until the box has
                   been measured and sized. Readium sizes the box from full line-box
                   rects, so showing either before the clamp paints it across the
                   whole line and then snaps it tight to the words. Suppressing both
                   means the first thing drawn is already correct. The wash is an
                   inline !important background-color, so it needs its own
                   !important here to win. Removing the class restores the cascade,
                   including vertical writing modes and the sparse pitch. */
                .$dictionaryUnderlineClass.$dictionaryPendingClass::after {
                    background-image: none !important;
                    background-color: transparent !important;
                }
                .$dictionaryUnderlineClass.$dictionaryPendingClass::before {
                    display: none;
                }
                /* A word inside a saved sentence draws no dots. It exists only so a tap
                   on the word opens that word's menu rather than the sentence's; Readium
                   hit-tests the boxes, not the text. Its wash stays: this is the box the
                   menu opened for, so the word has to look selected. */
                .$dictionaryUnderlineClass.$dictionaryHiddenClass::after {
                    display: none !important;
                }
            """.trimIndent()
        )
    )
    return templates
}
