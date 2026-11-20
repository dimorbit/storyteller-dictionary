package expo.modules.readium

import java.io.ByteArrayOutputStream

/**
 * One spelling for an audio file href, used on both sides of every clip lookup.
 *
 * Clip keys come from the SMIL files (`OverlayPar.audioResource`, resolved
 * against the SMIL path). Track ids come from the audio manifest's reading order
 * (`track.relativeUri`), whose hrefs are the OPF's spelling: Readium's
 * `linkWithHref` matches a normalized URL but returns the link as the OPF wrote
 * it. The two can name the same file differently (percent-encoding, a leading
 * slash, `./` or `../` segments). Without a shared key, clip lookups silently
 * find nothing and the readaloud highlight never moves.
 *
 * Pure Kotlin so it can be unit tested without Android classes.
 */
fun audioResourceKey(href: String): String {
    val path = href.substringBefore('#').substringBefore('?')
    val segments = ArrayDeque<String>()
    for (segment in percentDecode(path).split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> segments.removeLastOrNull()
            else -> segments.addLast(segment)
        }
    }
    return segments.joinToString("/")
}

private fun percentDecode(value: String): String {
    if ('%' !in value) return value
    val out = ByteArrayOutputStream()
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c == '%' && i + 2 < value.length) {
            val byte = value.substring(i + 1, i + 3).toIntOrNull(16)
            if (byte != null) {
                out.write(byte)
                i += 3
                continue
            }
        }
        out.write(c.toString().toByteArray(Charsets.UTF_8))
        i++
    }
    return out.toString(Charsets.UTF_8.name())
}

// Logcat tag for readaloud diagnostics (clip keys, seeks, decorations).
const val READALOUD_LOG_TAG = "StorytellerRA"
