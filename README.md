# Storyteller mobile

Storyteller mobile is the Android reader client for a self hosted Storyteller
server. It is an Expo and React Native application that connects to your server
over its API, lets you browse and download your library, and supports offline
reading and audio playback with local storage for reading positions, bookmarks,
highlights, collections, series and dictionary lookups.

## Requirements

- JDK 17. The Android Gradle build does not work with other JDK versions.
- Android SDK, platform 36 and build tools 36.0.0.
- An Android device or emulator with USB or network debugging enabled.
- A reachable Storyteller server.

## Android debug build

From the project root:

```
npx expo run:android
```

That compiles the debug variant, installs it on the connected device and starts
Metro. To build the APK only, without installing:

```
cd android && ./gradlew assembleDebug
```

The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

## Development client and Metro

The app uses `expo-dev-client`. The installed app is a shell that loads
JavaScript from a Metro bundler, so Metro must be running for the app to start.
Run it with:

```
npx expo start --dev-client
```

and then open the app, which connects to Metro. If Metro is not running, or the
device cannot reach it on the network, the app shows the dev client launcher
instead of the reader.
