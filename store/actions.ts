import { createAction } from "@reduxjs/toolkit"

import {
  type ReadiumClip,
  type ReadiumLocator,
} from "@/modules/readium/src/Readium.types"
import { type UUID } from "@/uuid"

export const bookImported = createAction(
  "bookImported",
  (payload: { url: string; from: "home" | "open-with" }) => ({ payload }),
)

export const bookDoubleTapped = createAction(
  "bookDoubleTapped",
  (payload: {
    bookUuid: UUID
    locator: ReadiumLocator
    timestamp: number
  }) => ({
    payload,
  }),
)

export const bookDetailPressed = createAction(
  "bookDetailPressed",
  (payload: {
    bookUuid: UUID
    format: "audiobook" | "ebook" | "readaloud"
  }) => ({
    payload,
  }),
)

export const bookLocatorChanged = createAction(
  "bookLocatorChanged",
  (payload: {
    bookUuid: UUID
    locator: ReadiumLocator
    timestamp: number
  }) => ({
    payload,
  }),
)

export const miniPlayerSliderChapterPositionChanged = createAction(
  "miniPlayerSliderChapterPositionChanged",
  (payload: {
    bookUuid: UUID
    locator: ReadiumLocator
    timestamp: number
  }) => ({
    payload,
  }),
)

export const serverPositionUpdated = createAction(
  "serverPositionUpdated",
  (payload: {
    bookUuid: UUID
    locator: ReadiumLocator
    timestamp: number
  }) => ({
    payload,
  }),
)

export const navItemPressed = createAction(
  "navitemPressed",
  (payload: {
    bookUuid: UUID
    locator: ReadiumLocator
    timestamp: number
    currentLocator?: ReadiumLocator | undefined
  }) => ({ payload }),
)

export const bookmarkPressed = createAction(
  "bookmarkPressed",
  (payload: {
    bookUuid: UUID
    locator: ReadiumLocator
    timestamp: number
    currentLocator?: ReadiumLocator | undefined
  }) => ({ payload }),
)

export const clearReturnPosition = createAction(
  "clearReturnPosition",
  (payload: { bookUuid: UUID }) => ({ payload }),
)

export const returnToPreviousPosition = createAction(
  "returnToPreviousPosition",
  (payload: { bookUuid: UUID; timestamp: number }) => ({
    payload,
  }),
)

export const playerPositionUpdated = createAction("playerPositionUpdated")

export const playerPositionSeeked = createAction(
  "playerPositionSeeked",
  (payload: { progress: number }) => ({
    payload,
  }),
)

export const playerTotalPositionSeeked = createAction(
  "playerTotalPositionSeeked",
  (payload: { progress: number }) => ({
    payload,
  }),
)

export const playerTrackChanged = createAction(
  "playerTrackChanged",
  (payload: { relativeUri: string; position?: number }) => ({
    payload,
  }),
)

export const playerClipChanged = createAction(
  "playerClipChanged",
  (payload: { clip: ReadiumClip }) => ({
    payload,
  }),
)

export const nextTrackPressed = createAction("nextTrackPressed")

export const prevTrackPressed = createAction("prevTrackPressed")

export const nextFragmentPressed = createAction("nextFragmentPressed")

export const previousFragmentPressed = createAction("previousFragmentPressed")
