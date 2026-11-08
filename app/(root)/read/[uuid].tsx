import { useLocalSearchParams, useSegments } from "expo-router"
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
  // The reader is only ever covered by the sheets this screen pushes on top of
  // itself, and leaving it pops the screen. Gating on focus tore the reader
  // down while a sheet was closing, because the sheet's route disappears before
  // focus returns, which rebuilt the EPUB view on every close.
  const showEpub = !!book && !!locator && isNotBackground && !isAudioLoading

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
