# Expo 58 defect investigation

Investigated at `4bed2da` on `chore/sync-upstream`, using `101b36b` as the
pre-merge `main` and `a6b56c3` as the fork side of the upstream merge. The
[`applications/mobile` tree](https://gitlab.com/api/v4/projects/storyteller-platform%2Fstoryteller/repository/tree?path=applications&ref=4a1ab11&per_page=100) at upstream commit
`4a1ab1153c13970c11219af348ab987e137baa13` has tree ID
`9fe5382073bc679c5e6f01d3eddee39b5142ac06`, which is also the root tree of
the local upstream split `c3e246d`. Thus the split is an exact comparison for
upstream's mobile source at that commit.

## 1. Book details: `DomWebView.injectJavaScript` rejection

**Classification: upstream Expo DOM behavior; no fork-specific cause shown.**
The rejection says Android could not resolve tag 838 to a `DomWebView`. It
does not say why the view was unavailable at that moment.

`components/BookDescription.tsx` is the only `"use dom"` component in this
checkout, and the book details screen renders it with `matchContents` after
the book loads. It is therefore the likely source on that screen, but the
reported error does not itself map the tag to that component. The file has
the same Git blob at `a6b56c3`, `101b36b`, `c3e246d`, and `4bed2da`: it is
unchanged upstream code, not a fork change introduced by the merge.

The checkout's lockfile resolves Expo to `58.0.23` and `@expo/dom-webview` to
`58.0.2`. In the [Expo 58.0.23 source archive](https://registry.npmjs.org/expo/-/expo-58.0.23.tgz),
`package/src/dom/webview-wrapper.tsx` calls `injectJavaScript` from a prop
effect and on DOM readiness without handling the returned rejection. In the
[`@expo/dom-webview` 58.0.2 source archive](https://registry.npmjs.org/@expo/dom-webview/-/dom-webview-58.0.2.tgz),
Android exposes that operation as an asynchronous function on the native view.
This explains how a missing view tag can become an unhandled rejection. It
does not prove whether this particular tag was not yet attached, had been
removed, or was lost for another reason. The device's exact installed package
versions were not checked.

The upstream header changes are not evidence of an Android remount: the
system header is enabled only on iOS, `ScreenBackButton` renders nothing on
Android, and the book stack still hides the Android header. A timing effect
from navigation or the Expo upgrade remains possible, not established.

**Left unchanged.** The plain `react-native-webview` approach used for the
footnote would avoid the Expo DOM bridge here too, but it would need to retain
the description's content-height sizing, formatting, theme, and link behavior.
Changing `useExpoDOMWebView` alone is not that fix; the existing
`docs/dev-only-warnings.md` records why it failed for the footnote. Upstream
could instead make DOM prop injection safe against a view that is not yet
available or has been removed, including handling the rejected call, or
replace its description DOM component with a plain WebView. The exact trigger
and either remedy require a running app to verify.

## 2. Reader font appears to fall back

**Classification: unresolved configuration or runtime behavior; no fork or
upstream regression established.** A serif appearance alone does not identify
which font was rendered.

The `getCustomFontUrl` implementation is identical at `a6b56c3`, `101b36b`,
`c3e246d`, and `4bed2da`. The Android `applyCustomFonts`, CSS-building, and
`injectCustomFonts` functions are also identical across those revisions, as
are the relevant calls on font changes and page load. Imported files are read
from the app documents directory and embedded as Base64 `@font-face` rules.
The locked `expo-file-system` 58.0.13 source still exports the legacy
`documentDirectory`, and its Android legacy module supplies a files-directory
URI. That supports the continued availability of the API, not proof that a
particular file was present or loaded on the device.

The distinction between built-in and imported fonts matters:

- `app.config.ts` configures `expo-font` with YoungSerif, OpenDyslexic, and
  Literata; there is no separate custom-font plugin in `plugins/`. The same
  font list existed before the merge. The tracked Android Literata asset is
  identical on `main` and this branch.
- `database/preferencesTypes.ts` defaults to **Literata** and an empty
  imported `customFonts` list. Android Readium registers and serves Literata
  in `EpubFragment.kt`, with publisher styles disabled by default. Readium
  3.1.2 also supplies OpenDyslexic itself, as its
  [font guide](https://readium.org/kotlin-toolkit/3.1.2/guides/navigator/epub-fonts/)
  explains. Literata is a serif font, so the appearance alone is not proof of
  fallback to the book's CSS.
- The repository contains Bookerly files under `assets/fonts`, but they are
  not in the Expo font list or the default font selection. The legacy
  preferences migration maps an old global `Bookerly` choice to `Literata`. A
  user may still import a font through `FontLoader`; imported files and
  preferences are device data, not something the source tree can establish.
  Startup can remove failed imports and reset the selected family to Literata.

**Left unchanged.** No break in the injection code or Expo 58 font loading is
demonstrated by this comparison. Nor can we say that this device had no custom
font configured: the default is empty, but saved preferences and imported files
can differ. Identifying the actual cause requires the affected book, the
device's selected global/book font and imported files, and evidence of the
font that the WebView loaded. If those show a library or asset-loading failure,
upstream would need to correct the relevant font registration, asset serving,
or runtime loading path; if no font was imported or selected, there is no
custom-font regression to repair.

## 3. Saved-word jump is one screen too low

**Classification: unconfirmed; a fork/upstream scroll interaction is a
plausible cause.** The source establishes competing positioning requests, not
which request produced the reported offset.

`Dictionary.tsx` dispatches `bookmarkPressed` with the saved locator. The
listener in `store/listeners/syncListeners.ts` saves that locator and seeks
the matching audio clip when applicable; `app/(root)/read/[uuid].tsx` and
`components/Epub.tsx` pass the resulting position to the native reader. The
dictionary, sync listener, bookshelf hold, and return-control files are
identical to `101b36b`. The hold prevents stale audio updates from moving the
reader and temporarily tells the native view playback is paused; it does not
calculate a screen offset. The return bar is absolutely positioned in
`components/Epub.tsx`, outside the reader's flex layout. That supports the
reported zero-pixel layout shift, though the bar can cover part of the page.

One concrete interaction merits checking in **scroll mode**:

1. Fork commit `bf9f87d` added `locatorWithClip`: when a clip can be found, a
   selected word retains its text locator but gains an audio clip ID in
   `locations.fragments`. Upstream `c3e246d` does not add that clip ID to the
   selection.
2. The shared `EpubView.go` calls Readium's `navigator.go` and separately
   launches `scrollFragmentIntoView`. The latter centers the whole element for
   the first fragment ID, not the selected word. The fragment-centering logic
   already existed before the merge and is also present upstream.
3. Readium 3.1.2's
   [`R2EpubPageFragment`](https://github.com/readium/kotlin-toolkit/blob/3.1.2/readium/navigator/src/main/java/org/readium/r2/navigator/pager/R2EpubPageFragment.kt)
   tries a text highlight first, then an HTML ID, then progression. The
   separate fragment scroll is not synchronized with completion of that
   locator navigation. If it wins and the clip element is sufficiently tall
   or misplaced, it can move the selected text out of view. We do not have the
   actual locator, clip-element geometry, scroll mode, or timing from the
   reported run, so this is not a proven explanation of the one-screen offset.

The Android `EpubView.kt`, `EpubFragment.kt`, native module, and JS Readium
bridge are byte-identical between `101b36b` and `4bed2da`; both use Readium
3.1.2. The same jump code was therefore already on `main`, although Expo/RN
and some reader layout geometry changed: for example, the non-floating reader
now has 24 logical pixels of top padding. Identical runtime behavior is not
guaranteed. The earlier emulator note in `docs/dictionary-known-gaps.md`
records the same "impressively muscled" passage at the top after a jump from
the **book details screen**. That check predates the later jump-hold/overlay
commits and is not a test of the current Dictionary-tab path. It would be
wrong to claim either that the offset always existed or that Expo 58 caused it.

**Left unchanged.** If the competing scrolls are confirmed as the cause,
upstream's shared scroll behavior would need to preserve a precise text target
and coordinate it with any clip-centered scrolling; the fork must still retain
the clip ID for audio seeking. Simply disabling all fragment scrolling would
also disable the active-playback follow behavior. Compare the affected saved
locator and book on `main` and this branch, in scroll mode with playback both
paused and playing, before choosing a fix.

## Verification limits

This investigation used source, Git history, the locked package sources, and
the versioned Readium documentation. No packages were installed; no Gradle,
typecheck, lint, emulator, or device run was performed. `node_modules` is absent
here, so the repository's Vitest suite was not available without installation.
The observed font, view lifecycle, jump geometry, and any proposed remedy have
not been verified in a running app.
