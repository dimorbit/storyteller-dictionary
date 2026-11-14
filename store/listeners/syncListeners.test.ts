import assert from "node:assert/strict"
import { combineReducers, configureStore } from "@reduxjs/toolkit"
import { test, vi } from "vitest"

// The native player: each command waits until the test lets it finish, like a
// slow bridge call, so actions can be dispatched while one is in flight.
const calls: string[] = []
const pending: (() => void)[] = []
const nativeCall = (name: string) => {
  calls.push(name)
  return new Promise<void>((resolve) => pending.push(resolve))
}

// Saved positions, in order. saveGate lets a test hold a save in flight.
const saved: unknown[] = []
let saveGate: () => Promise<void> = async () => {}

vi.mock("@/modules/readium", () => ({
  Storyteller: {
    skip: (position: number) => nativeCall(`skip ${position}`),
    seekTo: (uri: string, position: number) =>
      nativeCall(`seekTo ${uri} ${position}`),
    next: () => nativeCall("next"),
    prev: () => nativeCall("prev"),
  },
  buildAudiobookLocator: () => undefined,
  getClip: async () => null,
  getFragment: async () => null,
}))
vi.mock("@/logger", () => ({
  logger: { debug: () => {}, info: () => {}, warn: () => {}, error: () => {} },
}))
vi.mock("@sentry/react-native", () => ({ captureException: () => {} }))
vi.mock("@/store/localApi", () => ({
  localApi: {
    endpoints: {
      deleteBook: { matchFulfilled: () => false },
      updatePosition: {
        initiate: (arg: { locator: unknown }) => () => {
          saved.push(arg.locator)
          return { unwrap: () => saveGate() }
        },
      },
      getBook: { initiate: () => () => ({ unwrap: async () => null }) },
      getBookPositions: { initiate: () => () => ({ unwrap: async () => [] }) },
    },
  },
}))
vi.mock("@/store/selectors/bookshelfSelectors", () => ({
  getCurrentlyPlayingBookUuid: (s: { bookshelf: { currentlyPlayingBookUuid: unknown } }) =>
    s.bookshelf.currentlyPlayingBookUuid,
  getCurrentlyPlayingFormat: (s: { bookshelf: { currentlyPlayingFormat: unknown } }) =>
    s.bookshelf.currentlyPlayingFormat,
  getIsPlaying: (s: { bookshelf: { isPlaying: boolean } }) => s.bookshelf.isPlaying,
  getJumpHold: (s: { bookshelf: { jumpHold: unknown } }) => s.bookshelf.jumpHold,
  getReturnToPosition: () => undefined,
  getTracks: (s: { bookshelf: { tracks: unknown[] } }) => s.bookshelf.tracks,
}))

const { listenerMiddleware } = await import("./listenerMiddleware")
await import("./syncListeners")
const actions = await import("@/store/actions")
const { bookshelfSlice } = await import("@/store/slices/bookshelfSlice")

const bookUuid = "00000000-0000-4000-8000-000000000001" as const

function makeStore() {
  const store = configureStore({
    reducer: combineReducers({ bookshelf: bookshelfSlice.reducer }),
    middleware: (getDefault) =>
      getDefault({ serializableCheck: false }).prepend(
        listenerMiddleware.middleware,
      ),
  })
  store.dispatch(bookshelfSlice.actions.bookOpened({ bookUuid, format: "audiobook" }))
  store.dispatch(
    bookshelfSlice.actions.playerQueued({
      tracks: [
        { bookUuid, relativeUri: "a.mp3", duration: 100 },
        { bookUuid, relativeUri: "b.mp3", duration: 50 },
      ] as never,
    }),
  )
  return store
}

const flush = () => new Promise<void>((resolve) => setTimeout(resolve, 0))

async function finishAll() {
  while (pending.length) {
    pending.shift()!()
    await flush()
  }
}

test("a seek dispatched while skip() is awaiting is not dropped", async () => {
  calls.length = 0
  const store = makeStore()

  store.dispatch(actions.playerPositionSeeked({ progress: 10 }))
  await flush()
  assert.deepEqual(calls, ["skip 10"])

  // Before the fix the listener was unsubscribed here and these were lost.
  store.dispatch(actions.playerPositionSeeked({ progress: 20 }))
  store.dispatch(actions.playerPositionSeeked({ progress: 30 }))
  await flush()
  assert.deepEqual(calls, ["skip 10"], "commands do not overlap")

  await finishAll()
  // The drag ends on its final value; the stale intermediate seek is skipped.
  assert.deepEqual(calls, ["skip 10", "skip 30"])
})

test("track buttons pressed during a seek still run, in order", async () => {
  calls.length = 0
  const store = makeStore()

  store.dispatch(actions.playerPositionSeeked({ progress: 5 }))
  await flush()
  store.dispatch(actions.nextTrackPressed())
  store.dispatch(actions.nextTrackPressed())
  await finishAll()
  assert.deepEqual(calls, ["skip 5", "next", "next"])
})

test("a book-scope seek moves to the right track and offset", async () => {
  calls.length = 0
  const store = makeStore()

  store.dispatch(actions.playerTotalPositionSeeked({ progress: 120 }))
  await finishAll()
  assert.deepEqual(calls, ["seekTo b.mp3 20"])

  calls.length = 0
  // Tapping the very end of the book bar.
  store.dispatch(actions.playerTotalPositionSeeked({ progress: 150 }))
  await finishAll()
  assert.deepEqual(calls, ["seekTo b.mp3 50"])
})

test("a clip save queued before a bookmark jump does not overwrite it", async () => {
  calls.length = 0
  saved.length = 0
  const store = makeStore()
  const clip = (name: string) =>
    ({ relativeUrl: "a.mp3", start: 0, end: 1, locator: name }) as never

  // The first clip save is in flight; the second waits behind it.
  let releaseSave!: () => void
  saveGate = () => new Promise<void>((resolve) => (releaseSave = resolve))
  store.dispatch(actions.playerClipChanged({ clip: clip("clip 1") }))
  await flush()
  saveGate = async () => {}
  store.dispatch(actions.playerClipChanged({ clip: clip("clip 2") }))

  // The user taps a bookmark: the navigation saves its own place.
  store.dispatch(
    actions.bookmarkPressed({
      bookUuid,
      locator: "bookmark" as never,
      timestamp: Date.now(),
    }),
  )
  await flush()
  releaseSave()
  await flush()
  await flush()

  assert.deepEqual(saved, ["clip 1", "bookmark"])
})
