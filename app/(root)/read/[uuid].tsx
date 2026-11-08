import { useLocalSearchParams, useSegments } from "expo-router"
import { StatusBar } from "expo-status-bar"
import { useEffect } from "react"

import { Epub } from "@/components/Epub"
import { LoadingView } from "@/components/LoadingView"
import { useSpansFold } from "@/lib/pose"
import { useIsFocused } from "@/hooks/useIsFocused"
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
  // Every screen that can cover the reader dismisses back to it, and leaving
  // the reader pops this screen, so the reader stays mounted until then.
  // Gating on focus tore it down while a sheet was closing: the sheet's route
  // leaves `segments` before the focus effect runs again, which rebuilt the
  // EPUB view on every close.
  const showEpub = !!book && !!locator && isNotBackground && !isAudioLoading

  useEffect(() => {
    dispatch(bookshelfSlice.actions.bookOpened({ bookUuid: uuid, format }))
  }, [dispatch, format, uuid])

  // The reader no longer remounts when it is uncovered, so re-assert the
  // reading speed when it regains focus instead of relying on a remount.
  useEffect(() => {
    if (!isFocused) return

    dispatch(
      bookshelfSlice.actions.playbackSpeedContextChanged({
        context: format === "readaloud" && showEpub ? "reading" : "listening",
      }),
    )
  }, [dispatch, format, isFocused, showEpub])

  useEffect(
    () => () => {
      dispatch(
        bookshelfSlice.actions.playbackSpeedContextChanged({
          context: "listening",
        }),
      )
    },
    [dispatch],
  )

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
