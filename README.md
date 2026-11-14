# tvhome (couch-tv-android-native)

A native Android TV home screen with no WebView and no libraries, aimed at slow, low-memory TVs
(about 1.4 GB RAM). Inspired by [couch-tv](https://github.com/Seagey/couch-tv) by Seagey (MIT) and
reusing parts of its code: see `NOTICE.md`.

**Status:** just started. Today it builds a placeholder home that shows a clock.

By lukemonaghan. App id `com.lukemonaghan.tvhome`.
The plan and feature list are in [`note.md`](note.md).

## Build and run
```sh
export JAVA_HOME=/home/lukem/Documents/git/couch-tv/build/jdk
export ANDROID_HOME=/home/lukem/Documents/git/couch-tv/build/android-sdk
./gradlew assembleDebug
```
The APK is `app/build/outputs/apk/debug/app-debug.apk`. Test it on the emulator first (see `note.md`).

## Layout
- `app/` the Android app.
- `reference/upstream/` couch-tv code, scripts, docs and web UI, copied unchanged as a spec. Not compiled.
- `note.md` the plan.
