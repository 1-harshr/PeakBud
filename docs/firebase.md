# Crash reports and usage logging (Firebase)

PeakBud uses Firebase Crashlytics for crashes and non-fatal errors, and Firebase Analytics for a
small set of usage events. Both are wired in but stay inactive until a Firebase config file exists.
Without it the app builds normally and logs to Logcat under the `PeakBud` tag.

## Turn it on

1. In the [Firebase console](https://console.firebase.google.com/), create a project (Google Analytics on).
2. Add an Android app with package name `com.harsh.peakbud`.
   Add the SHA-1 of your debug and release signing keys if you plan to use other Firebase features later.
3. Download `google-services.json` and place it at `app/google-services.json`.
4. Build. The Google Services and Crashlytics Gradle plugins apply automatically when that file is present.

`app/google-services.json` is listed in `.gitignore` because this repository is public. It is not secret, but it
identifies your project. Release builds also upload R8 mapping files to Crashlytics once minification is on.

## Check it works

- Run the app, open Settings and keep **Send crash reports and usage** on.
- Force a test crash from a debug session, for example with `adb shell am crash com.harsh.peakbud`,
  then relaunch the app. Crashlytics sends reports on the next start.
- Events appear in Analytics → DebugView after `adb shell setprop debug.firebase.analytics.app com.harsh.peakbud`.

## What is collected

Collection follows the in-app setting (default on). The manifest starts with collection off, and the app
switches it on once it has read the setting; Firebase remembers the choice for the next launch.

| Kind | Examples |
| --- | --- |
| Screens | `screen_view` for camera, home, peaks, regions |
| Regions | `region_open`, `region_download_start/complete/failed`, `region_delete` (region ID, size, error type) |
| Peaks | `peak_open` (visibility, which screen) |
| Settings | `demo_mode`, `units`, `diagnostics_off` |
| Crash context | breadcrumbs for location/camera status and label nudges; keys `region`, `demo_mode`, `build_type`, `version` |
| Non-fatal errors | unexpected failures in view calculation, downloads (not network drops), delete, settings, camera start |

Never logged: coordinates, altitude, peak positions or any account data. Advertising IDs are disabled and
the AD_ID / AdServices permissions are removed from the merged manifest.

## Play Store data safety

Declare: **Crash logs** and **Diagnostics** (app performance), and **App interactions** (analytics), collected,
not shared, not required (users can turn it off in Settings), encrypted in transit. Location is used on the
device only and is not collected.
