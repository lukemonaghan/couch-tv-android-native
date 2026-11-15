# tvhome status (written 2026-11-15)

Read `CLAUDE.md` and `note.md` first. This file says where the work stopped.

## Where things are
- Repo: `/home/lukem/Documents/git/couch-tv-android-native`, local git only, no remote. Branch `main`, head `a83ed79`.
- Build: `export JAVA_HOME=/home/lukem/Documents/git/couch-tv/build/jdk ANDROID_HOME=/home/lukem/Documents/git/couch-tv/build/android-sdk; ./gradlew --no-daemon -q assembleDebug`
- Everything merged into `main` builds. Nothing from the merged feature branches has been run on the emulator or a TV yet.

## Merged into main (built, NOT yet looked at on screen)
- `feat/apps`: hide from home, tucked defaults, App Store label, TV inputs, Input quick button.
- `feat/quick`: screen off, screensaver, headphones, Bluetooth, network, TV settings quick buttons; Keep watching remove menu; hero title in Classic/OLED.
- `feat/people`: per-person app order, people list popup, People settings, kids mode with PIN pad.
- `feat/platform`: Updater (off until a release address is set), Home Assistant smart home, Doorbell, custom cards, first-run home prompt, Reset settings, `tools/send-config.sh`.
- `feat/idle`: Smart Idle, soundscape, navigation sound, ambient weather effects, Now playing card and music screen.
- Merge fixes I made: Input list and Soundscape added to `QuickButtons.ALL`; dock index clamps use `dockSlots()`.

## NOT merged yet
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
