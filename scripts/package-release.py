#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import zipfile

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--output', type=Path)
args = parser.parse_args()
props = dict(line.split('=', 1) for line in (root / 'version.properties').read_text().splitlines() if '=' in line)
version = props['APP_VERSION_NAME']
code = int(props['APP_VERSION_CODE'])
if not re.fullmatch(r'\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?', version) or code <= 0:
    raise SystemExit('Invalid application version')
if subprocess.check_output(['git', 'status', '--porcelain'], cwd=root, text=True).strip():
    raise SystemExit('Commit local changes before packaging a release')
commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
if not sdk:
    local = root / 'local.properties'
    if local.exists():
        sdk = next((line.split('=', 1)[1] for line in local.read_text().splitlines() if line.startswith('sdk.dir=')), None)
if not sdk:
    raise SystemExit('Set ANDROID_HOME to verify APK signatures')
tools = [p for p in (Path(sdk) / 'build-tools').glob('*/apksigner') if p.is_file()]
if not tools:
    raise SystemExit('Android apksigner is unavailable')
apksigner = max(tools, key=lambda p: tuple(int(x) for x in re.findall(r'\d+', p.parent.name)))
expected_certificate = (root / 'release-signing-certificate.sha256').read_text().strip().lower()
if not re.fullmatch(r'[a-f0-9]{64}', expected_certificate):
    raise SystemExit('Invalid release certificate fingerprint')
core_spec_path = root / 'patches' / 'xray' / props['XRAY_CORE_VERSION'] / 'build.json'
core_spec = json.loads(core_spec_path.read_text())
marker = core_spec['revision'][:7] + '-' + core_spec['buildSuffix']
build = root / 'app/build/outputs/apk/release'
output_metadata = json.loads((build / 'output-metadata.json').read_text())
if output_metadata['applicationId'] != 'com.simplexray.an':
    raise SystemExit('Unexpected Android application ID')
artifacts = {
    'simplexray-arm64-v8a.apk': {'arm64-v8a'},
    'simplexray-x86_64.apk': {'x86_64'},
    'simplexray-universal.apk': {'arm64-v8a', 'x86_64'},
}
elements = {entry['outputFile']: entry for entry in output_metadata['elements']}
entries = []
for name, abis in artifacts.items():
    entry = elements.get(name)
    if not entry or entry['versionName'] != version or entry['versionCode'] != code:
        raise SystemExit(f'{name}: Gradle output version does not match version.properties')
    apk = build / name
    badging = subprocess.check_output([str(apksigner.parent / 'aapt2'), 'dump', 'badging', str(apk)], text=True)
    package_line = next((line for line in badging.splitlines() if line.startswith('package: ')), '')
    manifest = dict(re.findall(r"([A-Za-z]+)='([^']*)'", package_line))
    if manifest.get('name') != 'com.simplexray.an' or manifest.get('versionName') != version or manifest.get('versionCode') != str(code):
        raise SystemExit(f'{name}: APK manifest does not match the release version')
    minimum = re.search(r"^(?:minSdkVersion|sdkVersion):'([0-9]+)'$", badging, re.MULTILINE)
    if not minimum or minimum.group(1) != '29':
        raise SystemExit(f'{name}: unexpected minimum Android version')
    result = subprocess.run([str(apksigner), 'verify', '--print-certs', str(apk)], capture_output=True, text=True, check=True)
    certificates = re.findall(r'certificate SHA-256 digest:\s*([a-fA-F0-9]+)', result.stdout)
    if certificates != [expected_certificate]:
        raise SystemExit(f'{name}: APK is not signed with the release key')
    core_hashes = {}
    with zipfile.ZipFile(apk) as archive:
        bundled = {path.split('/')[1] for path in archive.namelist() if re.fullmatch(r'lib/[^/]+/libxray\.so', path)}
        if bundled != abis:
            raise SystemExit(f'{name}: unexpected core ABIs')
        for abi in sorted(abis):
            core = archive.read(f'lib/{abi}/libxray.so')
            machine = 183 if abi == 'arm64-v8a' else 62
            if core[:6] != b'\x7fELF\x02\x01' or struct.unpack_from('<H', core, 18)[0] != machine:
                raise SystemExit(f'{name}: invalid native core for {abi}')
            if marker.encode() not in core:
                raise SystemExit(f'{name}: unexpected Xray build marker')
            core_hashes[abi] = hashlib.sha256(core).hexdigest()
    entries.append({'file': name, 'size': apk.stat().st_size, 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'coreSha256': core_hashes})
release = args.output or root / 'app/release' / f'v{version}'
release.mkdir(parents=True, exist_ok=True)
for name in artifacts:
    shutil.copy2(build / name, release / name)
metadata = {
    'applicationId': 'com.simplexray.an', 'versionName': version, 'versionCode': code, 'minSdk': 29,
    'tag': f'v{version}', 'prerelease': '-' in version,
    'sourceCommit': commit, 'signingCertificateSha256': expected_certificate,
    'xray': {'version': props['XRAY_CORE_VERSION'], 'revision': core_spec['revision'], 'build': marker,
             'patches': {name: hashlib.sha256((core_spec_path.parent / name).read_bytes()).hexdigest() for name in core_spec['patches']}},
    'artifacts': entries,
}
metadata_path = release / 'release-metadata.json'
metadata_path.write_text(json.dumps(metadata, indent=2) + '\n')
sums = [f"{entry['sha256']}  {entry['file']}" for entry in entries]
sums.append(f"{hashlib.sha256(metadata_path.read_bytes()).hexdigest()}  release-metadata.json")
(release / 'SHA256SUMS').write_text('\n'.join(sums) + '\n')
print(f'Verified release bundle: {release}')
