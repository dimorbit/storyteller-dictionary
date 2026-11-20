import { useIsFocused, useLocalSearchParams, useSegments } from "expo-router"
import { StatusBar } from "expo-status-bar"
import { useEffect, useState } from "react"
import { AppState } from "react-native"

import { Epub } from "@/components/Epub"
import { LoadingView } from "@/components/LoadingView"
import { useSpansFold } from "@/lib/pose"
import { useAppDispatch, useAppSelector } from "@/store/appState"
import { useGetBookQuery } from "@/store/localApi"
import { bookshelfSlice } from "@/store/slices/bookshelfSlice"
import { type UUID } from "@/uuid"

export default function BookScreen() {
  const { uuid, format } = useLocalSearchParams() as {
    uuid: UUID
    format: "readaloud" | "ebook"
  }

  const dispatch = useAppDispatch()

  const { data: book } = useGetBookQuery({ uuid })
  const locator = book?.position?.locator

  const isFocused = useIsFocused()
  const segments = useSegments()
  const isReaderSheetOpen =
    segments.includes("navigation-menu") ||
    segments.includes("speed-menu") ||
    segments.includes("reading-settings") ||
    segments.includes("footnote") ||
    // Define opens a modal WebView over the reader. It has to count as a sheet of
    // this screen, or the reader behind it is released: coming back then re-opened
    // the book, which is the reload and the late dots. It is the same kind of brief
    // overlay as the sheets above, so it belongs in the same list.
    segments.includes("dictionary-search")

  const isAudioLoading = useAppSelector(
    (state) => state.bookshelf.isAudioLoading,
  )

  // The reader is deliberately NOT released when the app goes to the background.
  // Unmounting it tore down the whole Readium view, so coming back re-opened the
  // book: that is the reload, the flash of a stale page before the right one, and
  // the dictionary dots arriving a moment late. A screen-off or the definition
  // browser is a brief absence and the reader is idle while hidden, so there is
  // nothing to release for it. Only leaving this screen releases the view.
  //
  // `useSegments` is computed during render. The local focus hook mirrored
  // focus into state from a post-commit effect, so on close the render that
  // dropped the sheet still saw `isFocused` false and the reader was rebuilt.
  // expo-router's `useIsFocused` reads focus in that same render, so the two
  // agree and the reader survives the close. Its own sheets keep it mounted;
  // any other screen that covers the reader releases it.
  const showEpub =
    !!book && !!locator && (isFocused || isReaderSheetOpen) && !isAudioLoading

  // The reader now stays mounted in the background, so focus alone can no longer
  // decide the playback-speed context. Locking the screen must still switch a
  // readaloud book from the Readaloud speed to the Listening speed.
  const [appActive, setAppActive] = useState(AppState.currentState === "active")
  useEffect(() => {
    const subscription = AppState.addEventListener("change", (state) => {
      setAppActive(state === "active")
    })
    return () => subscription.remove()
  }, [])

  useEffect(() => {
    dispatch(bookshelfSlice.actions.bookOpened({ bookUuid: uuid, format }))
  }, [dispatch, format, uuid])

  useEffect(() => {
    dispatch(
      bookshelfSlice.actions.playbackSpeedContextChanged({
        context:
          format === "readaloud" && showEpub && appActive
            ? "reading"
            : "listening",
      }),
    )

    return () => {
      dispatch(
        bookshelfSlice.actions.playbackSpeedContextChanged({
          context: "listening",
        }),
      )
    }
  }, [dispatch, format, showEpub, appActive])

  const spansFold = useSpansFold()

  return (
    <>
      <StatusBar
        verticalBarBehaviorIOS={spansFold ? "disabled" : "automatic"}
      />
      {!showEpub || !book || !locator ? (
        <LoadingView />
      ) : (
        <Epub
          key={book.uuid}
          format={format}
          book={book}
          locator={locator}
          isReaderSheetOpen={isReaderSheetOpen}
        />
      )}
    </>
  )
}
