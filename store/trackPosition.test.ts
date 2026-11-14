import assert from "node:assert/strict"
import { test } from "vitest"

import { type StorytellerTrack } from "@/modules/readium/src/Readium.types"

import { trackPositionFromTotal } from "./trackPosition"

const track = (relativeUri: string, duration: number) =>
  ({ relativeUri, duration }) as StorytellerTrack

const tracks = [track("a.mp3", 100), track("b.mp3", 50), track("c.mp3", 200)]

test("maps a book position inside the first track", () => {
  const target = trackPositionFromTotal(tracks, 42)
  assert.equal(target?.track.relativeUri, "a.mp3")
  assert.equal(target?.index, 0)
  assert.equal(target?.position, 42)
})

test("maps a book position to a later track and its offset", () => {
  const target = trackPositionFromTotal(tracks, 160)
  assert.equal(target?.track.relativeUri, "c.mp3")
  assert.equal(target?.index, 2)
  assert.equal(target?.position, 10)
})

test("a track boundary starts the next track", () => {
  const target = trackPositionFromTotal(tracks, 100)
  assert.equal(target?.track.relativeUri, "b.mp3")
  assert.equal(target?.position, 0)
})

test("the end of the bar lands at the end of the last track", () => {
  const target = trackPositionFromTotal(tracks, 350)
  assert.equal(target?.track.relativeUri, "c.mp3")
  assert.equal(target?.position, 200)
  assert.equal(trackPositionFromTotal(tracks, 9999)?.position, 200)
})

test("negative positions land at the start, no tracks gives null", () => {
  const target = trackPositionFromTotal(tracks, -5)
  assert.equal(target?.track.relativeUri, "a.mp3")
  assert.equal(target?.position, 0)
  assert.equal(trackPositionFromTotal([], 10), null)
})
