import { Storyteller } from "@/modules/readium"
import { type AppStore } from "@/store/appState"
import { sleepTimerSlice } from "@/store/slices/sleepTimerSlice"

/** Installs the application-lifetime sleep timer event handlers. */
export function addSleepTimerEventListeners(store: AppStore) {
  Storyteller.addListener("sleepTimerExpired", ({ deadline }) => {
    store.dispatch(sleepTimerSlice.actions.expired({ deadline }))
  })
}
