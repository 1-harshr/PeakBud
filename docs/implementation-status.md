> Updated regional coverage: broad Uttarakhand (250 km) and Jharkhand (200 km) packs now ship in the catalogue alongside smaller areas. See [regional coverage and current validation](regional-coverage.md). Older verification counts and small-area notes below describe the earlier prototype checkpoint.

# Implementation status

Updated 2026-10-17. The primary experience is now a live camera view with peak
labels; the panorama is an alternate reference and demo view. See
[camera-overlay.md](camera-overlay.md) for the camera pipeline and limitations.

## Implemented

- CameraX rear preview, runtime permission/denial/settings handling, lifecycle
  binding and cleanup, GPS/compass prerequisites, and manual heading alignment.
- Optional full-screen camera with compact location/direction metadata, explicit
  exit, Android Back handling, and system-bar restoration.
- Pinhole peak projection with true-north heading, pitch, roll, actual capture
  focal length, sensor size, and CameraX sensor-to-preview crop/rotation mapping.
  Labels have peak names, height/distance, leader lines, and detail tap targets.
- Built-in Poon Hill, Gokyo, and Auli region catalogue with verified public HTTPS
  links, pinned compressed/uncompressed terrain hashes, bundled GeoNames peaks,
  one-action download/activation, per-region resume/discard, and offline open/delete.
  User-facing import/file-picker and URL entry controls have been removed.
- Visible direction/reference/sensor source, latitude/longitude, GPS altitude and
  datum/source, terrain ground height, accuracy/fix status, camera horizontal and
  vertical field of view, and peak elevation angle. First-use location permission
  works before downloading a region. Stationary users can receive fresh GPS fixes.
- Terrain line of sight, curvature, uncertainty, and missing-data handling;
  file-backed HGT interpolation with bounded caching and independent per-peak rays.
- Foreground GPS/fix age, orientation sensors and reliability messaging,
  declination correction, settings, and manual panorama viewpoints.
- Room peak records, DataStore preferences, validated offline region installation,
  integrity checks, cancellation, selection/deletion/credits, and resumable HTTPS
  downloads with saved-transfer discovery.
- Region preparation tool for supplied HGT and OSM inputs.
- Fictional terrain for panorama exploration. Fictional peaks are never overlaid
  on live camera images.

## Verification

The session permissions now allow Gradle and Git writes. The earlier environment
blockers have been removed; they are not outstanding build failures.

- Full Gradle APK assembly, AAR metadata check, Room/KSP generation, and JVM unit
  tests pass. The JVM report contains 41 passing tests, including seven camera
  projection tests (center/pitch, perspective, north wrap, roll, rejection, crop,
  and field of view). Four catalogue tests cover actual shipped assets/coverage,
  download-to-activation, cancellation/retry reuse, and changed-source rejection.
- Four instrumentation tests pass on the API 36.1 arm64 emulator, covering camera
  as default, Panorama switching, demo/manual viewpoint, region entry points,
  settings, full-screen entry/exit/button/Back and metadata visibility, and the
  original application-context check. Navigation tests inject
  stable sensor observations; separate manual checks exercise the real providers.
- Android lint passes with warnings; no lint errors. Warnings include existing
  dependency/style/localization recommendations.
- The debug APK was installed and opened on that emulator. The actual camera
  permission dialog was denied and retried successfully. A live virtual rear
  camera preview was visually inspected. The app displayed region setup help and
  correctly withheld fictional peak labels. First-use location permission was
  also observed and granted. Auli terrain was downloaded through the actual region
  card and installed successfully. Simulated GPS at 30.53 N, 79.57 E produced real
  region peak labels over the virtual camera, and displayed coordinates, altitude,
  GPS accuracy, heading, sensor source, and camera view angles. Portrait and
  landscape layouts were visually checked; landscape uses a side status panel.
  After a cold start in airplane mode, the downloaded Auli region reopened and
  displayed peak labels over the camera with a fresh simulated GPS fix.
- The prior preparation-tool suite (three Python tests) and independent Room SQL
  checks remain available; Gradle now validates the actual generated Room wiring.

Run with JDK 21 / Android SDK 37:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Remaining work

- The catalogue currently supports three limited Himalayan viewpoint areas, not
  entire countries or ranges. Terrain downloads on demand. See
  [region-catalog.md](region-catalog.md) for coverage, source links, and maintenance.
- Real-device/outdoor alignment is unverified. Check orientation axes, portrait
  and landscape, magnetic interference, fresh/stale/disabled GPS, camera
  interruptions, permission revocation, and known summit bearings.
- Camera calibration is metadata-derived and approximate. Lens distortion,
  principal-point offsets, stabilization, and logical multi-camera behavior need
  device-specific measurement. There is no image-recognition correction or zoom.
- Independently compare real-terrain visibility, benchmark HGT on a named phone,
  and repeat offline cold-start checks on real phones. The DEM model cannot account
  for weather, vegetation, or terrain features smaller than its sampling.
- More regions, provider availability monitoring, a hosted catalogue update path,
  and a background download scheduler remain future work.

The prototype now builds and runs with camera preview. It has not been validated
as a complete, geographically accurate outdoor hiking experience.

## Commits

- `3579213`: checkpoint of the terrain panorama, offline storage/downloads, tests,
  and original documentation before changing the main experience to camera.
- `882bb1d`: camera as the main view, sensor-based peak projection, and camera
  permission/lifecycle handling.
- Built-in catalogue and observation-status updates follow as a separate commit.

Local IDE configuration changes are excluded from these implementation commits.
