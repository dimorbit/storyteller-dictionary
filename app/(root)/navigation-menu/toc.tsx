import { router, useGlobalSearchParams } from "expo-router"
import { View } from "react-native"

import { TableOfContents } from "@/components/toolbarItems/navigation/TableOfContents"
import { TrackLisk } from "@/components/toolbarItems/navigation/TrackList"
import { type UUID } from "@/uuid"

type NavigationMenuParams = {
  uuid?: UUID
  format?: "readaloud" | "ebook" | "audiobook"
  mode?: "text" | "audio"
}

export default function ContentsScreen() {
  const { uuid, format, mode } = useGlobalSearchParams<NavigationMenuParams>()

  return (
    <View className="bg-background flex-1">
      {mode === "audio" ? (
        <TrackLisk
          bookUuid={uuid}
          format={format}
          onClose={() => router.dismiss()}
        />
      ) : (
        <TableOfContents
          bookUuid={uuid}
          format={format}
          onClose={() => router.dismiss()}
        />
      )}
    </View>
  )
}
