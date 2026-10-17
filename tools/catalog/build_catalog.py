#!/usr/bin/env python3
"""Reproduce built-in catalog assets from GeoNames country dumps and verified Skadi files.

Downloads are developer preparation inputs, never user-facing URL entry. See
../../docs/region-catalog.md. This script does not download or publish anything.
"""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import zipfile

REGIONS = [
    dict(id='poon-hill', name='Poon Hill · Annapurna', country='Nepal',
         description='Poon Hill and Ghorepani viewpoints', countryCode='NP',
         tiles=['N28E083', 'N28E084'], terrainBounds=[28,83,29,85],
         observerBounds=[28.38,83.63,28.46,83.78], viewpoint=[28.4,83.69], radiusM=40000),
    dict(id='gokyo', name='Gokyo · Everest', country='Nepal',
         description='Gokyo valley viewpoints', countryCode='NP',
         tiles=['N27E086', 'N28E086'], terrainBounds=[27,86,29,87],
         observerBounds=[27.92,86.64,28.02,86.70], viewpoint=[27.95,86.68], radiusM=25000),
    dict(id='auli', name='Auli · Garhwal', country='India',
         description='Auli and Gorson viewpoints', countryCode='IN',
         tiles=['N30E079'], terrainBounds=[30,79,31,80],
         observerBounds=[30.50,79.55,30.55,79.60], viewpoint=[30.53,79.57], radiusM=30000),
]

def sha(data): return hashlib.sha256(data).hexdigest()
def bounds(values): return dict(zip(['south','west','north','east'],values))
def peaks(country_zip, code, extent):
    with zipfile.ZipFile(country_zip) as archive:
        rows = archive.read(code+'.txt').decode().splitlines()
    result=[]
    for line in rows:
        r=line.split('\t')
        if len(r)!=19 or r[6]!='T' or r[7] not in ('MT','PK'): continue
        lat,lon=float(r[4]),float(r[5])
        if not (extent[0]<=lat<=extent[2] and extent[1]<=lon<=extent[3]): continue
        # GeoNames DEM column is not a measured summit elevation. Do not use it.
        height=int(r[15]) if r[15] and -500<=int(r[15])<=10000 else None
        result.append(dict(id='geonames:'+r[0],name=r[1],latitude=lat,longitude=lon,
                           elevationM=height,source='GeoNames (CC BY 4.0)',
                           sourceUrl=f'https://www.geonames.org/{r[0]}/',modified=r[18],featureCode=r[7]))
    assert result, 'No mountain records in this region'
    return sorted(result,key=lambda p:p['id'])

def build(args):
    entries=[]
    for region in REGIONS:
        directory=args.output/region['id']; directory.mkdir(parents=True,exist_ok=True)
        source_zip=args.dumps/(region['countryCode']+'.zip')
        peak_data=json.dumps(peaks(source_zip,region['countryCode'],region['terrainBounds']),ensure_ascii=False,indent=2).encode()
        tiles=[]; files=[]
        for name in region['tiles']:
            compressed=(args.tiles/(name+'.hgt.gz')).read_bytes()
            raw=gzip.decompress(compressed)
            assert len(raw)==3601*3601*2, name
            address=f'https://s3.amazonaws.com/elevation-tiles-prod/skadi/{name[:3]}/{name}.hgt.gz'
            tiles.append(dict(path='terrain/'+name+'.hgt',url=address,downloadBytes=len(compressed),downloadSha256=sha(compressed)))
            files.append(dict(path='terrain/'+name+'.hgt',size=len(raw),sha256=sha(raw)))
        credit=f'''PeakBud {region['name']} · catalog version 1
Terrain: Mapzen/Tilezen Skadi, 1 arc-second HGT, WGS84 / EGM96.
Global SRTM and GMTED2010 terrain data courtesy of the U.S. Geological Survey.
Mapzen / Tilezen: https://github.com/tilezen/joerd
Source and attribution: https://github.com/tilezen/joerd/blob/master/docs/attribution.md
Format: https://github.com/tilezen/joerd/blob/master/docs/formats.md
Terrain tiles may combine SRTM and other source data; not labelled NASA SRTMGL1 v003.
Verified tile downloads: {args.date}. Source bytes are pinned by SHA-256 in this app.
Peak names/coordinates: GeoNames, https://www.geonames.org/ (CC BY 4.0).
License: https://creativecommons.org/licenses/by/4.0/
Country dump: https://download.geonames.org/export/dump/{region['countryCode']}.zip
Country dump retrieved {args.date}; SHA-256 {sha(source_zip.read_bytes())}.
Changes: selected single-mountain/peak records (MT/PK), clipped to terrain bounds,
normalized fields, retained IDs/source links/modification dates; absent elevation stays null.
The GeoNames DEM column is not used as a published summit elevation.
Data is provided as is. Coverage and summit positions require field validation.
Tile source URLs:
'''+''.join(t['url']+'\n' for t in tiles)
        for name,data in [('peaks.json',peak_data),('attribution.txt',credit.encode())]:
            (directory/name).write_bytes(data)
            files.append(dict(path=name,size=len(data),sha256=sha(data)))
        view=region['viewpoint']
        manifest=dict(schemaVersion=1,id=region['id'],name=region['name'],version='1',horizontalCrs='EPSG:4326',verticalDatum='EGM96',
                      observerBounds=bounds(region['observerBounds']),terrainBounds=bounds(region['terrainBounds']),radiusM=region['radiusM'],
                      viewpoint=dict(latitude=view[0],longitude=view[1],headingDeg=0),source='Mapzen/Tilezen Skadi; GeoNames CC BY 4.0',preparedAt=args.date,files=files)
        entries.append(dict(id=region['id'],country=region['country'],description=region['description'],assetDirectory='regions/'+region['id'],tiles=tiles,manifest=manifest))
        print(region['name'],len(json.loads(peak_data)),'mountains',sum(t['downloadBytes'] for t in tiles),'download bytes')
    (args.output/'catalog.json').write_text(json.dumps(dict(schemaVersion=1,regions=entries),ensure_ascii=False,indent=2)+'\n')

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dumps',required=True,type=Path)
    parser.add_argument('--tiles',required=True,type=Path)
    parser.add_argument('--output',default='app/src/main/assets/regions',type=Path)
    parser.add_argument('--date',required=True)
    build(parser.parse_args())
