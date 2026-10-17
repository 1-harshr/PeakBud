# Built-in region downloads

Users open **Regions**, choose a named hiking area, and tap **Download**. They do
not search for data, paste URLs, or import files. The region list, HTTPS links,
versions, sizes, SHA-256 checksums, and small peak catalogues ship in the APK.
Terrain downloads once and is installed into app-private offline storage.

## Initial catalogue

| Region | Supported viewpoints | Peak search radius | Download | Terrain storage |
| --- | --- | --- | --- | --- |
| Poon Hill · Annapurna, Nepal | 28.38–28.46 N, 83.63–83.78 E | 40 km | 36 MiB | about 49 MiB |
| Gokyo · Everest, Nepal | 27.92–28.02 N, 86.64–86.70 E | 25 km | 34 MiB | about 49 MiB |
| Auli · Garhwal, India | 30.50–30.55 N, 79.55–79.60 E | 30 km | 19 MiB | about 25 MiB |

These are starter viewpoint areas, not country-wide coverage. Nearby summit
catalogues contain 141, 76, and 88 mountain/peak records respectively; only candidates
inside the selected viewing radius and with usable sightlines can be labelled.
Downloading another region does not extend an existing region's observer bounds.

## Files and sources

`app/src/main/assets/regions/catalog.json` is the built-in download catalogue.
Each entry contains an existing format-v1 pack manifest plus pinned gzip terrain
sources. Its subdirectory supplies `peaks.json` and `attribution.txt`. The Kotlin
`RegionCatalog` loader and `CatalogInstaller` use those recipes automatically.

Terrain is public [Mapzen/Tilezen Skadi](https://github.com/tilezen/joerd/blob/master/docs/formats.md)
1 arc-second, 3601×3601 HGT with WGS84 / EGM96 heights. Sources are a composite;
we do not relabel it NASA SRTMGL1 v003. Download links follow the provider's
[documented public S3 layout](https://github.com/tilezen/joerd/blob/master/docs/use-service.md):

- [N28E083](https://s3.amazonaws.com/elevation-tiles-prod/skadi/N28/N28E083.hgt.gz)
- [N28E084](https://s3.amazonaws.com/elevation-tiles-prod/skadi/N28/N28E084.hgt.gz)
- [N27E086](https://s3.amazonaws.com/elevation-tiles-prod/skadi/N27/N27E086.hgt.gz)
- [N28E086](https://s3.amazonaws.com/elevation-tiles-prod/skadi/N28/N28E086.hgt.gz)
- [N30E079](https://s3.amazonaws.com/elevation-tiles-prod/skadi/N30/N30E079.hgt.gz)

All five URLs were actually downloaded and both compressed and decompressed
SHA-256 values were checked during preparation on 2026-10-17. There are no dummy
region ZIP URLs and no PeakBud hosting service is needed for this first list.
External source availability is outside the app's control. Changed bytes fail
verification and require a reviewed catalogue update, not silent acceptance.

Peak snapshots come from [GeoNames country dumps](https://download.geonames.org/export/dump/readme.txt)
(Nepal and India), under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
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

Use `tools/catalog/build_catalog.py` with downloaded GeoNames NP/IN country ZIPs
and the five verified `.hgt.gz` files. It does not publish or download anything:

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
