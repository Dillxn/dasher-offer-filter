#!/usr/bin/env python3
"""One version per source; verify embedded metadata and publish no private build files."""
import fnmatch, hashlib, html, json, os, pathlib, re, shutil, subprocess, xml.etree.ElementTree as ET
from release_identity import check_channel, require_current_main
ROOT = pathlib.Path(__file__).resolve().parents[1]
# The name the user sees, from its one source in the app (AppName.NAME), as tools/legal_texts.py reads it.
NAME = re.search(r'NAME = "([^"]+)"', (ROOT / 'app/src/main/java/com/local/dasherfilter/AppName.java').read_text()).group(1)
def run(*args): return subprocess.run(args, check=True, text=True, capture_output=True).stdout

def main():
    gradle = (ROOT / 'app/build.gradle').read_text()
    version = re.search(r"versionName '([^']+)'", gradle).group(1)
    code = int(re.search(r'versionCode (\d+)', gradle).group(1))
    head = run('git', 'rev-parse', 'HEAD').strip()
    # release/latest.json is only evidence of the other channel if this checkout is still current main.
    # An unavailable/private remote or an old deployment fails closed; Render normally mirrors release/ instead.
    require_current_main(head, run('git', 'ls-remote', '--exit-code', 'origin', 'refs/heads/main'))
    previous = json.loads((ROOT / '.channel-check/latest.json').read_text())
    if code < previous['versionCode'] or (code == previous['versionCode'] and previous.get('sourceCommit') != head):
        raise ValueError('Refusing a downgrade or changed source without a version increment')
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    suites = {}
    reports = list((ROOT / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
    if not reports: raise ValueError('JUnit reports missing')
    for path in reports:
        r = ET.parse(path).getroot()
        counts = {key: int(r.get(key, 0)) for key in totals}
        suites[r.get('name', path.name)] = counts
        for key, value in counts.items(): totals[key] += value
    # Each floor counts every suite its name matches as a glob, so a suite split by topic into several classes
    # (AndroidAdapter*Test) must still reach its floor together.
    required = {'com.local.dasherfilter.AndroidAdapter*Test': 172, 'com.local.dasherfilter.AreaMapTest': 16, 'com.local.dasherfilter.AccessibilityAdapterTest': 106, 'com.local.dasherfilter.DecisionLogTest': 18, 'com.local.dasherfilter.MotionAdapterTest': 12, 'com.local.dasherfilter.PlacesTest': 10, 'com.local.dasherfilter.AreaMapViewTest': 4,
                # Accountless feedback, its outbox and dialogs, the opt-in dash summary, stop reports, the offer report
                # and the one-time removal of the GitHub state.
                'com.local.dasherfilter.Feedback*Test': 48, 'com.local.dasherfilter.DashSummaryTest': 14, 'com.local.dasherfilter.StopReportsTest': 8, 'com.local.dasherfilter.OfferReportTest': 8, 'com.local.dasherfilter.LegacyReportingCleanupTest': 10}
    adapter_suites = {}
    for pattern, minimum in required.items():
        counts = dict.fromkeys(totals, 0)
        for name, suite in suites.items():
            if fnmatch.fnmatchcase(name, pattern):
                for key, value in suite.items(): counts[key] += value
        if counts['tests'] < minimum: raise ValueError(f"Missing Android adapter coverage: {pattern} ran {counts['tests']} of {minimum}")
        adapter_suites[pattern] = counts
    if totals['tests'] < 1046 or totals['failures'] or totals['errors'] or totals['skipped']:
        raise ValueError('Test gate failed: ' + json.dumps(totals))
    apk = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
    sdk = pathlib.Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0'
    badging = run(str(sdk / 'aapt2'), 'dump', 'badging', str(apk))
    expected = f"package: name='com.local.dasherfilter' versionCode='{code}' versionName='{version}'"
    if expected not in badging: raise ValueError('Packaged version disagrees with build.gradle')
    if code == previous['versionCode']:
        # Same-source redeploy verifies current live bytes without mutating a published version.
        apk = ROOT / '.channel-check/OfferFilter.apk'
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    check_channel(code, head, digest, previous, 'Render')
    repo_feed = ROOT / 'release/latest.json'
    if repo_feed.exists():
        check_channel(code, head, digest, json.loads(repo_feed.read_text()), 'release/ (the Render mirror input)')
    public = ROOT / 'public'; shutil.rmtree(public, ignore_errors=True); public.mkdir()
    if code == previous['versionCode']:
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
    verification = {'sourceCommit': head, 'versionName': version, 'versionCode': code, 'junit': totals, 'adapterSuites': adapter_suites, 'androidAdapterRuntime': 'Robolectric Android API 26 and 35 (simulation; not a physical phone)', 'liveTransportProbe': json.loads((ROOT / '.channel-check/transport-proof.json').read_text()), 'deviceInstallVerified': False, 'physicalSoundAndVibrationVerified': False}
    (public / 'verification.json').write_text(json.dumps(verification, indent=2) + '\n')
    (public / 'signing-receipt.txt').write_text('versionName=' + version + '\nversionCode=' + str(code) + '\nsourceCommit=' + head + '\nsha256=' + feed['sha256'] + '\nsize=' + str(feed['size']) + '\n' + signature)
    name = html.escape(NAME)
    (public / 'index.html').write_text(f'''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>{name} {version}</title><body><h1>{name} {version}</h1><p>Quiet background review and visible-screen filtering. Not an official DoorDash app.</p><p><a href="/OfferFilter.apk" download="OfferFilter-{version}.apk">Install {name} {version}</a></p><p>Install over the existing cloud-signed 0.4.x app; do not uninstall. Android may ask for confirmation.</p><p>Unknown offers get a review card that rings once. This app cannot suppress sound or vibration generated inside Dasher itself.</p><p><a href="/verification.json">Test and transport verification</a> · <a href="/signing-receipt.txt">APK signing receipt</a></p></body></html>''')
    print('JUNIT_VERIFIED ' + json.dumps(totals))
    print('ANDROID_ADAPTERS_VERIFIED ' + json.dumps(verification['adapterSuites']))
    print('RELEASE_VERIFIED ' + json.dumps(feed, sort_keys=True))
    print('TRANSPORT_VERIFIED_VERSION ' + verification['liveTransportProbe']['versionName'])
if __name__ == '__main__': main()
