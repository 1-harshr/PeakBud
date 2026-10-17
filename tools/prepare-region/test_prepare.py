import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('prepare_region', Path(__file__).with_name('prepare.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PrepareRegionTest(unittest.TestCase):
    bounds = dict(south=28, west=83, north=29, east=84)

    def test_keeps_provenance_and_does_not_guess_imperial_heights(self):
        doc = {'elements': [dict(type='node', id=123, lat=28.5, lon=83.5, version=7,
                                tags={'natural': 'peak', 'name': 'Example', 'ele': '15,000 ft'})]}
        peak = module.normalize_peaks(doc, self.bounds)[0]
        self.assertEqual(peak['id'], 'osm:node/123')
        self.assertEqual(peak['sourceVersion'], 7)
        self.assertIsNone(peak['elevationM'])
        self.assertEqual(peak['rawElevation'], '15,000 ft')

    def test_duplicate_identity_is_rejected(self):
        peak = dict(id='x', name='Same', latitude=28.5, longitude=83.5, elevationM=3000)
        with self.assertRaises(ValueError):
            module.normalize_peaks([peak, peak], self.bounds)

    def test_archive_contract_and_hashes(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            tile = root / 'N28E083.hgt'
            with tile.open('wb') as stream:
                stream.truncate(module.HGT_BYTES)
            (root / 'peaks.json').write_text(json.dumps([dict(id='test-only', name='Fixture', latitude=28.51, longitude=83.5, elevationM=None, source='TEST ONLY')]))
            (root / 'credits.txt').write_text('Synthetic fixture. Never distribute as geographical terrain.')
            args = SimpleNamespace(id='fixture', name='Test only', version='1', observer_bounds=(28.49, 83.49, 28.51, 83.51), viewpoint=(28.5, 83.5), radius_km=1, heading=0, hgt_dir=root, peaks=root/'peaks.json', attribution=root/'credits.txt', licence=[], output=root/'fixture.zip', source='TEST ONLY')
            manifest = module.prepare(args)
            self.assertEqual(manifest['terrainBounds'], self.bounds)
            with zipfile.ZipFile(args.output) as archive:
                self.assertEqual(json.loads(archive.read('manifest.json'))['verticalDatum'], 'EGM96')
                for record in manifest['files']:
                    data = archive.read(record['path'])
                    self.assertEqual(len(data), record['size'])
                    self.assertEqual(hashlib.sha256(data).hexdigest(), record['sha256'])
            tile.unlink()
            with self.assertRaisesRegex(ValueError, 'Missing or invalid'):
                module.prepare(args)

if __name__ == '__main__':
    unittest.main()
