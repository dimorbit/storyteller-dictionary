import { type ListenerEffectAPI } from "@reduxjs/toolkit"
import { Alert, AppState } from "react-native"

import { logger } from "@/logger"
import { Storyteller } from "@/modules/readium"
import { type AppDispatch, type RootState } from "@/store/appState"
import {
  getLastSyncedNativeSleepTimerDeadline,
  getSleepTimerDeadline,
} from "@/store/selectors/sleepTimerSelectors"
import { sleepTimerSlice } from "@/store/slices/sleepTimerSlice"

import { startAppListening } from "./listenerMiddleware"

function showSleepTimerExpiredPrompt(
  listenerApi: ListenerEffectAPI<RootState, AppDispatch>,
) {
  if (AppState.currentState !== "active") return

  const sleepTimer = listenerApi.getState().sleepTimer
  if (sleepTimer.status !== "expired") return

  const { duration } = sleepTimer
  listenerApi.dispatch(sleepTimerSlice.actions.promptShown())

  Alert.alert(
    "Sleep timer ended",
    "Would you like to restart it?",
    [
      {
        text: "Not now",
        style: "cancel",
        onPress: () => {
          listenerApi.dispatch(sleepTimerSlice.actions.declined())
        },
      },
      {
        text: "Restart",
        onPress: () => {
          listenerApi.dispatch(
            sleepTimerSlice.actions.restarted({
              deadline: Date.now() + duration,
            }),
          )
        },
      },
    ],
    { cancelable: false },
  )
}

function syncNativeSleepTimer(
  listenerApi: ListenerEffectAPI<RootState, AppDispatch>,
  force = false,
) {
  const deadline = getSleepTimerDeadline(listenerApi.getState())
  const lastSyncedNativeDeadline = getLastSyncedNativeSleepTimerDeadline(
    listenerApi.getState(),
  )

  if (!force && deadline === lastSyncedNativeDeadline) return

  void Storyteller.setSleepTimer(deadline)
    .then(() => {
      listenerApi.dispatch(
        sleepTimerSlice.actions.nativeDeadlineSynced({ deadline }),
      )
    })
    .catch((error: unknown) => {
      logger.error("Failed to schedule the native sleep timer")
      logger.error(error)
    })
}

startAppListening({
  actionCreator: sleepTimerSlice.actions.expired,
  effect: (_, listenerApi) => {
    showSleepTimerExpiredPrompt(listenerApi)
  },
})

startAppListening({
  predicate: () => true,
  effect: (_, listenerApi) => {
    syncNativeSleepTimer(listenerApi)
  },
})

startAppListening({
  predicate: () => true,
  effect: (_, listenerApi) => {
    listenerApi.unsubscribe()

    AppState.addEventListener("change", (appState) => {
      if (appState !== "active") return

      const sleepTimer = listenerApi.getState().sleepTimer
      if (
        sleepTimer.status === "running" &&
        sleepTimer.deadline <= Date.now()
      ) {
        listenerApi.dispatch(
          sleepTimerSlice.actions.expired({ deadline: sleepTimer.deadline }),
        )
        void Storyteller.pause().catch((error: unknown) => {
          logger.error("Failed to pause playback for an overdue sleep timer")
          logger.error(error)
        })
      } else if (sleepTimer.status === "running") {
        syncNativeSleepTimer(listenerApi, true)
      }

      showSleepTimerExpiredPrompt(listenerApi)
    })
  },
})
