/**
 * The lookup key for a dictionary word.
 *
 * A word saved in one book has to be recognised in every other book, so the key
 * ignores whatever the selection happened to include around the word: extra
 * spaces, quotes, brackets, a trailing comma, a full stop or an ellipsis, and
 * letter case. Apostrophes and hyphens inside the word are kept, so "don't"
 * stays different from "dont" and "well-known" from "wellknown".
 */
export function wordKey(word: string): string {
  const collapsed = word.replace(/\s+/g, " ").trim()
  const { start, end } = letterBounds(collapsed)
  // A selection with no letters at all, such as a page number, is kept as it is
  // rather than becoming an empty key that would collide with every other one.
  const core = start === -1 ? collapsed : collapsed.slice(start, end)
  return (core || collapsed).toLowerCase()
}

function letterBounds(text: string) {
  let start = -1
  let end = -1
  let offset = 0

  for (const char of text) {
    if (isLetter(char)) {
      if (start === -1) start = offset
      end = offset + char.length
    }
    offset += char.length
  }

  return { start, end }
}

// Letters are recognised by their case mapping where the script has one. Scripts
// without case are listed by code point so a word in one of them is never
// trimmed away.
function isLetter(char: string) {
  if (char.toLowerCase() !== char.toUpperCase()) return true

  const code = char.codePointAt(0) ?? 0
  return (
    (code >= 0x0590 && code <= 0x08ff) || // Hebrew, Arabic, Syriac, Thaana
    (code >= 0x0900 && code <= 0x0dff) || // Indic scripts
    (code >= 0x0e00 && code <= 0x0eff) || // Thai, Lao
    (code >= 0x1000 && code <= 0x109f) || // Myanmar
    (code >= 0x1100 && code <= 0x11ff) || // Hangul Jamo
    (code >= 0x1780 && code <= 0x17ff) || // Khmer
    (code >= 0x3040 && code <= 0x30ff) || // Hiragana, Katakana
    (code >= 0x3400 && code <= 0x4dbf) || // CJK extension A
    (code >= 0x4e00 && code <= 0x9fff) || // CJK unified ideographs
    (code >= 0xac00 && code <= 0xd7af) || // Hangul syllables
    (code >= 0xf900 && code <= 0xfaff) || // CJK compatibility ideographs
    (code >= 0x20000 && code <= 0x2ffff) // CJK extensions B and above
  )
}
