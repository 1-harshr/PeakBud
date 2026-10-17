# PeakBud

An Android hiking companion that identifies mountain peaks from local elevation
and peak data. Kotlin, Jetpack Compose, CameraX, coroutines, Room, and DataStore.

The app opens with a clearly labelled fictional landscape. Import a prepared
SRTM/OSM region to work with real terrain. Real region data is not yet bundled.

- Drag the panorama or use left/right controls to look around.
- Tap a labelled summit, or open **Peaks**, for elevation, distance, and visibility.
- Choose a manual viewpoint or use GPS within an installed region.
- Enable compass alignment; use Settings for calibration, phone height, and units.
- Open **Regions** to import, download, select, inspect, or delete offline packs.

Terrain visibility is an estimate; missing terrain is unknown and borderline
results are marked uncertain. Clouds and vegetation are outside the DEM model.
Camera overlay is a later optional feature and is not wired into the current UI.

## Development

Open the project in Android Studio with JDK 21 and Android SDK Platform 37.0.
The app supports Android API 26+, targets API 36, and compiles against API 37.0.

```sh
./gradlew :app:checkDebugAarMetadata :app:assembleDebug :app:testDebugUnitTest
python3 tools/check-room-schema.py
```

See [the implementation plan](docs/plan.md), [region preparation instructions](docs/region-packs.md),
and [current implementation and verification status](docs/implementation-status.md).
