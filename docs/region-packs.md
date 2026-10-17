# Preparing and Installing Offline Regions

PeakBud reads real 1 arc-second SRTM HGT elevation files and a named peak catalogue.
The Explorer demo is fictional. The built-in catalogue supplies real peak
snapshots and terrain download links; see [region-catalog.md](region-catalog.md).

## Prepare the source files

1. Download **SRTMGL1 v003** tiles for your region from
   [NASA Earthdata Search](https://search.earthdata.nasa.gov/search?q=SRTMGL1).
   Authentication may be needed on the preparation machine. Unzip the elevation
   files, keeping names such as `N28E083.hgt`. Each supported tile is 25,934,402
   bytes: 3601 × 3601 signed big-endian metre samples referenced to EGM96.
   GeoTIFFs and 1201 × 1201 HGT are not accepted by this initial importer.
2. Include terrain around the observer area out to the entire viewing radius.
   The preparation tool checks the tile list for the expanded rectangle. Actual
   NoData can still occur within these tiles; it remains unknown in the app.
3. Obtain an OSM peak extract over that **expanded terrain area**. For a small
   development region, use an Overpass query such as:

   ```text
   [out:json][timeout:60];
   node["natural"="peak"](27,83,29,85);
   out meta;
   ```

   This is an example extent, not a verified hiking dataset. Respect the
   [public Overpass resource policy](https://dev.overpass-api.de/overpass-doc/en/preface/commons.html).
   Save the JSON as `peaks-overpass.json`. For production packs, use regional OSM
   downloads and an offline extraction pipeline instead of repeated public queries.
4. Create `attribution.txt` with source dataset versions, extraction dates, credits,
   source/download links, and applicable licence notices. For OSM include
   `© OpenStreetMap contributors`, the [ODbL link](https://www.openstreetmap.org/copyright),
   and access to the distributed derived database as applicable. The ZIP includes
   its normalized `peaks.json`; retain the original extract for reproducibility.
   Retain NASA product notices and cite the exact DEM used. Do not label arbitrary
   third-party HGT files as NASA SRTMGL1 v003.
5. Put additional redistribution notices in plain files passed with `--licence`.
   Copernicus heights use a different vertical datum and are not supported by this
   initial EGM96-only format. Do not simply rename a GeoTIFF or mix providers.

## Create a ZIP

Requires Python 3.9 or later; no Python packages or Android native GIS library are
needed. Example for a small **proposed** viewpoint area near Poon Hill:

```sh
python3 tools/prepare-region/prepare.py \
  --id annapurna-demo \
  --name 'Annapurna viewpoints' \
  --version 1 \
  --hgt-dir /path/to/srtm \
  --peaks /path/to/peaks-overpass.json \
  --attribution /path/to/attribution.txt \
  --observer-bounds 28.39,83.68,28.41,83.70 \
  --viewpoint 28.4,83.69 \
  --radius-km 50 \
  --heading 0 \
  --output region-output/annapurna.zip
```

For these bounds, the tool expects `N27E083.hgt`, `N27E084.hgt`, `N28E083.hgt`, and
`N28E084.hgt`. Confirm actual viewpoint coordinates and reference summit identities
before field validation. The example is not evidence that those files have been
downloaded or that the panorama is accurate.

The tool preserves missing summit heights rather than inventing values, keeps raw
height tags and source IDs, counts NoData cells, and hashes every payload file.
All viewing geometry uses sampled terrain heights; published peak heights remain
separate display metadata. The supported observer area is currently a rectangle,
away from the poles/date line, and the maximum viewing radius is 50 km. A pack is
limited to 512 MiB unpacked and 20,000 peaks. DEM samples may include voids; the
app must report incomplete directions and unknown visibility for affected paths.

## Product installation flow

Users choose a named area under **Regions** and tap **Download**. There is no
file-picker import or URL field. See [region-catalog.md](region-catalog.md) for the
built-in source links, bundled peak snapshots, and automated installation.

The preparation instructions above are developer utilities. They are not steps
that hikers need to follow. Existing manually installed packs remain readable
for upgrade compatibility. Pack files, download fragments, and Room metadata stay
in app-private storage, with the selected pack persisted by DataStore.

Before hiking, cold-start the app in airplane mode and open a downloaded region.
Verify that your viewpoint is inside its advertised coverage. GPS satellite
fixes and camera/compass use do not require a terrain web service.

## Archive contract

```text
manifest.json
terrain/N28E083.hgt
terrain/...
peaks.json
attribution.txt
licences/optional-notice.txt
```

`manifest.json` contains schema version 1, region ID/name/version, EPSG:4326,
EGM96, observer and terrain bounds, radius in metres, default viewpoint/heading,
source information, preparation timestamp, and a `files` array of
`{path, size, sha256}`. All payload files must be listed; `manifest.json` itself
is not listed. Directory traversal, unexpected payload files, duplicates, and
incomplete required tile lists are rejected.

`peaks.json` is an array of `{id, name, latitude, longitude, elevationM, source}`.
The preparation tool also retains raw elevations, localized names, and OSM object
versions where available. `elevationM` may be null. Longitude is east-positive;
latitude is north-positive. All heights and distances in pack files use metres.

## Verification

```sh
./gradlew :app:checkDebugAarMetadata :app:assembleDebug :app:testDebugUnitTest
python3 -m unittest discover -s tools/prepare-region -p 'test_*.py'
python3 tools/check-room-schema.py
```

If Gradle cannot start in a restricted environment, `python3 tools/check-core.py`
compiles the actual platform-independent Kotlin sources and runs their JUnit
suite using already cached Maven artifacts. `check-room-schema.py` separately
checks the exported tables and actual DAO SQL in host SQLite. These do not replace
Android build, Room runtime, emulator, or hardware verification. Set `JAVA_HOME` to a JDK 21 install
for that fallback when the default system JDK is newer.
