# Camera alignment: validation results

Measured 17 October 2026. Desktop numbers use Android Studio's bundled JVM on the development Mac; emulator numbers
use the arm64 Pixel emulator running a **debug** build. Neither is a physical phone outdoors, and nothing here is a claim
of outdoor recognition accuracy.

## How it works (summary)

1. **Prediction from location only.** `AlignmentHorizon` traces the downloaded terrain from the phone's position across the
   sector the camera faces (field of view ± 25°, every 0.2°, up to the region radius) with standard refraction. Terrain
   within 1 km is excluded; bearings where that near ground would rise above the far skyline are marked unusable, because a
   few metres of GPS error makes near angles unpredictable. No photos of mountains are stored or downloaded.
2. **Skyline in the frame.** `SkylineExtractor` walks down each column from a clear sky band with an adaptive sky colour,
   ignores pale haze and thin cloud wisps, drops smooth cloud tops, and rejects fragmented, flat, roof-like or foliage-like
   outlines. Frames without a clear bluish sky band are refused.
3. **Registration.** Boundary pixels become camera rays through the preview's calibrated intrinsics (CameraX transforms,
   roll included). `SkylineMatcher` searches ±20° heading and ±10° pitch around the sensor estimate, skips unpredictable
   columns, and accepts only a tight, well-supported fit with clear relief and no competing direction.
4. **Session.** Three agreeing frames are required. An accepted correction is applied on top of the sensor pose, follows
   small drift, and is revoked on a different fit, three misses, staleness, manual nudging, a new position or region.

## Results

| Check | Data | Result |
| --- | --- | --- |
| Skyline extraction, real photo | Dhanaulti (CC BY 2.0), 28 hand-marked points | median 2.2 px, p90 7.6 px (previous fixed-blue rule: 140 px, it followed the foreground ridge) |
| Negative scenes, real photos | sky only, ground only, trees, temple and trees under cloud | all refused at extraction |
| Cloud challenges, real photos | Deoria Tal, Hemkund | refused (conservative) |
| Matching on real terrain, phone geometry | 60 random lookouts per region, 66° lens, sensor error ±15° / ±4°, pixel noise, 10% missing columns, a foreground blob | Uttarakhand: 28 accepted, **0 wrong**, heading error median 0.072°, p90 0.146°. Jharkhand plateau: 4 accepted, **0 wrong** (low relief is refused) |
| Real photo against terrain from its recorded location | Dhanaulti, EXIF 120 mm-equivalent | recorded coordinate is on a hillside with the view blocked; 4 of 5 nearby lookouts point the camera at Nanda Devi's bearing (91.7°, 163 km), but the fit is not unique, and the production matcher answers **AMBIGUOUS** instead of guessing |
| End-to-end controller, rendered frames | known pose, sensor 7°/2° off | aligned to within 0.3°; ground frames revoke alignment; ground-only frames fail with "needs a clear sky"; a foreground tree hides the covered summit; reset clears state |
| Android flow, emulator | virtual indoor room | "Couldn't match this view · Needs a clear sky above the mountains"; no crash across background/foreground; works in airplane mode |

## Latency

| Step | Measurement |
| --- | --- |
| Terrain prediction, desktop | 1.0 s per view (Uttarakhand, 250 km), 0.34 s (Jharkhand, 200 km) |
| Terrain prediction, emulator debug | 7.3–14 s (Jharkhand, 200 km, 110° sector); cached for Retry and prefetched once peak labels are ready. Catalogue areas (15–40 km) are far smaller |
| Frame analysis, desktop | 65–140 ms when a skyline is matched; 1–2 ms when a frame is refused; frames analysed at most every 300 ms on a dedicated thread |
| First peak labels, emulator | 5.6 s (nearest-first progressive results) instead of waiting for the full 37 s Jharkhand calculation |

## Limitations and what needs a physical phone outdoors

- **Accuracy outdoors is unmeasured.** Real-terrain numbers above isolate registration from image extraction. Outdoor tests
  need recorded frames with synchronized GPS/orientation and an independent pose (surveyed summit bearings).
- **Clear skies only.** Overcast, white or heavily clouded skies are refused. Snow-covered summits under haze worked on the
  one real clear photo; more real data is needed.
- **Viewpoint sensitivity.** Near terrain (< 1 km) is ignored, so valley floors with nearby slopes usually cannot align.
  Lookouts work best.
- **Low-relief regions** (e.g. the Jharkhand plateau) rarely have enough skyline shape and are mostly refused.
- **Sensor timing.** Frames and orientation readings are not timestamp-synchronised; frames taken while the phone swings are
  skipped. Lens distortion is not modelled.
- **Phone performance.** Terrain prediction on large regions takes several seconds on the emulator; per-frame cost on real
  phones and battery impact need measuring.
- **Foreground occlusion** is detected only where the observed outline rises clearly above the predicted skyline at a
  summit's bearing; lower obstructions are not detected.

## Reproduce

```sh
# unit and end-to-end tests
./gradlew :app:testDebugUnitTest
# opt-in real-terrain accuracy and real-photo checks (installed regional packs required)
PEAKBUD_TERRAIN_BENCH_DIR=/private/tmp/peakbud-broad-validation/installed \
  ./gradlew :app:testDebugUnitTest --tests '*RealTerrainMatchingTest*' --tests '*RealPhotoAlignmentTest*' --rerun-tasks -i
# optional overlays of extracted boundaries and the registered photo
PEAKBUD_DEBUG_DIR=/tmp/peakbud-debug ...
```
