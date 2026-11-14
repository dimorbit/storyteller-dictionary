import { isAnyOf } from "@reduxjs/toolkit"

import { logger } from "@/logger"

import {
  Storyteller,
  buildAudiobookLocator,
  getClip,
  getFragment,
} from "@/modules/readium"
import {
  type ReadiumLocator,
  type StorytellerTrack,
} from "@/modules/readium/src/Readium.types"
import {
  bookDoubleTapped,
  bookLocatorChanged,
  bookmarkPressed,
  miniPlayerSliderChapterPositionChanged,
  navItemPressed,
  nextTrackPressed,
  playerClipChanged,
  playerPositionSeeked,
  playerTotalPositionSeeked,
  playerTrackChanged,
  prevTrackPressed,
  returnToPreviousPosition,
  serverPositionUpdated,
} from "@/store/actions"
import { localApi } from "@/store/localApi"
import {
  getCurrentlyPlayingBookUuid,
  getCurrentlyPlayingFormat,
  getIsPlaying,
  getJumpHold,
  getReturnToPosition,
} from "@/store/selectors/bookshelfSelectors"
import { bookshelfSlice } from "@/store/slices/bookshelfSlice"
import { type UUID } from "@/uuid"

import { startAppListening } from "./listenerMiddleware"

const matchReaderLocatorUpdate = isAnyOf(
  bookDoubleTapped,
  miniPlayerSliderChapterPositionChanged,
  bookLocatorChanged,
  serverPositionUpdated,
  navItemPressed,
  bookmarkPressed,
  returnToPreviousPosition,
)

startAppListening({
  matcher: matchReaderLocatorUpdate,
  effect: async (action, listenerApi) => {
    let bookUuid: UUID
    let locator: ReadiumLocator
    let timestamp: number

    if (action.type === returnToPreviousPosition.type) {
      const { bookUuid: uuid, timestamp: ts } = action.payload as ReturnType<
        typeof returnToPreviousPosition
      >["payload"]
      bookUuid = uuid
      timestamp = ts
      const returnPos = getReturnToPosition(
        listenerApi.getOriginalState(),
        bookUuid,
      )
      if (!returnPos) return
      locator = returnPos.locator
    } else {
      ;({ bookUuid, locator, timestamp } = action.payload)
    }

    const updatePositionPromise = listenerApi.dispatch(
      localApi.endpoints.updatePosition.initiate(
        {
          bookUuid,
          locator,
          timestamp,
        },
        { track: false },
      ),
    )

    await updatePositionPromise.unwrap()

    const book = await listenerApi
      .dispatch(
        localApi.endpoints.getBook.initiate(
          { uuid: bookUuid },
          { subscribe: false },
        ),
      )
      .unwrap()

    if (!book) return

    const currentlyPlaying = getCurrentlyPlayingBookUuid(listenerApi.getState())
    if (bookUuid !== currentlyPlaying) return

    const format = getCurrentlyPlayingFormat(listenerApi.getState())
    if (!format) return

    let positions: ReadiumLocator[] | null = null
    if (format !== "audiobook") {
      positions = await listenerApi
        .dispatch(
          localApi.endpoints.getBookPositions.initiate(
            { bookUuid, format },
            { forceRefetch: false, subscribe: false },
          ),
        )
        .unwrap()
    }

    const clip = await getClip(book, format, locator, positions)

    if (!clip) return

    listenerApi.dispatch(
      bookshelfSlice.actions.jumpTargetResolved({
        bookUuid,
        timestamp,
        target: {
          relativeUrl: clip.relativeUrl,
          start: clip.start,
          end: clip.end,
        },
      }),
    )

    if (action.type === bookDoubleTapped.type) {
      await Storyteller.seekTo(clip.relativeUrl, clip.start + 0.01, true)
      await Storyteller.play(false)
    } else {
      await Storyteller.seekTo(clip.relativeUrl, clip.start, true)
    }
  },
})

const matchPlayerPositionUpdate = isAnyOf(
  playerTotalPositionSeeked,
  playerPositionSeeked,
  playerTrackChanged,
  nextTrackPressed,
  prevTrackPressed,
)

startAppListening({
  matcher: matchPlayerPositionUpdate,
  effect: async (action, listenerApi) => {
    listenerApi.unsubscribe()

    try {
      switch (action.type) {
        // case playerTotalPositionSeeked.type: {
        // const { progress } = action.payload

        // let skipTo = progress
        // let nextTrack = null

        // const tracks = await TrackPlayer.getQueue()

        // let acc = 0
        // for (let i = 0; i < tracks.length; i++) {
        //   const track = tracks[i]!
        //   acc += track.duration ?? 0
        //   if (acc > progress) break
        //   nextTrack = i
        //   skipTo -= track.duration ?? 0
        // }
        // if (nextTrack === null) return

        // await TrackPlayer.skip(nextTrack, skipTo)
        // break
        // }
        case playerPositionSeeked.type: {
          const { progress } = action.payload

          await Storyteller.skip(progress)
          break
        }
        case playerTrackChanged.type: {
          const { relativeUri, position } = action.payload
          await Storyteller.seekTo(relativeUri, position ?? 0)
          break
        }
        case nextTrackPressed.type: {
          await Storyteller.next()
          break
        }
        case prevTrackPressed.type: {
          await Storyteller.prev()
          break
        }
      }
    } finally {
      listenerApi.subscribe()
    }
  },
})

async function getLocatorFromTrackPosition(
  bookUuid: UUID,
  format: "ebook" | "readaloud" | "audiobook",
  track: StorytellerTrack,
  position: number,
): Promise<ReadiumLocator | undefined> {
  if (format !== "audiobook") {
    const fragment = await getFragment(bookUuid, track.relativeUri, position)
    return fragment?.locator
  }

  const tracks = await Storyteller.getTracks()
  return buildAudiobookLocator(tracks, track, position)
}

startAppListening({
  actionCreator: bookshelfSlice.actions.isPlayingChanged,
  effect: async (action, listenerApi) => {
    if (action.payload.isPlaying) return
    if (
      getIsPlaying(listenerApi.getOriginalState()) ===
      getIsPlaying(listenerApi.getState())
    )
      return
    // The stop position is still the old place while a jump waits for the audio.
    if (getJumpHold(listenerApi.getOriginalState())) return

    listenerApi.unsubscribe()
    try {
      const currentTrack = await Storyteller.getCurrentTrack()
      if (!currentTrack) return

      const position = await Storyteller.getPosition()

      const currentBookUuid = getCurrentlyPlayingBookUuid(
        listenerApi.getState(),
      )
      if (!currentBookUuid) return

      const format = getCurrentlyPlayingFormat(listenerApi.getState())
      if (!format) return

      const locator = await getLocatorFromTrackPosition(
        currentBookUuid,
        format,
        currentTrack,
        position,
      )

      if (!locator) return

      const payload = {
        bookUuid: currentBookUuid,
        locator: locator,
        timestamp: Date.now(),
      }

      await listenerApi
        .dispatch(
          localApi.endpoints.updatePosition.initiate(payload, { track: false }),
        )
        .unwrap()
    } finally {
      listenerApi.subscribe()
    }
  },
})

const JUMP_TARGET_RESOLUTION_BOUND_MS = 60_000
const JUMP_TARGET_PLAYBACK_BOUND_MS = 30_000

const jumpTimeoutKey = (bookUuid: UUID, timestamp: number) => `${bookUuid}:${timestamp}`
const jumpTimeouts = new Map<string, ReturnType<typeof setTimeout>>()

function clearJumpTimeout(bookUuid: UUID, timestamp: number) {
  const key = jumpTimeoutKey(bookUuid, timestamp)
  const timeout = jumpTimeouts.get(key)
  if (timeout) clearTimeout(timeout)
  jumpTimeouts.delete(key)
}

function scheduleJumpTimeout(
  bookUuid: UUID,
  timestamp: number,
  delay: number,
  expire: () => void,
) {
  clearJumpTimeout(bookUuid, timestamp)
  const key = jumpTimeoutKey(bookUuid, timestamp)
  const handle = setTimeout(() => {
    if (jumpTimeouts.get(key) !== handle) return
    jumpTimeouts.delete(key)
    logger.info(`Saved-place jump ${bookUuid}:${timestamp} expired; resume following audio`)
    expire()
  }, delay)
  jumpTimeouts.set(key, handle)
}

startAppListening({
  actionCreator: bookmarkPressed,
  effect: async ({ payload: { bookUuid, timestamp } }, listenerApi) => {
    if (bookUuid !== getCurrentlyPlayingBookUuid(listenerApi.getState())) return
    // Unresolved lookups get an outer bound. Resolving the target replaces it
    // with a 30s clip bound: audio older than 3s cannot snap the reader back.
    const expire = () => {
      listenerApi.dispatch(bookshelfSlice.actions.jumpHoldExpired({ bookUuid, timestamp }))
    }
    scheduleJumpTimeout(bookUuid, timestamp, JUMP_TARGET_RESOLUTION_BOUND_MS, expire)
    const cancelExpiry = () => clearJumpTimeout(bookUuid, timestamp)
    listenerApi.signal.addEventListener("abort", cancelExpiry, { once: true })
    let hadTarget = getJumpHold(listenerApi.getState())?.target !== undefined
    try {
      await listenerApi.condition((_, state) => {
        const hold = getJumpHold(state)
        if (hold?.bookUuid !== bookUuid || hold.timestamp !== timestamp) return true
        if (hold.target && !hadTarget) {
          hadTarget = true
          // Arm only on the transition to a resolved target. Player position
          // actions must not postpone this deadline forever.
          scheduleJumpTimeout(bookUuid, timestamp, JUMP_TARGET_PLAYBACK_BOUND_MS, expire)
        }
        return false
      })
    } catch (_) {
      // A cancelled worker has its own timeout listener above.
    } finally {
      listenerApi.signal.removeEventListener("abort", cancelExpiry)
      cancelExpiry()
    }
  },
})

startAppListening({
  actionCreator: playerClipChanged,
  effect: async (action, listenerApi) => {
    listenerApi.unsubscribe()
    try {
      const { clip } = action.payload
      const currentBookUuid = getCurrentlyPlayingBookUuid(
        listenerApi.getState(),
      )
      if (!currentBookUuid) return
      // Clips reported before a jump settles would move the reader erroneously;
      // an expired hold allows normal clip persistence to resume.
      if (getJumpHold(listenerApi.getState())) return

      const locator = clip.locator

      const payload = {
        bookUuid: currentBookUuid,
        locator: locator,
        timestamp: Date.now(),
      }

      await listenerApi
        .dispatch(
          localApi.endpoints.updatePosition.initiate(payload, { track: false }),
        )
        .unwrap()
    } finally {
      listenerApi.subscribe()
    }
  },
})
