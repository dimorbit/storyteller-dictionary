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
    val sparse: Boolean = false
) : Decoration.Style {
    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(tint)
        dest.writeInt(wash)
        dest.writeInt(if (active) 1 else 0)
        dest.writeInt(if (sparse) 1 else 0)
    }

    companion object CREATOR : Parcelable.Creator<DictionaryUnderlineStyle> {
        override fun createFromParcel(source: Parcel) =
            DictionaryUnderlineStyle(
                source.readInt(),
                source.readInt(),
                source.readInt() == 1,
                source.readInt() == 1
            )

        override fun newArray(size: Int) = arrayOfNulls<DictionaryUnderlineStyle>(size)
    }
}

private const val dictionaryUnderlineClass = "storyteller-dictionary-underline"

private const val dictionarySparseClass = "storyteller-dictionary-sparse"

private const val dictionaryActiveClass = "storyteller-dictionary-active"

// Dot and pitch are in em so they scale with the reader's font size. The dot is
// 1.25px at 16px: at a 2.625x device pixel ratio a 1.5px dot snapped onto the
// readaloud line in most columns, while 1.25px keeps two clear device pixels.
private const val dictionaryDotEm = 0.078125
private const val dictionaryPitchEm = 0.25

// Box edge to dot centre. Clears the fixed 2px readaloud border and sits in the
// middle of what remains, which is 2.84px at 16px.
private const val dictionaryInset = "calc(1px + 0.115em)"

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
                val classes = buildString {
                    append(dictionaryUnderlineClass)
                    if (sparse) append(" $dictionarySparseClass")
                    if (active) append(" $dictionaryActiveClass")
                }
                val washRule =
                    if (active) " background-color: $washColor !important;" else ""
                """<div class="$classes" style="--dictionary-dot-color: $color; --dictionary-active-wash: $washColor;$washRule"/>"""
            },
            stylesheet = """
                .$dictionaryUnderlineClass {
                    box-sizing: border-box;
                    border: 0;
                    pointer-events: none;
                    user-select: none;
                    -webkit-user-select: none;
                    margin: 0 -1px 0 0;
                    background-image: radial-gradient(
                        circle closest-side,
                        var(--dictionary-dot-color) 78%,
                        transparent 100%
                    );
                    background-size: ${dictionaryPitchEm}em ${dictionaryDotEm}em;
                    background-repeat: round no-repeat;
                    background-position: 0 calc(100% - $dictionaryInset);
                }
                [data-writing-mode="vertical-rl"].$dictionaryUnderlineClass,
                [data-writing-mode="sideways-rl"].$dictionaryUnderlineClass,
                [data-writing-mode="vertical-lr"].$dictionaryUnderlineClass {
                    background-size: ${dictionaryDotEm}em ${dictionaryPitchEm}em;
                    background-repeat: no-repeat round;
                    background-position: $dictionaryInset 0;
                }
                [data-writing-mode="sideways-lr"].$dictionaryUnderlineClass {
                    background-size: ${dictionaryDotEm}em ${dictionaryPitchEm}em;
                    background-repeat: no-repeat round;
                    background-position: calc(100% - $dictionaryInset) 0;
                }
                /* The copy the word was saved from keeps the same dot, colour and
                   inset, with the pitch doubled so the row reads as dots with
                   wider gaps. These rules follow the writing-mode ones above so
                   that a sparse mark overrides the pitch in every direction. */
                .$dictionaryUnderlineClass.$dictionarySparseClass {
                    background-size: calc(2 * ${dictionaryPitchEm}em) ${dictionaryDotEm}em;
                }
                [data-writing-mode="vertical-rl"].$dictionaryUnderlineClass.$dictionarySparseClass,
                [data-writing-mode="sideways-rl"].$dictionaryUnderlineClass.$dictionarySparseClass,
                [data-writing-mode="vertical-lr"].$dictionaryUnderlineClass.$dictionarySparseClass {
                    background-size: ${dictionaryDotEm}em calc(2 * ${dictionaryPitchEm}em);
                }
                [data-writing-mode="sideways-lr"].$dictionaryUnderlineClass.$dictionarySparseClass {
                    background-size: ${dictionaryDotEm}em calc(2 * ${dictionaryPitchEm}em);
                }
                /* The tapped word carries the same wash the reader paints over a
                   text selection, so it reads as "this is the word you tapped".
                   The colour itself is inline (see DictionaryUnderlineStyle); a
                   background colour from a rule here never paints, because the
                   reading CSS makes every element's background transparent.
                   z-index:-1 puts the box under the glyphs, so the word stays
                   readable on top of it the way a text selection looks. */
                .$dictionaryUnderlineClass.$dictionaryActiveClass {
                    border-radius: 2px;
                    z-index: -1;
                }
            """.trimIndent()
        )
    )
    return templates
}
