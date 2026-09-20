import { useState } from "react"
import { Platform, View } from "react-native"
import { ScrollView } from "react-native-gesture-handler"

import Footnote from "@/components/Footnote"
import {
  readerFormSheetBottomPadding,
  readerFormSheetGrabberHeight,
  useReaderFormSheetBottomInset,
} from "@/components/toolbarItems/readerFormSheetLayout"
import { PortalHost } from "@/components/ui/portal-context"
import { useColorTheme } from "@/hooks/useColorTheme"
import { useAppSelector } from "@/store/appState"

export default function FootnoteScreen() {
  const { foreground } = useColorTheme()
  const footnoteContent = useAppSelector(
    (state) => state.bookshelf.footnoteContent,
  )
  const [height, setHeight] = useState(0)
  const bottomInset = useReaderFormSheetBottomInset()
  const scrollHeight =
    height -
    readerFormSheetGrabberHeight -
    readerFormSheetBottomPadding -
    bottomInset

  if (!footnoteContent) return null

  return (
    <View
      className="bg-background overflow-hidden"
      style={{ marginBottom: -bottomInset }}
      onLayout={(event) => {
        setHeight((prev) => prev ?? event.nativeEvent.layout.height)
      }}
    >
      <View
        className="items-center justify-center"
        style={{ height: readerFormSheetGrabberHeight }}
      >
        <View className="bg-muted-foreground/40 h-1 w-12 rounded-full" />
      </View>
      <PortalHost>
        <ScrollView
          style={{ height: scrollHeight }}
          contentContainerClassName="px-6 pb-20"
        >
          <View>
            <Footnote
              footnote={
                Platform.OS === "ios"
                  ? footnoteContent.replaceAll(/"/g, '\\"')
                  : footnoteContent
              }
              textColor={foreground}
              dom={{ matchContents: true }}
            />
          </View>
        </ScrollView>
      </PortalHost>
    </View>
  )
}
