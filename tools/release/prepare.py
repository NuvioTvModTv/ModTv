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
import base64

REPOSITORY = 'NuvioTvModTv/ModTv'
BASE = f'https://github.com/{REPOSITORY}/releases'


def version_name():
    return re.search(
        r'versionName\s*=\s*"([^"]+)"',
        Path('app/build.gradle.kts').read_text()
    ).group(1)


def check_repository():
    if os.environ.get('GITHUB_REPOSITORY') != REPOSITORY:
        raise SystemExit(
            'Release publication is restricted to the official ModTv repository'
        )


def version():
    check_repository()

    # Serialized workflow + all release tags protect retries/clock drift
    # and guarantee unique tags/versionCodes.
    pages = json.loads(
        subprocess.check_output(
            [
                'gh',
                'api',
                '--paginate',
                '--slurp',
                f'repos/{REPOSITORY}/releases?per_page=100'
            ],
            text=True
        )
    )

    codes = [1062]

    for page in pages:
        for release in page:
            match = re.fullmatch(
                r'mod-v[0-9.]+-b([0-9]+)',
                release.get('tag_name', '')
            )
            if match:
                codes.append(int(match[1]))

    code = max(
        int(time.time()) - 1577836800,
        max(codes) + 1
    )

    if not 1062 < code <= 2100000000:
        raise SystemExit('Version code outside supported range')

    name = version_name()

    if not re.fullmatch(r'[0-9]+(?:\.[0-9]+)*', name):
        raise SystemExit('Invalid public version name')

    with open(os.environ['GITHUB_ENV'], 'a') as out:
        out.write(
            f'NUVIO_CI_VERSION_CODE={code}\n'
            f'MOD_VERSION_NAME={name}\n'
            f'MOD_RELEASE_TAG=mod-v{name}-b{code}\n'
        )


def sha256(path):
    digest = hashlib.sha256()

    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(65536), b''):
            digest.update(chunk)

    return digest.hexdigest()


def signing_certificates(apksigner, apk):
    """
    Verify the APK and return SHA-256 fingerprints of its signing
    certificates without depending on apksigner's human-readable output.
    """

    result = subprocess.run(
        [
            str(apksigner),
            'verify',
            '--print-certs-pem',
            str(apk)
        ],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False
    )

    if result.returncode != 0:
        detail = result.stderr.strip()
        if detail:
            print(f'apksigner verification error for {apk.name}: {detail}')
        raise SystemExit(
            f'APK signature verification failed: {apk.name}'
        )

    # According to apksigner, --print-certs-pem emits the signing
    # certificates as PEM. Parse the certificates themselves instead
    # of relying on the formatting of "SHA-256 digest:" text.
    output = result.stdout + '\n' + result.stderr

    pem_blocks = re.findall(
        r'-----BEGIN CERTIFICATE-----\s*'
        r'(.*?)'
        r'\s*-----END CERTIFICATE-----',
        output,
        flags=re.DOTALL
    )

    if not pem_blocks:
        # Safe diagnostic: do not expose keystore/password/private key.
        print(
            f'apksigner returned success for {apk.name}, '
            'but no PEM signing certificate was found.'
        )
        raise SystemExit(
            f'Could not read signing certificate from APK: {apk.name}'
        )

    fingerprints = []

    for pem_body in pem_blocks:
        encoded = re.sub(r'\s+', '', pem_body)

        try:
            certificate_der = base64.b64decode(
                encoded,
                validate=True
            )
        except Exception:
            raise SystemExit(
                f'Invalid certificate PEM returned for APK: {apk.name}'
            )

        if not certificate_der:
            raise SystemExit(
                f'Empty signing certificate returned for APK: {apk.name}'
            )

        fingerprints.append(
            hashlib.sha256(certificate_der).hexdigest()
        )

    # Remove accidental duplicates while keeping comparison deterministic.
    fingerprints = tuple(sorted(set(fingerprints)))

    if not fingerprints:
        raise SystemExit(
            f'Could not calculate signing certificate for APK: {apk.name}'
        )

    return fingerprints

def assets():
    check_repository()

    sdk = Path(
        os.environ.get('ANDROID_HOME')
        or os.environ['ANDROID_SDK_ROOT']
    )

    candidates = [
        p
        for p in (sdk / 'build-tools').iterdir()
        if (p / 'apksigner').is_file()
        and (p / 'aapt').is_file()
    ]

    if not candidates:
        raise SystemExit(
            'Existing Android build tools not found'
        )

    tool = max(
        candidates,
        key=lambda p: tuple(
            map(int, re.findall(r'\d+', p.name))
        )
    )

    code = int(
        os.environ['NUVIO_CI_VERSION_CODE']
    )

    name = version_name()

    output = Path('release-assets')
    output.mkdir(exist_ok=True)

    manifest = {
        'versionCode': code,
        'versionName': name,
        'title': 'Nova atualização disponível',
        'changelog':
            f'NuvioTV Mod {name} — Build {code}. '
            'Consulte as notas desta Release para as mudanças.',
        'releaseUrl':
            f'{BASE}/tag/{os.environ["MOD_RELEASE_TAG"]}'
    }

    expected_certificates = None
    expected_certificate_apk = None

    for abi, field in [
        ('armeabi-v7a', 'armeabiV7a'),
        ('universal', 'universal')
    ]:

        matches = list(
            Path(
                'app/build/outputs/apk/full/release'
            ).glob(
                f'app-full-{abi}-release.apk'
            )
        )

        if len(matches) != 1:
            raise SystemExit(
                f'Missing or ambiguous Full Release APK: {abi}'
            )

        apk = matches[0]

        certificates = signing_certificates(
            tool / 'apksigner',
            apk
        )

        # Safe to log: certificate fingerprints are public.
        print(
            f'{apk.name}: signing certificate SHA-256 = '
            f'{", ".join(certificates)}'
        )

        if expected_certificates is None:
            expected_certificates = certificates
            expected_certificate_apk = apk.name

        elif certificates != expected_certificates:
            raise SystemExit(
                'Signing certificate mismatch between Full Release APKs: '
                f'{expected_certificate_apk} and {apk.name}. '
                'Both APKs must be signed by the same certificate.'
            )

        badging = subprocess.check_output(
            [
                str(tool / 'aapt'),
                'dump',
                'badging',
                str(apk)
            ],
            text=True
        )

        package = re.search(
            r"package: name='([^']+)' "
            r"versionCode='(\d+)' "
            r"versionName='([^']+)'",
            badging
        )

        if (
            not package
            or package.groups()
            != (
                'com.nuvio.tv',
                str(code),
                name
            )
        ):
            raise SystemExit(
                'APK package/version does not match manifest'
            )

        xml = subprocess.check_output(
            [
                str(tool / 'aapt'),
                'dump',
                'xmltree',
                str(apk),
                'AndroidManifest.xml'
            ],
            text=True
        )

        if re.search(
            r'android:(?:testOnly|debuggable)'
            r'\([^\n]*=\(type 0x12\)'
            r'(?:0xffffffff|0x1)\b',
            xml
        ):
            raise SystemExit(
                'Test-only or debuggable release rejected'
            )

        minimum = re.search(
            r"sdkVersion:'(\d+)'",
            badging
        )

        if (
            not minimum
            or int(minimum[1]) > 28
        ):
            raise SystemExit(
                'APK must support API 28'
            )

        if (
            "'armeabi-v7a'" not in badging
            or (
                abi == 'universal'
                and "'arm64-v8a'" not in badging
            )
        ):
            raise SystemExit(
                'Missing required native ABI'
            )

        target = output / f'NuvioTV-{abi}.apk'

        shutil.copyfile(
            apk,
            target
        )

        manifest[field] = (
            f'{BASE}/latest/download/{target.name}'
        )

        manifest[
            field + 'Sha256'
        ] = sha256(target)

    (
        output / 'update.json'
    ).write_text(
        json.dumps(
            manifest,
            ensure_ascii=False,
            indent=2
        ) + '\n'
    )

    (
        output / 'notes.md'
    ).write_text(
        f'NuvioTV Mod {name} — Build {code}\n\n'
        'Base upstream: NuvioTV 1.0.0.\n'
    )


if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit(
            'Usage: prepare.py <version|assets>'
        )

    commands = {
        'version': version,
        'assets': assets
    }

    command = commands.get(sys.argv[1])

    if command is None:
        raise SystemExit(
            f'Unknown command: {sys.argv[1]}'
        )

    command()