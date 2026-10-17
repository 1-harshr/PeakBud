# Built-in region downloads

Users open **Regions**, choose a named hiking area, and tap **Download**. They do
not search for data, paste URLs, or import files. The region list, HTTPS links,
versions, sizes, SHA-256 checksums, and small peak catalogues ship in the APK.
Terrain downloads once and is installed into app-private offline storage.

## Catalogue

Built-in hiking areas. Each is a small supported viewpoint box with a viewing radius,
not country-wide coverage. Terrain tiles are chosen automatically so that the box plus
radius is fully covered. Peak counts are GeoNames MT/PK records inside the terrain
tiles; only candidates inside the radius with usable sightlines can be labelled.

| Region | Supported viewpoints | Peak search radius | Download | Terrain storage | Peak records |
| --- | --- | --- | --- | --- | --- |
| Poon Hill · Annapurna, Nepal | 28.38–28.46 N, 83.63–83.78 E | 40 km | 36 MiB | 49 MiB | 141 |
| Gokyo · Everest, Nepal | 27.92–28.02 N, 86.64–86.70 E | 25 km | 33 MiB | 49 MiB | 76 |
| Kala Patthar · Everest, Nepal | 27.96–28.00 N, 86.80–86.84 E | 15 km | 33 MiB | 49 MiB | 76 |
| Annapurna Base Camp, Nepal | 28.50–28.55 N, 83.85–83.90 E | 25 km | 36 MiB | 49 MiB | 141 |
| Langtang · Kyanjin Gompa, Nepal | 28.19–28.23 N, 85.52–85.60 E | 20 km | 17 MiB | 25 MiB | 45 |
| Nagarkot, Nepal | 27.70–27.73 N, 85.50–85.54 E | 40 km | 32 MiB | 49 MiB | 52 |
| Auli · Garhwal, India | 30.50–30.55 N, 79.55–79.60 E | 30 km | 18 MiB | 25 MiB | 88 |
| Chopta · Tungnath, India | 30.47–30.51 N, 79.21–79.25 E | 20 km | 18 MiB | 25 MiB | 88 |
| Valley of Flowers · Hemkund, India | 30.70–30.74 N, 79.58–79.64 E | 20 km | 18 MiB | 25 MiB | 88 |
| Kedarnath, India | 30.72–30.75 N, 79.05–79.09 E | 20 km | 36 MiB | 49 MiB | 113 |
| Triund · Dharamshala, India | 32.22–32.27 N, 76.32–76.40 E | 20 km | 18 MiB | 25 MiB | 108 |
| Nainital · Naina Peak, India | 29.37–29.42 N, 79.42–79.47 E | 40 km | 14 MiB | 25 MiB | 72 |
| Fairy Meadows · Nanga Parbat, Pakistan | 35.36–35.40 N, 74.56–74.60 E | 25 km | 18 MiB | 25 MiB | 62 |
| Hunza · Eagle's Nest, Pakistan | 36.31–36.35 N, 74.65–74.71 E | 30 km | 36 MiB | 49 MiB | 105 |
| Paro · Tiger's Nest, Bhutan | 27.47–27.52 N, 89.33–89.40 E | 25 km | 18 MiB | 25 MiB | 3 |
| Kazbegi · Gergeti, Georgia | 42.64–42.68 N, 44.62–44.66 E | 20 km | 15 MiB | 25 MiB | 155 |
| Mount Fuji · Kawaguchiko, Japan | 35.48–35.52 N, 138.74–138.80 E | 30 km | 26 MiB | 49 MiB | 253 |
| Kamikochi · Japan Alps, Japan | 36.23–36.27 N, 137.60–137.68 E | 20 km | 15 MiB | 25 MiB | 199 |
| Zermatt · Matterhorn, Switzerland | 45.96–46.02 N, 7.72–7.80 E | 15 km | 31 MiB | 49 MiB | 2086 |
| Courmayeur · Mont Blanc, Italy | 45.77–45.81 N, 6.95–7.00 E | 15 km | 32 MiB | 49 MiB | 1097 |
| Grindelwald · Jungfrau, Switzerland | 46.62–46.68 N, 7.95–8.06 E | 20 km | 32 MiB | 49 MiB | 3838 |
| Dolomites · Tre Cime, Italy | 46.59–46.64 N, 12.28–12.33 E | 20 km | 16 MiB | 25 MiB | 824 |
| Lake Bled · Triglav, Slovenia | 46.35–46.40 N, 14.08–14.14 E | 30 km | 28 MiB | 49 MiB | 167 |
| Zakopane · Tatras, Poland | 49.23–49.31 N, 19.93–20.00 E | 20 km | 21 MiB | 49 MiB | 802 |
| Innsbruck · Nordkette, Austria | 47.26–47.32 N, 11.36–11.42 E | 20 km | 13 MiB | 25 MiB | 506 |
| Zugspitze · Garmisch, Germany | 47.40–47.50 N, 10.98–11.10 E | 20 km | 26 MiB | 49 MiB | 2255 |
| Lake District · Keswick, United Kingdom | 54.50–54.62 N, 3.20–3.05 W | 20 km | 12 MiB | 49 MiB | 95 |
| Yosemite · Glacier Point, United States | 37.70–37.76 N, 119.62–119.52 W | 25 km | 15 MiB | 25 MiB | 307 |
| Rocky Mountain · Bear Lake, United States | 40.28–40.40 N, 105.70–105.55 W | 25 km | 13 MiB | 25 MiB | 343 |
| Grand Teton · Jenny Lake, United States | 43.70–43.80 N, 110.78–110.68 W | 15 km | 13 MiB | 25 MiB | 162 |
| Mount Rainier · Paradise, United States | 46.76–46.80 N, 121.75–121.70 W | 15 km | 14 MiB | 25 MiB | 260 |
| Glacier · Logan Pass, United States | 48.68–48.72 N, 113.72–113.68 W | 20 km | 13 MiB | 25 MiB | 273 |
| Banff · Lake Louise, Canada | 51.40–51.45 N, 116.22–116.14 W | 20 km | 29 MiB | 49 MiB | 399 |
| Machu Picchu, Peru | 13.18–13.15 S, 72.57–72.52 W | 15 km | 18 MiB | 25 MiB | 751 |
| El Chaltén · Fitz Roy, Argentina | 49.35–49.30 S, 72.92–72.86 W | 20 km | 23 MiB | 49 MiB | 134 |
| Kilimanjaro · Moshi, Tanzania | 3.37–3.33 S, 37.32–37.36 E | 35 km | 11 MiB | 25 MiB | 8 |
| Table Mountain · Cape Town, South Africa | 33.98–33.93 S, 18.38–18.44 E | 15 km | 8 MiB | 49 MiB | 131 |
| Queenstown · Remarkables, New Zealand | 45.05–45.00 S, 168.63–168.70 E | 20 km | 28 MiB | 49 MiB | 249 |
| Aoraki · Mount Cook, New Zealand | 43.75–43.72 S, 170.08–170.12 E | 25 km | 21 MiB | 49 MiB | 251 |

Downloading another region does not extend an existing region's observer bounds.
Users can filter the list with the search field (place name, country or description).

## Files and sources

`app/src/main/assets/regions/catalog.json` is the built-in download catalogue.
Each entry contains an existing format-v1 pack manifest plus pinned gzip terrain
sources. Its subdirectory supplies `peaks.json` and `attribution.txt`. The Kotlin
`RegionCatalog` loader and `CatalogInstaller` use those recipes automatically.

Terrain is public [Mapzen/Tilezen Skadi](https://github.com/tilezen/joerd/blob/master/docs/formats.md)
1 arc-second, 3601×3601 HGT with WGS84 / EGM96 heights. Sources are a composite;
we do not relabel it NASA SRTMGL1 v003. Download links follow the provider's
[documented public S3 layout](https://github.com/tilezen/joerd/blob/master/docs/use-service.md).
The catalogue currently pins 48 tiles:

- N27E085: https://s3.amazonaws.com/elevation-tiles-prod/skadi/N27/N27E085.hgt.gz
- N27E086: https://s3.amazonaws.com/elevation-tiles-prod/skadi/N27/N27E086.hgt.gz
- N27E089: https://s3.amazonaws.com/elevation-tiles-prod/skadi/N27/N27E089.hgt.gz
- N28E083: https://s3.amazonaws.com/elevation-tiles-prod/skadi/N28/N28E083.hgt.gz
- N28E084: https://s3.amazonaws.com/elevation-tiles-prod/skadi/N28/N28E084.hgt.gz
- N28E085: https://s3.amazonaws.com/elevation-tiles-prod/skadi/N28/N28E085.hgt.gz
- N28E086: https://s3.amazonaws.com/elevation-tiles-prod/skadi/N28/N28E086.hgt.gz
- N29E079: https://s3.amazonaws.com/elevation-tiles-prod/skadi/N29/N29E079.hgt.gz
- N30E078: https://s3.amazonaws.com/elevation-tiles-prod/skadi/N30/N30E078.hgt.gz
- N30E079: https://s3.amazonaws.com/elevation-tiles-prod/skadi/N30/N30E079.hgt.gz
- N32E076: https://s3.amazonaws.com/elevation-tiles-prod/skadi/N32/N32E076.hgt.gz
- N35E074: https://s3.amazonaws.com/elevation-tiles-prod/skadi/N35/N35E074.hgt.gz
- N35E138: https://s3.amazonaws.com/elevation-tiles-prod/skadi/N35/N35E138.hgt.gz
- N35E139: https://s3.amazonaws.com/elevation-tiles-prod/skadi/N35/N35E139.hgt.gz
- N36E074: https://s3.amazonaws.com/elevation-tiles-prod/skadi/N36/N36E074.hgt.gz
- N36E075: https://s3.amazonaws.com/elevation-tiles-prod/skadi/N36/N36E075.hgt.gz
- N36E137: https://s3.amazonaws.com/elevation-tiles-prod/skadi/N36/N36E137.hgt.gz
- N37W120: https://s3.amazonaws.com/elevation-tiles-prod/skadi/N37/N37W120.hgt.gz
- N40W106: https://s3.amazonaws.com/elevation-tiles-prod/skadi/N40/N40W106.hgt.gz
- N42E044: https://s3.amazonaws.com/elevation-tiles-prod/skadi/N42/N42E044.hgt.gz
- N43W111: https://s3.amazonaws.com/elevation-tiles-prod/skadi/N43/N43W111.hgt.gz
- N45E006: https://s3.amazonaws.com/elevation-tiles-prod/skadi/N45/N45E006.hgt.gz
- N45E007: https://s3.amazonaws.com/elevation-tiles-prod/skadi/N45/N45E007.hgt.gz
- N46E007: https://s3.amazonaws.com/elevation-tiles-prod/skadi/N46/N46E007.hgt.gz
- N46E008: https://s3.amazonaws.com/elevation-tiles-prod/skadi/N46/N46E008.hgt.gz
- N46E012: https://s3.amazonaws.com/elevation-tiles-prod/skadi/N46/N46E012.hgt.gz
- N46E013: https://s3.amazonaws.com/elevation-tiles-prod/skadi/N46/N46E013.hgt.gz
- N46E014: https://s3.amazonaws.com/elevation-tiles-prod/skadi/N46/N46E014.hgt.gz
- N46W122: https://s3.amazonaws.com/elevation-tiles-prod/skadi/N46/N46W122.hgt.gz
- N47E010: https://s3.amazonaws.com/elevation-tiles-prod/skadi/N47/N47E010.hgt.gz
- N47E011: https://s3.amazonaws.com/elevation-tiles-prod/skadi/N47/N47E011.hgt.gz
- N48W114: https://s3.amazonaws.com/elevation-tiles-prod/skadi/N48/N48W114.hgt.gz
- N49E019: https://s3.amazonaws.com/elevation-tiles-prod/skadi/N49/N49E019.hgt.gz
- N49E020: https://s3.amazonaws.com/elevation-tiles-prod/skadi/N49/N49E020.hgt.gz
- N51W116: https://s3.amazonaws.com/elevation-tiles-prod/skadi/N51/N51W116.hgt.gz
- N51W117: https://s3.amazonaws.com/elevation-tiles-prod/skadi/N51/N51W117.hgt.gz
- N54W003: https://s3.amazonaws.com/elevation-tiles-prod/skadi/N54/N54W003.hgt.gz
- N54W004: https://s3.amazonaws.com/elevation-tiles-prod/skadi/N54/N54W004.hgt.gz
- S04E037: https://s3.amazonaws.com/elevation-tiles-prod/skadi/S04/S04E037.hgt.gz
- S14W073: https://s3.amazonaws.com/elevation-tiles-prod/skadi/S14/S14W073.hgt.gz
- S34E018: https://s3.amazonaws.com/elevation-tiles-prod/skadi/S34/S34E018.hgt.gz
- S35E018: https://s3.amazonaws.com/elevation-tiles-prod/skadi/S35/S35E018.hgt.gz
- S44E169: https://s3.amazonaws.com/elevation-tiles-prod/skadi/S44/S44E169.hgt.gz
- S44E170: https://s3.amazonaws.com/elevation-tiles-prod/skadi/S44/S44E170.hgt.gz
- S45E168: https://s3.amazonaws.com/elevation-tiles-prod/skadi/S45/S45E168.hgt.gz
- S46E168: https://s3.amazonaws.com/elevation-tiles-prod/skadi/S46/S46E168.hgt.gz
- S50W073: https://s3.amazonaws.com/elevation-tiles-prod/skadi/S50/S50W073.hgt.gz
- S50W074: https://s3.amazonaws.com/elevation-tiles-prod/skadi/S50/S50W074.hgt.gz

Every URL was downloaded and both compressed and decompressed SHA-256 values were
recorded during preparation on 2026-10-17. There are no dummy
region ZIP URLs and no PeakBud hosting service is needed for this first list.
External source availability is outside the app's control. Changed bytes fail
verification and require a reviewed catalogue update, not silent acceptance.

Peak snapshots come from [GeoNames country dumps](https://download.geonames.org/export/dump/readme.txt)
(one or more countries per region, merged for border areas), under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
They include single mountain/peak features MT/PK, source IDs/URLs and modification
dates. Missing published altitude remains null; the GeoNames coarse DEM column
is not a measured summit height. These gazetteer positions still need geographic
and field validation. Source credits are available on every installed region.

## Installation and recovery

The app downloads bounded gzip files over HTTPS using the existing strong-ETag
resume protection. It verifies the pinned compressed checksum, decompresses with
an exact HGT size cap, and verifies the resulting tile checksum. It packages the
bundled peaks/credits and terrain locally, then uses the existing manifest/hash/
coverage validator and atomic region activation. Partial data is never presented
as an installed region. Temporary preparation needs substantially more free disk
space than the final terrain; a free-space check runs before downloading.

Cancellation/failure retains completed or resumable terrain downloads. The region
card offers **Resume** and **Discard**. Process restart discovers those transfers
by their catalogue URLs. Successful installation removes download fragments and
selects the downloaded region; the list shows **Ready offline**, **Open**, **Credits**,
and **Delete**. Previously installed custom packs remain usable after upgrade,
but their import and URL entry controls have been removed from the product UI.

Downloads are user initiated and currently run while the application process
remains alive; there is no foreground download service or background scheduler.
After interruption, reopen Regions and resume. Core terrain checks, GPS, compass,
and camera identification work offline once the appropriate region is installed.

## Maintaining the catalogue

Use `tools/catalog/build_catalog.py` with the GeoNames country ZIPs and `.hgt.gz` tiles
it needs (`--list-tiles` prints the tile list). It does not publish or download anything:

```sh
python3 tools/catalog/build_catalog.py \
  --dumps /path/to/geonames-country-zips \
  --tiles /path/to/skadi-gzip-tiles \
  --date YYYY-MM-DD
```

Adding an area is a developer task: choose honest observer bounds and radius,
include every terrain tile needed by those bounds plus that radius, verify source
provenance and licences, normalize peak metadata, pin all bytes, update the app's
catalogue/version, and validate the region. Users never perform these steps.
`tools/prepare-region/` remains a developer utility for independently prepared ZIPs.

## Observation status

On first use, the app requests precise foreground location. Camera permission is
requested separately by **Enable camera**. Coordinates/GPS altitude remain visible
even without a downloaded region; the fictional panorama stays at its own
explicitly labelled demo viewpoint and cannot identify real mountains.

The camera/panorama status panel reports direction/reference, sensor source,
latitude/longitude, GPS accuracy/freshness, altitude/source, and horizontal view
angle (plus vertical angle once camera intrinsics are ready). Peak details include
an elevation angle above the horizon. Rotation-vector sensors are preferred;
magnetometer plus accelerometer is the fallback. GPS position drives magnetic
north-to-true-north correction. Until a location is known in demo mode, heading is
labelled magnetic north. GNSS ellipsoidal altitude is never silently substituted
for the EGM96 terrain height used by the visibility engine.
