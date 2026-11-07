#!/usr/bin/env python3
"""Verify the 1.0.9 auth source boundary and the reobfuscated Forge release jar."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tomllib
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BASE = '9c4479a758a3347732a1172c52b4edae8be9b336'
UNCHANGED_AUTH = [
    'src/main/java/cn/alini/trueuuid/mixin/client/ClientHandshakeMixin.java',
    'src/main/java/cn/alini/trueuuid/net/NetIds.java',
    'src/main/java/cn/alini/trueuuid/server/SessionCheck.java',
    'src/main/java/cn/alini/trueuuid/server/AuthDecider.java',
    'src/main/java/cn/alini/trueuuid/server/AuthState.java',
    'src/main/java/cn/alini/trueuuid/server/NameRegistry.java',
    'src/main/java/cn/alini/trueuuid/server/RecentIpGraceCache.java',
]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source-only', action='store_true')
    args = parser.parse_args()
    properties = dict(line.split('=', 1) for line in (ROOT / 'gradle.properties').read_text().splitlines()
                      if '=' in line and not line.startswith('#'))
    version = properties['mod_version']
    assert re.fullmatch(r'1\.0\.9-dimensionworks\.[1-9][0-9]*', version), version
    assert os.environ.get('RELEASE_VERSION', version) == version, 'release input differs from source version'
    assert properties['minecraft_version'] == '1.20.1'
    assert properties['forge_version'] == '47.4.23'
    for relative in UNCHANGED_AUTH:
        baseline = subprocess.check_output(['git', 'show', BASE + ':' + relative], cwd=ROOT)
        assert (ROOT / relative).read_bytes() == baseline, '1.0.9 auth/wire code changed: ' + relative
    assert 'distributionUrl=https\\://services.gradle.org/distributions/gradle-8.8-bin.zip' in (ROOT / 'gradle/wrapper/gradle-wrapper.properties').read_text()
    if args.source_only:
        print('PASS: Forge/Java baseline and unchanged 1.0.9 client/session protocol')
        return
    jar = ROOT / 'build/libs' / f'trueuuid-{version}-forge1.20.1.jar'
    with zipfile.ZipFile(jar) as archive:
        names = set(archive.namelist())
        mod = tomllib.loads(archive.read('META-INF/mods.toml').decode())['mods'][0]
        assert mod['modId'] == 'trueuuid' and mod['version'] == version
        assert 'META-INF/LICENSE' in names
        mixins = json.loads(archive.read('trueuuid.mixins.json'))
        assert mixins['compatibilityLevel'] == 'JAVA_17'
        refmap = json.loads(archive.read(mixins['refmap']))
        mappings = refmap['mappings']['cn/alini/trueuuid/mixin/server/ServerLoginMixin']
        for method in ['handleHello', 'handleCustomQueryPacket', 'tick']:
            assert any((key == method or key.startswith(method + '(')) and 'm_' in value
                       for key, value in mappings.items()), 'Missing SRG mapping: ' + method
        for name in ['MigrationFiles', 'MigrationTransaction', 'FtbMigrationCaches', 'MigrationRecovery']:
            assert 'cn/alini/trueuuid/server/' + name + '.class' in names
        for name in names:
            if name.endswith('.class'):
                data = archive.read(name)
                assert data[:4] == b'\xca\xfe\xba\xbe' and int.from_bytes(data[6:8], 'big') <= 61, name
                assert '/test/' not in name and not name.endswith('Test.class'), 'test code in release jar'
    print('PASS: reobfuscated Forge 1.20.1 jar, Java 17 classes, mixin mappings and LGPL license')


if __name__ == '__main__':
    main()
