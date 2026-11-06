import { useMemo } from "react"
import { View } from "react-native"
import { WebView } from "react-native-webview"

import {
  readerFormSheetBottomPadding,
  readerFormSheetGrabberHeight,
  useReaderFormSheetBottomInset,
  useReaderFormSheetHeight,
} from "@/components/toolbarItems/readerFormSheetLayout"
import { PortalHost } from "@/components/ui/portal-context"
import { useColorTheme } from "@/hooks/useColorTheme"
import { useAppSelector } from "@/store/appState"

export default function FootnoteScreen() {
  const { foreground, background } = useColorTheme()
  const footnoteContent = useAppSelector(
    (state) => state.bookshelf.footnoteContent,
  )
  const height = useReaderFormSheetHeight()
  const bottomInset = useReaderFormSheetBottomInset()
  const scrollHeight =
    height -
    readerFormSheetGrabberHeight -
    readerFormSheetBottomPadding -
    bottomInset
  const source = useMemo(
    () => ({
      html: footnoteDocument(footnoteContent ?? "", foreground, background),
    }),
    [footnoteContent, foreground, background],
  )

  if (!footnoteContent) return null

  return (
    <View
      className="bg-background overflow-hidden"
      style={{ height, marginBottom: -bottomInset }}
    >
      <View
        className="items-center justify-center"
        style={{ height: readerFormSheetGrabberHeight }}
      >
        <View className="bg-muted-foreground/40 h-1 w-12 rounded-full" />
      </View>
      <PortalHost>
        <WebView
          // Static HTML only loads with the wildcard whitelist.
          originWhitelist={["*"]}
          source={source}
          javaScriptEnabled={false}
          style={{ height: scrollHeight, backgroundColor: background }}
        />
      </PortalHost>
    </View>
  )
}

// Same reset and text sizes the DOM version got from Tailwind.
function footnoteDocument(html: string, color: string, background: string) {
  return `<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
  html, body { margin: 0; background: ${background}; }
  body {
    padding: 0 1.5rem 5rem;
    font-family: ui-sans-serif, system-ui, sans-serif, "Apple Color Emoji", "Segoe UI Emoji", "Segoe UI Symbol", "Noto Color Emoji";
    font-size: 0.875rem;
    line-height: 1.625;
  }
  * { margin: 0; padding: 0; color: ${color} !important; }
  a { text-decoration: inherit; }
  h1, h2, h3, h4, h5, h6 { font-size: inherit; font-weight: inherit; }
  ol, ul { list-style: none; }
  img { display: block; max-width: 100%; height: auto; }
</style>
</head>
<body>${html}</body>
</html>`
}
