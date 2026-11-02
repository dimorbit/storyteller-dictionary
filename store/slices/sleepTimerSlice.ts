import { type PayloadAction, createSlice } from "@reduxjs/toolkit"

export type SleepTimerState = (
  | { status: "off" }
  | {
      status: "running"
      deadline: number
      duration: number
    }
  | {
      status: "expired"
      duration: number
    }
  | {
      status: "prompting"
      duration: number
    }
) & { lastSyncedNativeDeadline: number | null }

const initialState: SleepTimerState = {
  status: "off",
  lastSyncedNativeDeadline: null,
}

export const sleepTimerSlice = createSlice({
  name: "sleepTimer",
  initialState: initialState as SleepTimerState,
  reducers: {
    started: (
      state,
      action: PayloadAction<{ deadline: number; duration: number }>,
    ): SleepTimerState => ({
      status: "running",
      deadline: action.payload.deadline,
      duration: action.payload.duration,
      lastSyncedNativeDeadline: state.lastSyncedNativeDeadline,
    }),
    cancelled: (state): SleepTimerState => ({
      status: "off",
      lastSyncedNativeDeadline: state.lastSyncedNativeDeadline,
    }),
    expired: (
      state,
      action: PayloadAction<{ deadline: number }>,
    ): SleepTimerState | undefined => {
      if (
        state.status !== "running" ||
        state.deadline !== action.payload.deadline
      ) {
        return
      }

      return {
        status: "expired",
        duration: state.duration,
        lastSyncedNativeDeadline: state.lastSyncedNativeDeadline,
      }
    },
    promptShown: (state): SleepTimerState | undefined => {
      if (state.status !== "expired") return
      return {
        status: "prompting",
        duration: state.duration,
        lastSyncedNativeDeadline: state.lastSyncedNativeDeadline,
      }
    },
    declined: (state): SleepTimerState | undefined => {
      if (state.status !== "prompting") return
      return {
        status: "off",
        lastSyncedNativeDeadline: state.lastSyncedNativeDeadline,
      }
    },
    restarted: (
      state,
      action: PayloadAction<{ deadline: number }>,
    ): SleepTimerState | undefined => {
      if (state.status !== "prompting") return
      return {
        status: "running",
        deadline: action.payload.deadline,
        duration: state.duration,
        lastSyncedNativeDeadline: state.lastSyncedNativeDeadline,
      }
    },
    nativeDeadlineSynced: (
      state,
      action: PayloadAction<{ deadline: number | null }>,
    ) => {
      state.lastSyncedNativeDeadline = action.payload.deadline
    },
  },
})
