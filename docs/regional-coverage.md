# Broad regional coverage

Uttarakhand and Jharkhand are the first broad offline packs. They appear first in Regions; the existing small viewpoint packs remain available as lighter downloads. Both keep the existing 1 arc-second terrain (approximately 30 m north/south grid spacing); terrain is not downsampled to make a smaller but less reliable skyline.

| Pack | Observer rectangle | Peak search | Terrain rectangle | Tiles | Download | Terrain on device | Bundled peak/hill records |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Uttarakhand Himalaya | 28.70–31.50 N, 77.50–81.10 E | 250 km | 26–34 N, 74–84 E | 80 | 910,569,467 bytes (868.4 MiB) | about 2.1 GB | 1,579 |
| Jharkhand · Hills and Plateau | 21.90–25.40 N, 83.20–88.00 E | 200 km | 20–28 N, 81–91 E | 80 | 634,470,030 bytes (605.1 MiB) | about 2.1 GB | 3,184 |

These are inclusive rectangular coverage areas, not administrative boundary polygons. Uttarakhand includes Garhwal/Kumaon and surrounding terrain and names across Tibet/China, Nepal, Himachal and nearby areas. Jharkhand covers Ranchi, Jamshedpur/Dalma, Parasnath/Giridih, Netarhat and Rajmahal/Sahibganj, with surrounding terrain outside the state. The bundled source may omit a locally named summit even when terrain is covered; a named hill or a country border does not itself establish visibility.

The Uttarakhand rectangle encompasses the state extents described by the [Uttarakhand government](https://pwd.uk.gov.in/about-department/about-us/). Jharkhand's broad northern/eastern coverage includes the [Sahibganj district extent](https://sahibganj.nic.in/profile/). [Giridih's district government](https://giridih.nic.in/tourist-place-category/religious/) documents the Parasnath hills. These sources describe geography; actual bundled positions and names retain their GeoNames provenance.

## Data preparation

`tools/catalog/build_catalog.py` computes the terrain buffer from observer bounds plus the viewing radius, independent of borders. It includes every intersecting one-degree tile and validates/decompresses the actual provider files to pin both wire and terrain SHA-256 values. Only peak/attribution snapshots and download recipes ship in the APK.

Uttarakhand merges GeoNames IN/CN/NP/PK dumps. Jharkhand merges IN/NP/BD/BT/CN dumps because the expanded terrain rectangle crosses those datasets. Broad packs include individual mountains, peaks, and hills (MT/PK/HLL); range centroids are not relabelled as individual summits. Each record retains its country code, ID, source link, modification date, and nullable published elevation. Dataset licences, provider links and country dump hashes are bundled in each attribution file.

```sh
python3 tools/catalog/build_catalog.py \
  --dumps /path/to/geonames-country-zips \
  --tiles /path/to/verified-skadi-gzip-tiles \
  --date YYYY-MM-DD \
  --regions uttarakhand-himalaya jharkhand
```

## Runtime changes

- Pack validation and the calculation engine accept up to 300 km searches. Pack size is bounded at 4 GiB; the per-tile format, hash, path and coverage checks remain enforced.
- Catalogue installation writes directly to a staging directory and activates only after all bytes and peak records validate. This avoids the previous full local ZIP and extraction copies. Completed compressed downloads survive interruption; abandoned staging is removed at startup.
- Matching installed terrain is hash-checked and shared through filesystem hard links, with copy fallback. Removal of a region does not remove another region's linked data. Free-space checks reserve the worst-case storage, including cached compressed tiles.
- Room queries the rectangle around the observer's actual search radius, then the terrain engine applies the circular distance filter. It does not scan the whole country catalogue for every viewpoint.
- Long-range angles use spherical line-of-sight geometry. Each ray reuses its origin/bearing trigonometry and compares elevation slopes before converting to angles, reducing repeated math without changing sample spacing. Curvature is included once. Atmospheric refraction is not modelled, so apparent long-distance positions and marginal visibility still require field validation.
- HGT blocks read their contiguous row span in one operation instead of 128 seek/read pairs. The reader keeps at most four open tiles, 48 MiB of height blocks, and approximately 4 MiB of reusable read buffers.
- Named peaks keep their independent fine terrain ray. A ray stops early only after its current maximum ridge proves occlusion with the same error budget used for final classification. Missing terrain cannot establish visibility. The drawn draft skyline retains adaptive distance sampling.
- Label placement prioritizes terrain-visible candidates, then higher angular positions, instead of allowing the nearest low hills to occupy every label slot. The existing collision handling and uncertain-peak controls remain. Unlabelled panorama markers are capped to avoid a dense white band when a broad pack supplies hundreds of candidates.

A search radius is a data/computation limit, not a promise that a mountain is visible at that distance. Heights, haze, clouds, vegetation, gazetteer positions and sensor alignment still affect the result. The app continues to overlay terrain-derived candidates on the camera; it does not recognize arbitrary imported photographs.

## Validation

The full-data JVM test uses the actual pinned provider gzip files, exercises the same catalogue installer, verifies both complete regions, and computes a panorama at Auli and Ranchi. It makes no network requests during a test:

```sh
PEAKBUD_SKADI_DIR=/path/to/verified-skadi-gzip-tiles \
PEAKBUD_REGIONAL_QA_DIR=/tmp/peakbud-regional-qa \
./gradlew :app:testDebugUnitTest --tests '*RealRegionalDataTest' --rerun-tasks
```

Without the data environment variable, that large-data test is skipped. Ordinary tests cover both manifests, named Parasnath/hill records, Chinese-source candidates over 50 km from Auli, 200 km synthetic visibility and missing-data paths, spherical geometry, early occlusion, tile reuse/deletion, and cleanup after interruption.

### Checks completed on 17 October 2026

- All 54 JVM tests passed, including the opt-in full-data test with no skipped tests. Both complete packs were installed from actual cached provider bytes and verified against the shipped manifest hashes. Auli produced 556 candidates and Ranchi 1,197; all 720 horizon directions had terrain coverage at both points. Results remained conservative (uncertain/occluded), rather than claiming confirmed visual recognition.
- Debug APK, instrumentation APK and Android lint passed; three Python preparation tests and Room schema/DAO checks passed.
- Seven emulator UI tests passed, covering navigation, the new region cards, saved appearance, resource formatting, and full-screen camera metadata/exit.
- Jharkhand was downloaded over the provider's actual public HTTPS URLs through the app's region picker, installed, and reopened after a cold start in airplane mode. The Ranchi GPS mock produced an offline panorama and candidate labels beyond 50 km. Camera preview and position metadata were inspected offline. Airplane mode was restored afterwards.
- Latest debug-emulator Ranchi calculation took about 38 seconds, with roughly four seconds for initial pack verification. Desktop calculation was about 2–3 seconds per region. Android initial-scene latency remains a performance limitation to tune and measure on physical devices; these results do not establish outdoor identification accuracy.
