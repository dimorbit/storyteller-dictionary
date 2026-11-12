# Storyteller Dictionary

Storyteller Dictionary is a modified version of the Storyteller mobile app, the
Android reader for a self-hosted [Storyteller](https://gitlab.com/storyteller-platform/storyteller)
server (ebooks with synced audiobooks). It adds a built-in dictionary: long-press
a word or sentence in the book, look it up, and save it for later.

This is a personal fork. It is not the official Storyteller app and is not
supported by the Storyteller project.

## Credit

All the core reader, server connection, audio and library features come from
the upstream Storyteller mobile app. This fork builds on that work. See the
upstream project for its license and original documentation. The upstream
project carries its own license; this fork does not change it.

## What this fork adds

### Dictionary

- Long-press a word or sentence, then choose Dictionary. The app opens an
  in-app Google `define:` search for it.
- The word or selected sentence is saved.
- Saved words get a dotted underline in the text.
- You can delete a saved entry and undo the deletion.
- A Dictionary tab lists everything you saved, grouped by date.
- The book details screen shows the saved words for that book. You can jump to
  the saved place, and saved words can include a short audio clip.

### Fixes

- Read-aloud highlighting is steadier. Highlight updates now go through a
  single worker where the latest update wins, outdated clip messages are
  ignored, and the highlight id is tied to the chapter.
- Text selection is more reliable. The page no longer scrolls sideways while
  you select, the default Android selection toolbar is hidden, the custom
  selection menu is back, and Define, Copy and Highlight now use the text you
  actually selected, including across paragraphs.

### From upstream

Bookmarks, highlights, collections, series, offline reading, and reading
positions stored on the device.

## Requirements

- JDK 17. The Android build does not work with other versions.
- Android SDK, platform 36 and build tools 36.0.0.
- An Android phone or emulator with debugging enabled.
- A reachable Storyteller server.

## Build a debug version

From the project folder:

```
npx expo run:android
```

This builds the debug app, installs it on the connected device, and starts
Metro. To build the APK without installing:

```
cd android && ./gradlew assembleDebug
```

The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

The debug app uses `expo-dev-client`. It only loads JavaScript from Metro, so
Metro must be running:

```
npx expo start --dev-client
```

If Metro is not running or the phone cannot reach it, the app shows the dev
client launcher instead of the reader.

## Release build

Releases are arm64 APKs signed with the owner's personal keystore. The keystore
and its passwords are not in this repository. They are kept in
`android/keystore.properties`, which is gitignored. Without that file you can
build the debug version but cannot produce a signed release.

```
cd android && ./gradlew assembleRelease \
  -PreactNativeArchitectures=arm64-v8a \
  -Pandroid.enableMinifyInReleaseBuilds=false
```

The APK is written to `android/app/build/outputs/apk/release/app-release.apk`.

R8 shrinking is turned off for release builds because it fails in this project
for reasons unrelated to the fork. The exact release flags are also in
AGENTS.md.

## Known limitations

- Saved sentences of several words are saved but do not get the dotted
  underline. The underline only matches whole single words.
- iOS code exists but has not been tested. Only Android is tested.
- JDK 17 is required.
- Release builds do not use R8 (see above).

## For contributors

Project rules, such as never editing shipped database migrations, registering
new migrations, keeping native bridge names identical on both sides, and the
approved look of the dictionary dots and the tapped-word box, are in
[AGENTS.md](AGENTS.md). Read it before changing anything.

Current version: 2.12.0.
