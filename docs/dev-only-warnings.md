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

Why it is not fixed yet. The obvious workaround, rendering the footnote with
`react-native-webview` by passing `dom={{ useExpoDOMWebView: false }}`, does not work. That path
does not deliver the host platform value the DOM bundle needs, so the bundle throws
`Top OS ($$EXPO_DOM_HOST_OS) is not defined` and the sheet comes up empty. That was checked on a
device on 2026-11-05: with the Expo webview the sheet renders its text, with `react-native-webview`
it renders a drag handle and nothing else. Do not repeat that change.

What would actually fix it. Either wait for the upstream fix in `@expo/dom-webview`, which is to
hold the first props emit until the native view reports that it is ready, or stop using a DOM
component for the footnote. Catching it in app code is not possible, because the rejected promise
is created inside the wrapper.

Until then the banner is noise in development builds only. It does not affect readers.
