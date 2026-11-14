import assert from "node:assert/strict"
import { test, vi } from "vitest"

vi.mock("@/logger", () => ({
  logger: { debug: () => {}, info: () => {}, warn: () => {}, error: () => {} },
}))
vi.mock("@/store/localApi", () => ({
  localApi: {
    endpoints: { deleteBook: { matchFulfilled: () => false } },
  },
}))

const actions = await import("@/store/actions")
const { bookshelfSlice } = await import("./bookshelfSlice")

const bookUuid = "00000000-0000-4000-8000-000000000001" as const

function heldState() {
  let state = bookshelfSlice.reducer(
    undefined,
    bookshelfSlice.actions.bookOpened({ bookUuid, format: "readaloud" }),
  )
  state = bookshelfSlice.reducer(
    state,
    actions.bookmarkPressed({
      bookUuid,
      timestamp: 1,
      locator: { href: "a.html", type: "text/html", locations: {} },
    } as Parameters<typeof actions.bookmarkPressed>[0]),
  )
  assert.ok(state.jumpHold, "precondition: a saved-place jump is held")
  return state
}

const userSeeks = [
  ["playerPositionSeeked", actions.playerPositionSeeked({ progress: 12 })],
  [
    "playerTotalPositionSeeked",
    actions.playerTotalPositionSeeked({ progress: 120 }),
  ],
  [
    "playerTrackChanged",
    actions.playerTrackChanged({ relativeUri: "b.mp3", position: 0 }),
  ],
  ["nextTrackPressed", actions.nextTrackPressed()],
  ["prevTrackPressed", actions.prevTrackPressed()],
] as const

for (const [name, action] of userSeeks) {
  test(`${name} releases a pending jump hold`, () => {
    const state = bookshelfSlice.reducer(heldState(), action)
    assert.equal(state.jumpHold, null)
  })
}

test("an unrelated position report keeps the jump hold", () => {
  const state = bookshelfSlice.reducer(
    heldState(),
    bookshelfSlice.actions.audioPositionChanged({ position: 3 }),
  )
  assert.ok(state.jumpHold)
})
