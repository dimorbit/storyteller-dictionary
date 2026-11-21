import deepmerge from "deepmerge"
import { useKeepAwake } from "expo-keep-awake"
import { Tabs, useRouter } from "expo-router"
import { useVerticalBarEdgeIOS } from "expo-status-bar"
import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import {
  DeviceEventEmitter,
  Pressable,
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

const ENABLE_SELECTION_UI = true

type Props = {
  book: BookWithRelations
  format: "readaloud" | "ebook"
  locator: ReadiumLocator
  isReaderSheetOpen: boolean
}

const forwardNavKeyCodes = [93, 117]
const backwardNavKeyCodes = [92]

// Volume up back, volume down forward, the way Tachiyomi and most e-readers do it. The
// setting can swap them; the native side only decides *whether* a press turns the page,
// so the direction is chosen here.
const volumeUpKeyCode = 24
const volumeDownKeyCode = 25

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
  const invertVolumeKeys = preferences?.invertVolumeKeys ?? false

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
    selectedText?: string
  } | null>(null)
  const [toolbarHeight, setToolbarHeight] = useState(0)

  // A dictionary menu belongs to the page it was opened on. Turning the page hides it,
  // and coming back brings it back, so the stash is keyed by page rather than kept in a
  // single slot: a second menu opened on another page must not evict the first.
  // Held in a ref alongside state because the restore decision runs inside an effect
  // that must see the newest stash without re-subscribing.
  type MenuSnapshot = {
    x: number
    y: number
    yBottom?: number
    locator: ReadiumLocator
    selectedText?: string
    activeDictionaryEntryUuid: UUID | null
    washDecorationId: string | null
    activeHighlight: UUID | null
  }
  const menuStash = useRef(new Map<string, MenuSnapshot>())
  // A swipe keeps the wash through the slide, then drops it so a hidden menu cannot
  // keep volume keys gated or spend the next tap. The token cancels that drop when a
  // menu is restored or a new one opens before the timer fires.
  const menuWashTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const menuWashToken = useRef(0)
  // The page the open menu belongs to, so the effect below can tell a turn away from
  // the recompute events Readium also emits.
  const menuPage = useRef<string | null>(null)
  const currentPage = useRef<string | null>(null)
  // Left null until the first native page report: nothing can be selected before the
  // reader has painted, so no menu can exist yet. A made-up placeholder key was worse
  // than nothing — it could match a later page and restore a stash on the wrong one.

  // A stash is keyed only by `href|pageIndex`. Changing any setting that reflows text
  // moves a saved word to a different page number, so a snapshot left under the old
  // key would restore its menu on a page the word no longer occupies. Drop every
  // snapshot when pagination inputs change; the first render has nothing to drop.
  const paginationKey = [
    preferences?.typography?.scale,
    preferences?.typography?.lineHeight,
    preferences?.typography?.alignment,
    preferences?.typography?.fontFamily,
    preferences?.layout.marginLeft,
    preferences?.layout.marginRight,
    preferences?.layout.scroll,
    customFonts?.map((font) => font.filename).join(","),
  ].join("|")
  const paginationReady = useRef(false)
  useEffect(() => {
    if (!paginationReady.current) {
      paginationReady.current = true
      return
    }
    menuStash.current.clear()
  }, [paginationKey])

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

  // Closes the selection menu and the native selection together.
  const closeSelection = useCallback(() => {
    menuWashToken.current += 1
    if (menuWashTimer.current !== null) {
      clearTimeout(menuWashTimer.current)
      menuWashTimer.current = null
    }
    setSelection(null)
    setActiveHighlight(null)
    setActiveDictionaryEntryUuid(null)
    setWashDecorationId(null)
    if (menuPage.current !== null) menuStash.current.delete(menuPage.current)
    menuPage.current = null
    clearNativeSelection()
  }, [clearNativeSelection])

  // Hides the menu without dismissing it, so a page turn can take it away and bring it
  // back. The snapshot is stashed under the page the menu belongs to, and the wash is
  // left alone — it is what keeps the mark highlighted while the page slides.
  const hideMenu = useCallback(() => {
    // Stashed before the state is cleared, outside any state updater: an updater may
    // run more than once, and writing the stash from inside it would store the
    // snapshot twice.
    if (selection && menuPage.current !== null) {
      menuStash.current.set(menuPage.current, {
        ...selection,
        activeDictionaryEntryUuid,
        washDecorationId,
        activeHighlight,
      })
    }
    setSelection(null)
    setActiveHighlight(null)
    setActiveDictionaryEntryUuid(null)
    // The wash is left alone here, and the page turn that calls this no longer clears
    // it either. It belongs to the mark's own page, so a wash with no visible menu is
    // harmless — the render guard hides the menu — whereas clearing it is what lost the
    // highlight when pages were switched quickly. Only an explicit dismissal clears it.
    // menuPage is deliberately left set: the turn that follows reads it to know which
    // page the menu belonged to, and the clear and the turn can arrive in either order.
  }, [selection, activeDictionaryEntryUuid, activeHighlight, washDecorationId])

  const restoreMenu = useCallback((snapshot: MenuSnapshot, page: string) => {
    menuWashToken.current += 1
    if (menuWashTimer.current !== null) {
      clearTimeout(menuWashTimer.current)
      menuWashTimer.current = null
    }
    // A deleted entry must not be resurrected by a page turn.
    if (
      snapshot.activeDictionaryEntryUuid &&
      !dictionaryEntries?.some(
        (entry) => entry.uuid === snapshot.activeDictionaryEntryUuid,
      )
    ) {
      return
    }
    setSelection({
      x: snapshot.x,
      y: snapshot.y,
      ...(snapshot.yBottom !== undefined && { yBottom: snapshot.yBottom }),
      locator: snapshot.locator,
      ...(snapshot.selectedText !== undefined && {
        selectedText: snapshot.selectedText,
      }),
    })
    setActiveHighlight(snapshot.activeHighlight)
    setActiveDictionaryEntryUuid(snapshot.activeDictionaryEntryUuid)
    // Re-applied rather than assumed: a non-swipe turn clears the native active id,
    // and a swipe may have left it set with menu ownership hidden. Sending the same
    // value twice would be a no-op prop, so clear it first and put it back on the next
    // frame. That makes native re-open menu ownership and re-add the wash class.
    const wash = snapshot.washDecorationId
    if (wash !== null) {
      setWashDecorationId(null)
      const token = menuWashToken.current
      setTimeout(() => {
        if (menuWashToken.current === token) setWashDecorationId(wash)
      }, 0)
    } else {
      setWashDecorationId(null)
    }
    // Pinned to the page the snapshot was stored for, not to whatever page the reader is
    // on now: a recompute can move currentPage between the stash and the restore, and the
    // menu would then belong to the wrong page.
    menuPage.current = page
  }, [dictionaryEntries])

  const handlePageTurned = useCallback(
    (pageKey: string, turned: boolean) => {
      const previousPage = currentPage.current
      currentPage.current = pageKey
      // Only a real turn moves the menu off its page. Readium recomputes the current
      // page on other occasions too, and hiding on those would close the menu the
      // reader just opened.
      if (!turned) return
      if (selection && menuPage.current !== null && menuPage.current !== pageKey) {
        hideMenu()
        return
      }
      // Restore only when this page was actually arrived at from a different one. A
      // swipe that hid the menu and then ended on the same page leaves a stash behind,
      // and restoring it here is the menu popping back up on its own; the abandoned-swipe
      // timer decides that case instead.
      if (previousPage === null || previousPage === pageKey) return
      const stashed = menuStash.current.get(pageKey)
      if (stashed) {
        // Not removed: arriving back on this page restores the menu, and leaving it
        // again must find the same snapshot. The entry is replaced only when a new menu
        // is opened on that page.
        restoreMenu(stashed, pageKey)
      }
      // The wash is never cleared here. It belongs to the page its mark is on, and the
      // render guard hides the menu when that page is not the current one — so a wash
      // with no menu is harmless, while clearing it on a turn is what lost the highlight
      // during fast switching. It goes only on an explicit dismissal.
    },
    [hideMenu, restoreMenu, selection],
  )

  const isPlaying = useAppSelector(getIsPlaying)
  const returnPosition = useAppSelector((state) =>
    getReturnToPosition(state, book.uuid),
  )
  // While a jump waits for the audio, the native view behaves as paused, so it
  // does not schedule page turns toward where the audio still is.
  const jumpHold = useAppSelector(getJumpHold)
  const isJumpHeld = jumpHold?.bookUuid === book.uuid

  const floatingToolbar = preferences?.floatingToolbar
  // The unmodified reader has no extra top padding when the toolbar is in flow;
  // adding one here leaves a blank strip above the text. Only the floating
  // toolbar needs padding, to clear the status bar.
  const pageTopPadding = floatingToolbar ? insets.top : 0
  const readerTop = pageTopPadding + (rail || floatingToolbar ? 0 : toolbarHeight)
  const returnBarTop = rail ? pageTopPadding : toolbarHeight
  // Everything above the page's WebView: the reader's top padding, plus the
  // toolbar when it sits above the page or floats over it while shown.
  const selectionDismissTop = Math.max(
    readerTop,
    !rail && floatingToolbar && preferences?.showReaderUi ? toolbarHeight : 0,
  )
  const returnBarBottom = Math.max(
    0,
    returnBarTop + navigationReturnHeight - readerTop,
  )

  const showingFootnote = useAppSelector(
    (state) => state.bookshelf.footnoteContent !== null,
  )

  // A menu only belongs on the page it was opened on. Without this the menu was drawn
  // whenever `selection` was set, so any event that restored it while the reader had
  // moved on put it on the wrong page — the intermittent bug. The page is read through a
  // ref, so this is evaluated on each render against the page the reader is actually on.
  const menuBelongsToCurrentPage =
    selection !== null &&
    (menuPage.current === null || menuPage.current === currentPage.current)
  const showSelectionMenu = ENABLE_SELECTION_UI && menuBelongsToCurrentPage

  const router = useRouter()

  useEffect(() => {
    const listener = DeviceEventEmitter.addListener(
      "storyteller:keydown",
      (event) => {
        if (forwardNavKeyCodes.includes(event.keyCode)) {
          epubViewRef.current?.goForward()
        } else if (backwardNavKeyCodes.includes(event.keyCode)) {
          epubViewRef.current?.goBackward()
        } else if (
          event.keyCode === volumeUpKeyCode ||
          event.keyCode === volumeDownKeyCode
        ) {
          // Native has already decided this press turns a page, so the only question
          // left is which way. Swapping is a setting, so it is read at press time
          // rather than baked into the listener.
          const baseIsForward = event.keyCode === volumeDownKeyCode
          const forward = invertVolumeKeys ? !baseIsForward : baseIsForward
          if (forward) {
            epubViewRef.current?.goForward()
          } else {
            epubViewRef.current?.goBackward()
          }
        }
      },
    )

    return () => listener.remove()
  }, [invertVolumeKeys])

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
      {(rail || toolbarHeight > 0) &&
        !isReaderSheetOpen &&
        !!returnPosition &&
        !!preferences?.showReaderUi && (
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
        {/* The menu has no full-screen backdrop, so selection handles stay
            draggable. The strip above the page is not part of the WebView,
            though: the top toolbar (or, with it hidden, empty padding) took a
            tap there and the selection never closed. While a selection is
            open, a tap anywhere above the page closes it instead. */}
        {showSelectionMenu && selectionDismissTop > 0 && (
          <Pressable
            accessibilityLabel="Close selection menu"
            accessibilityRole="button"
            className="absolute top-0 right-0 left-0"
            style={{ height: selectionDismissTop }}
            onPress={closeSelection}
          />
        )}
        {showSelectionMenu && (
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
            getCurrentSelection={() =>
              epubViewRef.current?.getCurrentSelection() ?? Promise.resolve(null)
            }
            selectedText={selection.selectedText}
            existingHighlight={activeHighlight}
            dictionaryEntry={activeDictionaryEntry}
            onDictionaryEntryDeleted={handleDictionaryEntryDeleted}
            onClose={closeSelection}
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
          // Paged mode must never reserve space: a missed undo left a permanent
          // blank strip above the text. Scroll mode top-aligns a bookmarked quote,
          // so there the target needs clearance while the bar is on screen.
          jumpBarBottom={
            preferences?.scrollMode && returnPosition && preferences?.showReaderUi
              ? navigationReturnHeight
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
          activeHighlightDecorationId={activeHighlight}
          selectionMenuVisible={showSelectionMenu}
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
          // While the UI is on screen a tap only dismisses it; without this a tap in
          // the side zones turned the page instead, so the chrome stayed up and the
          // page moved behind it.
          readerUiVisible={preferences?.showReaderUi ?? false}
          // A reader sheet (footnote, menus, definition) covers the page but leaves the
          // view mounted. Without this the native gate still claims volume keys and the
          // JS listener turns pages behind the sheet instead of changing the volume.
          volumeKeysTurnPages={
            (preferences?.volumeKeysTurnPages ?? false) && !isReaderSheetOpen
          }
          invertVolumeKeys={invertVolumeKeys}
          colorTheme={{ foreground, background }}
          customFonts={customFonts?.map((font) => ({
            ...font,
            uri: getCustomFontUrl(font.filename),
          }))}
          onHighlightTap={(event) => {
            menuWashToken.current += 1
            if (menuWashTimer.current !== null) {
              clearTimeout(menuWashTimer.current)
              menuWashTimer.current = null
            }
            menuPage.current = currentPage.current
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
            // A visible UI is dismissed first, whatever else is on screen. Taps while
            // the UI is up exist only to hide it, so a live selection must not swallow
            // that tap and leave the reader stuck with the chrome showing.
            if (preferences?.showReaderUi) {
              updateGlobalPreference({ name: "showReaderUi", value: false })
              return
            }
            // A tap that ends a selection only ends it; it must not also show
            // or hide the reader UI under the menu.
            if (selection) {
              closeSelection()
              return
            }
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
            if ("restore" in event.nativeEvent) {
              // The swipe never turned a page, so the reader is still looking at this
              // text and the menu goes back where it was. The snapshot is kept: a later
              // swipe away from this page has to find it again, and dropping it here is
              // what made the menu vanish for good after one failed swipe.
              const stashed = menuPage.current
                ? menuStash.current.get(menuPage.current)
                : undefined
              if (stashed && menuPage.current) {
                restoreMenu(stashed, menuPage.current)
              }
            } else if ("swiping" in event.nativeEvent) {
              // Hidden, but kept: the swipe may be abandoned, in which case it comes
              // back. hideMenu stashes it under the page it belongs to for that reason.
              //
              // The native side emits this from the touch handler, so the work here is
              // kept to a single state write: every extra setState is a frame of the menu
              // still being on screen after the finger has moved.
              if (selection) {
                // Keyed by the page the reader is on right now, not by menuPage, which can
                // be left over from an earlier restore or a recompute that moved it. A
                // stale key is what put the menu on the wrong page.
                const key = currentPage.current
                if (key !== null) {
                  menuPage.current = key
                  menuStash.current.set(key, {
                    ...selection,
                    activeDictionaryEntryUuid,
                    washDecorationId,
                    activeHighlight,
                  })
                }
              }
              menuWashToken.current += 1
              const token = menuWashToken.current
              if (menuWashTimer.current !== null) clearTimeout(menuWashTimer.current)
              // Keep the wash through the slide, then drop the native active decoration.
              // While it is set, volume keys stay gated and the first tap is spent closing
              // a menu that is no longer on screen.
              menuWashTimer.current = setTimeout(() => {
                if (menuWashToken.current === token) {
                  setWashDecorationId(null)
                  menuWashTimer.current = null
                }
              }, 600)
              setSelection(null)
              setActiveHighlight(null)
              setActiveDictionaryEntryUuid(null)
              // The wash is deliberately NOT cleared here. It is a class on the mark's box
              // inside the page, so it travels with the text while the page slides, which
              // is the blue highlight during the animation. Nulling the prop cleared it
              // natively and it blinked out the moment the swipe began. It is cleared when
              // the gesture ends, or put back from the stash if the swipe is abandoned.
            } else if ("cleared" in event.nativeEvent) {
              // A tab on the reader rather than a page turn: the stash must not hold
              // it, or coming back to this page would reopen a menu the reader closed.
              menuWashToken.current += 1
              if (menuWashTimer.current !== null) {
                clearTimeout(menuWashTimer.current)
                menuWashTimer.current = null
              }
              setSelection(null)
              setActiveHighlight(null)
              setActiveDictionaryEntryUuid(null)
              setWashDecorationId(null)
              if (menuPage.current !== null) menuStash.current.delete(menuPage.current)
              menuPage.current = null
            } else {
              // Selecting something on another page replaces whatever was stashed
              // there. A menu belongs to the text it was opened on, so the old one must
              // not come back when this page is revisited.
              menuStash.current.clear()
              setSelection(event.nativeEvent)
              setActiveDictionaryEntryUuid(
                savedWordUuidForText(event.nativeEvent.locator.text?.highlight),
              )
              // Remember which page this menu belongs to, so a turn can hide it and a
              // turn back can restore it.
              menuPage.current = currentPage.current
            }
          }}
          onDictionaryTap={(event) => {
            const entry = dictionaryEntries?.find(
              ({ uuid }) => uuid === event.nativeEvent.uuid,
            )
            if (!entry) return

            menuWashToken.current += 1
            if (menuWashTimer.current !== null) {
              clearTimeout(menuWashTimer.current)
              menuWashTimer.current = null
            }
            setActiveHighlight(null)
            setActiveDictionaryEntryUuid(entry.uuid)
            setWashDecorationId(event.nativeEvent.decorationId)
            // Tapping a different word is a new menu, so anything stashed from a word
            // opened earlier is dropped rather than resurfacing later.
            menuStash.current.clear()
            setSelection({
              x: event.nativeEvent.x,
              y: event.nativeEvent.y,
              locator: event.nativeEvent.locator,
            })
            menuPage.current = currentPage.current
          }}
          onPageTurned={(event) => {
            handlePageTurned(
              `${event.nativeEvent.href}|${event.nativeEvent.pageIndex}`,
              event.nativeEvent.turned,
            )
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
