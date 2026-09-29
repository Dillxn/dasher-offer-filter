#!/usr/bin/env python3
"""One version per source; verify embedded metadata and publish no private build files."""
import hashlib, json, os, pathlib, re, shutil, subprocess, xml.etree.ElementTree as ET
ROOT = pathlib.Path(__file__).resolve().parents[1]
def run(*args): return subprocess.run(args, check=True, text=True, capture_output=True).stdout

def main():
    gradle = (ROOT / 'app/build.gradle').read_text()
    version = re.search(r"versionName '([^']+)'", gradle).group(1)
    code = int(re.search(r'versionCode (\d+)', gradle).group(1))
    head = run('git', 'rev-parse', 'HEAD').strip()
    previous = json.loads((ROOT / '.channel-check/latest.json').read_text())
    if code < previous['versionCode'] or (code == previous['versionCode'] and previous.get('sourceCommit') != head):
        raise ValueError('Refusing a downgrade or changed source without a version increment')
    tests = failures = errors = skipped = 0
    reports = list((ROOT / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
    if not reports: raise ValueError('JUnit reports missing')
    for path in reports:
        r = ET.parse(path).getroot()
        tests += int(r.get('tests', 0)); failures += int(r.get('failures', 0)); errors += int(r.get('errors', 0)); skipped += int(r.get('skipped', 0))
    if tests < 60 or failures or errors or skipped: raise ValueError(f'Test gate failed: tests={tests}, failures={failures}, errors={errors}, skipped={skipped}')
    apk = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
    sdk = pathlib.Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0'
    badging = run(str(sdk / 'aapt2'), 'dump', 'badging', str(apk))
    expected = f"package: name='com.local.dasherfilter' versionCode='{code}' versionName='{version}'"
    if expected not in badging: raise ValueError('Packaged version disagrees with build.gradle')
    public = ROOT / 'public'; shutil.rmtree(public, ignore_errors=True); public.mkdir()
    if code == previous['versionCode']:
        # A same-source redeploy verifies current live bytes without changing them under the same version.
        apk = ROOT / '.channel-check/OfferFilter.apk'
        shutil.copy2(ROOT / '.channel-check/latest.json', public / 'latest.json')
    else:
        data = apk.read_bytes()
        feed = {'packageName': 'com.local.dasherfilter', 'versionCode': code, 'versionName': version, 'apkUrl': 'https://dash-offer-filter-build.onrender.com/OfferFilter.apk', 'sha256': hashlib.sha256(data).hexdigest(), 'size': len(data), 'encoding': 'raw', 'sourceCommit': head}
        (public / 'latest.json').write_text(json.dumps(feed, indent=2) + '\n')
    shutil.copy2(apk, public / 'OfferFilter.apk')
    signature = run(str(sdk / 'apksigner'), 'verify', '--print-certs', str(apk))
    signer = '553994c4d1310bf92f236525d1d293df597f37be39a7fd34f8b58e68dda0c703'
    if 'certificate SHA-256 digest: ' + signer not in signature: raise ValueError('Built APK signer mismatch')
    feed = json.loads((public / 'latest.json').read_text())
    verification = {'sourceCommit': head, 'versionName': version, 'versionCode': code, 'junit': {'tests': tests, 'failures': failures, 'errors': errors, 'skipped': skipped}, 'androidAdapterRuntime': 'Robolectric Android API 35 (not a physical phone)', 'liveTransportProbe': json.loads((ROOT / '.channel-check/transport-proof.json').read_text()), 'deviceInstallVerified': False, 'physicalSoundAndVibrationVerified': False}
    (public / 'verification.json').write_text(json.dumps(verification, indent=2) + '\n')
    (public / 'signing-receipt.txt').write_text('versionName=' + version + '\nversionCode=' + str(code) + '\nsourceCommit=' + head + '\nsha256=' + feed['sha256'] + '\nsize=' + str(feed['size']) + '\n' + signature)
    (public / 'index.html').write_text(f'''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Offer Filter {version}</title><body><h1>Offer Filter {version}</h1><p>Quiet background review and visible-screen filtering. Not an official DoorDash app.</p><p><a href="/OfferFilter.apk">Install Offer Filter {version}</a></p><p>Install over the existing cloud-signed 0.4.x app; do not uninstall. Android may ask for confirmation.</p><p>Unknown offers are silent review cards. This app cannot suppress sound or vibration generated inside Dasher itself.</p><p><a href="/verification.json">Test and transport verification</a> · <a href="/signing-receipt.txt">APK signing receipt</a></p></body></html>''')
    print('JUNIT_VERIFIED ' + json.dumps(verification['junit']))
    print('RELEASE_VERIFIED ' + json.dumps(feed, sort_keys=True))
    print('TRANSPORT_VERIFIED_VERSION ' + verification['liveTransportProbe']['versionName'])
if __name__ == '__main__': main()
