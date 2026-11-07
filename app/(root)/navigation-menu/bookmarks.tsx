import { useNavigationMenuParams } from "@/components/toolbarItems/navigation/useNavigationMenuParams"
import { router } from "expo-router"
import { View } from "react-native"

import { Bookmarks } from "@/components/toolbarItems/navigation/Bookmarks"
import { type UUID } from "@/uuid"

type NavigationMenuParams = {
  uuid?: UUID
  format?: "readaloud" | "ebook" | "audiobook"
}

export default function BookmarksScreen() {
  const { uuid, format } = useNavigationMenuParams()

  return (
    <View className="bg-background flex-1">
      <Bookmarks
        bookUuid={uuid}
        format={format}
        onClose={() => router.dismiss()}
      />
    </View>
  )
}
