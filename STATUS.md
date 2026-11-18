# tvhome status (written 2026-11-15)

Read `CLAUDE.md` and `note.md` first. This file says where the work stopped.

## Where things are
- Repo: `/home/lukem/Documents/git/couch-tv-android-native`, published at github.com/lukemonaghan/couch-tv-android-native (public). Branch `main`, head `f064be0` plus later commits (see `git log`).
- Build: `export JAVA_HOME=/home/lukem/Documents/git/couch-tv/build/jdk ANDROID_HOME=/home/lukem/Documents/git/couch-tv/build/android-sdk; ./gradlew --no-daemon -q assembleDebug`
- Everything merged into `main` builds. Nothing from the merged feature branches has been run on the emulator or a TV yet.

## Merged into main (built, NOT yet looked at on screen)
- `feat/apps`: hide from home, tucked defaults, App Store label, TV inputs, Input quick button.
- `feat/quick`: screen off, screensaver, headphones, Bluetooth, network, TV settings quick buttons; Keep watching remove menu; hero title in Classic/OLED.
- `feat/people`: per-person app order, people list popup, People settings, kids mode with PIN pad.
- `feat/platform`: Updater (off until a release address is set), Home Assistant smart home, Doorbell, custom cards, first-run home prompt, Reset settings, `tools/send-config.sh`.
- `feat/idle`: Smart Idle, soundscape, navigation sound, ambient weather effects, Now playing card and music screen.
- Merge fixes I made: Input list and Soundscape added to `QuickButtons.ALL`; dock index clamps use `dockSlots()`.

## Update, same day: feat/looks is now merged too (`f064be0`)
All six feature branches are in `main` and it builds. Worktrees in `../couch-tv-android-native-wt/` can be removed with `git worktree remove`.

### Emulator check done after the merge (2026-11-15)
Seeded prefs by writing `shared_prefs/home.xml` (script idea: push XML to /data/local/tmp, `run-as ... cp`, force-stop, start). No crashes in logcat.
- OK: Night with glass on, glass app icons, liquid clock; ten quick buttons in Night (round) and five in Classic (labelled pills); three-person avatar button and its list popup; Classic hero title ("The Bear") top left; Settings pages (Location, Weather, Look and scenes, Apps, People, Quick buttons, Idle and sound, Smart home, Updates, About) and the Look page's glass, moving tiles, lighter home, clock style, bedtime, video link and photo rows; OLED default.
- Not yet checked: kids mode and PIN pad, Smart home screen, Doorbell, Updater, Now playing card, soundscape audio, Smart Idle, hold-OK menus (hide, dismiss show), Input menu, screen off, moving-tile animation, rain/snow fx, video background, own photos.
- Seen but not fixed: with ten quick buttons the Night row runs over the centred date line (the reference would crowd too); OLED look with glass on plus weather fx once showed a flat dark green background (not reproduced with defaults, so look at that combination).
- Note: restarting the app twice within seconds (force-stop in test scripts) makes CrashGuard think it crashed and show the TV's own home for a few minutes. Clear it with `adb shell "run-as com.lukemonaghan.tvhome rm -f shared_prefs/crash_guard.xml"`.
- No side-by-side comparison with the Playwright reference was done yet for the new features.

## (old note) NOT merged yet
- **`feat/looks`** (commit `4387d16`, worktree `../couch-tv-android-native-wt/looks`): glass options, glass app icons, liquid clock, lighter home, moving tiles, keep one photo, own photos, video background, OLED shift, bedtime amber dimming. Run `git merge --no-ff feat/looks` on main. Expect conflicts in `HomeView.kt`, `SceneRenderer.kt`, `SettingsScreen.kt`, `MainActivity.kt`, `AndroidManifest.xml`. Resolve by keeping both sides, as for the other branches.
- Worktrees `../couch-tv-android-native-wt/{apps,quick,people,looks,idle,platform}` can be removed with `git worktree remove` once looks is merged.

## Next steps, in order
1. Merge `feat/looks`, build.
2. Install on the emulator (see below) and walk every new feature. Fix crashes first.
3. Evaluate against the reference with Playwright (`http://localhost:8765/index.html?mock=1`, 1920x1080, serving `couch-tv/tv-home/web`). Compare dock, focus states, quick buttons, settings pages, people popup, kids shelf, Now playing, glass options. Playwright screenshots must be saved under `/home/lukem/Documents/git/couch-tv/.playwright-mcp/`.
4. Language support (13 languages, Hebrew right to left, 12/24 hour clock) with Android string resources. Not started; do it after the merge because it touches every string. New text from the platform bundle is in `PlatformStrings.kt` and most other new text is inline English.
5. Measure memory on a real TV (`dumpsys meminfo`, old web version was 218 MB on the Kogan). Needs the user's clear yes before changing any real TV.

## Known gaps and notes from the builders
- Per-person hidden apps and inputs follow the person (prefs are keyed per profile).
- Kid and guest flags are stored but there is no "taste" to exclude them from (that was bridge only).
- Glass blur is a downscale approximation; glass icons are only in the Night dock and Classic/OLED banner tiles, not the More grid.
- Now playing needs notification access. `MediaListener` appears once `WRITE_SECURE_SETTINGS` is granted; `install.sh` does not grant the listener yet (command is in the `MediaListener.kt` comment).
- Weather effects and moving tiles redraw about 25 times a second; both default off.
- Classic Now playing card sits near y=380 and can overlap the fourth poster.
- Smart home and Doorbell are configured with `tools/send-config.sh`; Doorbell needs `DOORBELL=1 tools/install.sh`.
- Hero and menu positions come from upstream CSS numbers and were not checked in a browser.

## Emulator and test setup
- Emulator: `cd /home/lukem/Documents/git/couch-tv/build; export ANDROID_AVD_HOME=$PWD/avd ANDROID_HOME=$PWD/android-sdk ANDROID_SDK_ROOT=$PWD/android-sdk; nohup android-sdk/emulator/emulator -avd tv30 -gpu host -no-snapshot -no-audio &`
- After each cold boot set the time zone: `adb -s emulator-5554 shell "service call alarm 3 s16 Australia/Sydney"`.
- Install with `TV_IP=127.0.0.1 TV_ID=emulator PY=/home/lukem/Documents/git/couch-tv/.venv/bin/python sh tools/install.sh`; undo with `tools/undo.sh`.
- adb: `/home/lukem/Documents/git/couch-tv/build/android-sdk/platform-tools/adb`.
- Reference web server: `cd /home/lukem/Documents/git/couch-tv/tv-home/web && python3 -m http.server 8765`.
- Both the emulator and the web server may still be running.

## Real TVs (original couch-tv repo, untouched by this project)
- Kogan (<tv-ip>) and Bedroom (<tv-ip>), both Android 11, running the old web couch-tv as home. The old app is still installed on both; removing it needs the user's yes.
- Bedroom TV still has USB debugging ON (port 5555). Re-run `scripts/check-tv.py <tv-ip>` in the couch-tv repo and confirm it is closed when done.
- TV 3 is not set up yet.
- Undo for the old app: `TV_IP=<ip> TV_ID=<id> TV_NAME=<id> sh tv-home/tools/undo.sh` in the couch-tv repo.

## Update 2026-11-15 evening (`137cb44`)
- Sleep timer is now a dropdown (15/30/60/90/Off) like upstream; buttons show "Off in N min"; Classic Look pill shows the next look.
- Kids mode poster/app rings and the people pill focus ring are strokes now (were solid white discs). Checked on the emulator.
- Still unchecked on screen: Smart home, Doorbell, Updater, Now playing, soundscape audio, Smart Idle, Input menu with real inputs, moving tiles, weather fx, video background, own photos, First-run prompt.
- Still to do: compare new features with the Playwright reference, language support, real-TV memory check (needs the user's yes), close Bedroom TV USB debugging.

## Music visualiser (2026-11-16)
- MilkDrop presets behind the music view, played by projectM built for **OpenGL ES 2.0** (the Bedroom and Kogan TVs have a Mali-470, ES 2.0 only). Spike on the Bedroom TV: no failed loads, no GL errors; at 960x540 about two thirds of 36 sampled favourites ran at 25 fps or more, a sixth under 14 fps.
- Code: `ProjectM.kt` (JNI), `Visualiser.kt` (GLSurfaceView behind the home, added by `MainActivity` only while the music view is open), `PresetStore.kt` (index, per-file downloads, textures zip, remove), `VizSettings.kt` (Settings, Music visualiser: options and a Category > Group > preset browser), `NowPlaying.kt` (half-clear card, eye button hides everything, brightness slider on up/down while hidden, sparkle button = next preset).
- Presets that run under 14 fps, fail, or crash the app are put on a skip list (`viz_slow`) and not played again on that TV.
- Native libraries: `tools/build-native.sh` (armeabi-v7a, arm64-v8a, x86). Not committed. `tools/make-preset-index.py` makes the preset list.
- Not done yet: favourites you can mark in the app (the 64 starter presets are a fixed list), brightness above 100%, a first-run prompt, checking the arm64 build on a real TV.

## Music view and preset browser (2026-11-16, later)
- Music view buttons: play/pause exactly centred with previous and next; eye and next-preset (with the preset name) on the left; hint, stop and the app button on the right. Lyrics can stay up, centred on the screen, when the buttons are hidden (Settings, "Lyrics with the buttons hidden"). There are no EQ bars any more (they shrank to fit their space).
- Settings, Music visualiser: "Browse your presets" (installed only: category, group, preset), "Favourites", and "Play: everything that is on / favourites only". OK on a preset opens Add to favourites, Turn off and Remove. A turned-off preset is never played. A live 320 x 180 preview of the focused preset sits beside the list (PresetPreview.kt, z-on-top GLSurfaceView, 20 fps); presets that are skipped or not installed show text instead.
- Prefs: viz_fav, viz_off, viz_favs_only, np_lyrics_hidden. Not yet tried on a real TV.

## Home first, music-view clock (2026-11-18)
- Music that is already playing when the TV wakes or tvhome comes to the front no longer opens the music view by itself (`NowPlaying.adopt`). A new song still does.
- Settings, "Clock on music view" (pref `np_clock`, off by default): a small clock top right in the music view (`HomeView.drawSmallClock`).
- Pushed this debug build to the Bedroom (<tv-ip>), Kogan (<tv-ip>) and the TCL lounge room TV (<tv-ip>) with adb. Not yet checked on screen. Reinstalling may switch the Home button accessibility service off again.
- The lounge room TV is a TCL Smart TV Pro (id "living"), found by `scripts/find-tvs.py`. It is not in the couch-tv `config.json`.
