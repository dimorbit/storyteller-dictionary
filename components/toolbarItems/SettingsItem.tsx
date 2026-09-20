import { router } from "expo-router"
import { CaseSensitive } from "lucide-react-native"

import { Button } from "@/components/ui/button"
import { Icon } from "@/components/ui/icon"
import { useAppSelector } from "@/store/appState"
import { getCurrentlyPlayingBookUuid } from "@/store/selectors/bookshelfSelectors"

export function SettingsItem() {
  const bookUuid = useAppSelector(getCurrentlyPlayingBookUuid)

  return (
    <Button
      accessibilityLabel="Open reading settings"
      variant="ghost"
      size="icon"
      className="items-center rounded"
      disabled={!bookUuid}
      onPress={() => {
        if (!bookUuid) return

        router.push({
          pathname: "/reading-settings",
          params: { uuid: bookUuid },
        })
      }}
    >
      <Icon as={CaseSensitive} className="mt-0.5 -mb-0.5" size={24} />
    </Button>
  )
}
