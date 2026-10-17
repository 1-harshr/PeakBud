#!/usr/bin/env python3
"""Exercise the exported Room schema and actual DAO SQL in host SQLite.

This supplements KSP processing; it does not run the Android Room runtime.
Regenerate app/schemas with Gradle whenever the annotated database changes.
"""
import json
from pathlib import Path
import re
import sqlite3

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = ROOT / 'app/schemas/com.harsh.peakbud.data.peaks.PeakDatabase/1.json'
DAO = ROOT / 'app/src/main/java/com/harsh/peakbud/data/peaks/PeakDatabase.kt'


def main():
    schema = json.loads(SCHEMA.read_text())['database']
    queries = dict((name, sql) for sql, name in re.findall(r'@Query\("([^"\n]+)"\)\s*suspend fun (\w+)', DAO.read_text()))
    assert set(queries) == {'candidates', 'region', 'deletePeaks', 'deleteRegion'}, 'Update this contract check when the DAO changes'
    with sqlite3.connect(':memory:') as database:
        for entity in schema['entities']:
            database.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
            for index in entity.get('indices', []):
                database.execute(index['createSql'].replace('${TABLE_NAME}', entity['tableName']))
        for sql in schema['setupQueries']:
            database.execute(sql)
        database.executemany('INSERT INTO regions VALUES (?, ?, ?, ?)', [
            ('pack-a', 'region', 'Version A', '1'), ('pack-b', 'region', 'Version B', '2')])
        database.executemany('INSERT INTO peaks VALUES (?, ?, ?, ?, ?, ?, ?)', [
            ('pack-a', 'osm:1', 'Nearby', 28.5, 83.5, None, 'Test'),
            ('pack-a', 'osm:2', 'Outside bounds', 29.5, 83.5, 5000.0, 'Test'),
            ('pack-b', 'osm:1', 'Other version', 28.5, 83.5, 4000.0, 'Test')])
        params = dict(key='pack-a', south=28.4, west=83.4, north=28.6, east=83.6)
        rows = database.execute(queries['candidates'], params).fetchall()
        assert len(rows) == 1 and rows[0][2] == 'Nearby' and rows[0][5] is None
        assert database.execute(queries['region'], {'key': 'pack-a'}).fetchone()[3] == '1'
        database.execute(queries['deletePeaks'], {'key': 'pack-a'})
        database.execute(queries['deleteRegion'], {'key': 'pack-a'})
        assert database.execute(queries['candidates'], params).fetchall() == []
        assert database.execute(queries['region'], {'key': 'pack-a'}).fetchone() is None
        other = database.execute(queries['candidates'], dict(params, key='pack-b')).fetchall()
        assert len(other) == 1 and other[0][2] == 'Other version'
        assert database.execute(queries['region'], {'key': 'pack-b'}).fetchone()[3] == '2'
    print('Room schema / DAO SQL checks passed: pack isolation, bounds, nullable heights, and deletion.')


if __name__ == '__main__':
    main()
