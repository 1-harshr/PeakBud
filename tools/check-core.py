#!/usr/bin/env python3
"""Run the real Kotlin/JUnit core tests without Gradle daemons or network.

Uses already cached Maven artifacts. Normal Gradle tests remain authoritative for
the complete Android app; this checks the platform-independent terrain engine.
"""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle'))) / 'caches/modules-2/files-2.1'

def jar(group, artifact, version):
    found = list((CACHE / group / artifact / version).glob(f'*/{artifact}-{version}.jar'))
    if len(found) != 1:
        raise SystemExit(f'Expected cached {group}:{artifact}:{version}; resolve dependencies with Gradle first')
    return found[0]

compiler = jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.2.10')
stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.2.10')
annotations = jar('org.jetbrains', 'annotations', '13.0')
coroutines = jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.10.2')
junit = jar('junit', 'junit', '4.13.2')
hamcrest = jar('org.hamcrest', 'hamcrest-core', '1.3')
json = jar('org.json', 'json', '20260719')
compiler_cp = os.pathsep.join(map(str, [compiler, stdlib, annotations, coroutines]))
test_cp = os.pathsep.join(map(str, [stdlib, annotations, junit, hamcrest, json]))
sources = list((ROOT / 'app/src/main/java/com/harsh/peakbud/core').rglob('*.kt'))
sources += list((ROOT / 'app/src/main/java/com/harsh/peakbud/data/terrain').rglob('*.kt'))
sources += list((ROOT / 'app/src/main/java/com/harsh/peakbud/data/regions').rglob('*.kt'))
sources += list((ROOT / 'app/src/main/java/com/harsh/peakbud/data/download').rglob('*.kt'))
sources += list((ROOT / 'app/src/test/java/com/harsh/peakbud/core').rglob('*.kt'))
with tempfile.TemporaryDirectory(prefix='peakbud-tests-') as out:
    java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if 'JAVA_HOME' in os.environ else 'java'
    subprocess.run([java, '-cp', compiler_cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-jvm-target', '11', '-classpath', test_cp,
                    '-d', out, *map(str, sources)], check=True)
    subprocess.run([java, '-cp', out + os.pathsep + test_cp, 'org.junit.runner.JUnitCore',
                    'com.harsh.peakbud.core.TerrainEngineTest', 'com.harsh.peakbud.core.RegionStoreTest',
                    'com.harsh.peakbud.core.RegionDownloaderTest', 'com.harsh.peakbud.core.CameraProjectorTest',
                    'com.harsh.peakbud.core.RegionCatalogTest'], check=True)
