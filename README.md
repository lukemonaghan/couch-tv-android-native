# tvhome (couch-tv-android-native)

A native Android TV home screen with no WebView and no libraries, aimed at slow, low-memory TVs
(about 1.4 GB RAM). Inspired by [couch-tv](https://github.com/Seagey/couch-tv) by Seagey (MIT) and
reusing parts of its code: see `NOTICE.md`.

**Status:** working. It runs as the home screen on two real Android 11 TVs with 1.4 GB of RAM (about 45 MB for the app).
What it does: a clock and scenes, Keep watching and Now playing cards, a dock, More apps, Home settings, people and kids mode,
Smart Idle, Home Assistant cards and a doorbell, and a full-screen music view with synced lyrics and an optional MilkDrop visualiser.
See `STATUS.md` for the details and `note.md` for the plan.

By lukemonaghan. App id `com.lukemonaghan.tvhome`. Android 6 (API 23) and up.

## Build and run
You need a JDK 17 and the Android SDK (`JAVA_HOME`, `ANDROID_HOME`).
```sh
./gradlew assembleDebug
```
The APK is `app/build/outputs/apk/debug/app-debug.apk`. Try it on an emulator first, then measure memory on a real TV
(`dumpsys meminfo com.lukemonaghan.tvhome`). `tools/install.sh` and `tools/undo.sh` set the app up as the home on a TV and put the old one back.
Read `reference/upstream/AGENTS.md` and `CLAUDE.md` before changing a real TV.

### Music visualiser (optional)
The visualiser uses projectM (LGPL-2.1). Its libraries are not in this repository. Build them with the Android NDK:
```sh
ANDROID_HOME=/path/to/sdk sh tools/build-native.sh        # armeabi-v7a, arm64-v8a and x86
```
Without them the app builds and runs, and the music view keeps its blurred cover. No presets are bundled: pick and download them in
Settings, Music visualiser. They are MilkDrop presets from the projectM project, each by its own author.

## Licence
MIT, see `LICENSE`. Third-party code, fonts and pictures, and what the LGPL means for projectM, are in `NOTICE.md`; licence texts are in `licenses/`.

## Layout
- `app/` the Android app.
- `reference/upstream/` couch-tv code, scripts, docs and web UI, copied unchanged as a spec. Not compiled.
- `note.md` the plan.
