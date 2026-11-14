# couch-tv-android-native

A native Android TV home screen, inspired by and reusing MIT code from couch-tv
(https://github.com/Seagey/couch-tv). Android TV and Google TV only. Built with Claude Code.
Read `note.md` first: it has the feature list, the plan and the pitfalls.

## Rules
- Lightweight first. Kotlin, plain Android Views and Canvas. **No Compose, no AndroidX UI libraries, no
  image or network libraries** unless the user agrees and the memory cost is measured. The target TVs
  have about 1.4 GB RAM, a 32-bit ARM chip and 250 to 450 MB free.
- `reference/upstream/` is a read-only spec copied from couch-tv. Port from it, never compile it, never edit it.
- Keep `NOTICE.md` and `LICENSE` accurate when copying more code from upstream.
- minSdk 23, targetSdk 34. Anything newer than API 23 needs a fallback.
- Never disable a TV's own home or setup app. It boot-loops some Google TVs.
- Test on the emulator first (`note.md`), then measure memory on a real TV with `dumpsys meminfo`.
- Do not change a real TV without the user's clear yes. Their TVs and the undo steps live in the
  original couch-tv repo's `.kogan-tv.history` and `.bedroom-tv.history`, not here.
- Never commit `config.json`, `.env`, keystores, TV addresses or `*.history`.

## Build
JDK 21 and the Android SDK (platform 34, build-tools 34.0.0) already exist in the sibling repo:
`export JAVA_HOME=/home/lukem/Documents/git/couch-tv/build/jdk ANDROID_HOME=/home/lukem/Documents/git/couch-tv/build/android-sdk`
then `./gradlew assembleDebug`.
