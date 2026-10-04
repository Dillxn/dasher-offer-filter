#!/usr/bin/env python3
"""Publishes the repo feed's release (release/OfferFilter.apk, already signed with the cloud key by
tools/sign-local.sh and checked by tools/publish-repo-feed.py) on Render as public/, without building or signing.

The user's decision ("Render serves the GitHub build"): Render's build minutes ran out at 0.4.13, and an
installation that has never connected GitHub reads only Render, so new installs got 0.4.13, which cannot update
itself. Copying the signed APK takes seconds. Nothing here signs anything or holds a key.

Checks before anything is written (every phone checks size, SHA-256, package, version and signer again):
  * release/latest.json names this package, a version, and the APK's exact size and SHA-256;
  * the APK's v2/v3 signing block names the cloud certificate (553994c4…);
  * the version is not below what Render serves now (the same version only from the same source commit).
"""
import hashlib, html, json, pathlib, re, shutil, struct, sys, urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
PACKAGE = 'com.local.dasherfilter'
SIGNER = '553994c4d1310bf92f236525d1d293df597f37be39a7fd34f8b58e68dda0c703'
RENDER = 'https://dash-offer-filter-build.onrender.com'
NAME = re.search(r'NAME = "([^"]+)"', (ROOT / 'app/src/main/java/com/local/dasherfilter/AppName.java')
                 .read_text(encoding='utf-8')).group(1)
SIG_V2 = 0x7109871A
SIG_V3 = 0xF05368C0


def signer_digests(apk: bytes):
    """SHA-256 of each certificate the APK's v2/v3 signing block names (not a cryptographic check; phones do that)."""
    eocd = apk.rfind(b'PK\x05\x06', max(0, len(apk) - 65557))
    if eocd < 0:
        raise ValueError('not a zip: no end of central directory')
    cd_offset = struct.unpack_from('<I', apk, eocd + 16)[0]
    if apk[cd_offset - 16:cd_offset] != b'APK Sig Block 42':
        raise ValueError('no APK signing block (v2/v3)')
    size = struct.unpack_from('<Q', apk, cd_offset - 24)[0]
    start = cd_offset - (size + 8)
    if start < 0 or struct.unpack_from('<Q', apk, start)[0] != size:
        raise ValueError('malformed APK signing block')
    pos, end, found = start + 8, cd_offset - 24, []
    while pos + 12 <= end:
        length = struct.unpack_from('<Q', apk, pos)[0]
        block_id = struct.unpack_from('<I', apk, pos + 8)[0]
        value = apk[pos + 12:pos + 8 + length]
        pos += 8 + length
        if block_id not in (SIG_V2, SIG_V3):
            continue
        signers = lp(value, 0)
        at = 0
        while at < len(signers):
            signer = lp(signers, at)
            at += 4 + len(signer)
            signed = lp(signer, 0)
            digests = lp(signed, 0)
            certs = lp(signed, 4 + len(digests))
            c = 0
            while c < len(certs):
                cert = lp(certs, c)
                c += 4 + len(cert)
                found.append(hashlib.sha256(cert).hexdigest())
    if not found:
        raise ValueError('no v2/v3 signer certificate found')
    return found


def lp(data: bytes, offset: int) -> bytes:
    """The uint32-length-prefixed bytes at offset."""
    length = struct.unpack_from('<I', data, offset)[0]
    if offset + 4 + length > len(data):
        raise ValueError('length-prefixed value runs past its container')
    return data[offset + 4:offset + 4 + length]


def live_feed():
    try:
        with urllib.request.urlopen(RENDER + '/latest.json', timeout=20) as response:
            return json.loads(response.read().decode('utf-8'))
    except Exception as error:  # A first deploy, or Render unreachable from its own build: nothing to compare.
        print('LIVE_FEED_UNAVAILABLE ' + type(error).__name__, file=sys.stderr)
        return None


def main(out=ROOT / 'public', live=None, check_live=True):
    feed = json.loads((ROOT / 'release/latest.json').read_text(encoding='utf-8'))
    apk = (ROOT / 'release/OfferFilter.apk').read_bytes()
    if feed.get('packageName') != PACKAGE:
        raise ValueError('release feed names another package')
    code, version = int(feed['versionCode']), str(feed['versionName'])
    if len(apk) != int(feed['size']) or hashlib.sha256(apk).hexdigest() != feed['sha256']:
        raise ValueError('release APK disagrees with release/latest.json (size or SHA-256)')
    digests = signer_digests(apk)
    if SIGNER not in digests:
        raise ValueError('release APK is not signed with the cloud certificate')
    if check_live:
        live = live if live is not None else live_feed()
    if live:
        if code < int(live['versionCode']) or (code == int(live['versionCode'])
                                              and live.get('sourceCommit') != feed.get('sourceCommit')):
            raise ValueError('refusing a downgrade, or changed bytes without a version increment')
    shutil.rmtree(out, ignore_errors=True)
    out.mkdir(parents=True)
    shutil.copy2(ROOT / 'release/OfferFilter.apk', out / 'OfferFilter.apk')
    published = {'packageName': PACKAGE, 'versionCode': code, 'versionName': version,
                 'apkUrl': RENDER + '/OfferFilter.apk', 'sha256': feed['sha256'], 'size': len(apk),
                 'encoding': 'raw', 'sourceCommit': feed.get('sourceCommit')}
    (out / 'latest.json').write_text(json.dumps(published, indent=2) + '\n', encoding='utf-8')
    (out / 'verification.json').write_text(json.dumps({
        'sourceCommit': feed.get('sourceCommit'), 'versionName': version, 'versionCode': code,
        'mirroredFrom': 'release/ on main (signed by tools/sign-local.sh, which runs the tests on Android 8 and 15 '
                        'simulated; checked by tools/publish-repo-feed.py)',
        'signerSha256': SIGNER, 'deviceInstallVerified': False}, indent=2) + '\n', encoding='utf-8')
    (out / 'signing-receipt.txt').write_text(
        f'versionName={version}\nversionCode={code}\nsourceCommit={feed.get("sourceCommit")}\n'
        f'sha256={feed["sha256"]}\nsize={len(apk)}\nsigner certificate SHA-256 digest: {SIGNER}\n', encoding='utf-8')
    name = html.escape(NAME)
    (out / 'index.html').write_text(f'''<!doctype html><html lang="en"><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>{name} {html.escape(version)}</title>
<body style="font-family:sans-serif;max-width:38em;margin:2em auto;padding:0 1em;line-height:1.5">
<h1>{name} {html.escape(version)}</h1>
<p><strong>Experimental beta.</strong> Installation, updates, touch, audio and split-screen behavior still need
independent real-phone checks. Not made by, endorsed by or affiliated with DoorDash.</p>
<p>Automatic declines may dramatically lower your acceptance rate. Using this app may break DoorDash's terms;
DoorDash could limit or deactivate your account. It can misread, accept or decline an offer you did not want it to.
Use at your own risk, with no warranty. Don't handle your phone while driving.</p>
<p>No GitHub account is needed to download, use or receive updates from this server. Connecting GitHub is optional
and requires access to the private app repository; skip it for normal setup.</p>
<p><a href="/OfferFilter.apk" download="OfferFilter-{html.escape(version)}.apk">Download {name} {html.escape(version)}</a></p>
<h2>First install</h2>
<ol>
<li>Open the downloaded file. If Android asks, allow your browser to install unknown apps. Play Protect may warn about
an app from outside the Play Store. Keep Play Protect enabled. If Android blocks installation or you are unsure
about a warning, stop and review it; this page is not an instruction to bypass a security warning.</li>
<li>Open {name} and read the notice and linked terms. Tap <b>I understand and accept</b> only if you agree;
<b>Not now</b> closes the app without accepting.</li>
<li>The homepage has separate <b>Fix</b> rows for three Android accesses. Enable the ones needed for the features you use:
<ul>
<li><b>Screen reading is off</b>: Accessibility access for on-screen filtering.</li>
<li><b>Background offers are off</b>: notification access for background offers and Peek.</li>
<li><b>Alerts are blocked</b>: {name}'s notification permission for its passing/review alerts and notices.</li>
</ul>
On Android 13 and later, if Android blocks a switch, open App info for {name}, tap the ⋮ menu and choose
<b>Allow restricted settings</b> only if you understand and accept that access, then return to that access setting.
Menus vary by phone.</li>
<li>Drag a knob on the constellation to set a minimum, then tap the mascot to resume filtering. The mascot also pauses
it. <b>Peek is on by default</b> and can briefly open Dasher for background offers; turn it off in Settings if unwanted.
<b>Auto-accept is off by default</b> and needs its own explicit confirmation. Optional location is only for the offer map;
it is not needed for filtering.</li>
</ol>
<h2>Updates</h2>
<p>Already installed? Install over the existing cloud-signed 0.4.x app; don't uninstall for a normal update.
The retired 0.3.1 signing chain is different.</p>
<p>In {name}'s Settings, tap <b>Fix</b> beside <b>Updates can't install</b> and enable <b>Allow from this source</b>
if you want in-app updates. This is separate from your browser's first-install permission. Tap <b>Updates</b> to check
now. Android may request installation confirmation; review its prompt. Automatic installation waits for an observed
dash end, while a manual check can update mid-dash when Dasher is not on screen.</p>
<p><a href="https://dillxn.github.io/offer-filter-site/#help">Help and setup</a> ·
<a href="https://github.com/Dillxn/offer-filter-site/blob/app-source/TERMS.md">Terms of use</a> ·
<a href="https://github.com/Dillxn/offer-filter-site/blob/app-source/PRIVACY.md">Privacy</a> ·
<a href="https://github.com/Dillxn/offer-filter-site/blob/app-source/LICENSE">MIT License</a>.
The Terms and Privacy are drafts, not legal advice; have a lawyer review them before public release.</p>
<p><a href="/verification.json">Verification</a> · <a href="/signing-receipt.txt">Signing receipt</a></p>
</body></html>
''', encoding='utf-8')
    print('MIRRORED ' + json.dumps(published, sort_keys=True))
    return published


if __name__ == '__main__':
    main()
