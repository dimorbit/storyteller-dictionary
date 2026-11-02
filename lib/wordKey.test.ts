import { describe, expect, it } from "vitest"

import { wordKey } from "./wordKey"

describe("wordKey", () => {
  it("lowercases the word", () => {
    expect(wordKey("Heart")).toBe("heart")
    expect(wordKey("MEMORIES")).toBe("memories")
  })

  it("collapses whitespace and trims the edges", () => {
    expect(wordKey("  healthy   \n\t")).toBe("healthy")
    expect(wordKey("well   known")).toBe("well known")
  })

  it("strips quotes, brackets and punctuation around the word", () => {
    expect(wordKey("“heart”")).toBe("heart")
    expect(wordKey("(chair)")).toBe("chair")
    expect(wordKey("[memories]")).toBe("memories")
    expect(wordKey("‘all,’")).toBe("all")
    expect(wordKey("were...")).toBe("were")
    expect(wordKey("healthy…")).toBe("healthy")
    expect(wordKey("«heart»")).toBe("heart")
  })

  it("keeps apostrophes and hyphens inside the word", () => {
    expect(wordKey("don't")).toBe("don't")
    expect(wordKey("well-known")).toBe("well-known")
    expect(wordKey("“don't,”")).toBe("don't")
  })

  it("keeps a selection that has no letters at all", () => {
    expect(wordKey("1984")).toBe("1984")
    expect(wordKey("...")).toBe("...")
    expect(wordKey("")).toBe("")
  })

  it("leaves a word without case alone", () => {
    expect(wordKey("日本語")).toBe("日本語")
    expect(wordKey("«日本語»")).toBe("日本語")
  })

  it("gives the same key to the same word from two places", () => {
    const fromOneBook = "heart"
    const fromAnotherBook = "  Heart,\n"
    expect(wordKey(fromAnotherBook)).toBe(wordKey(fromOneBook))
  })
})
