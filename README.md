# Shuffle Frame

Sideloaded Android app for the OnePlus Pad: a full-screen, freshly shuffled slideshow of selected
Google Photos albums (photos and videos), kept in sync automatically.

## How it works

- Albums are added by their **share link** (Google Photos → album → Share → Create link → share
  to *Shuffle Frame*, or paste it under *Add album*). Google's official APIs stopped letting
  third-party apps read existing albums on 2025-03-31, so the app reads the album the same way the
  public share page does (`GooglePhotos.kt`, internal `snAcKc` request). No API key or Google
  sign-in is needed; anyone with an album's link can view it.
- Background sync (WorkManager, every 15 min by default, Wi-Fi only, plus whenever the slideshow
  starts) mirrors each enabled album into app storage: photos as screen-sized JPEGs rendered by
  Google (so HEIC needs no on-device decoding), videos as original files (HEVC plays on the
  hardware decoder; if one fails, Google's 1080p H.264 version is fetched instead).
- **Deletions mirror**: an item removed from the album is deleted from the tablet on the next sync.
  Safety net: if one sync would remove more than half of an album (or the album lists as empty),
  the items are hidden at once and only deleted when the next sync agrees. A failed or partial
  listing never deletes anything.
- **Removing from the tablet**: the slideshow's 🗑 button offers *Remove from album in Google
  Photos* (opens the item on photos.google.com in Chrome: ⋮ → Remove from album; it stays in the
  library, and the tablet deletes its copy on the follow-up sync) or *Hide on this tablet only*
  (restorable from the album's menu). Share links are read-only, so the app itself can't edit
  the album.
- The slideshow uses shuffle bags seeded from `SecureRandom`: a new order every run, and
  everything is shown once before anything repeats. It plays offline from the local copy.
- **Frame mode** (`FrameService`): whenever the screen turns off, the slideshow turns it back on
  over the lock screen (via a full-screen notification, like an alarm clock), if the conditions
  in Settings allow: battery at least N % or charging, not in battery saver, optionally not in
  airplane mode. It ends when a condition fails. Pressing power or ✕ on the frame keeps it off
  until the tablet is next unlocked. Android's own screen saver isn't used because OxygenOS
  hides it and never runs it on battery (`mDreamsEnabledOnBatteryConfig=false`).
- A *Finish setting up* card on the main screen lists any missing one-time permission
  (notifications, full-screen notifications, background activity).

## Build and install

Toolchain lives in `~/Android` (JDK 17, SDK 35, no sudo needed).

```bash
JAVA_HOME=~/Android/jdk ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On-device media check (decodes every downloaded photo and plays every video). Don't use
`connectedDebugAndroidTest`: it uninstalls the app afterwards, wiping the downloads.

```bash
JAVA_HOME=~/Android/jdk ./gradlew assembleDebugAndroidTest
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class com.gjnet.shuffleframe.MediaPlaybackTest com.gjnet.shuffleframe.test/androidx.test.runner.AndroidJUnitRunner
```

If Google changes its page format, sync shows an error on the album card and the slideshow keeps
playing what is already downloaded; the parser is all in `GooglePhotos.kt`.
