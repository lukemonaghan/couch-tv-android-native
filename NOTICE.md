# Attribution

This project is inspired by, and reuses code from, **couch-tv** by Seagey
(https://github.com/Seagey/couch-tv), released under the MIT licence. The original copyright and
permission notice is kept in `LICENSE`, as the licence requires.

## What was copied, verbatim, from couch-tv (at the commit this repo was started from)

| Here | From couch-tv |
|---|---|
| `app/src/main/java/com/lukemonaghan/tvhome/HomeRole.kt` | `tv-home/android/app/src/main/java/com/lukemonaghan/tvhome/HomeRole.kt` |
| `app/src/main/java/com/lukemonaghan/tvhome/TclPlaceholderInput.kt` | same folder, `TclPlaceholderInput.kt` |
| `app/src/main/res/` (icons, strings, styles, accessibility service xml) | `tv-home/android/app/src/main/res/` |
| `gradlew`, `gradlew.bat`, `gradle/`, `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties` | `tv-home/android/` |
| `reference/upstream/` | Kotlin, install scripts, docs and the web UI, copied unchanged as a spec to port from. **Not compiled.** |

Both copied classes were moved to the package `com.lukemonaghan.tvhome` and their comments reworded.
Other changes made after copying are in this repository's git history.

## What is not copied
couch-tv's own `config.json`, `.env`, TV addresses, build output and any history files. Those are
private to the machine they were made on.

## Fonts and pictures
- `app/src/main/assets/fonts/antonio_light.ttf` is Antonio Light (SIL Open Font Licence 1.1), converted from the woff2 that couch-tv ships.
- `app/src/main/assets/scenes/scene-night*.{jpg,webp}` are couch-tv's Night scene, made there with an AI image model from text only
  (no source photo, not a real place), copied unchanged. More scenes will be copied here as they are used.

- Inter (static instances at weights 450, 500 and 600 cut from the variable font in couch-tv): SIL Open Font License 1.1, (c) The Inter Project Authors.

- Scene art: the five scenes (night, golden, water, fog, dawn), their cut-outs and glass copies come from couch-tv (generated for that project, free to use per its CREDITS.md).
