# Camera-assisted alignment implementation plan

Goal: attach peak names to their real positions in the camera by registering a measured skyline to the offline DEM skyline. A compass overlay alone does not satisfy this goal.

## Approach and order

1. Independently implement a JVM-testable image boundary extractor, camera-ray geometry, bounded terrain registration, and alignment session. See [research](camera-alignment-research.md) for primary sources and licence decisions. No third-party skyline implementation or trained weights are being copied. Initially target clear, textured ridges beneath a visible sky; do not silently relax rejection thresholds to make demonstrations work.
2. Extract a top-connected sky boundary at bounded resolution. Keep unsupported columns missing, reject weak/flat/jagged/rectilinear evidence, and retain the boundary as foreground evidence. Sky colour is supporting evidence, not semantic proof of mountains. Evaluate real trees/buildings/ground/clouds as well as synthetic shapes; if false positives remain, improve the extractor or introduce appropriately licensed semantic inference before claiming the negative-view requirement is met.
3. Invert calibrated pixel rays, including roll and preview crop, to compare each observed boundary point against the predicted terrain elevation at its bearing. Search heading ±20° and pitch ±10° around sensor readings, followed by local refinement. Reject missing DEM, insufficient angular support/relief, high robust error, competing yaw minima, and search-edge solutions. Report measured residual and support internally, not an invented recognition probability.
4. Require agreement over multiple frames before accepting. Keep corrections separate from sensor pose. Recheck visually as the phone moves; revoke on repeated mismatches, stale validation, lifecycle/geometry/scene changes, poor location, or manual trim. A user can retry or reset. Suppress aligned labels where foreground evidence covers their anchor; registration is not proof that every summit is visible. Preserve terrain visibility filtering.
5. Bind CameraX Preview and ImageAnalysis together with a common ViewPort. Analyze on a dedicated single worker with KEEP_ONLY_LATEST, throttle work, and close every proxy in finally. Use cropRect, rotation, sensor-to-buffer and sensor-to-view transforms, not assumed thumbnail FOV. Guard asynchronous results against stale pose, camera, region, and lifecycle generations.
6. Add an explicit Align with camera action and compact Sensor estimate / Aligning / Aligned / Couldn't match states with reset/retry. Retain manual adjustment, metadata, fullscreen, theme tokens, strings, and the existing four tabs. No new calibration settings.
7. Profile actual regional HGT reads and terrain rays, retain cancellation and numerical results, and measure Android timing. Frame processing reuses the observer skyline and never repeats the regional terrain calculation per frame.

## Validation and completion gates

- Synthetic known-geometry tests: heading/pitch recovery including roll, heading wrap, crop/rotation round trips, repeated/flat skyline rejection, missing terrain, large incorrect headings, partial foreground occlusion, temporal agreement and loss.
- Real licensed fixtures: use recorded source viewpoints and manually annotated clear-ridge boundary; cloudy and partially occluded views plus real building/tree/ground/sky negatives. See [fixture attribution](camera-alignment-fixtures.md). These photos do not have calibrated intrinsics/pose, so they cannot establish absolute angular registration accuracy.
- Android: build, unit tests, lint, instrumented control flow, lifecycle and permission checks; emulator camera negatives; downloaded-region airplane-mode operation. Preserve downloaded app data when installing.
- Measure extraction/matching and terrain latency with device/runtime/data identified. Outdoor physical-phone testing remains necessary for magnetic errors, lens distortion, image/sensor timing, real alignment accuracy, and false acceptance rates. Never call emulator results outdoor recognition proof.
- Make coherent incremental commits, preserve unrelated IDE changes, and do not push. Final audit must trace every requested behavior to code and evidence; unfinished or unverified requirements remain explicitly open.

## Progress

Plan written before alignment implementation. Research and real-image acquisition are available; implementation and acceptance gates remain open.
