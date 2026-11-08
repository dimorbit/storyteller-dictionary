import { useGlobalSearchParams } from "expo-router"

import {
  type NavigationMenuParams,
  getLastNavigationMenuParams,
} from "@/components/toolbarItems/navigation/navigationMenuSessionState"
import { useAppSelector } from "@/store/appState"
import {
  getCurrentlyPlayingBookUuid,
  getCurrentlyPlayingFormat,
} from "@/store/selectors/bookshelfSelectors"

// The menu is pushed with these params, but the nested tab screens no longer
// receive them, so fall back to what the menu was opened with, then to the
// book that is open in the player.
export function useNavigationMenuParams() {
  const params = useGlobalSearchParams<NavigationMenuParams>()
  const opened = getLastNavigationMenuParams()
  const playingBookUuid = useAppSelector(getCurrentlyPlayingBookUuid)
  const playingFormat = useAppSelector(getCurrentlyPlayingFormat)

  const uuid = params.uuid ?? opened.uuid ?? playingBookUuid ?? undefined
  const format = params.format ?? opened.format ?? playingFormat ?? undefined

  return {
    uuid,
    format,
    mode:
      params.mode ??
      opened.mode ??
      (format === "audiobook" ? "audio" : "text"),
  }
}
