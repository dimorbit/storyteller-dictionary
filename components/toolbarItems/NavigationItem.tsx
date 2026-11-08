import { router } from "expo-router"
import { TableOfContentsIcon } from "lucide-react-native"

import {
  getLastNavigationMenuTab,
  setLastNavigationMenuParams,
} from "@/components/toolbarItems/navigation/navigationMenuSessionState"
import { Button } from "@/components/ui/button"
import { Icon } from "@/components/ui/icon"
import { useAppSelector } from "@/store/appState"
import {
  getCurrentlyPlayingBookUuid,
  getCurrentlyPlayingFormat,
} from "@/store/selectors/bookshelfSelectors"

interface Props {
  mode: "text" | "audio"
}

export function NavigationItem({ mode }: Props) {
  const bookUuid = useAppSelector(getCurrentlyPlayingBookUuid)
  const format = useAppSelector(getCurrentlyPlayingFormat) ?? "readaloud"

  return (
    <Button
      accessibilityLabel="Open navigation menu"
      variant="ghost"
      size="icon"
      disabled={!bookUuid}
      onPress={() => {
        if (!bookUuid) return

        const tab = getLastNavigationMenuTab()
        const params = { uuid: bookUuid, format, mode }

        setLastNavigationMenuParams(params)
        router.push({
          pathname: `/navigation-menu/${tab}`,
          params,
        })
      }}
    >
      <Icon as={TableOfContentsIcon} size={24} />
    </Button>
  )
}
