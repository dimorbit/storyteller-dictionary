# Warnings that only exist in development builds

Written 2026-11-05.

## The red `DomWebView.injectJavaScript` banner

In a development build, a red banner can appear at the bottom of the screen reading:

`Uncaught (in promise, id: 1) Error: Call to function 'DomWebView.injectJavaScript' has been rejected.`

It appears after the footnote sheet has been opened and closed, and then sits on every screen
until the app is restarted. It is a development-only banner. Release builds do not show it.

What causes it. The footnote is a DOM component, so Expo renders it in a webview and pushes its
props in by calling `injectJavaScript` from an effect that also runs on the first render. On
Android the native view can still be missing at that moment, the call is rejected, and nothing
in the wrapper handles that rejection. The banner is that rejection being reported.

Why the obvious workaround does not work. Rendering the footnote with `react-native-webview` by
passing `dom={{ useExpoDOMWebView: false }}` does not work. That path does not deliver the host
platform value the DOM bundle needs, so the bundle throws
`Top OS ($$EXPO_DOM_HOST_OS) is not defined` and the sheet comes up empty. That was checked on a
device on 2026-11-05: with the Expo webview the sheet renders its text, with `react-native-webview`
it renders a drag handle and nothing else. Do not repeat that change.

How it was fixed. The footnote no longer uses a DOM component at all. It renders in a plain
`react-native-webview` WebView built from generated HTML that sets the text colour, size and line
height by hand, and a small injected script reports the content height so the sheet still fits its
text. Nothing goes through the Expo DOM runtime, so nothing can reject the way it did.

Checked on a device on 2026-11-06: the sheet shows its text in the normal colour and size, it hugs
the text instead of filling the screen, paragraphs are separated, emphasis and links render, the
height is the same when the sheet is opened again, and no banner appears after closing it.

`components/BookDescription.tsx` is still a DOM component, so the banner could still appear after
that sheet has been shown. That has not been seen on a device.

The footnote sheet no longer uses a DOM component. It renders with a plain `react-native-webview` WebView in `app/(root)/footnote.tsx`, so the footnote should no longer raise this banner. That change has not been run on a device yet. `components/BookDescription.tsx` is still a DOM component and goes through the same wrapper, so it can still raise the banner.

Until then the banner is noise in development builds only. It does not affect readers.
