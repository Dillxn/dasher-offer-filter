#!/usr/bin/env python3
"""Verify the unauthenticated channel using the production Java HTTP and metadata code."""
import hashlib, json, os, pathlib, re, subprocess, sys, time
ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / '.channel-check'
SIGNER = '553994c4d1310bf92f236525d1d293df597f37be39a7fd34f8b58e68dda0c703'
BASE = 'https://dash-offer-filter-build.onrender.com'
def run(*args):
    return subprocess.run(args, check=True, text=True, capture_output=True).stdout

def main():
    OUT.mkdir(exist_ok=True)
    classes = OUT / 'classes'; classes.mkdir(exist_ok=True)
    source = ROOT / 'app/src/main/java/com/local/dasherfilter'
    run('javac', '-d', str(classes), str(source / 'UpdatePolicy.java'), str(source / 'UpdateTransport.java'), str(ROOT / 'tools/UpdateChannelProbe.java'))
    java = ['java', '-cp', str(classes), 'com.local.dasherfilter.UpdateChannelProbe']
    def download(url, path, limit):
        run(*java, 'download', url, str(path), str(limit))
    download(BASE + '/latest.json?t=' + str(time.time_ns()), OUT / 'latest.json', 16384)
    feed = json.loads((OUT / 'latest.json').read_text())
    if type(feed.get('versionCode')) is not int or type(feed.get('size')) is not int:
        raise ValueError('Feed version/size are not JSON integers')
    run(*java, 'metadata', feed['packageName'], str(feed['versionCode']), feed['apkUrl'], feed['sha256'], str(feed['size']), feed.get('encoding', 'raw'), feed['versionName'])
    if feed.get('encoding', 'raw') != 'raw': raise ValueError('Render release must be a raw APK')
    apk = OUT / 'OfferFilter.apk'; download(feed['apkUrl'], apk, feed['size'])
    if apk.stat().st_size != feed['size'] or hashlib.sha256(apk.read_bytes()).hexdigest() != feed['sha256']:
        raise ValueError('Downloaded APK does not match feed')
    sdk = pathlib.Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0'
    signature = run(str(sdk / 'apksigner'), 'verify', '--print-certs', str(apk))
    actual = re.search(r'Signer #1 certificate SHA-256 digest: ([a-f0-9]+)', signature)
    if actual is None or actual.group(1) != SIGNER: raise ValueError('Signing identity changed')
    badging = run(str(sdk / 'aapt2'), 'dump', 'badging', str(apk))
    package = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
    if package is None or package.groups() != (feed['packageName'], str(feed['versionCode']), feed['versionName']):
        raise ValueError('APK package/embedded version disagrees with feed')
    receipt = {'result': 'PASS', 'transport': 'production UpdateTransport.java', 'versionCode': feed['versionCode'], 'versionName': feed['versionName'], 'sourceCommit': feed.get('sourceCommit'), 'sha256': feed['sha256'], 'signerSha256': SIGNER, 'bytes': feed['size']}
    (OUT / 'transport-proof.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print('LIVE_CHANNEL_VERIFIED ' + json.dumps(receipt, sort_keys=True))
if __name__ == '__main__': main()
