import { useWindowDimensions } from "react-native"
import { useSafeAreaInsets } from "react-native-safe-area-context"

export const readerFormSheetTabBarHeight = 58
export const readerFormSheetGrabberHeight = 24
export const readerFormSheetBottomPadding = 16
const readerFormSheetHeightRatio = 2 / 3

export function useReaderFormSheetHeight() {
  const { height } = useWindowDimensions()
  const insets = useSafeAreaInsets()

  return Math.max(
    240,
    Math.round(height * readerFormSheetHeightRatio) + insets.bottom,
  )
}

export function useReaderFormSheetBottomInset() {
  return useSafeAreaInsets().bottom
}

export function useReaderFormSheetScrollPaddingBottom() {
  const insets = useSafeAreaInsets()

  return Math.max(readerFormSheetBottomPadding, insets.bottom + 16)
}
