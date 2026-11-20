import { AppState } from "react-native"

import { Storyteller } from "@/modules/readium"
import { playerClipChanged } from "@/store/actions"
import { type AppStore } from "@/store/appState"
import { bookshelfSlice } from "@/store/slices/bookshelfSlice"
import { throttle } from "@/throttle"

function listenWhenActive(...args: Parameters<typeof Storyteller.addListener>) {
  AppState.addEventListener("change", (appState) => {
    if (appState === "active") {
      Storyteller.addListener(...args)
    } else {
      Storyteller.removeListener(...args)
    }
  })

  if (AppState.currentState === "active") {
    Storyteller.addListener(...args)
  }
}

export function addStorytellerEventListeners(store: AppStore) {
  // Throttled with a trailing call: the last position in a burst (the end of a
  // slider drag, a seek while paused) must still reach the store, or the seek
  // bar snaps back to the stale position.
  const dispatchPosition = throttle((position: number) => {
    store.dispatch(bookshelfSlice.actions.audioPositionChanged({ position }))
  }, 500)
  listenWhenActive("positionChanged", ({ position }) => {
    dispatchPosition(position)
  })

  listenWhenActive("trackChanged", ({ track, position, index }) => {
    // A held trailing position belongs to the previous track. Delivered after
    // this event it would overwrite the new track's position, and while paused
    // no later position event corrects it. trackChanged carries the position.
    dispatchPosition.dropPending()
    store.dispatch(
      bookshelfSlice.actions.audioTrackChanged({ track, position, index }),
    )
  })

  Storyteller.addListener("isPlayingChanged", ({ isPlaying }) => {
    store.dispatch(bookshelfSlice.actions.isPlayingChanged({ isPlaying }))
  })

  listenWhenActive("clipChanged", (clip) => {
    store.dispatch(playerClipChanged({ clip }))
  })

  AppState.addEventListener("change", (appState) => {
    if (appState === "active") {
      Storyteller.getIsPlaying().then((isPlaying) => {
        store.dispatch(bookshelfSlice.actions.isPlayingChanged({ isPlaying }))

        // If the audio is currently playing, then do a one-time catch up so that
        // we can display the correct highlight in the reader.
        //
        // If not, then we will have caught up when the player paused/stopped
        if (isPlaying) {
          Storyteller.getCurrentClip().then((clip) => {
            if (!clip) return
            store.dispatch(playerClipChanged({ clip }))
          })
        }
      })

      Promise.all([
        Storyteller.getPosition(),
        Storyteller.getCurrentTrack(),
        Storyteller.getCurrentTrackIndex(),
      ]).then(([position, track, index]) => {
        if (!track) return
        dispatchPosition.dropPending()
        store.dispatch(
          bookshelfSlice.actions.audioTrackChanged({ track, position, index }),
        )
      })
    }
  })
}
