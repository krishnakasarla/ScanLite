#!/usr/bin/env python3
"""Compile actual platform-independent production Kotlin with an installed/cached compiler.
No Android stubs, fake encoders, or downloaded dependencies are used.
"""
from pathlib import Path
import os
import shutil
import subprocess

root = Path(__file__).resolve().parent.parent
out = root / 'tools' / 'build'
out.mkdir(exist_ok=True)
jar = out / 'checks.jar'
sources = [root / 'app/src/main/java/com/scanlite/util/ExportSettings.kt',
           root / 'app/src/main/java/com/scanlite/processing/RasterPdfWriter.kt',
           root / 'tools/CoreChecks.kt']
kotlinc = shutil.which('kotlinc')
if kotlinc:
    subprocess.run([kotlinc, *map(str, sources), '-include-runtime', '-d', str(jar)], check=True)
    command = ['java', '-Djava.awt.headless=true', '-jar', str(jar), str(out)]
else:
    # Use the wrapper version pinned by this project, not a historical Gradle cache.
    properties = (root / 'gradle/wrapper/gradle-wrapper.properties').read_text()
    import re
    match = re.search(r'gradle-([\d.]+)-bin\.zip', properties)
    if not match:
        raise SystemExit('Could not determine the project Gradle wrapper version.')
    version = match.group(1)
    gradle = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle')))
    candidates = list((gradle / 'wrapper/dists').glob(f'gradle-{version}-bin/*/gradle-{version}/lib'))
    if not candidates:
        raise SystemExit('Install Kotlin CLI or run ./gradlew testDebugUnitTest with an Android SDK.')
    lib = candidates[0]
    compiler_cp = ':'.join(str(p) for p in lib.glob('*.jar'))
    stdlib = next(lib.glob('kotlin-stdlib-*.jar'))
    annotations = next(lib.glob('annotations-*.jar'))
    runtime_cp = f'{stdlib}:{annotations}'
    subprocess.run(['java', '-cp', compiler_cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', runtime_cp, '-d', str(jar),
                    *map(str, sources)], check=True)
    command = ['java', '-Djava.awt.headless=true', '-cp', f'{jar}:{runtime_cp}', 'CoreChecksKt', str(out)]
subprocess.run(command, check=True)
