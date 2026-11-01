# Shuffle Frame

Sideloaded Android app for the OnePlus Pad: a full-screen, freshly shuffled slideshow of selected
Google Photos albums (photos and videos), kept in sync automatically.

## How it works

- Albums are added by their **share link** (Google Photos → album → Share → Create link → share
  to *Shuffle Frame*, or paste it under *Add album*). Google's official APIs stopped letting
  third-party apps read existing albums on 2025-03-31, so the app reads the album the same way the
  public share page does (`GooglePhotos.kt`, internal `snAcKc` request). No API key or Google
  sign-in is needed; anyone with an album's link can view it.
- Background sync (WorkManager, every 30 min by default, Wi-Fi only, plus whenever the slideshow
  starts) mirrors each enabled album into app storage. It lists every album first (4 at a time),
  then downloads new items 6 at a time, photos first. Photos are screen-sized WebP renders from
  Google (about 40% smaller than JPEG; HEIC needs no on-device decoding). Videos above 1080p, and
  H.264 videos, are converted on the tablet's hardware encoder to 1080p HEVC at ~12.5 Mbit/s with
  HDR kept (Settings → Video quality: converted / Google compact / original). Existing files are
  converted in the background. Downloads pause below 5 GB free.
- Everything in a shared album is included, whoever added it; nobody needs to tap "Save".
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
  (notifications, full-screen notifications, installing updates, background activity).
- **Album list backup**: an automatic copy is kept in `Download/Shuffle Frame` on the tablet;
  Settings has *Save a backup copy…* (e.g. to Google Drive) and *Restore from a backup…* (adds
  missing albums). The file holds the share links, so keep it private.
- **Self-update**: once a day the app downloads a newer GitHub release of this repository in the
  background and shows an "update ready" notification. It installs when you next open the app
  (or tap the notification, or Settings → *Check for updates now*), because Google Play Protect
  asks about every new sideloaded version and that question shouldn't pop up over the frame.

## Releasing an update

```bash
./release.sh "What changed"
```

Bumps `version.properties`, builds the signed APK, commits, pushes and publishes a GitHub
release; the tablet installs it within a day. Every build is signed with
`~/Android/keys/shuffleframe.jks`, which is not in the repo: **back it up**, since Android only
accepts updates signed with the same key.

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
