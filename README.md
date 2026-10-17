# PeakBud

An Android hiking companion that identifies mountain peaks from local elevation
and peak data. Kotlin, Jetpack Compose, CameraX, coroutines, Room, and DataStore.

The app opens in **Live camera** mode. Allow foreground location, enable the rear camera, then open **Regions**,
choose a hiking area, and tap **Download**. Peak labels use terrain
visibility, GPS, true-north heading, pitch, roll, and the camera preview transform.
The built-in catalogue starts with Poon Hill, Gokyo, and Auli. Peak names
and trusted terrain links are bundled; terrain downloads on demand.

- Tap **Full screen** in camera mode for an edge-to-edge preview with location metadata.
  Use **Exit full screen** or Android Back to return.
- Tap a peak label for its elevation, distance, and visibility estimate.
- Use **−1° / +1° / Reset** to adjust compass alignment.
- Switch to **Panorama** to explore terrain without the camera or with a manual viewpoint.
- The **Explorer demo** contains fictional peaks and never overlays them on live video.
- Open **Regions** to download a named area, resume, open it offline, or delete it.
- View heading, coordinates, GPS accuracy, altitude/source, and camera view angles in the status panel.

Labels are sensor-based estimates, without image recognition. Compass interference,
lens metadata, GPS error, clouds, and vegetation can affect alignment or visibility.
Device and outdoor validation are still needed before relying on peak identities.

## Development

Open the project in Android Studio with JDK 21 and Android SDK Platform 37.0.
The app supports Android API 26+, targets API 36, and compiles against API 37.0.

```sh
./gradlew :app:checkDebugAarMetadata :app:assembleDebug :app:testDebugUnitTest
python3 tools/check-room-schema.py
```

See [the implementation plan](docs/plan.md), [region preparation instructions](docs/region-packs.md),
[built-in region catalogue](docs/region-catalog.md), and [current implementation and verification status](docs/implementation-status.md).
