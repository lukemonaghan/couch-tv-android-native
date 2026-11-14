# Rebuilding the couch-tv home as a native Android app

Written 2026-11-14 after setting up two slow Android TVs (a Haier Kogan SmarterTV and a Hitachi, both
Android 11, 32-bit ARM, ~1.4 GB RAM). Scope: **Android TV and Google TV only, option A** (no bridge, no
computer, no phone remote). Goal: the same features as the current home, as lightweight as possible.

Nothing here has been built or measured. Where a number appears it is either read from this repo or
from the TVs, and it says which. Everything else is a hypothesis to test.

## Why bother

Today the home is a native shell (`MainActivity.kt`) holding one `WebView` that runs ~15,000 lines of
HTML, CSS and JS from `tv-home/web/`. The page is bundled in the APK (`assets/www/tv`) and loaded as
`http://<offline host>/tv/index.html?offline=1`. A small `TVOS` JavaScript interface lets the page open
apps, read Watch Next, set the sleep timer and so on.

Measured on the Kogan (1.4 GB RAM, ~250 MB free): the couch-tv process held about 218 MB, before
counting the shared WebView renderer. On the Hitachi the stock native home held about 249 MB, so a
native home is not free either. A browser engine is the cost we can remove. Hypothesis: a native home
that draws bitmaps and text with the Canvas can run in a few tens of MB. Verify with
`dumpsys meminfo com.lukemonaghan.tvhome` before and after.

What is already native and should be kept almost as is: `FireWatch.kt` (Home button watch),
`KeyWatch.kt`, `HomeRole.kt`, `CrashGuard.kt`, `Updater.kt`, `Doorbell.kt`, `HomeAssistant.kt`,
`TclPlaceholderInput.kt`. That is about 1,600 of the 3,272 Kotlin lines.

## Size of the job

| Part | Today | Native plan |
|---|---|---|
| Web UI: `app.js` 4,865, `app.css` 1,932, `index.html` 165, plus kids, smarthome, soundscape, ambient-weather, clearicon, cards, vega, spotify, mock | ~9,000 lines JS/CSS | **Rewrite** as Kotlin views |
| `i18n.js` | 5,792 lines, 13 languages | Move to `res/values-xx/strings.xml` |
| `MainActivity.kt` | 1,665 lines, mostly the JS bridge | Becomes the UI host, bridge methods become plain calls |
| Other Kotlin | ~1,600 lines | Keep |
| Background photos | 21 MB in `tv-home/web/backgrounds` (APK is 21.9 MB) | Cut by about half (see Assets) |

`vega.js` and `spotify-player.js` are for other platforms or option B and can be dropped.

## Feature list (option A, what must still work)

**Home screen**
- Becomes the Android home (HOME and LEANBACK_LAUNCHER intent filters), `singleTask`, landscape,
  excluded from recents.
- Big clock (12h or 24h, locale default), the date, and weather for the configured place.
- Five Night scenes that take turns (`night`, `golden`, `water`, `fog`, `dawn`), each with a photo, a
  frosted copy for the clock, and a foreground layer (the skyline overlaps the clock). 5 s or more per
  scene. Own scenes (`my_scenes`) with clock position, size, font, date, light or dark.
- Looks: Night, OLED (Classic grid on pure black, with a 1 to 2 px nudge once a minute), Classic
  (photo behind the app grid, changing every 90 s, optionally `keep_photo`).
- Options: sun sync (scene from the sun's height), glass panels (on, lite, off), glass app icons,
  glass amount 1 to 5, focused app colour or glass, clock style frosted or liquid, moving tiles,
  lighter mode, hi res, ambient weather effects, sunlight colour grading, video background (looping,
  muted MP4 or MOV by URL), idle fade of rows (`idle_photo_seconds`), bedtime dim, nav sounds.
- Smart Idle (OLED protection and idle power-off).

**Apps**
- Dock of up to 10 apps, Settings, More apps, full apps view on Down. Every app with a Leanback
  entry, plus the Play Store or Appstore tile.
- Hold OK on an app: Move, Hide from home, Uninstall (Android's dialog), App info. Order is saved.
  New apps go to the end of the dock, or first in More apps with a blue dot until opened once.
- `hide_packages`, and pinned input tiles (HDMI or console tiles) with optional picture.

**Keep watching**
- Android's Watch Next list (`READ_TV_LISTINGS`), up to 10 cards, newest first, per-app cap
  (`watch_next_per_app`), `skip_watch_next`, dismiss a card, open a card's deep link in its app.
  Hide the row with `keep_watching: false`.
- Custom cards (`custom_cards`): up to 8 display-only cards from a JSON file or URL, refreshed every
  10 minutes, bad file keeps the last good cards.

**People and kids**
- One to many people. One person: no switcher. Two: a toggle. Three or more: a head icon and list.
  Add, rename, remove and reorder. Kid and guest flags.
- Kids mode: a home with only chosen shows and apps, locked by a 4 to 8 digit parent PIN.

**Quick buttons** (option A only): sleep timer (15, 30, 60, 90 min, with the turn-off at the end),
look, input, screensaver, soundscape, screen off, TV settings. `picture`, `headphones`, `bluetooth`,
`network` need the bridge or the on-TV app and are out of scope.
- Input menu: the TV's inputs by their own names (passthrough channels), CEC devices, switch with OK,
  then Bluetooth and Network rows.

**Home settings** (the gear tile, or the remote's settings button): language, location (type a town,
pick from Open-Meteo's results), weather on or off, units, refresh every 15, 30 or 60 min, look and
scenes (order, seconds, clock), glass and video, own photos (up to 50, from a picker or a web
address), apps, people, quick buttons, updates, about and Reset. Settings win over `config.json`, key
by key.

**Extras**
- Smart home through Home Assistant (REST): up to 8 climate rooms, up to 10 scene or script tiles,
  and the doorbell card (a camera still every 2 s, over other apps via `SYSTEM_ALERT_WINDOW`).
- Updates from GitHub releases (weekly check if opted in, or on demand), handed to Android's installer.
- 13 languages including Hebrew with full RTL.
- Soundscape (rain, fireplace, ocean, brown noise) and the sleep fade.
- First run: ask once for "Make couch-tv your home screen?" and "Tell you when a new version is out?".

**Out of scope for option A, as today:** Now playing card (needs B or C), the phone remote, the
Claude assistant, picture mode, screens, Wake-on-LAN, other TV brands.

## How to rebuild it natively

### Principles (to stay light)
- Kotlin, **no Compose, no AndroidX UI libraries**. The app already ships with no dependencies at all
  (`build.gradle.kts` has none), and that is the baseline to keep. Compose and its runtime are a large
  cost on a 1.4 GB, 32-bit TV, and Leanback is deprecated.
- One Activity. One custom `View` per screen, drawn on the Canvas, with a tiny focus engine. Avoid
  deep view hierarchies and RecyclerView adapters for a row of at most 10 tiles. Use a plain
  `HorizontalScrollView`/custom row only where a list can really be long (More apps, settings).
- Bitmaps: `BitmapFactory` with `inSampleSize`, `RGB_565` where alpha is not needed, an `LruCache`
  sized to the free memory, and decode off the main thread. No Glide, Coil or Picasso.
- Network: `HttpURLConnection` with a small thread pool, `org.json` for JSON. No Retrofit, OkHttp or
  Moshi. Only three hosts are needed: Open-Meteo (forecast and geocoding), GitHub releases, and the
  user's Home Assistant.
- Video: `MediaPlayer` on a `TextureView`, not ExoPlayer, and only when the video background is on.
- minSdk 23 as today, targetSdk 34, R8 with `isShrinkResources = true`. Release builds must use a real
  keystore, not the debug key (`build.gradle.kts` falls back to the debug signing config today).
- Keep every Android behaviour that was learned the hard way (see Pitfalls). Do not relearn them.

### Mapping: web feature to native approach

| Feature | Native approach |
|---|---|
| Clock and date | One `TextView` or custom draw, updated each minute by a `Handler`. `DateFormat.is24HourFormat` default, per-locale. |
| Scenes with clock behind the skyline | Draw order on one Canvas: background JPEG, clock text, foreground WebP (`scene-*-fg.webp`). This is the web version's trick (the `-fg` layers) and is simpler natively. |
| Cross-fade between scenes | `ValueAnimator` alpha between two bitmaps. Only one scene decoded plus the next, never all five. |
| Frosted clock | The repo already ships pre-blurred `scene-*-glass.jpg` copies. Mask the clock text onto that bitmap (`PorterDuff.Mode.DST_IN`). No runtime blur needed. |
| Glass panels and blur | `RenderEffect.createBlurEffect` on API 31+ only. Below that, draw a pre-blurred crop with a tint, which is what `lite` already means. Default `off`. |
| Liquid clock (bending glass) | `RuntimeShader` on API 33+. Fallback to frosted. Treat as optional polish. |
| Glass app icons | The web code (`clearicon.js`, 218 lines) cuts each icon's symbol from its background. Port the algorithm to a Canvas pass, cache the result per package on disk. |
| Moving tiles, focus ring | `ValueAnimator` on scale and a gradient sweep. Off when `lighter` or animator scale is 0. |
| Ambient weather (rain, snow, fog, storm) | Canvas particle loop at 30 fps, capped particle count, stopped when an app or Settings is in front. |
| Sun sync and sun grade | Port the sun-position formula from `app.js` (a few lines) and tint with a `ColorFilter`. |
| Weather | `GET api.open-meteo.com` plus geocoding, cached to `SharedPreferences`. Place search uses the TV keyboard through an `EditText` in a dialog. |
| Apps list | `PackageManager.queryIntentActivities` for `LEANBACK_LAUNCHER`, plus the same `<queries>` block already in the manifest. Order and hidden set in `SharedPreferences` or a small JSON file. |
| Move, hide, uninstall, app info | Long-press handling on the focused tile, then `ACTION_DELETE` and `APPLICATION_DETAILS_SETTINGS` intents exactly as `MainActivity.kt` does now. |
| Keep watching | `TvContract.WatchNextPrograms` query (this is already native in `readWatchNext`). Cards drawn natively, art loaded lazily. |
| Custom cards | Fetch JSON, validate, draw. Same limits as today (8 cards, nothing executed). |
| People, kids mode, PIN | Plain data classes saved as JSON. PIN stored hashed with a salt, never in plain text. |
| Quick buttons | Same intents and `Settings` calls the Kotlin already makes (`setNativeSleepTimer`, `startAmbientNow`, `setScreenOff`, `tvInputs`, `openInput`). |
| Sleep timer | The Kotlin side already owns it (`setNativeSleepTimer`). Keep it. |
| Soundscape | Synthesize noise into an `AudioTrack` (brown noise is a filtered random stream, rain and waves are noise with an envelope, fire is crackle bursts). The web version synthesizes too (`soundscape.js`), so no audio files are needed. |
| i18n | Convert `i18n.js` into `values-xx/strings.xml` per language. Hebrew needs `supportsRtl="true"`, which the manifest turns off today. Keep the "follow the TV language" default. |
| Settings storage | `SharedPreferences`, with the same precedence as today: defaults, then bundled config, then settings made on the TV. |
| Bundled config | Keep `apply.sh` handing over `config_b64` and `ha_b64` in the launch intent. The native app parses the same JSON, so `apply.sh` and `home-config.py` need no change. |
| Home Assistant | `HomeAssistant.kt` and `Doorbell.kt` are already native. Re-use them, and only re-draw the tiles. |
| Updates | `Updater.kt` is already native. Keep it as is. |
| Video background | `MediaPlayer` on a `TextureView`, stopped when an app is in front, photo fallback on error. |
| Remote navigation | A focus map over the views, D-pad and long-press OK. The web page implements this by hand in `app.js` already, so it is a port, not a design. |

### Assets (where most of the APK size is)
- 21 MB of photos today, including `@2x` 4K copies. A 1080 panel never needs them. Ship 1080 only by
  default, and offer 4K only through `hi_res`. Hypothesis: this alone roughly halves the APK.
- Convert JPEGs to WebP at q80. Keep the `-glass` and `-fg` layers as WebP.
- Do not load more than two full-screen bitmaps at once. A 1080p ARGB bitmap is about 8 MB, so five
  decoded scenes would be 40 MB.

### Pitfalls the current code already solved (keep them)
- **Home key.** On some Google TV and Android TV builds the Home key opens the TV's own home no matter
  what is set. The answer is `FireWatch` plus `set-home-activity`, not disabling the stock home.
  Never disable the TV's own home or setup app: it boot-loops some Google TVs.
- **TCL.** `TclPlaceholderInput` (issue #94), unconfirmed on a real TCL.
- **Crash loop guard.** `CrashGuard` hands the screen back to the stock home if the app keeps crashing.
  A native rewrite will crash differently, so keep this.
- **Watch Next permission.** `READ_TV_LISTINGS` has to be granted over ADB. Handle it missing.
- **Low-memory TVs.** On this Kogan and Hitachi (1.4 GB, 32-bit) only 250 to 450 MB is free. Watch
  `onTrimMemory` and drop the cached scenes.
- **Storage.** The Hitachi was 97% full and refused the 21 MB install. A lighter APK helps there too.
- **Blur, video and 4K decode** are the heaviest things on these TVs. Default them off.
- **`WRITE_SECURE_SETTINGS` and the accessibility service** are granted over ADB, not by Play.
- **Android 6 to 14.** minSdk 23 means every API 31 or 33 feature needs a fallback.

### Suggested order of work
1. Skeleton: HOME activity, crash guard, FireWatch, config intent parsing. Prove it installs with
   `apply.sh` unchanged and the Home key works on the Kogan.
2. Apps row and More apps, focus engine, hold-OK menu, saved order. This is the part people use.
3. Clock, date, one static scene, Classic and OLED looks.
4. Weather and place search, settings screen, languages.
5. Keep watching, custom cards, quick buttons and Input menu.
6. Scene rotation with the clock behind the skyline, then glass, ambient effects, video.
7. People and kids mode, smart home and doorbell, soundscape, updates.
8. Measure on both TVs (`dumpsys meminfo`, `dumpsys gfxinfo`), compare with the WebView build, and
   cut anything that does not earn its memory.

### How to know it is "1:1"
- Build a checklist from the feature list above, and drive each item on a real TV.
- Re-use `tv-home/web/mock.js` data and the `docs/screens/*.jpg` screenshots as the visual reference.
- Keep `config.json` and `docs/CONFIG.md` as the contract: every key that works today must work in the
  native app, or be listed as dropped.
- Keep `apply.sh`, `undo.sh` and `remove-all.sh` unchanged. If they work, the install path is intact.

## Open questions
- Is a native rewrite worth the maintenance? The web page also serves the bridge, the phone remote and
  the other platform homes. A native Android app means two code bases for the home.
- Licence: the original is MIT (Seagey). This repo keeps that notice in `LICENSE` and lists what was
  copied in `NOTICE.md`. It is its own project, not a fork that has to track upstream.
- How much of the slowness is the WebView at all? On the Kogan the persistent Haier packages we could
  not disable also cost memory. Measure a trivial native launcher on both TVs first (an hour of work)
  and see how much it frees before committing to the full rewrite.

## Testing on the laptop first (no TV needed)

This laptop has KVM (`/dev/kvm`), 20 cores, 38 GB RAM and plenty of disk, so an Android TV emulator runs fast.
Android TV images exist for API 30 (the same Android 11 as both TVs) and API 34.

1. Install the emulator and image into the shared SDK:
   `sdkmanager "emulator" "system-images;android-30;android-tv;x86"`
2. Create a TV virtual device (1080p, 1.5 GB RAM, 2 to 4 cores to mimic a weak TV):
   `avdmanager create avd -n tv30 -k "system-images;android-30;android-tv;x86" -d tv_1080p`
   then set `hw.ramSize=1536` and `hw.cpu.ncore=2` in the AVD's `config.ini`.
3. Boot it: `emulator -avd tv30`. The arrow keys, Enter and Esc on the keyboard act as the remote.
4. Build and install: `./gradlew installDebug`, or `adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk`.
5. Look at it: `adb exec-out screencap -p > shot.png`. Claude can read the PNG, so layouts can be checked
   without a remote.

The emulator is good for layout, focus, scenes, settings, languages and RTL, Watch Next and the install path.
It is **not** good for speed or memory: a laptop is far faster than the TVs. Measure memory
(`dumpsys meminfo com.lukemonaghan.tvhome`) on a real TV before calling anything done. It also lacks the
MediaTek inputs, CEC and the vendor quirks.

## Status (2026-11-14)
- Repo created. It builds a placeholder home (a clock on a dark background, drawn on a Canvas): an
  828 KB debug APK, against 21.9 MB for the original WebView app.
- `HomeRole.kt` and `TclPlaceholderInput.kt` are copied into `app/` and compile. `Doorbell.kt` needs
  `HomeAssistant.kt` first, so it stays in `reference/upstream/kotlin/` until that is ported.
- `FireWatch.kt`, `KeyWatch.kt`, `CrashGuard.kt`, `HomeAssistant.kt` and `Updater.kt` are in
  `reference/upstream/kotlin/`. They reference `MainActivity` or a `WebView`, so each needs a small
  port before it moves into `app/`.
- Next: the emulator, then step 1 of the work order (HOME activity, crash guard, FireWatch, config intent).

## Identity (2026-11-14)
Package and app id: `com.lukemonaghan.tvhome`. Owner: lukemonaghan. Because the id differs from upstream's
`app.couchtv.launcher`, both apps can sit on one TV, but upstream's `apply.sh` and `undo.sh` will not find
this app (they hard-code the old id). This project needs its own install and undo script. Until then, set it
as the home by hand: `cmd package set-home-activity com.lukemonaghan.tvhome/.MainActivity`.

## Emulator, as set up (2026-11-15)
- Installed into the shared SDK: `emulator` and `system-images;android-30;android-tv;x86` (about 4 GB together).
- AVD `tv30` lives in `/home/lukem/Documents/git/couch-tv/build/avd` (set `ANDROID_AVD_HOME` to it): 1920x1080 at
  320 dpi (same as both TVs), 1536 MB RAM, 2 cores. KVM accelerated.
- Boot: `ANDROID_AVD_HOME=.../couch-tv/build/avd .../build/android-sdk/emulator/emulator -avd tv30 -gpu host -no-snapshot -no-audio`
- Like the real TVs, the stock home wins the Home key, so start the app with
  `adb -s emulator-5554 shell am start -n com.lukemonaghan.tvhome/.MainActivity`.
- First measurement, placeholder app: PSS 32.7 MB, RSS 121.7 MB (`dumpsys meminfo`). That is an emulator
  on a fast CPU, so **not comparable** to the 218 MB measured for the WebView app on the Kogan. Measure
  both builds on the same device before drawing a conclusion.

## Step 1 done (2026-11-15): HOME activity, crash guard, Home button watch
- Ported from upstream into `app/` (package renamed, WebView mentions removed, logic unchanged):
  `CrashGuard.kt`, `FireWatch.kt`, `KeyWatch.kt`. `MainActivity` now does the crash-guard dance
  (stand aside, `started`, `settled` after 20 s), `FireWatch.ensure`, the 10 s rebind, `isInFront()`,
  and a stub `backToFirstTile()` for when there is a first tile. The manifest has the two services,
  the boot/update receiver, the TCL placeholder input, the home `<queries>`, and the permissions.
- APK: 851 KB debug.
- Verified on the `tv30` emulator (stock home is `com.google.android.tvlauncher`, same as both TVs):
  - with `WRITE_SECURE_SETTINGS` granted, starting the app switches the watch on by itself
    (`enabled_accessibility_services` lists FireWatch, `accessibility_enabled=1`);
  - the stock home opened on top is replaced by the app again;
  - two unsettled starts make the guard stand aside: the stock home is on screen, and the watch leaves
    it alone. Clearing `shared_prefs/crash_guard.xml` (`run-as`) puts the app back.
- Not tested yet: boot-time restore after a reboot, Xiaomi, Amazon and Bell paths, KeyWatch's gear button,
  Android 10 `moveToFront`. Those are upstream behaviours copied as is.
- To run it on a device: `adb install -r app/build/outputs/apk/debug/app-debug.apk`, then
  `adb shell pm grant com.lukemonaghan.tvhome android.permission.WRITE_SECURE_SETTINGS`, then start the app.

## Step 2 slice done (2026-11-15): the apps row
- New files: `AppStore.kt` (scan, order, line, new-app dots, move/send/bring), `Icons.kt` (async banner cache,
  12 MB bound, one worker thread), `HomeView.kt` (clock, dock, More apps grid, hold-OK menu, move mode, all on one
  Canvas). `MainActivity` now hosts `HomeView` and does launch, Settings, uninstall (Android's dialog) and App info.
  Manifest got the `LEANBACK_LAUNCHER` `<queries>` (without it Android 11+ lists no apps) and
  `REQUEST_DELETE_PACKAGES`. APK: 868 KB.
- Same model as upstream: an ordered package list plus a "line"; items before the line are the dock. A new app goes
  at the line with a blue dot until opened once. More apps shows everything. Hold OK on a dock app: Move, Uninstall,
  App info. In Move: left and right reorder, Down sends the app behind More apps, OK or Back drops it. Hold OK on an
  app behind More apps: it goes to the end of the dock, and a full dock pushes its last app behind More apps.
- Bugs found by running it and fixed: the fresh-app set was never saved (dots vanished on the next reload), and the
  first run did not fix the order (a later install landed alphabetically in the middle).
- Verified on the emulator with screenshots and `adb input` (including `--longpress`): dock, focus scale, dots,
  menu, move, send to More, More grid, bring to dock, and that the order survives in `shared_prefs/apps.xml`.
- Not done yet from step 2: **Hide from home** (needs Home settings, Apps to undo it), `hide_packages`, pinned
  inputs, the App Store tile default position, per-TV `dock_size` from config, Down on the dock's last row opening
  More apps at the focused app (it opens at the same index), the focus ring is hard to see on white banners
  (YouTube, Play Store), and no scroll hint in the More grid.
- Not tested: more than ~10 apps (dock overflow, grid scrolling with several rows), a real TV, performance.
