# Terrain performance for camera alignment

The camera pipeline needs the terrain skyline as a geometric prediction. This
change optimizes HGT access without reducing terrain resolution, extending ray
steps, changing visibility margins, or discarding uncertain candidates.

## Investigation

A full real-data Jharkhand scene performs **12,819,216 terrain samples** for 1,197
candidate peaks. Uttarakhand performs **1,831,173 samples** for 556 candidates.
The fine, independent named-peak rays account for much of Jharkhand's cost.

Previously each sample allocated latitude/longitude lists, constructed a tile
filename, looked up the tile, then looked up the same decoded height block up to
four times for bilinear interpolation. Most adjacent ray samples use the same
tile and block.

The optimized implementation uses a collision-free integer key for one-degree
tiles, primitive loops for shared-edge fallback, and cursors for the last tile
and decoded block. Cache misses still use the existing access-ordered LRU maps.
Only the lookup path changes; interpolation and sampled coordinates are unchanged.

Four open tiles, 384 128×128 decoded blocks per tile (48 MiB total), read buffers
(under 4 MiB total), synchronized access, NoData handling, and shared-edge fallback
remain in place. Closing clears the cursors as well as the caches.

A full-width row-band alternative reduced read amplification but decoded more
unused heights and was slower on both real regions; it was rejected.

## Desktop measurements

Measured on the same host with Android Studio's bundled JVM, `-Xmx768m`, three
full calculations per region, each using a newly opened HgtTerrain. Provider
files were already in the operating system's file cache. Timings exclude pack
verification/download and are **not Android/device latency claims**.

| Region | Before (seconds) | After (seconds) | Median improvement |
|---|---|---|---|
| Jharkhand | 2.573, 2.711, 2.721 | 1.590, 1.481, 1.456 | 45% |
| Uttarakhand Himalaya | 1.822, 1.498, 1.464 | 1.378, 1.360, 1.363 | 9% |

The previously observed approximately 38-second debug-emulator calculation must
be remeasured on Android. These desktop timings do not establish that result.
No camera-frame or outdoor end-to-end latency is implied.

## Correctness and reproduction

`HgtTerrainCacheTest` covers repeated tile eviction/reopen, more blocks than the
LRU capacity, dateline fallback, corrupt-primary edge fallback, bilinear NoData
across a block boundary, missing tiles, and outside coverage. Existing terrain
visibility tests retain narrow-ridge and unknown-coverage coverage.

The opt-in `TerrainPerformanceTest` loads the existing verified regional packs,
prints three timings and terrain-sample counts per pack, and requires identical
complete scene objects across fresh application caches. It never downloads or
installs anything:

```sh
PEAKBUD_TERRAIN_BENCH_DIR=/private/tmp/peakbud-broad-validation/installed \
  JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :app:testDebugUnitTest --tests '*TerrainPerformanceTest' --rerun-tasks
```

For cache-only before/after verification, an additional local comparison compiled
the pre-change HgtTerrain under a different class name and calculated both real
regions with the same terrain engine and peak data. Compare full MountainScene
equality, rather than just candidate counts or a rounded skyline. This passed
with exact equality for all 720 horizon samples and all 1,197/556 complete
PeakView records in the Jharkhand/Uttarakhand packs. The local Kotlin runner also
passed 46 core/cache tests and the opt-in benchmark test (six full calculations).

## Remaining limits

This optimization retains all named rays and calculates a complete 360° scene;
it is not an incremental skyline API. Progressive prediction, spatial indexing,
and reuse between nearby observers may improve startup further, but must preserve
missing-data semantics and independent named-peak ray accuracy. Physical-phone
measurements remain necessary for practical camera alignment latency.
