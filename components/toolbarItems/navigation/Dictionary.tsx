import { Circle, CircleDot, Funnel } from "lucide-react-native"
import { type ReactNode, useState } from "react"
import { Pressable, ScrollView, SectionList, View } from "react-native"

import {
  type DictionaryScope,
  getLastDictionaryScope,
  setLastDictionaryScope,
} from "@/components/toolbarItems/navigation/navigationMenuSessionState"
import { useReaderFormSheetScrollPaddingBottom } from "@/components/toolbarItems/readerFormSheetLayout"
import { Button } from "@/components/ui/button"
import { Icon } from "@/components/ui/icon"
import { Text } from "@/components/ui/text"
import { type DictionaryEntry } from "@/database/dictionary"
import { useGetDictionaryEntriesQuery } from "@/store/localApi"
import { type UUID } from "@/uuid"

type DictionarySection = {
  dateKey: string
  title: string
  data: DictionaryEntry[]
}

const scopeOptions: { scope: DictionaryScope; label: string }[] = [
  { scope: "book", label: "This book" },
  { scope: "all", label: "All books" },
]

// Menu sits under the h-14 header and stops 8px above the panel bottom.
const menuTop = 56
const menuBottomGap = 8

interface Props {
  bookUuid?: UUID | undefined
}

export function Dictionary({ bookUuid }: Props) {
  const { data: entries, isLoading } = useGetDictionaryEntriesQuery()
  const paddingBottom = useReaderFormSheetScrollPaddingBottom()
  const [scope, setScope] = useState(getLastDictionaryScope)
  const [filterOpen, setFilterOpen] = useState(false)
  const [panelHeight, setPanelHeight] = useState<number>()
  const menuMaxHeight =
    panelHeight === undefined
      ? undefined
      : Math.max(0, panelHeight - menuTop - menuBottomGap)

  const hasBook = Boolean(bookUuid)
  const allEntries = entries ?? []
  const visibleEntries =
    hasBook && scope === "book"
      ? allEntries.filter((entry) => entry.bookUuid === bookUuid)
      : allEntries
  const sections = groupEntriesByDate(visibleEntries)

  const chooseScope = (nextScope: DictionaryScope) => {
    setLastDictionaryScope(nextScope)
    setScope(nextScope)
    setFilterOpen(false)
  }

  let body: ReactNode
  if (isLoading) {
    body = (
      <View className="flex-1 items-center justify-center px-8">
        <Text className="text-muted-foreground">Loading dictionary...</Text>
      </View>
    )
  } else if (!allEntries.length) {
    body = (
      <View className="flex-1 items-center justify-center px-8">
        <Text className="text-muted-foreground text-center">
          Words you look up while reading will appear here.
        </Text>
      </View>
    )
  } else if (!visibleEntries.length) {
    body = (
      <View className="flex-1 items-center justify-center px-8">
        <Text className="text-muted-foreground text-center">
          No words saved from this book yet.
        </Text>
      </View>
    )
  } else {
    body = (
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

  return (
    <View
      className="flex-1"
      onLayout={(event) => setPanelHeight(event.nativeEvent.layout.height)}
    >
      <View className="h-14 flex-row items-center justify-between pr-2 pl-4">
        <Text variant="large">Saved words</Text>
        {hasBook ? (
          <Button
            accessibilityLabel="Filter saved words"
            variant="ghost"
            size="icon"
            onPress={() => setFilterOpen((open) => !open)}
          >
            <Icon as={Funnel} size={24} />
          </Button>
        ) : null}
      </View>
      {body}
      {hasBook && filterOpen ? (
        <>
          <Pressable
            accessibilityLabel="Close filter"
            accessibilityRole="button"
            className="absolute top-0 right-0 bottom-0 left-0 z-50"
            onPress={() => setFilterOpen(false)}
          />
          <View
            className="border-border bg-popover absolute top-14 right-2 z-50 w-44 rounded-md border p-1 shadow-lg shadow-black/5"
            style={{ maxHeight: menuMaxHeight }}
          >
            <ScrollView>
              {scopeOptions.map((option) => {
                const selected = option.scope === scope
                return (
                  <Button
                    key={option.scope}
                    accessibilityState={{ selected }}
                    className="h-auto justify-start rounded-sm px-3 py-2 sm:h-auto"
                    variant="ghost"
                    onPress={() => chooseScope(option.scope)}
                  >
                    <Icon
                      as={selected ? CircleDot : Circle}
                      className={
                        selected ? "text-primary" : "text-muted-foreground"
                      }
                      size={18}
                    />
                    <Text>{option.label}</Text>
                  </Button>
                )
              })}
            </ScrollView>
          </View>
        </>
      ) : null}
    </View>
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
