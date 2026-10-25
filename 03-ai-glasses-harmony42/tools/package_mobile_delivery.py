"""Allowlisted, credential-checked source handoff. Does not fabricate an APK."""
from pathlib import Path
import hashlib
import json
import re
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def collect():
    files = {}

    def add(source, destination):
        path = ROOT / source
        if not path.is_file() or path.is_symlink():
            raise ValueError(f'Missing or unsafe delivery input: {source}')
        if destination in files:
            raise ValueError(f'Duplicate destination: {destination}')
        files[destination] = path.read_bytes()

    for path in (ROOT / 'mobile-android').rglob('*'):
        if not path.is_file() or any(part in {'build', '.gradle'} for part in path.parts):
            continue
        if path.suffix not in {'.java', '.xml', '.gradle', '.properties', '.md', '.ps1', '.yml'} and path.name != '.gitignore':
            continue
        if path.name == 'local.properties':
            continue
        relative = path.relative_to(ROOT).as_posix()
        add(relative, relative)
    for path in (ROOT / 'app').iterdir():
        if path.suffix in {'.py', '.html'}:
            add(path.relative_to(ROOT), 'backend/app/' + path.name)
    for path in (ROOT / 'tests').glob('test_*.py'):
        add(path.relative_to(ROOT), 'backend/tests/' + path.name)
    for name in ['requirements.txt', 'render.yaml', '.env.example', 'pytest.ini', 'README.md',
                 'WSS_CAMERA_DEPLOY.md', 'MULTIFUNCTION_ASSISTANT.md', 'REALTIME_ENHANCEMENT.md',
                 'DEPLOY_RENDER.md', 'OCR_INTEGRATION.md', 'RENDER_TWO_AGENTS.md']:
        add(name, 'backend/' + name)
    add('scripts/local_deploy.py', 'backend/scripts/local_deploy.py')
    add('config/ocr.local.example.json', 'backend/config/ocr.local.example.json')
    for path in (ROOT / 'medicine-vision').glob('*.py'):
        add(path.relative_to(ROOT), 'ocr-cloud/' + path.name)
    for path in (ROOT / 'medicine-vision/vision_service').iterdir():
        if path.suffix in {'.py', '.sql', '.json'}:
            add(path.relative_to(ROOT), 'ocr-cloud/vision_service/' + path.name)
    for name in ['requirements-cloud.txt', 'Dockerfile.cloud', 'Dockerfile.cloud.dockerignore',
                 '.dockerignore', '.env.example', 'API_REFERENCE.md', 'THIRD_PARTY_NOTICES.md']:
        add('medicine-vision/' + name, 'ocr-cloud/' + name)
    for path in (ROOT / 'medicine-vision/licenses').rglob('*'):
        if path.is_file() and path.suffix.lower() in {'.txt', '.md'}:
            add(path.relative_to(ROOT), 'ocr-cloud/licenses/' + path.relative_to(ROOT / 'medicine-vision/licenses').as_posix())
    for path in (ROOT / 'delivery-docs').glob('*.md'):
        add(path.relative_to(ROOT), 'docs/' + path.name)
    for name in ['package_mobile_delivery.py', 'build_android_delivery.ps1']:
        add('scripts/' + name, 'tools/' + name)
    apk = ROOT / 'mobile-android/app/build/outputs/apk/debug/app-debug.apk'
    if apk.exists():
        raise ValueError('APK now exists: update verification record and explicitly verify signature before packaging it.')
    return files


def validate(files):
    secrets = []
    for env in [ROOT / '.env', ROOT / 'medicine-vision/.env']:
        if env.is_file():
            for line in env.read_text(encoding='utf-8-sig').splitlines():
                if '=' not in line or line.lstrip().startswith('#'):
                    continue
                key, value = line.split('=', 1)
                value = value.strip().strip('\"\'')
                if re.search(r'TOKEN|SECRET|API_KEY|PASSWORD', key, re.I) and len(value) >= 8:
                    secrets.append(value.encode())
    for token in [ROOT / 'config/ocr-test.token', ROOT / 'config/ocr-test.token.txt']:
        if token.is_file():
            value = token.read_text(encoding='utf-8-sig').strip()
            if len(value) >= 8:
                secrets.append(value.encode())
    for name, data in files.items():
        parts = Path(name).parts
        if any(p in {'.venv', 'uploads', 'local_vision_data', '__pycache__', 'artifacts', 'models'} for p in parts):
            raise ValueError(f'Forbidden directory in delivery: {name}')
        if Path(name).name == '.env' or '.token' in name or name.endswith(('.jks', '.keystore', '.sqlite3', '.jpg', '.bmp')):
            raise ValueError(f'Sensitive file in delivery: {name}')
        if any(secret in data for secret in secrets):
            raise ValueError(f'Known local credential detected in delivery input: {name}')
        if name.endswith('.xml'):
            ET.fromstring(data)
        if name.endswith('.json'):
            json.loads(data)
    java = files['mobile-android/app/src/main/java/cn/aiglasses/companion/MainActivity.java'].decode()
    for contract in ['/api/v1/assistant/analyze', '/camera/latest', '/api/v1/assistant/ocr/',
                     'FLAG_SECURE', 'TextToSpeech', 'getMaxSpeechInputLength', 'setInstanceFollowRedirects(false)']:
        if contract not in java:
            raise ValueError('Mobile source contract missing: ' + contract)
    if 'SharedPreferences' in java or 'addJavascriptInterface' in java:
        raise ValueError('Unexpected token persistence or JS bridge')
    manifest = files['mobile-android/app/src/main/AndroidManifest.xml']
    tree = ET.fromstring(manifest)
    ns = '{http://schemas.android.com/apk/res/android}'
    permissions = [p.get(ns + 'name') for p in tree.findall('uses-permission')]
    if permissions != ['android.permission.INTERNET']:
        raise ValueError('Unexpected permission surface')


def main():
    files = collect()
    validate(files)
    manifest = {name: hashlib.sha256(data).hexdigest() for name, data in sorted(files.items())}
    files['docs/SHA256SUMS.json'] = json.dumps(manifest, ensure_ascii=False, indent=2).encode('utf-8')
    destination = ROOT / 'delivery/ai-glasses-harmony42-integration-source-20261025.zip'
    if destination.exists():
        raise FileExistsError('Refusing to overwrite existing delivery ZIP')
    with zipfile.ZipFile(destination, 'x', zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(files.items()):
            archive.writestr(name, data)
    with zipfile.ZipFile(destination) as archive:
        assert archive.testzip() is None
        for name, digest in manifest.items():
            assert hashlib.sha256(archive.read(name)).hexdigest() == digest
    print(json.dumps({'archive': str(destination), 'files': len(files), 'bytes': destination.stat().st_size,
                      'sha256': hashlib.sha256(destination.read_bytes()).hexdigest(),
                      'apk_included': False, 'known_credential_scan': 'passed',
                      'note': 'Source handoff only; Android compilation and device validation not completed'},
                     ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
