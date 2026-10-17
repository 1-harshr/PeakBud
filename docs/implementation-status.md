# Implementation status

Updated 2026-10-17. The primary experience is now a live camera view with peak
labels; the panorama is an alternate reference and demo view. See
[camera-overlay.md](camera-overlay.md) for the camera pipeline and limitations.

## Implemented

- CameraX rear preview, runtime permission/denial/settings handling, lifecycle
  binding and cleanup, GPS/compass prerequisites, and manual heading alignment.
- Pinhole peak projection with true-north heading, pitch, roll, actual capture
  focal length, sensor size, and CameraX sensor-to-preview crop/rotation mapping.
  Labels have peak names, height/distance, leader lines, and detail tap targets.
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
  tests pass. The JVM report contains 36 passing tests, including six camera
  projection tests (center/pitch, perspective, north wrap, roll, rejection, crop).
- Three instrumentation tests pass on the API 36.1 arm64 emulator, covering camera
  as default, Panorama switching, demo/manual viewpoint, region entry points,
  settings, and the original application-context check.
- Android lint passes with warnings; no lint errors. Warnings include existing
  dependency/style/localization recommendations.
- The debug APK was installed and opened on that emulator. The actual camera
  permission dialog was denied and retried successfully. A live virtual rear
  camera preview was visually inspected. The app displayed region setup help and
  correctly withheld fictional peak labels.
- The prior preparation-tool suite (three Python tests) and independent Room SQL
  checks remain available; Gradle now validates the actual generated Room wiring.

Run with JDK 21 / Android SDK 37:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Remaining work

- No real SRTM/OSM region is bundled. Prepare/import one before testing real peak
  identities. See [region-packs.md](region-packs.md).
- Real-device/outdoor alignment is unverified. Check orientation axes, portrait
  and landscape, magnetic interference, fresh/stale/disabled GPS, camera
  interruptions, permission revocation, and known summit bearings.
- Camera calibration is metadata-derived and approximate. Lens distortion,
  principal-point offsets, stabilization, and logical multi-camera behavior need
  device-specific measurement. There is no image-recognition correction or zoom.
- Independently compare real-terrain visibility, benchmark HGT on a named phone,
  and validate offline cold starts in airplane mode. The DEM model cannot account
  for weather, vegetation, or terrain features smaller than its sampling.
- A hosted region catalogue and automatic background scheduler remain future work.

The prototype now builds and runs with camera preview. It has not been validated
as a complete, geographically accurate outdoor hiking experience.

## Commits

- `3579213`: checkpoint of the terrain panorama, offline storage/downloads, tests,
  and original documentation before changing the main experience to camera.
- The subsequent camera implementation is a separate commit in Git history.

Local IDE configuration changes are excluded from these implementation commits.
