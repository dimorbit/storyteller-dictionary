import { Stack, useLocalSearchParams } from "expo-router"
import { ActivityIndicator, View } from "react-native"
import { WebView } from "react-native-webview"
import { useSafeAreaInsets } from "react-native-safe-area-context"

import { BackButton } from "@/components/BackButton"
import { Text } from "@/components/ui/text"

export default function DictionarySearchScreen() {
  const { word: wordParam } = useLocalSearchParams<{
    word?: string | string[]
  }>()
  const word = Array.isArray(wordParam) ? wordParam[0] : wordParam
  const insets = useSafeAreaInsets()

  if (!word) {
    return (
      <View className="bg-background flex-1 items-center justify-center">
        <Stack.Screen options={{ presentation: "modal" }} />
        <Text className="text-muted-foreground">No word selected.</Text>
      </View>
    )
  }

  const query = `define:${word}`
  const url = `https://www.google.com/search?q=${encodeURIComponent(query)}`

  return (
    <View className="bg-background flex-1" style={{ paddingTop: insets.top }}>
      <Stack.Screen options={{ presentation: "modal" }} />
      <View className="border-border flex-row items-center border-b px-2">
        <BackButton />
        <View className="flex-1 pr-4">
          <Text className="text-base font-semibold" numberOfLines={1}>
            {query}
          </Text>
        </View>
      </View>
      <WebView
        source={{ uri: url }}
        startInLoadingState
        renderLoading={() => (
          <View className="bg-background absolute top-0 right-0 bottom-0 left-0 items-center justify-center">
            <ActivityIndicator />
          </View>
        )}
      />
    </View>
  )
}
