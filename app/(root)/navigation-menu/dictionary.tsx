import { useGlobalSearchParams } from "expo-router"
import { View } from "react-native"

import { Dictionary } from "@/components/toolbarItems/navigation/Dictionary"
import { type UUID } from "@/uuid"

type NavigationMenuParams = {
  uuid?: UUID
}

export default function DictionaryScreen() {
  const { uuid } = useGlobalSearchParams<NavigationMenuParams>()

  return (
    <View className="bg-background flex-1">
      <Dictionary bookUuid={uuid} />
    </View>
  )
}
