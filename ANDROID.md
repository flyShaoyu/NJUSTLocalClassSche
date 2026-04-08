# Android APK Packaging

## Current State

This repository now includes an `android/` WebView shell that can display the locally cached timetable page from:

- `android/app/src/main/assets/timetable-view.html`

The Android shell is meant to wrap the generated local timetable UI into an APK.

## Important Limitation

The current desktop fetch flow uses Playwright. Playwright does not run inside a normal Android APK, so the exact desktop pipeline cannot be copied into the phone unchanged.

That means there are two practical paths:

1. Desktop updates + APK offline display
2. Rebuild the update flow for Android WebView manual login

This repository currently implements path 1 and prepares the codebase for path 2.

## What Is Implemented

- Android native project scaffold under `android/`
- `MainActivity` with a `WebView`
- Cleartext HTTP enabled for the current school URLs
- Asset export script that copies:
  - `artifacts/timetable-view.html`
  - `artifacts/timetable.json`
  - `artifacts/timetable.html`

## Export Latest Cache Into Android Assets

After refreshing the timetable on desktop:

```bash
npm run start
npm run export:android
```

Or if you already have the latest cache:

```bash
npm run export:android
```

## Build APK In Android Studio

1. Open the `android/` folder in Android Studio.
2. Wait for Gradle sync.
3. Build:

```text
Build > Build Bundle(s) / APK(s) > Build APK(s)
```

## Next Step For A Truly Self-Updating APK

If you want the APK itself to handle:

- login
- captcha/manual verification
- jump to timetable page
- capture HTML
- parse timetable
- update local cache

then the fetch/update layer needs to be rewritten for Android, likely using:

- `WebView` for manual login
- `evaluateJavascript` to read timetable page HTML
- browser-side or native-side HTML parsing
- local file persistence inside app storage

That is a different runtime from Playwright, so it should be treated as a mobile-specific implementation rather than a direct packaging step.
