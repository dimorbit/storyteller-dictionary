import Clipboard from "@react-native-clipboard/clipboard"
import { skipToken } from "@reduxjs/toolkit/query"
import { router } from "expo-router"
import { BookA } from "lucide-react-native"
import {
  Pressable,
  StyleSheet,
  View,
  useWindowDimensions,
} from "react-native"
import { useSafeAreaInsets } from "react-native-safe-area-context"

import { Icon } from "@/components/ui/icon"
import { type DictionaryEntry } from "@/database/dictionary"
import { useColorTheme } from "@/hooks/useColorTheme"
import { CopyIcon } from "@/icons/CopyIcon"
import { TrashIcon } from "@/icons/TrashIcon"
import { wordKey } from "@/lib/wordKey"
import { logger } from "@/logger"
import { type ReadiumLocator } from "@/modules/readium/src/Readium.types"
import {
  useCreateDictionaryEntryMutation,
  useCreateHighlightMutation,
  useDeleteHighlightMutation,
  useDeleteDictionaryEntryMutation,
  useGetHighlightQuery,
  useUpdateHighlightMutation,
} from "@/store/localApi"
import { type UUID, randomUUID } from "@/uuid"

import { HighlightColorPicker } from "./HighlightColorPicker"

type Props = {
  bookUuid: UUID
  x: number
  y: number
  locator: ReadiumLocator
  existingHighlight?: UUID | null
  dictionaryEntry?: DictionaryEntry | null
  onDictionaryEntryDeleted: (entry: DictionaryEntry) => void
  onClose: () => void
}

export function SelectionMenu({
  bookUuid,
  x,
  y,
  locator,
  existingHighlight: highlightUuid,
  dictionaryEntry,
  onDictionaryEntryDeleted,
  onClose,
}: Props) {
  const dimensions = useWindowDimensions()
  const insets = useSafeAreaInsets()
  const { background } = useColorTheme()
  const [createHighlight] = useCreateHighlightMutation()
  const [updateHighlight] = useUpdateHighlightMutation()
  const [deleteHighlight] = useDeleteHighlightMutation()
  const [createDictionaryEntry] = useCreateDictionaryEntryMutation()
  const [deleteDictionaryEntry] = useDeleteDictionaryEntryMutation()
  const { data: existingHighlight } = useGetHighlightQuery(
    highlightUuid ? { uuid: highlightUuid } : skipToken,
  )

  const selectedText = (existingHighlight?.locator ?? locator).text?.highlight
    ?.trim()
    .replace(/\s+/g, " ")
  // Saved as the lookup key, so the word reads the same in the Dictionary tab
  // whichever book it came from.
  const cleanedWord = selectedText ? wordKey(selectedText) : ""
  const showDictionaryAction = !!dictionaryEntry || !!selectedText
  const numIcons =
    6 + (showDictionaryAction ? 1 : 0) + (existingHighlight ? 1 : 0)
  const panelWidth = numIcons * (24 + 16)
  const leftOffset = Math.max(
    16, // Minimum left padding
    Math.min(x - panelWidth / 2, dimensions.width - panelWidth - 16), // Prevent overflow on the right side
  )

  const handleDictionaryPress = async () => {
    if (dictionaryEntry) {
      try {
        await deleteDictionaryEntry({ uuid: dictionaryEntry.uuid }).unwrap()
        onDictionaryEntryDeleted(dictionaryEntry)
        onClose()
      } catch (error) {
        logger.error("Could not remove dictionary entry")
        logger.error(error)
        return
      }
      return
    }

    if (!selectedText) return

    const timestamp = new Date().toISOString()
    try {
      await createDictionaryEntry({
        uuid: randomUUID(),
        bookUuid,
        word: cleanedWord,
        locator,
        createdAt: timestamp,
        updatedAt: timestamp,
      }).unwrap()
      onClose()
      router.push({
        pathname: "/dictionary-search",
        params: { word: cleanedWord },
      })
    } catch (error) {
      logger.error("Could not save dictionary entry")
      logger.error(error)
      return
    }
  }

  return (
    <>
      <View
        pointerEvents="box-none"
        style={[
          styles.menu,
          {
            backgroundColor: background,
            top: Math.max(y - 80, insets.top),
            left: leftOffset,
          },
        ]}
      >
        <HighlightColorPicker
          value={existingHighlight?.color}
          onChange={(color) => {
            if (existingHighlight) {
              updateHighlight({ highlightId: existingHighlight.uuid, color })
              onClose()
              return
            }

            createHighlight({
              highlightId: randomUUID(),
              color,
              locator,
              bookUuid,
            })
            onClose()
          }}
        />
        <Pressable
          accessibilityLabel="Copy selected text"
          accessibilityRole="button"
          style={styles.copyButton}
          onPress={() => {
            Clipboard.setString(
              (
                existingHighlight?.locator ?? locator
              ).text?.highlight?.toString() ?? "",
            )
            onClose()
          }}
        >
          <CopyIcon />
        </Pressable>
        {existingHighlight && (
          <Pressable
            accessibilityLabel="Delete highlight"
            accessibilityRole="button"
            style={styles.trashButton}
            onPress={() => {
              deleteHighlight({ uuid: existingHighlight.uuid })
              onClose()
            }}
          >
            <TrashIcon />
          </Pressable>
        )}
        {showDictionaryAction && (
          <Pressable
            accessibilityLabel={
              dictionaryEntry
                ? `Delete dictionary entry ${dictionaryEntry.word}`
                : `Look up ${selectedText}`
            }
            accessibilityRole="button"
            style={styles.copyButton}
            onPress={() => {
              void handleDictionaryPress()
            }}
          >
            {dictionaryEntry ? (
              <TrashIcon />
            ) : (
              <Icon as={BookA} size={24} />
            )}
          </Pressable>
        )}
      </View>
    </>
  )
}

const styles = StyleSheet.create({
  menu: {
    position: "absolute",
    zIndex: 1000,
    flexDirection: "row",
    borderRadius: 8,
    shadowRadius: 4,
    shadowOpacity: 0.3,
    shadowOffset: {
      width: 0,
      height: 2,
    },
    shadowColor: "black",
  },
  button: {
    width: 24,
    height: 24,
    borderRadius: 12,
    margin: 8,
    borderWidth: 1,
    borderColor: "#AAA",
  },
  copyButton: {
    width: 24,
    height: 24,
    margin: 8,
  },
  trashButton: {
    width: 24,
    height: 24,
    margin: 8,
  },
})
