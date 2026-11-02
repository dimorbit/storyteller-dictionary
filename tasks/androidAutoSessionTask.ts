import { logger } from "@/logger"
import { attachToAndroidAutoSession } from "@/store/androidAutoSession"
import { store } from "@/store/store"
import { type UUID } from "@/uuid"

// Headless task key. Must match PlaybackService.ANDROID_AUTO_SESSION_TASK.
export const ANDROID_AUTO_SESSION_TASK = "StorytellerAndroidAutoSession"

type AndroidAutoSessionData = {
  bookUuid?: string
  format?: string
}

// Started by the native PlaybackService when Android Auto begins playback and no
// JS context is attached. Importing the store (above) boots Redux + all of its
// listeners, then we attach to the running session. From that point the normal
// listeners persist progress locally and the background sync task syncs to the
// server, so we don't do any of that work here.
//
// The task resolves once attached; the JS context stays alive in the React host
// for the rest of the session (the foreground media service keeps the process
// running), the same way it would if the app were merely backgrounded.
export async function androidAutoSessionTask(
  data: AndroidAutoSessionData,
): Promise<void> {
  try {
    const { bookUuid, format } = data

    if (format !== "audiobook" && format !== "readaloud") return
    if (!bookUuid) {
      logger.debug("androidAutoSessionTask: missing bookUuid, skipping")
      return
    }

    await attachToAndroidAutoSession(store, {
      bookUuid: bookUuid as UUID,
      format,
    })
  } catch (e) {
    logger.error(`androidAutoSessionTask failed: ${String(e)}`)
  }
}
