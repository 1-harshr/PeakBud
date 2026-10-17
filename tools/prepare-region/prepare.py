#!/usr/bin/env python3
"""Create a PeakBud region from locally downloaded 1 arc-second HGT + OSM JSON.

No account credentials or network access are required by this tool. See
../../docs/region-packs.md for source downloads, licensing and the file contract.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import re
import zipfile

HGT_BYTES = 3601 * 3601 * 2
MAX_BYTES = 512 * 1024 * 1024


def numbers(text, count):
    result = tuple(float(x) for x in text.split(','))
    if len(result) != count or not all(math.isfinite(x) for x in result):
        raise argparse.ArgumentTypeError(f'Expected {count} finite comma-separated numbers')
    return result


def tile_name(lat, lon):
    return f'{"N" if lat >= 0 else "S"}{abs(lat):02d}{"E" if lon >= 0 else "W"}{abs(lon):03d}.hgt'


def normalize_peaks(document, bounds):
    """Accept Overpass nodes or the normalized array; preserve source metadata."""
    elements = document.get('elements', []) if isinstance(document, dict) else document
    result = []
    seen = set()
    for obj in elements:
        if 'tags' in obj:
            if obj.get('type') != 'node' or obj['tags'].get('natural') != 'peak':
                continue
            tags = obj['tags']
            name = tags.get('name:en') or tags.get('name')
            latitude, longitude = obj.get('lat'), obj.get('lon')
            source_id = f'osm:node/{obj["id"]}'
            raw = tags.get('ele')
            source = 'OpenStreetMap contributors'
        else:
            name, latitude, longitude = obj.get('name'), obj.get('latitude'), obj.get('longitude')
            source_id, raw = str(obj['id']), obj.get('elevationM')
            tags, source = {}, obj.get('source', 'OpenStreetMap contributors')
        if not name or latitude is None or longitude is None:
            continue
        if not (bounds['south'] <= latitude <= bounds['north'] and bounds['west'] <= longitude <= bounds['east']):
            continue
        if source_id in seen:
            raise ValueError(f'Duplicate peak ID: {source_id}')
        seen.add(source_id)
        height = None
        if raw is not None:
            try:
                # Do not guess semicolon lists, imperial units, or ambiguous commas.
                candidate = float(str(raw).strip().removesuffix(' m'))
                if math.isfinite(candidate) and -500 <= candidate <= 10000:
                    height = candidate
            except ValueError:
                pass
        result.append(dict(id=source_id, name=name, latitude=latitude, longitude=longitude,
                           elevationM=height, rawElevation=raw, source=source,
                           sourceVersion=obj.get('version'), names={k: v for k, v in tags.items() if k == 'name' or k.startswith('name:')}))
    if not result:
        raise ValueError('No named peaks in the region. Check the extract bounds and tags.')
    if len(result) > 20000:
        raise ValueError('The initial format supports at most 20,000 peaks per pack')
    return result


def prepare(args):
    if not re.fullmatch(r'[a-z0-9][a-z0-9-]{0,63}', args.id):
        raise ValueError('ID must be lowercase letters, digits and hyphens')
    south, west, north, east = args.observer_bounds
    lat, lon = args.viewpoint
    if not (-55 < south <= lat <= north < 59 and -180 < west <= lon <= east < 180):
        raise ValueError('Use an observer rectangle in SRTM coverage, away from the date line')
    if not 0.1 <= args.radius_km <= 50:
        raise ValueError('Radius must be between 0.1 and 50 km')
    angular = math.degrees(args.radius_km * 1000 / 6371008.8) + 1 / 3600
    longitude_margin = angular / math.cos(math.radians(max(abs(south), abs(north)) + angular))
    low_lat, high_lat = math.floor(south - angular), math.floor(north + angular)
    low_lon, high_lon = math.floor(west - longitude_margin), math.floor(east + longitude_margin)
    if not (-56 <= low_lat and high_lat < 60 and -180 <= low_lon <= high_lon < 180):
        raise ValueError('Expanded viewing coverage exceeds supported region bounds')
    terrain_bounds = dict(south=low_lat, west=low_lon, north=high_lat + 1, east=high_lon + 1)
    inputs = {}
    for tile_lat in range(low_lat, high_lat + 1):
        for tile_lon in range(low_lon, high_lon + 1):
            name = tile_name(tile_lat, tile_lon)
            path = args.hgt_dir / name
            if not path.is_file() or path.stat().st_size != HGT_BYTES:
                raise ValueError(f'Missing or invalid 3601 x 3601 tile: {path}')
            inputs[f'terrain/{name}'] = path
    peak_document = json.loads(args.peaks.read_text())
    peaks = normalize_peaks(peak_document, terrain_bounds)
    payloads = {
        'peaks.json': json.dumps(peaks, ensure_ascii=False, indent=2).encode(),
        'attribution.txt': args.attribution.read_bytes(),
    }
    if not payloads['attribution.txt'].strip():
        raise ValueError('Attribution must not be empty')
    for licence in args.licence:
        if not re.fullmatch(r'[a-zA-Z0-9_.-]+', licence.name) or '..' in licence.name:
            raise ValueError('Invalid licence filename')
        key = f'licences/{licence.name}'
        if key in payloads:
            raise ValueError('Duplicate licence filename')
        payloads[key] = licence.read_bytes()
    records = []
    nodata = 0
    for name, path in inputs.items():
        digest = hashlib.sha256()
        with path.open('rb') as stream:
            while block := stream.read(1024 * 1024):
                digest.update(block)
                nodata += sum(block[i:i+2] == b'\x80\x00' for i in range(0, len(block), 2))
        records.append(dict(path=name, size=path.stat().st_size, sha256=digest.hexdigest()))
    for name, data in payloads.items():
        records.append(dict(path=name, size=len(data), sha256=hashlib.sha256(data).hexdigest()))
    total = sum(record['size'] for record in records)
    if total > MAX_BYTES:
        raise ValueError('Region exceeds the current 512 MiB pack limit; reduce observer area/radius')
    manifest = dict(schemaVersion=1, id=args.id, name=args.name, version=args.version,
                    horizontalCrs='EPSG:4326', verticalDatum='EGM96',
                    observerBounds=dict(south=south, west=west, north=north, east=east),
                    terrainBounds=terrain_bounds, radiusM=args.radius_km * 1000,
                    viewpoint=dict(latitude=lat, longitude=lon, headingDeg=args.heading),
                    source=args.source, preparedAt=datetime.now(timezone.utc).isoformat(),
                    noDataSamples=nodata, files=records)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_suffix('.zip.part')
    try:
        with zipfile.ZipFile(temporary, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
            archive.writestr('manifest.json', json.dumps(manifest, indent=2))
            for name, data in payloads.items():
                archive.writestr(name, data)
            for name, path in inputs.items():
                archive.write(path, name)
        temporary.replace(args.output)
    finally:
        temporary.unlink(missing_ok=True)
    print(f'Created {args.output}: {len(inputs)} HGT tiles, {len(peaks)} named peaks, {total / 1048576:.1f} MiB unpacked')
    if nodata:
        print(f'WARNING: {nodata} NoData samples remain; affected visibility must be marked unknown')
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--id', required=True)
    parser.add_argument('--name', required=True)
    parser.add_argument('--version', default='1')
    parser.add_argument('--hgt-dir', required=True, type=Path)
    parser.add_argument('--peaks', required=True, type=Path, help='Overpass JSON or normalized peaks array')
    parser.add_argument('--attribution', required=True, type=Path)
    parser.add_argument('--licence', action='append', default=[], type=Path)
    parser.add_argument('--observer-bounds', required=True, type=lambda t: numbers(t, 4), help='south,west,north,east')
    parser.add_argument('--viewpoint', required=True, type=lambda t: numbers(t, 2), help='latitude,longitude')
    parser.add_argument('--radius-km', type=float, default=50)
    parser.add_argument('--heading', type=float, default=0)
    parser.add_argument('--source', default='NASA SRTMGL1 v003; OpenStreetMap contributors')
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    try:
        prepare(args)
    except (ValueError, OSError, KeyError) as error:
        parser.error(str(error))

if __name__ == '__main__':
    main()
