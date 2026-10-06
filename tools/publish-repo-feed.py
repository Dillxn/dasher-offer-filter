#!/usr/bin/env python3
"""Publish the APK that tools/sign-local.sh signed to release/ on main, the input Render's mirror publishes.

Render (render-build.sh, tools/mirror_repo_feed.py) copies release/OfferFilter.apk to public/ and rewrites the feed's
apkUrl to its own address; every phone reads only that public feed and installs only an APK signed like the installed
app. This script writes release/latest.json and release/OfferFilter.apk and nothing else, after checking that the APK
is the cloud-signed build of this exact, committed source: its signer, package and embedded version, a version code
above the one already published, and the feed fields, in the form Render serves them, against the app's own policy
code; and that offerfilter.org serves the page the app's Help row opens (tools/help_page.py). It never signs, commits
or pushes; commit release/ and push it to main afterwards.

release/latest.json keeps the retired repository address as its apkUrl for one more release: Offer Filter 0.4.72 and
older, on a phone still connected to GitHub, read release/ directly and require exactly that address (otherwise they
fall back to Render with a note). 0.4.73 and later never read release/; the mirror ignores this apkUrl.
"""
import hashlib, json, os, pathlib, re, subprocess
from help_page import check_help_page
from mirror_repo_feed import page_serves
from release_identity import check_channel
from runtime_http_proxy import java_environment
ROOT = pathlib.Path(__file__).resolve().parents[1]
SIGNER = '553994c4d1310bf92f236525d1d293df597f37be39a7fd34f8b58e68dda0c703'
PACKAGE = 'com.local.dasherfilter'
# The retired repository channel's APK address, which 0.4.72-and-older phones connected to GitHub still require.
REPO_APK = 'https://api.github.com/repos/Dillxn/dasher-offer-filter/contents/release/OfferFilter.apk?ref=main'
# What tools/mirror_repo_feed.py writes as apkUrl on Render, the only feed the app reads.
RENDER_APK = 'https://dash-offer-filter-build.onrender.com/OfferFilter.apk'
MAX_APK_BYTES = 20 * 1024 * 1024
def run(*args):
    # The nested live-channel verifier reads current standard HTTP(S)_PROXY.
    # Discard only stale inherited JVM routing, preserving TLS/trust-store options.
    return subprocess.run(args, check=True, text=True, capture_output=True, cwd=ROOT,
                          env=java_environment()).stdout

def main():
    gradle = (ROOT / 'app/build.gradle').read_text()
    version = re.search(r"versionName '([^']+)'", gradle).group(1)
    code = int(re.search(r'versionCode (\d+)', gradle).group(1))
    if run('git', 'status', '--porcelain', '--', 'app', 'build-local.sh', 'build.gradle', 'settings.gradle').strip():
        raise ValueError('Commit the app source first: the feed names the commit the APK was built from')
    # The app's Help row opens this page at a tap, with no fallback: it must be served before the APK ships.
    check_help_page(page_serves)
    head = run('git', 'rev-parse', 'HEAD').strip()
    release = ROOT / 'release'
    feed_path, apk_path = release / 'latest.json', release / 'OfferFilter.apk'
    if feed_path.exists():
        published = json.loads(feed_path.read_text())['versionCode']
        if code <= published:
            raise ValueError(f'versionCode {code} is not above the published {published}; bump app/build.gradle')

    apk = ROOT / 'dist' / f'OfferFilter-{version}.apk'
    data = apk.read_bytes()
    if not 0 < len(data) <= MAX_APK_BYTES: raise ValueError('APK size out of bounds')
    sdk = pathlib.Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0'
    certs = run(str(sdk / 'apksigner'), 'verify', '--min-sdk-version', '26', '--print-certs', str(apk))
    signer = re.search(r'Signer #1 certificate SHA-256 digest: ([a-f0-9]+)', certs)
    if signer is None or signer.group(1) != SIGNER: raise ValueError('APK is not signed with the cloud key')
    badging = run(str(sdk / 'aapt2'), 'dump', 'badging', str(apk))
    if f"package: name='{PACKAGE}' versionCode='{code}' versionName='{version}'" not in badging:
        raise ValueError('APK package/embedded version disagrees with app/build.gradle')

    # Check the other channel freshly with the app's actual transport and APK verification. A stale local
    # manifest cannot authorize reusing a code already served by the retired full Render build.
    run('python3', str(ROOT / 'tools/verify_channel.py'))
    live = json.loads((ROOT / '.channel-check/latest.json').read_text())
    check_channel(code, head, hashlib.sha256(data).hexdigest(), live, 'Render')

    feed = {'packageName': PACKAGE, 'versionCode': code, 'versionName': version, 'apkUrl': REPO_APK,
            'sha256': hashlib.sha256(data).hexdigest(), 'size': len(data), 'encoding': 'raw', 'sourceCommit': head}
    classes = ROOT / '.channel-check/classes'; classes.mkdir(parents=True, exist_ok=True)
    source = ROOT / 'app/src/main/java/com/local/dasherfilter'
    run('javac', '-d', str(classes), str(source / 'UpdatePolicy.java'), str(source / 'UpdateTransport.java'),
        str(ROOT / 'tools/UpdateChannelProbe.java'))
    # Checked as Render will serve it (the mirror rewrites apkUrl), by the app's own policy code.
    run('java', '-cp', str(classes), 'com.local.dasherfilter.UpdateChannelProbe', 'metadata', PACKAGE, str(code),
        RENDER_APK, feed['sha256'], str(feed['size']), 'raw', version)

    release.mkdir(exist_ok=True)
    apk_path.write_bytes(data)
    feed_path.write_text(json.dumps(feed, indent=2) + '\n')
    print('REPO_FEED_WRITTEN ' + json.dumps(feed, sort_keys=True))
if __name__ == '__main__': main()
