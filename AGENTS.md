# Rules for agents and reviewers

This repository is an Android e-reader. Expo and React Native for the app shell, a Kotlin
module for the Readium navigator, and a SQLite database layer using Kysely migrations.

The owner is not a developer. Prefer small, plain changes over clever ones, and explain what
you changed in plain words.

## Database

- Never edit a migration that has already shipped. Add a new one.
- Every new migration must be registered in `migrations/migrations.ts`. An unregistered
  migration silently never runs.
- Migrations run against databases that already hold a user's books, bookmarks and saved
  words. They must work on a populated database, not only on a fresh install.
- A rebuild of a table must keep its triggers and indexes, and must turn foreign keys back on
  if it turned them off.

## Reader and dictionary

- The dotted underline under saved dictionary words is a deliberate design and was approved by
  the owner. Do not change its dot size, pitch, colour or position without explicit approval.
- The blue box behind a tapped word is meant to look like the Android text selection. Do not
  change its colour.
- Do not reword user visible text without approval.

## Native module

- `modules/readium/` is a Kotlin Expo module behind a JavaScript bridge. Renaming a method,
  prop or event on one side without the other breaks the app at runtime, not at build time.
- Readium APIs used here are marked experimental and are pinned to version 3.1.2.

## Build

- JDK 17 is required. JDK 25 breaks the prefab step.
- Debug build for the emulator:
  `cd android && ./gradlew assembleDebug -PreactNativeArchitectures=x86_64`
- Release build for a phone: same with `-PreactNativeArchitectures=arm64-v8a` and
  `-Pandroid.enableMinifyInReleaseBuilds=false`, because R8 fails in this repository for
  unrelated reasons.
- The app is an Expo dev client. A debug build needs Metro running on port 8081.

## Known trap

`node_modules/@op-engineering/op-sqlite/android/build.gradle` must list
`abiFilters "arm64-v8a", "x86_64"`. Without it an arm64 build ships without
`libop-sqlite.so` and the app crashes on launch. `npm install` reverts this edit. It is not yet
carried by `patch-package`, so check it after any dependency install.
