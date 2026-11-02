import { SectionList, View } from "react-native"

import { useReaderFormSheetScrollPaddingBottom } from "@/components/toolbarItems/readerFormSheetLayout"
import { Text } from "@/components/ui/text"
import { type DictionaryEntry } from "@/database/dictionary"
import { useGetDictionaryEntriesQuery } from "@/store/localApi"

type DictionarySection = {
  dateKey: string
  title: string
  data: DictionaryEntry[]
}

export function Dictionary() {
  const { data: entries, isLoading } = useGetDictionaryEntriesQuery()
  const paddingBottom = useReaderFormSheetScrollPaddingBottom()

  const sections = groupEntriesByDate(entries ?? [])

  if (isLoading) {
    return (
      <View className="flex-1 items-center justify-center px-8">
        <Text className="text-muted-foreground">Loading dictionary...</Text>
      </View>
    )
  }

  if (!entries?.length) {
    return (
      <View className="flex-1 items-center justify-center px-8">
        <Text className="text-muted-foreground text-center">
          Words you look up while reading will appear here.
        </Text>
      </View>
    )
  }

  return (
    <SectionList
      className="flex-1"
      sections={sections}
      keyExtractor={(entry) => entry.uuid}
      stickySectionHeadersEnabled={false}
      contentContainerStyle={{ paddingBottom }}
      renderSectionHeader={({ section }) => (
        <Text className="text-muted-foreground px-4 pt-4 pb-2 text-sm">
          {section.title}
        </Text>
      )}
      renderItem={({ item }) => (
        <View className="bg-secondary mx-2 mb-2 rounded-lg px-4 py-3">
          <Text className="text-lg">{item.word}</Text>
        </View>
      )}
    />
  )
}

function groupEntriesByDate(entries: DictionaryEntry[]): DictionarySection[] {
  const sections: DictionarySection[] = []
  const sectionByDate = new Map<string, DictionarySection>()

  for (const entry of entries) {
    const date = new Date(entry.createdAt)
    const dateKey = Number.isNaN(date.getTime())
      ? entry.createdAt.slice(0, 10)
      : `${date.getFullYear()}-${date.getMonth() + 1}-${date.getDate()}`
    let section = sectionByDate.get(dateKey)

    if (!section) {
      section = {
        dateKey,
        title: formatDateTitle(date),
        data: [],
      }
      sectionByDate.set(dateKey, section)
      sections.push(section)
    }

    section.data.push(entry)
  }

  return sections
}

function formatDateTitle(date: Date) {
  if (Number.isNaN(date.getTime())) return "Earlier"

  const today = new Date()
  if (isSameLocalDay(date, today)) return "Today"

  const yesterday = new Date(today)
  yesterday.setDate(today.getDate() - 1)
  if (isSameLocalDay(date, yesterday)) return "Yesterday"

  return new Intl.DateTimeFormat(undefined, {
    month: "long",
    day: "numeric",
    year: "numeric",
  }).format(date)
}

function isSameLocalDay(left: Date, right: Date) {
  return (
    left.getFullYear() === right.getFullYear() &&
    left.getMonth() === right.getMonth() &&
    left.getDate() === right.getDate()
  )
}
