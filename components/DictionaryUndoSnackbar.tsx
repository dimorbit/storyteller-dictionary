import { useEffect } from "react"
import { Pressable, View } from "react-native"
import { useSafeAreaInsets } from "react-native-safe-area-context"

import { Text } from "@/components/ui/text"
import { type DictionaryEntry } from "@/database/dictionary"

type Props = {
  entry: DictionaryEntry
  onUndo: () => void
  onDismiss: () => void
}

export function DictionaryUndoSnackbar({ entry, onUndo, onDismiss }: Props) {
  const insets = useSafeAreaInsets()

  useEffect(() => {
    const timeout = setTimeout(onDismiss, 5000)
    return () => clearTimeout(timeout)
  }, [entry.uuid, onDismiss])

  return (
    <View
      pointerEvents="box-none"
      className="absolute right-4 left-4 z-100"
      style={{ bottom: insets.bottom + 72 }}
    >
      <View className="bg-foreground flex-row items-center justify-between rounded-md px-4 py-3 shadow-lg">
        <Text className="text-background flex-1">Removed from Dictionary</Text>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={`Undo deleting ${entry.word}`}
          onPress={onUndo}
          className="ml-4 px-1 py-1"
        >
          <Text className="text-primary font-semibold">Undo</Text>
        </Pressable>
      </View>
    </View>
  )
}
