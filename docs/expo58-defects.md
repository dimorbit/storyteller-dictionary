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

**Classification: ours (fork).** The fork added an audio clip ID to the saved
word's fragments, causing scroll navigation to center the clip rather than the
word.

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

In **scroll mode**, the cause is:

1. Fork commit `bf9f87d` added `locatorWithClip`: when a clip can be found, a
   selected word retains its text locator but its fragments are replaced with
   the audio clip ID. Upstream `c3e246d` does not add that clip ID to the
   selection. `BookService.getClip` uses the ID to find the matching audio.
2. The shared `EpubView.go` calls Readium's `navigator.go` and separately
   launches `scrollFragmentIntoView`. The latter centers the whole element for
   the first fragment ID, not the selected word. The fragment-centering logic
   already existed before the merge and is also present upstream.
3. Readium 3.1.2's
   [`R2EpubPageFragment`](https://github.com/readium/kotlin-toolkit/blob/3.1.2/readium/navigator/src/main/java/org/readium/r2/navigator/pager/R2EpubPageFragment.kt)
   tries a text highlight first, then an HTML ID, then progression. The
   separate fragment scroll can override that position by centering the clip
   block, pushing the word above the screen and explaining the reported offset.

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

**Fixed in the fork.** New selections keep their original fragments and store
the clip ID in `otherLocations` (`locations.storytellerClipId` in JSON).
`BookService.getClip` reads that ID first and falls back to the first fragment.
At navigation time, older saved locators without clip metadata, but with a text
quote and a first fragment matching a clip in the same resource, are copied
with that fragment moved into the metadata; stored words are not migrated.
Genuine fragments and active-playback centering are left unchanged. Old and new
saved-word jumps, audio seeking, and playback follow still need a device check.

The Android scroll-mode jump now leaves room for the return bar: React passes
a 48-logical-pixel inset to the reader, which re-aligns the target and scrolls
back by that amount after Readium reports the destination. The overlay still
does not resize the page, and active-playback centering is untouched. At the
start of a resource, scrolling may clamp before the full inset is available;
the landing needs a device check. When a reader sheet is open, only the reader
overlay is hidden; the navigation sheet keeps its own control and the inset
keeps its extra 8 logical pixels of breathing room.

The return bar's inset follows the layout that places it. In the in-flow
toolbar layout the bar sits 24 logical pixels above the page, so the 48 already
clears it and is unchanged. With a floating toolbar, the opaque toolbar
covers the page from `insets.top` to `toolbarHeight`, so the inset adds
`toolbarHeight - insets.top` (never below zero). In rail mode there is no
horizontal toolbar: the bar is pinned to the page's top edge (`insets.top`
when floating, 24 otherwise) and kept inside the side insets, clear of the
rail, so the 48 clears it too. The rail relies on `useVerticalBarEdgeIOS`,
described for iOS devices such as iPhone Duo, and the native inset is
Android-only (the iOS `jumpTopInset` prop is a no-op), so an iOS saved-word
jump is not yet cleared of the bar. Device checks: in-flow matches the
earlier check; floating lands the word below the toolbar and bar; rail shows
the bar at the top, clear of the rail buttons, and hides it under a reader
sheet.

The saved-word jump reads the inset when Readium reports the destination page,
not when the jump starts. The return place and toolbar measurement can change
around the jump, so a value taken at jump time may be zero or stale, and the old
equality check then dropped the scroll-back.

## Footnote sheet keeps the reader covered after it closes

- **Fork cause identified:** the merge (`2413a03`) kept the fork's plain WebView `app/(root)/footnote.tsx` (`66480e9` and later), which never dispatches `footnoteClosed`. The fork's only dispatch was a `beforeRemove` listener on the footnote screen in `app/(root)/_layout.tsx`. The merge resolved that file to upstream's version, which has no footnote listener, so nothing dispatched the close.
- Effect: `footnoteContent` stays set, so the full-screen overlay in `components/Epub.tsx` keeps covering the reader, and each tap runs `router.dismiss()`.
- Restoring the `_layout.tsx` listener would not help: expo-router 58.0.30 does not emit `beforeRemove`. Its navigation core emits `removePrevented` and `removed`.
- **Fixed in `footnote.tsx`:** the screen dispatches `footnoteClosed` from a `removed` listener, as upstream `c3e246d` does. `removed` fires after any removal (swipe, tap outside, back, navigating away) and not on mount. Keep the dispatch in `footnote.tsx`; do not add a second one in `_layout.tsx`.
- Not run on a device yet.

## Verification limits

This investigation used source, Git history, the locked package sources, and
the versioned Readium documentation. No packages were installed; no Gradle,
typecheck, lint, emulator, or device run was performed. `node_modules` is absent
here, so the repository's Vitest suite was not available without installation.
The user confirmed the earlier saved-word landing and audio seek on an
emulator. The return-bar inset, observed font, and view lifecycle have not
been verified in a running app here.
