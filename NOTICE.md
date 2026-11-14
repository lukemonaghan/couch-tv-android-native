# Attribution

This project is inspired by, and reuses code from, **couch-tv** by Seagey
(https://github.com/Seagey/couch-tv), released under the MIT licence. The original copyright and
permission notice is kept in `LICENSE`, as the licence requires.

## What was copied, verbatim, from couch-tv (at the commit this repo was started from)

| Here | From couch-tv |
|---|---|
| `app/src/main/java/app/couchtv/launcher/HomeRole.kt` | `tv-home/android/app/src/main/java/app/couchtv/launcher/HomeRole.kt` |
| `app/src/main/java/app/couchtv/launcher/TclPlaceholderInput.kt` | same folder, `TclPlaceholderInput.kt` |
| `app/src/main/res/` (icons, strings, styles, accessibility service xml) | `tv-home/android/app/src/main/res/` |
| `gradlew`, `gradlew.bat`, `gradle/`, `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties` | `tv-home/android/` |
| `reference/upstream/` | Kotlin, install scripts, docs and the web UI, copied unchanged as a spec to port from. **Not compiled.** |

Changes made after copying are in this repository's git history.

## What is not copied
couch-tv's own `config.json`, `.env`, TV addresses, build output and any history files. Those are
private to the machine they were made on.
