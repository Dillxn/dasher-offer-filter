#!/usr/bin/env python3
"""Publish the APK that tools/sign-local.sh signed to release/ on main, the repository's update feed.

Phones connected to GitHub read release/latest.json and release/OfferFilter.apk through the GitHub API with their
own read-only token, and install only an APK signed like the installed app. This script writes those two files and
nothing else, after checking that the APK is the cloud-signed build of this exact, committed source: its signer,
package and embedded version, a version code above the one already published, and the feed fields against the
app's own policy code. It never signs, commits or pushes; commit release/ and push it to main afterwards.
"""
import hashlib, json, os, pathlib, re, subprocess
ROOT = pathlib.Path(__file__).resolve().parents[1]
SIGNER = '553994c4d1310bf92f236525d1d293df597f37be39a7fd34f8b58e68dda0c703'
PACKAGE = 'com.local.dasherfilter'
REPO_APK = 'https://api.github.com/repos/Dillxn/dasher-offer-filter/contents/release/OfferFilter.apk?ref=main'
MAX_APK_BYTES = 20 * 1024 * 1024
def run(*args): return subprocess.run(args, check=True, text=True, capture_output=True, cwd=ROOT).stdout

def main():
    gradle = (ROOT / 'app/build.gradle').read_text()
    version = re.search(r"versionName '([^']+)'", gradle).group(1)
    code = int(re.search(r'versionCode (\d+)', gradle).group(1))
    if run('git', 'status', '--porcelain', '--', 'app', 'build-local.sh', 'build.gradle', 'settings.gradle').strip():
        raise ValueError('Commit the app source first: the feed names the commit the APK was built from')
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

    feed = {'packageName': PACKAGE, 'versionCode': code, 'versionName': version, 'apkUrl': REPO_APK,
            'sha256': hashlib.sha256(data).hexdigest(), 'size': len(data), 'encoding': 'raw', 'sourceCommit': head}
    classes = ROOT / '.channel-check/classes'; classes.mkdir(parents=True, exist_ok=True)
    source = ROOT / 'app/src/main/java/com/local/dasherfilter'
    run('javac', '-d', str(classes), str(source / 'UpdatePolicy.java'), str(source / 'UpdateTransport.java'),
        str(ROOT / 'tools/UpdateChannelProbe.java'))
    run('java', '-cp', str(classes), 'com.local.dasherfilter.UpdateChannelProbe', 'repo-metadata', PACKAGE, str(code),
        REPO_APK, feed['sha256'], str(feed['size']), 'raw', version)

    release.mkdir(exist_ok=True)
    apk_path.write_bytes(data)
    feed_path.write_text(json.dumps(feed, indent=2) + '\n')
    print('REPO_FEED_WRITTEN ' + json.dumps(feed, sort_keys=True))
if __name__ == '__main__': main()
