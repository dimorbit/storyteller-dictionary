import { View } from "react-native"

import { Dictionary } from "@/components/toolbarItems/navigation/Dictionary"
import { useNavigationMenuParams } from "@/components/toolbarItems/navigation/useNavigationMenuParams"

export default function DictionaryScreen() {
  const { uuid } = useNavigationMenuParams()

  return (
    <View className="bg-background flex-1">
      <Dictionary bookUuid={uuid} />
    </View>
  )
}
