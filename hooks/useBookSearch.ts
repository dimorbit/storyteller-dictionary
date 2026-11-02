import { type BookWithRelations } from "@/database/books"
import { useListBooksQuery } from "@/store/localApi"

const EMPTY_BOOKS: BookWithRelations[] = []

const PUNCTUATION = /[.,/#!$%^&*;:{}=\-_`~()'"]/g

// stripTokenPunctuation matches the historical behavior: title tokens are
// punctuation-stripped before comparison, author/series/tag tokens are not.
function scoreField(
  terms: string[],
  values: string[],
  stripTokenPunctuation: boolean,
) {
  const termScores = terms.map((term) =>
    values.reduce(
      (acc, value) =>
        value
          .toLocaleLowerCase()
          .split(/\s+/)
          .filter((t) => !!t)
          .map((t) =>
            stripTokenPunctuation ? t.replaceAll(PUNCTUATION, "") : t,
          )
          .reduce(
            (acc, t) =>
              t === term
                ? acc + 1
                : t.includes(term)
                  ? acc + term.length / t.length
                  : acc,
            0,
          ) + acc,
      0,
    ),
  )

  return !termScores.length || termScores.includes(0)
    ? 0
    : termScores.reduce((acc, s) => acc + s)
}

/**
 * Scores every book in the library against the query, matching title,
 * authors, series and tags, and returns matches sorted by relevance.
 * All query terms must match at least one field for a book to be included.
 */
export function useBookSearch(query: string): BookWithRelations[] {
  const { data: books = EMPTY_BOOKS } = useListBooksQuery()

  const terms = query
    .split(/\s+/)
    .map((t) => t.toLocaleLowerCase().replaceAll(PUNCTUATION, ""))
    .filter((term) => !!term)

  return books
    .map((book) => {
      const titleScore = scoreField(terms, [book.title], true)
      const authorsScore = scoreField(
        terms,
        book.authors.map((a) => a.name),
        false,
      )
      const seriesScore = scoreField(
        terms,
        book.series.map((s) => s.name),
        false,
      )
      const tagsScore = scoreField(
        terms,
        book.tags.map((t) => t.name),
        false,
      )

      return [
        book,
        titleScore + authorsScore + seriesScore * 0.75 + tagsScore * 0.5,
      ] as const
    })
    .filter(([_, score]) => score > 0)
    .sort(([_a, a], [_b, b]) => b - a)
    .map(([book]) => book)
}
