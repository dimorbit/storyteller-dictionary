import { LegendList } from "@legendapp/list/react-native"
import { type ReactNode } from "react"
import { RefreshControl, View, useWindowDimensions } from "react-native"
import {
  KeyboardAwareScrollView,
  KeyboardGestureArea,
} from "react-native-keyboard-controller"
import { withUniwind } from "uniwind"

import { type BookWithRelations } from "@/database/books"
import { useListAllServerBooks } from "@/hooks/useListAllServerBooks"

import { BackButton } from "./BackButton"
import { BookThumbnail } from "./BookThumbnail"
import { MiniPlayerWidget } from "./MiniPlayerWidget"
import { Stack } from "./ui/Stack"
import { Text } from "./ui/text"

const BookLegendList = withUniwind(LegendList<BookWithRelations>)

interface Props {
  title: string
  books: BookWithRelations[]
  header?: ReactNode
  refreshable?: boolean
}

export function BookGrid({ title, books, header, refreshable = true }: Props) {
  const dimensions = useWindowDimensions()

  const { isLoading, refetch } = useListAllServerBooks()

  const horizontalPadding = 32
  const gap = 12
  const minThumbnailWidth = 150

  const numColumns = Math.max(
    1,
    Math.floor(
      (dimensions.width - horizontalPadding + gap) / (minThumbnailWidth + gap),
    ),
  )

  const thumbnailWidth = Math.floor(
    (dimensions.width - horizontalPadding - (numColumns - 1) * gap) /
      numColumns,
  )

  return (
    <Stack className="pt-safe flex-1 items-stretch">
      <View className="w-full flex-row items-center justify-start gap-2 self-start px-2">
        <BackButton />

        <Text className="font-youngserif my-2" variant="h3">
          {title}
        </Text>

        <Text className="text-muted-foreground mr-4 ml-auto text-sm">
          {books.length} books
        </Text>
      </View>

      {header}

      <KeyboardGestureArea style={{ flex: 1 }} enableSwipeToDismiss>
        <BookLegendList
          key={numColumns}
          className="flex-1"
          contentContainerClassName="px-2.5"
          data={books}
          numColumns={numColumns}
          keyExtractor={(book) => book.uuid}
          recycleItems
          drawDistance={dimensions.height}
          keyboardDismissMode="interactive"
          keyboardShouldPersistTaps="always"
          renderScrollComponent={(props) => (
            <KeyboardAwareScrollView {...props} />
          )}
          refreshControl={
            refreshable ? (
              <RefreshControl
                refreshing={isLoading}
                onRefresh={() => {
                  refetch()
                }}
              />
            ) : undefined
          }
          renderItem={({ item: book }) => (
            <View className="mx-1.5 my-2" style={{ width: thumbnailWidth }}>
              <BookThumbnail book={book} width={thumbnailWidth} />
            </View>
          )}
          ListFooterComponent={<View className="h-40 w-full" />}
        />
      </KeyboardGestureArea>
      <MiniPlayerWidget />
    </Stack>
  )
}
