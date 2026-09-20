import { router, useGlobalSearchParams } from "expo-router"
import { View } from "react-native"

import { Highlights } from "@/components/toolbarItems/navigation/Highlights"
import { type UUID } from "@/uuid"

type NavigationMenuParams = {
  uuid?: UUID
  format?: "readaloud" | "ebook" | "audiobook"
}

export default function HighlightsScreen() {
  const { uuid, format } = useGlobalSearchParams<NavigationMenuParams>()

  return (
    <View className="bg-background flex-1">
      <Highlights
        bookUuid={uuid}
        format={format}
        onClose={() => router.dismiss()}
      />
    </View>
  )
}
