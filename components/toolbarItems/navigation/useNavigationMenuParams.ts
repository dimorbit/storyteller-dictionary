import { useGlobalSearchParams } from "expo-router"

import { useAppSelector } from "@/store/appState"
import {
  getCurrentlyPlayingBookUuid,
  getCurrentlyPlayingFormat,
} from "@/store/selectors/bookshelfSelectors"
import { type UUID } from "@/uuid"

type NavigationMenuParams = {
  uuid?: UUID
  format?: "readaloud" | "ebook" | "audiobook"
  mode?: "text" | "audio"
}

// The menu is pushed with these params, but the nested tab screens no longer
// receive them, so fall back to the book that is open in the player.
export function useNavigationMenuParams() {
  const params = useGlobalSearchParams<NavigationMenuParams>()
  const playingBookUuid = useAppSelector(getCurrentlyPlayingBookUuid)
  const playingFormat = useAppSelector(getCurrentlyPlayingFormat)

  const uuid = params.uuid ?? playingBookUuid ?? undefined
  const format = params.format ?? playingFormat ?? undefined

  return {
    uuid,
    format,
    mode: params.mode ?? (format === "audiobook" ? "audio" : "text"),
  }
}
