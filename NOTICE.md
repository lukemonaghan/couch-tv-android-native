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

## What was ported (rewritten for plain Views and Canvas, logic and wording kept)

| Here | From couch-tv |
|---|---|
| `Updater.kt` | `tv-home/android/.../Updater.kt` (the page bridge removed, release address made a setting) |
| `HomeAssistant.kt`, `Doorbell.kt` | same folder, `HomeAssistant.kt`, `Doorbell.kt` (the page bridge removed) |
| `SmartHomeScreen.kt` | `tv-home/web/smarthome.js` and its CSS, drawn on a Canvas |
| `CustomCards.kt` | `tv-home/web/cards.js` |

## What is not copied
couch-tv's own `config.json`, `.env`, TV addresses, build output and any history files. Those are
private to the machine they were made on.

## Fonts and pictures
- `app/src/main/assets/fonts/antonio_light.ttf` is Antonio Light (SIL Open Font Licence 1.1), converted from the woff2 that couch-tv ships.
- `app/src/main/assets/scenes/scene-night*.{jpg,webp}` are couch-tv's Night scene, made there with an AI image model from text only
  (no source photo, not a real place), copied unchanged. More scenes will be copied here as they are used.

- Inter (static instances at weights 450, 500 and 600 cut from the variable font in couch-tv): SIL Open Font License 1.1, (c) The Inter Project Authors.

- Scene art: the five scenes (night, golden, water, fog, dawn), their cut-outs and glass copies come from couch-tv (generated for that project, free to use per its CREDITS.md).
- Classic look photos (tessellated-pavement, grose-valley, albury-storm, aresquiers-beach, vang-vieng-sunset) are from Wikimedia Commons under Creative Commons licences (CC BY-SA 2.5, 3.0, 4.0 and CC BY 4.0), as listed in couch-tv's `tv-home/web/backgrounds/CREDITS.md`: JJ Harrison, Diliff, Thennicke, Christian Ferrer and Basile Morin. The home shows each place and photographer under the clock.
- `tools/tvadb.py` is couch-tv's pure Python ADB client (MIT), unchanged.

## Music visualiser (third-party, not copied into this repository)

| What | Licence | How it is used |
|---|---|---|
| [projectM](https://github.com/projectM-visualizer/projectm), the MilkDrop player | LGPL-2.1 | Built by `tools/build-native.sh` from the OpenGL ES 2.0 fork [bbangert/projectm](https://github.com/bbangert/projectm) (branch `up-es2`, pinned commit in the script) as a separate `libprojectM-4.so` in the app. Not linked statically, so it can be swapped for another build. Its source is at the address above. |
| [presets-cream-of-the-crop](https://github.com/projectM-visualizer/presets-cream-of-the-crop) | see the repository's `LICENSE.md` | Nothing is bundled. The user picks presets in Settings and the app downloads them from GitHub (pinned commit in `PresetStore.kt`) into its own storage. `app/src/main/assets/presets/index.tsv` lists the file names and sizes only. |
| [presets-milkdrop-texture-pack](https://github.com/projectM-visualizer/presets-milkdrop-texture-pack) | see the repository | Downloaded the same way, on request. |

`app/src/main/jniLibs/` is not committed: run `tools/build-native.sh` (it needs the Android NDK). Without those libraries the app builds and runs, and the music view keeps its blurred cover.
