import { useIsFocused, useLocalSearchParams, useSegments } from "expo-router"
import { StatusBar } from "expo-status-bar"
import { useEffect } from "react"

import { Epub } from "@/components/Epub"
import { LoadingView } from "@/components/LoadingView"
import { useSpansFold } from "@/lib/pose"
import { useIsNotBackground } from "@/hooks/useIsNotBackground"
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
    segments.includes("footnote")
  const isNotBackground = useIsNotBackground()

  const isAudioLoading = useAppSelector(
    (state) => state.bookshelf.isAudioLoading,
  )
  // `useSegments` is computed during render. The local focus hook mirrored
  // focus into state from a post-commit effect, so on close the render that
  // dropped the sheet still saw `isFocused` false and the reader was rebuilt.
  // expo-router's `useIsFocused` reads focus in that same render, so the two
  // agree and the reader survives the close. Its own sheets keep it mounted;
  // any other screen that covers the reader releases it.
  const showEpub =
    !!book &&
    !!locator &&
    (isFocused || isReaderSheetOpen) &&
    isNotBackground &&
    !isAudioLoading

  useEffect(() => {
    dispatch(bookshelfSlice.actions.bookOpened({ bookUuid: uuid, format }))
  }, [dispatch, format, uuid])

  useEffect(() => {
    dispatch(
      bookshelfSlice.actions.playbackSpeedContextChanged({
        context: format === "readaloud" && showEpub ? "reading" : "listening",
      }),
    )

    return () => {
      dispatch(
        bookshelfSlice.actions.playbackSpeedContextChanged({
          context: "listening",
        }),
      )
    }
  }, [dispatch, format, showEpub])

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
