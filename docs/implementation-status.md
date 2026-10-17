# Implementation Status

The app is being developed against [plan.md](plan.md). Completion is currently
blocked by execution and data-access restrictions in this session. The following
status distinguishes source implementation from verification. It is not a claim
that the complete hiking experience has been field-validated.

## Implemented

- Independent Kotlin geographic models, spherical bearings/distances, curvature,
  horizon sampling, and per-peak checks limited to terrain before the summit.
- Visible, occluded, uncertain, and unknown outcomes; missing data is never zero.
  Large differences between published heights and sampled terrain are explicitly
  uncertain rather than silently moving the summit. The prototype threshold is
  the larger of 100 m and three times the terrain height-error estimate.
- HGT big-endian decoding, interpolation, shared-edge lookup, and a bounded block
  cache (up to four open tiles, 48 MiB of height blocks).
- Fictional terrain for exploring controls, clearly distinguished from real data.
- Compose panorama with labelled callouts, drag controls, field of view, peak
  list/details, manual coordinates/ground height, and portrait/landscape layouts.
- Foreground GPS with permission handling, fix age/accuracy, and manual fallback.
- Rotation-vector or accelerometer/magnetometer orientation, true-north correction,
  display rotation handling, compass quality messaging, and manual alignment trim.
- Room peak indexing; DataStore preferences and selected-region persistence.
- Validated ZIP installation, terrain-radius coverage checks, hashes, atomic
  activation, cancellation, selection, deletion, and attribution display.
  Installed files are rehashed before first use after process restart.
- User-initiated HTTPS pack downloads, redirect checks, bounded sizes, and strong
  ETag-protected resume support. Retry the same URL after interruption/process death.
  Saved transfers also appear in Regions with Resume/Restart and Discard controls.
- A desktop preparation tool that packages supplied HGT and OSM JSON, preserving
  provenance and checking required tiles. No secrets belong in the app.

## Verification Obtained

- 29 actual Kotlin/JUnit tests pass through `tools/check-core.py`, including geographic
  projection, occlusion, missing terrain, cancellation, HGT reading/interpolation,
  pack activation/integrity/path checks, and guarded download resumes.
  The suite covers same-size file corruption, saved-download discovery after
  recreation, weak ETags, and conflicting summit heights.
- Three passing Python preparation-tool tests check source normalization, duplicate IDs, archive
  hashes, and rejection of missing input terrain.
- Android Kotlin sources were also compiled with the Compose compiler against
  locally cached Android libraries as an additional source check, including the
  generated database/DAO implementations (21 Kotlin files in total). This does not
  validate Gradle's resolved dependency graph, packaging, or Android runtime.
- Standalone KSP 2.3.5 / Room 2.8.4 processing of the actual database sources
  succeeded and exported `app/schemas/com.harsh.peakbud.data.peaks.PeakDatabase/1.json`.
  The generated Kotlin implementations also compile with the app sources.
  `tools/check-room-schema.py` passes against that exported schema and the actual
  DAO SQL, checking pack isolation, bounding queries, nullable heights, and deletion.

## Outstanding Verification and Work

- Full Gradle AAR metadata/build/unit-test execution is blocked in this session:
  Gradle's file-lock service cannot open its required local socket. Run the command
  in README in Android Studio's terminal. The standalone Room/KSP check above
  verifies generation independently; Gradle plugin wiring and APK packaging still
  require a full build.
- No actual SRTM/OSM demo region has been downloaded, curated, or bundled. Shell
  downloads fail DNS resolution in this session. A complete importer and preparation
  path exists, but synthetic tests cannot establish real geographic accuracy.
- No emulator/device execution or screenshots have been obtained. Verify first
  launch, landscape/small-screen layout, permission denial/approximate location,
  and cold start with an installed region in airplane mode.
  `PanoramaNavigationTest` covers the manual demo, region entry points, and settings
  once run with `./gradlew :app:connectedDebugAndroidTest` on a clean emulator.
- Validate sightlines independently with projected GDAL viewsheds or another
  renderer, then check known viewpoints and summit identities in the field.
- Benchmark real HGT data on a named Android phone. Draft horizon sampling is
  adaptive; per-peak rays sample more finely. This is not a mathematically exact
  surface-intersection solver. Near-field obstacles and narrow ridges require
  uncertainty handling and further validation.
- Test orientation axes, north correction, pitch, and magnetic interference on
  real hardware. The uncertainty model uses prototype height-error estimates.
- A hosted region catalogue, automatic background download scheduler, geoid-aware
  GNSS altitude conversion, and optional calibrated camera overlay remain future
  work. The current app uses DEM ground height plus phone height; it does not mix
  raw ellipsoidal GNSS altitude with EGM96 terrain.

## Commit Checkpoints

The user authorized focused commits. Staging was attempted, but this session
cannot create `.git/index.lock`, so no implementation commits could be made here.
Suggested groups once Git writes are available:

1. Geographic models, terrain engine, HGT/demo adapters, and core tests.
2. Offline pack preparation/import/download, Room metadata, and integrity tests.
3. Panorama UI, settings, location/orientation providers, and documentation.

## Completion Audit and Unblocking

The same environment restrictions were confirmed across three consecutive goal
turns. The goal is blocked, not complete. No APK or real region pack is currently
available in the project outputs for end-to-end verification.

| Plan gate | Current evidence | Remaining requirement |
| --- | --- | --- |
| 0: Android baseline | Kotlin compilation, Room/KSP generation and schema checks pass independently. | Full Gradle metadata check, APK assembly, and Gradle unit-test run; local socket creation is denied. |
| 1: Synthetic prototype | Demo scene and geometry tests pass; Compose sources compile. | Execute the app and navigation test on an emulator/device; inspect rendered layouts. |
| 2: Real offline terrain | HGT reader, pack tool, and installer tests pass with explicitly synthetic fixtures. | Download and curate real SRTM/OSM data, install its pack, then cold-start in airplane mode. DNS resolution is unavailable here. |
| 3: Visibility accuracy | Analytic occlusion, missing-data, height-consistency, and projection tests pass. | Independent real-terrain reference views and known-viewpoint validation. |
| 4: GPS and compass | Providers, permissions, heading correction and calibration controls compile. | Real-device orientation, accuracy, denial/stale-fix, and lifecycle checks. |
| 5: Region management | Import integrity, corruption detection, restart recovery and download-resume tests pass. | Real HTTPS transfer and installed-pack lifecycle verification on Android. |
| 6: Optional camera | Camera dependencies/permissions are present. | Calibrated overlay remains optional future work, as specified in the plan. |

Resume in a session that permits Gradle's local sockets and outbound DNS/HTTPS.
Git metadata writes are also needed to create the authorized checkpoint commits.
Use [region-packs.md](region-packs.md) to supply genuine source files or a prepared
pack, then run:

```sh
./gradlew :app:checkDebugAarMetadata :app:assembleDebug :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
```

The second command needs a connected emulator/device. Follow with real-region
airplane-mode checks, independent terrain comparisons, and hardware/field
validation before considering the applicable plan gates complete.
