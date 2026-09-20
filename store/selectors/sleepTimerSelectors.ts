import { createSelector } from "@reduxjs/toolkit"

import { type RootState } from "@/store/appState"

export function getSleepTimerDeadline(state: RootState) {
  return state.sleepTimer.status === "running"
    ? state.sleepTimer.deadline
    : null
}

export function getLastSyncedNativeSleepTimerDeadline(state: RootState) {
  return state.sleepTimer.lastSyncedNativeDeadline
}

/** Get the running sleep timer deadline as a memoized Date object. */
export const getSleepTimer = createSelector(
  getSleepTimerDeadline,
  (deadline) => (deadline === null ? null : new Date(deadline)),
)

export function getShouldKeepReaderAwake(state: RootState) {
  return (
    state.sleepTimer.status === "off" || state.sleepTimer.status === "running"
  )
}
