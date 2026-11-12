import deepmerge from "deepmerge"
import { useKeepAwake } from "expo-keep-awake"
import { Tabs, useRouter } from "expo-router"
import { useVerticalBarEdgeIOS } from "expo-status-bar"
import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import {
  DeviceEventEmitter,
  StatusBar,
  TouchableOpacity,
  View,
} from "react-native"
import { useSafeAreaInsets } from "react-native-safe-area-context"

import { type Bookmark } from "@/database/bookmarks"
import { type BookWithRelations } from "@/database/books"
import { type DictionaryEntry } from "@/database/dictionary"
import { deepEquals } from "@/deepEquals"
import { useColorTheme } from "@/hooks/useColorTheme"
import { useSpansFold } from "@/lib/pose"
import { cn } from "@/lib/utils"
import { wordKey } from "@/lib/wordKey"
import { logger } from "@/logger"
import { EPUBView } from "@/modules/readium"
import {
  type EPUBViewRef,
  type ReadiumLocator,
} from "@/modules/readium/src/Readium.types"
import { bookDoubleTapped, bookLocatorChanged } from "@/store/actions"
import { useAppDispatch, useAppSelector } from "@/store/appState"
import {
  useCreateDictionaryEntryMutation,
  useGetBookBookmarksQuery,
  useGetBookHighlightsQuery,
  useGetBookPreferencesQuery,
  useGetDictionaryEntriesQuery,
  useGetGlobalPreferencesQuery,
  useUpdateGlobalPreferenceMutation,
} from "@/store/localApi"
import { getCustomFontUrl } from "@/store/persistence/fonts"
import {
  getIsPlaying,
  getJumpHold,
  getReturnToPosition,
} from "@/store/selectors/bookshelfSelectors"
import { getShouldKeepReaderAwake } from "@/store/selectors/sleepTimerSelectors"
import { bookshelfSlice } from "@/store/slices/bookshelfSlice"
import { type UUID } from "@/uuid"

import { BackButton } from "./BackButton"
import { DictionaryUndoSnackbar } from "./DictionaryUndoSnackbar"
import { LoadingView } from "./LoadingView"
import { MiniPlayer } from "./MiniPlayer"
import { SelectionMenu } from "./SelectionMenu"
import { Toolbar } from "./Toolbar"
import { NavigationReturnControl } from "./toolbarItems/navigation/NavigationReturnControl"
import { Group } from "./ui/Group"
import { HideableView } from "./ui/HideableView"

// Temporary diagnostic: when false, native selection events are ignored and
// the custom selection menu is never shown, so only the WebView's own
// selection handles are active.
const ENABLE_SELECTION_UI = false

type Props = {
  book: BookWithRelations
  format: "readaloud" | "ebook"
  locator: ReadiumLocator
  isReaderSheetOpen: boolean
}

const forwardNavKeyCodes = [93, 117]
const backwardNavKeyCodes = [92]

const barRowHeight = 52
const navigationReturnHeight = 40 // NavigationReturnControl's h-10

function EpubKeepAwake() {
  useKeepAwake()
  return null
}

export function Epub({ book, format, locator, isReaderSheetOpen }: Props) {
  const shouldKeepAwake = useAppSelector(getShouldKeepReaderAwake)

  const { foreground, background } = useColorTheme()
  const insets = useSafeAreaInsets()
  const railEdge = useVerticalBarEdgeIOS()
  const railWidth =
    railEdge === "leading"
      ? insets.left
      : railEdge === "trailing"
        ? insets.right
        : 0
  const rail = railEdge !== null && railWidth > 0
  const inlineBar = useSpansFold() && !rail
  const [activeBookmarks, setActiveBookmarks] = useState<Bookmark[]>([])
  const [activeHighlight, setActiveHighlight] = useState<UUID | null>(null)
  const [activeDictionaryEntryUuid, setActiveDictionaryEntryUuid] =
    useState<UUID | null>(null)
  // The id of the one mark whose menu is open, so only that copy of the word is
  // washed rather than every copy in the chapter.
  const [washDecorationId, setWashDecorationId] = useState<string | null>(null)
  const [dictionaryUndoEntry, setDictionaryUndoEntry] =
    useState<DictionaryEntry | null>(null)

  const {
    data: bookPreferences,
    isLoading: isLoadingBookPreferences,
    isUninitialized: isBookPreferencesUnitialized,
  } = useGetBookPreferencesQuery({ uuid: book.uuid })

  const {
    data: globalPreferences,
    isLoading: isLoadingGlobalPreferences,
    isUninitialized: isGlobalPreferencesUninitialized,
  } = useGetGlobalPreferencesQuery()

  const [updateGlobalPreference] = useUpdateGlobalPreferenceMutation()

  const isLoadingPreferences =
    isLoadingBookPreferences || isLoadingGlobalPreferences
  const isPreferencesUninitialized =
    isBookPreferencesUnitialized || isGlobalPreferencesUninitialized

  const preferences = useMemo(
    () =>
      bookPreferences
        ? globalPreferences && deepmerge(globalPreferences, bookPreferences)
        : globalPreferences,
    [globalPreferences, bookPreferences],
  )
  const hideStatusbar = preferences?.hideStatusbar

  const { data: highlights } = useGetBookHighlightsQuery({
    bookUuid: book.uuid,
  })

  const { data: bookmarks } = useGetBookBookmarksQuery({
    bookUuid: book.uuid,
  })

  // Saved words are global: a word saved in one book counts in every book.
  const { data: dictionaryEntries } = useGetDictionaryEntriesQuery()

  const [createDictionaryEntry] = useCreateDictionaryEntryMutation()

  const customFonts = preferences?.customFonts

  const [selection, setSelection] = useState<{
    x: number
    y: number
    yBottom?: number
    locator: ReadiumLocator
  } | null>(null)
  const [toolbarHeight, setToolbarHeight] = useState(0)

  const dictionaryEntriesByKey = useMemo(() => {
    const byKey = new Map<string, DictionaryEntry>()
    for (const entry of dictionaryEntries ?? []) {
      byKey.set(entry.wordKey, entry)
    }
    return byKey
  }, [dictionaryEntries])

  // The saved word a selection's text names. Used only when a selection or a
  // highlight opens the menu; a tapped mark carries its own id instead.
  const savedWordUuidForText = (text: string | null | undefined) => {
    if (!text) return null
    return dictionaryEntriesByKey.get(wordKey(text))?.uuid ?? null
  }

  // The saved word the open menu acts on. It is set when the menu
  // opens and is never looked up again from the text afterwards.
  const activeDictionaryEntry = useMemo(() => {
    if (!activeDictionaryEntryUuid) return null

    return (
      dictionaryEntries?.find(
        (entry) => entry.uuid === activeDictionaryEntryUuid,
      ) ?? null
    )
  }, [activeDictionaryEntryUuid, dictionaryEntries])

  const handleDictionaryEntryDeleted = useCallback((entry: DictionaryEntry) => {
    setWashDecorationId(null)
    setDictionaryUndoEntry(entry)
  }, [])

  const dismissDictionaryUndo = useCallback(() => {
    setDictionaryUndoEntry(null)
  }, [])

  const undoDictionaryDelete = useCallback(async () => {
    if (!dictionaryUndoEntry) return

    try {
      await createDictionaryEntry(dictionaryUndoEntry).unwrap()
      setDictionaryUndoEntry(null)
    } catch (error) {
      logger.error("Could not restore dictionary entry")
      logger.error(error)
    }
  }, [createDictionaryEntry, dictionaryUndoEntry])

  const dispatch = useAppDispatch()

  useEffect(() => {
    if (!hideStatusbar?.enabled) return

    StatusBar.setHidden(!preferences?.showReaderUi)

    return () => {
      StatusBar.setHidden(false)
    }
  }, [hideStatusbar?.enabled, preferences?.showReaderUi])

  const epubViewRef = useRef<EPUBViewRef | null>(null)

  // The selection menu can close after the reader has gone, when a save or delete
  // it started finishes late. The native view is torn down by then.
  const isMountedRef = useRef(false)
  useEffect(() => {
    isMountedRef.current = true
    return () => {
      isMountedRef.current = false
    }
  }, [])

  // The selection menu draws a backdrop over the reader, so the tap that dismisses
  // it never reaches the webview and the native text selection (box plus handles)
  // would otherwise stay behind. Ask the webview to collapse it explicitly.
  const clearNativeSelection = useCallback(() => {
    if (!isMountedRef.current) return

    void epubViewRef.current?.clearSelection().catch(() => {
      // The view is going away, so there is no selection left to clear.
    })
  }, [])

  const isPlaying = useAppSelector(getIsPlaying)
  const returnPosition = useAppSelector((state) =>
    getReturnToPosition(state, book.uuid),
  )
  // While a jump waits for the audio, the native view behaves as paused, so it
  // does not schedule page turns toward where the audio still is.
  const isJumpHeld = useAppSelector(getJumpHold)?.bookUuid === book.uuid

  const floatingToolbar = preferences?.floatingToolbar
  // Top padding of the reading area; the rail's return bar pins to it too.
  const pageTopPadding = floatingToolbar ? insets.top : 24
  const readerTop = pageTopPadding + (rail || floatingToolbar ? 0 : toolbarHeight)
  const returnBarTop = rail ? pageTopPadding : toolbarHeight
  const returnBarBottom = Math.max(
    0,
    returnBarTop + navigationReturnHeight - readerTop,
  )

  const showingFootnote = useAppSelector(
    (state) => state.bookshelf.footnoteContent !== null,
  )

  const router = useRouter()

  useEffect(() => {
    const listener = DeviceEventEmitter.addListener(
      "storyteller:keydown",
      (event) => {
        if (forwardNavKeyCodes.includes(event.keyCode)) {
          epubViewRef.current?.goForward()
        } else if (backwardNavKeyCodes.includes(event.keyCode)) {
          epubViewRef.current?.goBackward()
        }
      },
    )

    return () => listener.remove()
  }, [])

  if (isLoadingPreferences || isPreferencesUninitialized) {
    return (
      <View className="bg-background flex-1">
        {shouldKeepAwake && <EpubKeepAwake />}
        <LoadingView />
      </View>
    )
  }

  return (
    <View className="bg-background flex-1">
      {shouldKeepAwake && <EpubKeepAwake />}
      {showingFootnote && (
        <TouchableOpacity
          className="absolute top-0 right-0 bottom-0 left-0 z-100"
          onPress={() => {
            if (!showingFootnote) return
            router.dismiss()
          }}
        />
      )}
      <Tabs.Screen options={{ tabBarStyle: { display: "none" } }} />
      {rail ? (
        <View
          pointerEvents="box-none"
          className="absolute top-0 bottom-0 z-3 mr-1 flex-col items-center justify-end pb-26"
          style={{
            width: railWidth,
            ...(railEdge === "trailing" ? { right: 0 } : { left: 0 }),
          }}
        >
          <HideableView
            hidden={!preferences?.showReaderUi}
            className="items-center gap-1"
          >
            <BackButton />
            <Toolbar mode="text" activeBookmarks={activeBookmarks} vertical />
          </HideableView>
        </View>
      ) : (
        <HideableView hidden={!preferences?.showReaderUi} className="relative">
          <Group
            className={cn(
              "px-safe-or-4 z-3 flex-row items-center",
              inlineBar ? "gap-4" : "justify-between",
              {
                "bg-background border-b-foreground absolute right-0 left-0 border-b":
                  floatingToolbar,
              },
            )}
            style={{
              paddingTop: inlineBar
                ? Math.max(0, insets.top - barRowHeight)
                : insets.top,
            }}
            onLayout={(event) => {
              setToolbarHeight(event.nativeEvent.layout.height)
            }}
          >
            <BackButton />
            <Toolbar mode="text" activeBookmarks={activeBookmarks} />
          </Group>
        </HideableView>
      )}
      {/* Overlays the page top so showing it never resizes the reader. Stays
          before SelectionMenu so its backdrop (also z-2) sits above the bar. */}
      {(rail || toolbarHeight > 0) && !isReaderSheetOpen && (
        <HideableView
          hidden={!preferences?.showReaderUi}
          className="absolute z-2"
          style={
            rail
              ? { top: pageTopPadding, left: insets.left, right: insets.right }
              : { top: toolbarHeight, left: 0, right: 0 }
          }
        >
          <NavigationReturnControl bookUuid={book.uuid} />
        </HideableView>
      )}
      {/* Keep this wrapper mounted so opening or closing the selection menu
          never changes the reader's position in the React tree. Reordering the
          reader remounts the native EPUB view, which reloads the WebView and
          drops the live text selection. */}
      <View
        pointerEvents="box-none"
        className="absolute top-0 right-0 bottom-0 left-0 z-10"
      >
        {ENABLE_SELECTION_UI && selection && (
          <SelectionMenu
            bookUuid={book.uuid}
            x={selection.x + insets.left}
            // Native positions are relative to the reader's own top edge.
            y={selection.y + readerTop}
            yBottom={
              selection.yBottom !== undefined
                ? selection.yBottom + readerTop
                : undefined
            }
            locator={selection.locator}
            existingHighlight={activeHighlight}
            dictionaryEntry={activeDictionaryEntry}
            onDictionaryEntryDeleted={handleDictionaryEntryDeleted}
            onClose={() => {
              setSelection(null)
              setActiveHighlight(null)
              setActiveDictionaryEntryUuid(null)
              setWashDecorationId(null)
              clearNativeSelection()
            }}
          />
        )}
      </View>
      <View
        className={cn("z-1 flex-1", {
          "pb-safe-or-2": floatingToolbar,
        })}
        style={{
          backgroundColor: background,
          paddingLeft: insets.left,
          paddingRight: insets.right,
          paddingTop: pageTopPadding,
        }}
      >
        <EPUBView
          ref={epubViewRef}
          style={{ flex: 1 }}
          bookUuid={book.uuid}
          locator={locator}
          jumpBarBottom={
            returnPosition &&
            preferences?.showReaderUi &&
            (rail || !floatingToolbar || toolbarHeight > 0)
              ? returnBarBottom
              : 0
          }
          highlights={highlights ?? []}
          dictionaryEntries={(dictionaryEntries ?? []).map(
            ({ uuid, word, bookUuid, locator }) => ({
              uuid,
              word,
              bookUuid,
              locator,
            }),
          )}
          activeDictionaryDecorationId={washDecorationId}
          bookmarks={bookmarks?.map((bookmark) => bookmark.locator) ?? []}
          fontScale={preferences?.typography?.scale}
          lineHeight={preferences?.typography?.lineHeight}
          textAlign={preferences?.typography?.alignment}
          fontFamily={preferences?.typography?.fontFamily}
          marginLeft={preferences?.layout.marginLeft}
          marginRight={preferences?.layout.marginRight}
          readaloudColor={preferences?.readaloudColor}
          readaloudDecoratorStyle={preferences?.readaloudDecoratorStyle}
          scrollMode={preferences?.layout.scroll ?? false}
          colorTheme={{ foreground, background }}
          customFonts={customFonts?.map((font) => ({
            ...font,
            uri: getCustomFontUrl(font.filename),
          }))}
          onHighlightTap={(event) => {
            setActiveDictionaryEntryUuid(
              savedWordUuidForText(locator.text?.highlight),
            )
            setWashDecorationId(null)
            setSelection({
              x: event.nativeEvent.x,
              y: event.nativeEvent.y,
              locator: locator,
            })
            setActiveHighlight(event.nativeEvent.decoration)
          }}
          onBookmarksActivate={(event) => {
            const activeLocators = event.nativeEvent.activeBookmarks
            setActiveBookmarks(
              bookmarks?.filter((bookmark) =>
                activeLocators.some((locator) => {
                  const { target: _, ...bookmarkLocator } = bookmark.locator
                  return deepEquals(bookmarkLocator, locator)
                }),
              ) ?? [],
            )
          }}
          onLocatorChange={(event) => {
            if (isPlaying) {
              return
            }

            dispatch(
              bookLocatorChanged({
                bookUuid: book.uuid,
                locator: event.nativeEvent,
                timestamp: Date.now(),
              }),
            )
          }}
          onMiddleTouch={() => {
            updateGlobalPreference({
              name: "showReaderUi",
              value: !preferences?.showReaderUi,
            })
          }}
          onDoubleTouch={(event) => {
            dispatch(
              bookDoubleTapped({
                bookUuid: book.uuid,
                locator: event.nativeEvent,
                timestamp: Date.now(),
              }),
            )
          }}
          onSelection={(event) => {
            if (!ENABLE_SELECTION_UI) return
            if ("cleared" in event.nativeEvent) {
              setSelection(null)
              setActiveDictionaryEntryUuid(null)
              setWashDecorationId(null)
            } else {
              setSelection(event.nativeEvent)
              setActiveDictionaryEntryUuid(
                savedWordUuidForText(event.nativeEvent.locator.text?.highlight),
              )
            }
          }}
          onDictionaryTap={(event) => {
            const entry = dictionaryEntries?.find(
              ({ uuid }) => uuid === event.nativeEvent.uuid,
            )
            if (!entry) return

            setActiveHighlight(null)
            setActiveDictionaryEntryUuid(entry.uuid)
            setWashDecorationId(event.nativeEvent.decorationId)
            setSelection({
              x: event.nativeEvent.x,
              y: event.nativeEvent.y,
              locator: event.nativeEvent.locator,
            })
          }}
          onShowFootnote={(event) => {
            dispatch(
              bookshelfSlice.actions.footnoteOpened({
                content: event.nativeEvent.content,
              }),
            )
          }}
          isPlaying={isPlaying && !isJumpHeld}
        />
      </View>
      <MiniPlayer
        floatingToolbar={floatingToolbar ?? false}
        hidden={!preferences?.showReaderUi}
        book={book}
        format={format}
      />
      {dictionaryUndoEntry && (
        <DictionaryUndoSnackbar
          entry={dictionaryUndoEntry}
          onUndo={() => {
            void undoDictionaryDelete()
          }}
          onDismiss={dismissDictionaryUndo}
        />
      )}
    </View>
  )
}
