"""Release metadata only. Never reads signing secrets or modifies a signed APK."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time

REPOSITORY = 'NuvioTvModTv/ModTv'
BASE = f'https://github.com/{REPOSITORY}/releases'

def version_name():
    return re.search(r'versionName\s*=\s*"([^"]+)"', Path('app/build.gradle.kts').read_text()).group(1)

def check_repository():
    if os.environ.get('GITHUB_REPOSITORY') != REPOSITORY:
        raise SystemExit('Release publication is restricted to the official ModTv repository')

def version():
    check_repository()
    # Serialized workflow + all release tags protect retries/clock drift and unique tags.
    pages = json.loads(subprocess.check_output([
        'gh', 'api', '--paginate', '--slurp', f'repos/{REPOSITORY}/releases?per_page=100'
    ], text=True))
    codes = [1062]
    for page in pages:
        for release in page:
            match = re.fullmatch(r'mod-v[0-9.]+-b([0-9]+)', release.get('tag_name', ''))
            if match:
                codes.append(int(match[1]))
    code = max(int(time.time()) - 1577836800, max(codes) + 1)
    if not 1062 < code <= 2100000000:
        raise SystemExit('Version code outside supported range')
    name = version_name()
    if not re.fullmatch(r'[0-9]+(?:\.[0-9]+)*', name):
        raise SystemExit('Invalid public version name')
    with open(os.environ['GITHUB_ENV'], 'a') as out:
        out.write(f'NUVIO_CI_VERSION_CODE={code}\nMOD_VERSION_NAME={name}\nMOD_RELEASE_TAG=mod-v{name}-b{code}\n')

def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(65536), b''):
            digest.update(chunk)
    return digest.hexdigest()

def assets():
    check_repository()
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
    candidates = [p for p in (sdk / 'build-tools').iterdir()
                  if (p / 'apksigner').is_file() and (p / 'aapt').is_file()]
    if not candidates:
        raise SystemExit('Existing Android build tools not found')
    tool = max(candidates, key=lambda p: tuple(map(int, re.findall(r'\d+', p.name))))
    code = int(os.environ['NUVIO_CI_VERSION_CODE']); name = version_name()
    output = Path('release-assets'); output.mkdir(exist_ok=True)
    manifest = {'versionCode': code, 'versionName': name, 'title': 'Nova atualização disponível',
                'changelog': f'NuvioTV Mod {name} — Build {code}. Consulte as notas desta Release para as mudanças.',
                'releaseUrl': f'{BASE}/tag/{os.environ["MOD_RELEASE_TAG"]}'}
    certificate = None
    for abi, field in [('armeabi-v7a', 'armeabiV7a'), ('universal', 'universal')]:
        matches = list(Path('app/build/outputs/apk/full/release').glob(f'app-full-{abi}-release.apk'))
        if len(matches) != 1:
            raise SystemExit(f'Missing or ambiguous Full Release APK: {abi}')
        apk = matches[0]
        verified = subprocess.check_output([str(tool / 'apksigner'), 'verify', '--print-certs', str(apk)], text=True)
        cert = re.search(r'Signer #1 certificate SHA-256 digest: (\w+)', verified)
        if not cert or (certificate and cert[1] != certificate):
            raise SystemExit('Invalid or inconsistent signing certificate')
        certificate = cert[1]
        badging = subprocess.check_output([str(tool / 'aapt'), 'dump', 'badging', str(apk)], text=True)
        package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
        if not package or package.groups() != ('com.nuvio.tv', str(code), name):
            raise SystemExit('APK package/version does not match manifest')
        xml = subprocess.check_output([str(tool / 'aapt'), 'dump', 'xmltree', str(apk), 'AndroidManifest.xml'], text=True)
        if re.search(r'android:(?:testOnly|debuggable)\([^\n]*=\(type 0x12\)(?:0xffffffff|0x1)\b', xml):
            raise SystemExit('Test-only or debuggable release rejected')
        minimum = re.search(r"sdkVersion:'(\d+)'", badging)
        if not minimum or int(minimum[1]) > 28:
            raise SystemExit('APK must support API 28')
        if "'armeabi-v7a'" not in badging or (abi == 'universal' and "'arm64-v8a'" not in badging):
            raise SystemExit('Missing required native ABI')
        target = output / f'NuvioTV-{abi}.apk'
        shutil.copyfile(apk, target)
        manifest[field] = f'{BASE}/latest/download/{target.name}'
        manifest[field + 'Sha256'] = sha256(target)
    (output / 'update.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    (output / 'notes.md').write_text(f'NuvioTV Mod {name} — Build {code}\n\nBase upstream: NuvioTV 1.0.0.\n')

if __name__ == '__main__':
    {'version': version, 'assets': assets}[sys.argv[1]]()
