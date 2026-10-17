# PeakBud

An Android hiking companion that identifies mountain peaks from local elevation
and peak data. Kotlin, Jetpack Compose, CameraX, coroutines, Room, and DataStore.

The app opens on **Home**, with a terrain panorama. Open **Camera**, allow foreground location and rear-camera access, then open **Regions**,
choose a hiking area, and tap **Download**. Peak labels use terrain
visibility, GPS, true-north heading, pitch, roll, and the camera preview transform.
The built-in catalogue starts with broad **Uttarakhand Himalaya** (250 km search) and
**Jharkhand · Hills and Plateau** (200 km search) packs, alongside smaller hiking areas.
Coverage includes surrounding terrain and peaks across state/country borders. Peak names
and trusted terrain links are bundled; terrain downloads on demand.

- Tap **Full screen** in camera mode for an edge-to-edge preview with location metadata.
  Use **Exit full screen** or Android Back to return.
- Tap a peak label for its elevation, distance, and visibility estimate.
- Use **−1° / +1° / Reset** to adjust compass alignment.
- Switch to **Home** to explore terrain without the camera or use **Choose spot** for a manual viewpoint.
- **Settings → Demo mode** adds fictional peaks to Home; they never overlay live video.
- Open **Regions** to download a named area, resume, open it offline, or delete it.
- View heading, coordinates, GPS accuracy, altitude, and camera view angles in the position details.
- **Settings → Appearance** follows the device theme or saves a Light/Dark override.
  Shared spacing, shapes, scalable typography, and unit formatting keep the screens consistent.

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
[built-in region catalogue](docs/region-catalog.md), [broad regional coverage](docs/regional-coverage.md), [design system and UX review](docs/design-system.md), and [current implementation and verification status](docs/implementation-status.md).
