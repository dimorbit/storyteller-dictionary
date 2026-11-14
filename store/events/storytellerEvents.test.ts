import assert from "node:assert/strict"
import { afterEach, beforeEach, test, vi } from "vitest"

// Native events: the test fires them by name.
type Handler = (event: never) => void
const handlers = new Map<string, Handler>()

vi.mock("react-native", () => ({
  AppState: { currentState: "active", addEventListener: () => {} },
}))
vi.mock("@/modules/readium", () => ({
  Storyteller: {
    addListener: (name: string, handler: Handler) => handlers.set(name, handler),
    removeListener: () => {},
  },
}))
vi.mock("@/store/actions", () => ({
  playerClipChanged: (payload: unknown) => ({ type: "playerClipChanged", payload }),
}))
vi.mock("@/store/slices/bookshelfSlice", () => ({
  bookshelfSlice: {
    actions: {
      audioPositionChanged: (payload: unknown) => ({ type: "position", payload }),
      audioTrackChanged: (payload: unknown) => ({ type: "track", payload }),
      isPlayingChanged: (payload: unknown) => ({ type: "isPlaying", payload }),
    },
  },
}))

const { addStorytellerEventListeners } = await import("./storytellerEvents")

const fire = (name: string, event: unknown) => handlers.get(name)!(event as never)

let dispatched: { type: string; payload: unknown }[]

beforeEach(() => {
  vi.useFakeTimers()
  // throttle() schedules with window.setTimeout.
  vi.stubGlobal("window", globalThis)
  handlers.clear()
  dispatched = []
  addStorytellerEventListeners({
    dispatch: (action: { type: string; payload: unknown }) => {
      dispatched.push(action)
      return action
    },
  } as never)
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

test("the last position of a burst still reaches the store", () => {
  fire("positionChanged", { position: 1 })
  fire("positionChanged", { position: 2 })
  fire("positionChanged", { position: 3 })
  vi.advanceTimersByTime(500)
  assert.deepEqual(
    dispatched.map((a) => a.payload),
    [{ position: 1 }, { position: 3 }],
  )
})

test("a held position from the old track is dropped when the track changes", () => {
  // Paused slider drag on track a, then Next (or a cross-track seek) before
  // the trailing call fires.
  fire("positionChanged", { position: 40 })
  fire("positionChanged", { position: 80 })
  fire("trackChanged", { track: { relativeUri: "b.mp3" }, position: 0, index: 1 })
  vi.advanceTimersByTime(2000)
  assert.deepEqual(dispatched, [
    { type: "position", payload: { position: 40 } },
    {
      type: "track",
      payload: { track: { relativeUri: "b.mp3" }, position: 0, index: 1 },
    },
  ])

  // Positions on the new track keep flowing.
  fire("positionChanged", { position: 5 })
  assert.deepEqual(dispatched.at(-1), {
    type: "position",
    payload: { position: 5 },
  })
})
