# Basic Dependencies

PeakBud is a native Android application built with Kotlin and Jetpack Compose.
Versions are centralized in `gradle/libs.versions.toml` and dependencies are
declared in `app/build.gradle.kts`.

## Installed Foundation

| Capability | Dependency | Purpose |
| --- | --- | --- |
| Asynchronous work | Kotlin coroutines Core and Android 1.10.2 | Structured concurrency, Flow, background terrain calculations, and main-thread updates. Kotlin coroutines provide the async functionality for this app. |
| Coroutine tests | Kotlin coroutines Test 1.10.2 | Controlled dispatchers and virtual time in unit tests. |
| Camera | CameraX Core, Camera2, Lifecycle, and View 1.5.3 | Lifecycle-aware camera access and `PreviewView`, which can be embedded in Compose using `AndroidView`. |
| UI state | Lifecycle Runtime Compose and ViewModel Compose | Lifecycle-aware Flow collection and screen state; uses the existing Lifecycle version. |
| Offline peak database | Room Runtime and Compiler 2.8.4 | Typed SQLite storage; KSP 2.3.5 generates database implementations once entities and DAOs are added. |
| Settings | Preferences DataStore 1.2.1 | Persist units, calibration preferences, and selected offline region. |
| GPS | Android `LocationManager` and AndroidX Core `LocationManagerCompat` | Access satellite location without requiring Google Play services. AndroidX Core is already included. |
| Orientation | Android `SensorManager` and `GeomagneticField` | Read orientation sensors and correct magnetic heading toward true north; no extra library is needed. |
| Panorama rendering | Existing Compose Canvas and graphics APIs | Draw the terrain silhouette and peak labels. |

## Permissions and Hardware

The manifest declares camera, coarse location, and fine location permissions.
Camera and location still need runtime permission requests when their features
are implemented. Request coarse and fine location together; handle approximate
location, denial, disabled GPS, and missing hardware gracefully. Camera, GPS,
and compass hardware are optional so manual-location panorama mode can remain
available. Check sensor availability before use.

Use foreground location for the initial prototype. Sensor listeners and camera
sessions should follow the screen lifecycle. Background location, microphone,
and broad storage permissions are unnecessary for this foundation. Store offline
data in app-private storage.

## Implementation Still Needed

These declarations prepare the project; they do not implement camera preview,
location collection, sensor processing, or persistence. Camera integration remains
optional for the first terrain panorama.

Room will hold peak metadata. Terrain elevation tiles should live as files in
app-private storage, with their format and reader chosen after selecting a data
source. Terrain data, peak datasets, line-of-sight calculations, coordinate math,
and offline region packaging still need implementation.

Add an HTTP client, WorkManager, or a terrain-format library when downloads and
the elevation data format are defined. The current foundation does not require
an internet connection or an API key.

## Verification

Run `./gradlew :app:assembleDebug :app:testDebugUnitTest` to resolve dependencies,
compile the app, process the manifest, and run the existing unit tests.

The setup session validated the version catalog, Gradle aliases, and manifest
structure. Build verification could not run: the session blocks writes to the
default Gradle cache and blocks the local socket Gradle needs even when using a
temporary writable cache. Dependency resolution and compilation remain unverified;
run the command above from Android Studio's terminal or sync the project there.

## References

- [CameraX releases](https://developer.android.com/jetpack/androidx/releases/camera)
- [Kotlin coroutines 1.10.2](https://github.com/Kotlin/kotlinx.coroutines/releases/tag/1.10.2)
- [Room setup and releases](https://developer.android.com/jetpack/androidx/releases/room)
- [DataStore releases](https://developer.android.com/jetpack/androidx/releases/datastore)
- [KSP 2.3.5](https://github.com/google/ksp/releases/tag/2.3.5)
- [Android position sensors](https://developer.android.com/develop/sensors-and-location/sensors/sensors_position)
