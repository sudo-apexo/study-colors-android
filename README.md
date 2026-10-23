# Study Colors for Android

The Android version of [Study Colors](https://github.com/sudo-apexo/study-colors).
Add your subjects, then color each one to see your progress. Same app as the
desktop version, with the same features.

Your subjects are saved on your phone and are included in Android's normal
Google backup.

## Get the app on your phone

1. On your phone, open the **Releases** page of this repository and tap
   **Study Colors (latest build)**.
2. Download **StudyColors.apk**.
3. Open the downloaded file. Android asks you to allow installing apps from
   your browser. Allow it, then tap **Install**.

New builds appear on the same page a few minutes after every change.

## Update the app

Download the newest **StudyColors.apk** from the Releases page and open it.
It installs over the old version and keeps your subjects.

## How it is built

You do not need Android Studio. GitHub builds the app for you: every push to
`main` runs `.github/workflows/build.yml`, which makes `StudyColors.apk` and
attaches it to the **latest** release. You can watch a build on the **Actions**
tab. If a build fails, open it and read the red step.

## What works on Android

- Everything from the desktop version: colors, overview and filter, notes and
  exam dates, search and sort, undo, theme, backup.
- Reorder subjects with the **Earlier** and **Later** buttons in a subject
  (dragging is only for mouse users).
- The Back button closes an open window first, then leaves the app.
- Exam reminders show as notifications when you open the app on the day of
  the exam or the day before. Android asks for permission the first time.
- **Export backup** asks where to save the file. **Import backup** opens the
  file picker.
- The status bar and navigation bar follow the app's light or dark theme.

## Use a newer page from the desktop version

The page is `app/src/main/assets/index.html`. To copy the newest desktop page
here, with the desktop repo next to this one:

```sh
./sync-from-desktop.sh
```

Then raise `versionName` in `app/build.gradle`, commit and push.

## Files

| File | Purpose |
| --- | --- |
| `app/src/main/assets/index.html` | The app page, same as the desktop version |
| `app/src/main/assets/android-shim.js` | Connects the page to Android (notifications, backup files, bar colors) |
| `app/src/main/java/.../MainActivity.java` | The Android screen that shows the page |
| `app/src/main/res/` | Icon, colors and theme |
| `study-colors.keystore` | The signing key (see below) |
| `.github/workflows/build.yml` | Builds the APK on GitHub |
| `sync-from-desktop.sh` | Copies the newest desktop page here |

## About the signing key

Android only installs an update if it is signed with the same key as the
installed version. So the key is kept in this repository, which makes every
build update the previous one without losing your data. Because the key is
public, this is meant for personal use and sideloading, not for publishing on
the Play Store. For the Play Store, make a private key and keep it out of Git.

## Licenses

App code: MIT (`LICENSE`). Monocraft font: SIL Open Font License 1.1 (`OFL.txt`).

## Accounts and sync (optional)

Sign in with email or Google to keep subjects in sync with the desktop app. Setup is a one-time Firebase project; see `docs/SETUP-ACCOUNTS.md` in the desktop repo (https://github.com/sudo-apexo/study-colors). The Firebase settings are **not** stored in this repository: add them once as a GitHub secret named `FIREBASE_CONFIG_JS` (Settings > Secrets and variables > Actions) and every build writes them into the app. Without it the build stops with a clear message.

## Settings

Tap the gear in the top bar. Theme, accent color, font, text size, corner style, reminders, backup and account all live there. Run `./sync-from-desktop.sh` to bring in the latest page from the desktop repo.

## Project layout

```
study-colors-android/
├── app/src/main/
│   ├── java/…/studycolors/   MainActivity, Updater (in-app updates), ProgressWidget
│   ├── assets/               index.html (synced from the desktop repo), android-shim.js, study-sync.js
│   ├── res/                  Icons, widget layout, colors, themes
│   └── AndroidManifest.xml
├── .github/workflows/build.yml   Builds StudyColors.apk on GitHub
├── sync-from-desktop.sh      Copies the newest page from the desktop repo
├── app/build.gradle, build.gradle, settings.gradle, gradle.properties
└── study-colors.keystore     Signing key for personal installs (not for the Play Store)
```
