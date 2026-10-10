#!/usr/bin/env python3
"""Nonsecret split-release validation. Only sign-local.sh invokes signing.

The independently delivered preparation digest is the trust anchor. A checksum
inside the downloaded bundle is never an authority. Nothing here publishes.
"""
import argparse
import hashlib
import io
import json
import os
import pathlib
import re
import shutil
import stat
import struct
import subprocess
import sys
import tempfile
import unicodedata
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
SIGNER = '553994c4d1310bf92f236525d1d293df597f37be39a7fd34f8b58e68dda0c703'
JAR_SHA = '00ef9948f843fe395d2440ae3ef41405b8040a6d5d46493bd1902ac0ee6deae7'
PACKAGE = 'com.local.dasherfilter'
MAX_APK = 20 * 1024 * 1024
MAX_PACKET = 30 * 1024 * 1024
MAX_METADATA = 4 * 1024 * 1024
MAX_TOOL_FILE = 128 * 1024 * 1024
NOTICES = ('APKSIG-LICENSE.txt', 'APACHE-2.0.txt', 'CONSCRYPT-LICENSE.txt', 'SDK-NOTICE.txt', 'PROVENANCE.json')
BUNDLE_FILES = {'OfferFilter-unsigned.apk', 'inputs.json', 'gate.json', 'evidence.json',
                'tools/sign-local.sh', 'tools/split_release.py', 'tools/apksigner.jar'} | {'notices/' + x for x in NOTICES}
# This portable verifier has no Android SDK or repository imports. A test binds
# this set to the canonical, unchanged release gate; only that gate issues proof.
ADAPTERS = ('AndroidAdapterAlertsAndSettingsTest', 'AndroidAdapterChartTest',
            'AndroidAdapterHomepageTest', 'AndroidAdapterReportsAndUpdatesTest', 'AccessibilityAdapterTest',
            'AutoAcceptAdapterTest', 'AutoAcceptSettingsTest', 'ModelMigrationTest', 'AutopilotCommitAdapterTest',
            'ConfirmationArReadingAdapterTest', 'ConsentGateTest', 'MinimumsGrowthAdapterTest')
SHA = re.compile(r'[a-f0-9]{64}\Z')
GIT_SHA = re.compile(r'[a-f0-9]{40}\Z')
SIGNATURE_ENTRY = re.compile(r'META-INF/(?:MANIFEST\.MF|[A-Z0-9_-]+\.(?:SF|RSA|DSA|EC))\Z', re.I)


def need(condition, message):
    if not condition:
        raise ValueError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def encoded(value):
    return (json.dumps(value, sort_keys=True, separators=(',', ':')) + '\n').encode()


def no_duplicates(pairs):
    result = {}
    for key, value in pairs:
        need(key not in result, 'Duplicate JSON field')
        result[key] = value
    return result


def read_json(path):
    return json.loads(read_checked(path, MAX_METADATA), object_pairs_hook=no_duplicates)


def regular(path):
    path = pathlib.Path(path).absolute()
    for part in (path, *path.parents):
        need(not part.is_symlink(), 'Symlink is not a release input')
    need(path.is_file(), 'Missing regular release input')
    return path


def checked_file(path, limit, collect=False):
    """Bound before allocation, use one regular-file descriptor, reject mutation."""
    path = regular(path)
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        before = os.fstat(fd)
        need(stat.S_ISREG(before.st_mode), 'Release input is not a regular file')
        need(0 <= before.st_size <= limit, 'File exceeds size limit before reading')
        total, sha, chunks = 0, hashlib.sha256(), []
        while True:
            chunk = os.read(fd, min(65536, limit - total + 1))
            if not chunk:
                break
            total += len(chunk)
            need(total <= limit, 'File grew beyond size limit while reading')
            sha.update(chunk)
            if collect:
                chunks.append(chunk)
        def stamp(s):
            return (s.st_dev, s.st_ino, s.st_mode, s.st_size, s.st_mtime_ns, s.st_ctime_ns)
        need(stamp(before) == stamp(os.fstat(fd)) == stamp(path.stat(follow_symlinks=False))
             and total == before.st_size, 'Release input changed while reading')
        return b''.join(chunks) if collect else dict(size=total, sha256=sha.hexdigest())
    finally:
        os.close(fd)


def read_checked(path, limit):
    return checked_file(path, limit, collect=True)


def file_info(path, limit=MAX_TOOL_FILE):
    return checked_file(path, limit)


def write_new(path, data):
    path = pathlib.Path(path)
    need(not path.is_symlink(), 'Symlink output refused')
    with path.open('xb') as stream:
        stream.write(data)


def run(*args, cwd=ROOT):
    result = subprocess.run([str(a) for a in args], cwd=cwd, capture_output=True, text=True)
    need(result.returncode == 0, 'Required command failed: ' + str(args[0]))
    return result.stdout


def identity(source, tree):
    need(GIT_SHA.fullmatch(source or '') and GIT_SHA.fullmatch(tree or ''), 'Invalid candidate identity')
    need(run('git', 'rev-parse', 'HEAD').strip() == source, 'Unexpected candidate commit')
    need(run('git', 'rev-parse', 'HEAD^{tree}').strip() == tree, 'Unexpected candidate tree')
    need(not run('git', 'status', '--porcelain', '--untracked-files=all').strip(), 'Candidate checkout is not clean')


def check_pending(path):
    path = pathlib.Path(path)
    need(path.is_absolute() and path == path.resolve(), 'Pending path must be absolute without links or traversal')
    need('pending-unsigned' in path.parts and not {'dist', 'release'} & set(path.parts), 'Unsigned output must be pending-unsigned only')
    need(not path.is_relative_to(ROOT), 'Pending output must be outside the source checkout')
    need(not path.exists(), 'Pending output already exists')
    return path


def check_private(path):
    path = pathlib.Path(path)
    need(path.is_absolute() and path == path.resolve() and path.is_dir(), 'Private directory missing or linked')
    s = path.stat()
    need(s.st_uid == os.getuid() and stat.S_IMODE(s.st_mode) == 0o700, 'Private directory must be owned by this user and mode 0700')


def apk_entries(path, unsigned=False):
    data = read_checked(path, MAX_APK)
    need(0 < len(data) <= MAX_APK, 'APK size out of bounds')
    # This flow produces ordinary single-disk ZIPs without comments or ZIP64.
    need(len(data) >= 22 and data[-22:-18] == b'PK\x05\x06', 'APK has trailing data, comment or missing EOCD')
    disk, cd_disk, disk_count, count, cd_size, cd_offset, comment = struct.unpack_from('<HHHHIIH', data, len(data) - 18)
    need(disk == cd_disk == comment == 0 and disk_count == count and count != 65535,
         'Unsupported ZIP layout')
    need(cd_offset + cd_size == len(data) - 22, 'Ambiguous central directory')
    signed_block = data[max(0, cd_offset - 16):cd_offset] == b'APK Sig Block 42'
    if unsigned:
        need(not signed_block, 'Expected unsigned APK, found APK signing block')
    entries, folded = {}, set()
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        need(len(archive.infolist()) == count, 'ZIP entry count mismatch')
        total = 0
        for entry in archive.infolist():
            name = entry.filename
            need(name == entry.orig_filename and name == unicodedata.normalize('NFC', name), 'Ambiguous ZIP name')
            need(name and not name.startswith('/') and '\\' not in name and '\x00' not in name,
                 'Unsafe ZIP path')
            parts = name.rstrip('/').split('/')
            need(all(x not in ('', '.', '..') for x in parts), 'Unsafe ZIP path component')
            need(name.casefold() not in folded, 'Duplicate or ambiguous ZIP entry')
            folded.add(name.casefold())
            need(not entry.flag_bits & 1 and entry.compress_type in (0, 8), 'Unsupported ZIP entry')
            mode = entry.external_attr >> 16
            need(not stat.S_ISLNK(mode), 'ZIP symlink refused')
            total += entry.file_size
            need(total <= 100 * 1024 * 1024, 'Expanded APK is too large')
            if unsigned:
                need(not SIGNATURE_ENTRY.fullmatch(name), 'Expected unsigned APK, found JAR signature metadata')
            content = archive.read(entry)  # Checks CRC and local/central filename agreement.
            entries[name] = dict(size=len(content), sha256=digest(content), compression=entry.compress_type)
    need({'AndroidManifest.xml', 'classes.dex', 'resources.arsc'} <= entries.keys(), 'Incomplete packaged APK')
    return entries


def compare_payload(expected, actual, verified_v1=False):
    for name, info in expected.items():
        need(actual.get(name) == info, 'Signed APK payload differs from prepared unsigned APK')
    extras = actual.keys() - expected.keys()
    need(not extras or (verified_v1 and all(SIGNATURE_ENTRY.fullmatch(x) for x in extras)),
         'Unexpected additional signed APK entries')


def certs_ok(output):
    certs = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-f0-9]+)', output)
    need(certs == [SIGNER], 'APK must have exactly the original signer')
    return 'Verified using v1 scheme (JAR signing): true' in output


def verify_signed(path, jar=None):
    if jar is None:
        command = [str(sdk() / 'apksigner')]
    else:
        need(file_info(jar)['sha256'] == JAR_SHA, 'Unexpected signing JAR')
        command = ['java', '-Xmx1024M', '-jar', str(jar)]
    verified_v1 = certs_ok(run(*command, 'verify', '--verbose', '--min-sdk-version', '26', '--print-certs', path))
    # API 26+ can verify v2/v3 while reporting v1 as false because it was not
    # checked. Authenticate any JAR signature metadata independently on API 23;
    # this supplements, and never replaces, the required API 26+ verification.
    if not verified_v1 and any(SIGNATURE_ENTRY.fullmatch(name) for name in apk_entries(path)):
        verified_v1 = certs_ok(run(*command, 'verify', '--verbose', '--min-sdk-version', '23',
                                 '--max-sdk-version', '23', '--print-certs', path))
        need(verified_v1, 'JAR signature metadata did not pass independent v1 verification')
    return verified_v1


def sdk():
    return pathlib.Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0'


def package_info(path, version, code):
    badging = run(sdk() / 'aapt2', 'dump', 'badging', path)
    expected = f"package: name='{PACKAGE}' versionCode='{code}' versionName='{version}'"
    need(expected in badging, 'Packaged identity differs from candidate')
    need(re.search(r"^minSdkVersion:'26'$", badging, re.M)
         and re.search(r"^targetSdkVersion:'35'$", badging, re.M), 'Packaged SDK identity differs')
    run(sdk() / 'zipalign', '-c', '4', path)
    return dict(packageName=PACKAGE, versionName=version, versionCode=code, minSdk=26, targetSdk=35,
                aapt2BadgingSha256=digest(badging.encode()), zipalign=True)


def version_info():
    text = (ROOT / 'app/build.gradle').read_text()
    return re.search(r"versionName '([^']+)'", text).group(1), int(re.search(r'versionCode (\d+)', text).group(1))


def validate_gate(gate, frozen):
    files = frozen.get('files', {})
    need(files and frozen['count'] == len(files), 'Invalid frozen input inventory')
    aggregate = digest(json.dumps(files, sort_keys=True, separators=(',', ':')).encode())
    need(frozen['aggregate'] == aggregate, 'Frozen aggregate mismatch')
    need(gate['inputSha256'] == aggregate and gate['inputCount'] == len(files), 'Gate/frozen input mismatch')
    need(gate['junit']['tests'] >= 3272 and all(gate['junit'][x] == 0 for x in ('errors', 'failures', 'skipped')),
         'Full release tests not passed')
    need(gate['lint']['errors'] == 0, 'Lint did not pass')
    need(set(gate['adapterSdkCases']) == set(ADAPTERS), 'Required adapters missing')
    for counts in gate['adapterSdkCases'].values():
        need(set(counts) == {'26', '35'} and type(counts['26']) is int and counts['26'] > 0
             and counts['26'] == counts['35'], 'Required dual-SDK pairs missing')


def bundle_check(bundle, receipt_sha, source, tree):
    bundle = pathlib.Path(bundle).absolute()
    need(SHA.fullmatch(receipt_sha or ''), 'Independent preparation receipt SHA-256 is required')
    receipt_bytes = read_checked(bundle / 'prepare.json', MAX_METADATA)
    need(digest(receipt_bytes) == receipt_sha, 'Independent preparation receipt SHA-256 mismatch')
    receipt = json.loads(receipt_bytes, object_pairs_hook=no_duplicates)
    need(receipt['schema'] == 1 and receipt['purpose'] == 'UNSIGNED_NONPUBLISHABLE', 'Wrong preparation receipt')
    need(set(receipt['files']) == BUNDLE_FILES, 'Preparation manifest file allowlist mismatch')
    need(receipt['originalSigner'] == SIGNER, 'Wrong prepared original signer')
    need(GIT_SHA.fullmatch(source or '') and receipt['sourceCommit'] == source, 'Unexpected candidate commit')
    need(GIT_SHA.fullmatch(tree or '') and receipt['sourceTree'] == tree, 'Unexpected candidate tree')
    actual_files = {p.relative_to(bundle).as_posix() for p in bundle.rglob('*') if p.is_file() or p.is_symlink()}
    need(actual_files == set(receipt['files']) | {'prepare.json'}, 'Missing or unexpected bundle files')
    for name, info in receipt['files'].items():
        need(name and not name.startswith('/') and all(x not in ('', '.', '..') for x in name.split('/'))
             and '\\' not in name, 'Unsafe bundle path')
        limit = MAX_APK if name == 'OfferFilter-unsigned.apk' else MAX_METADATA
        need(file_info(bundle / name, limit) == info, 'Bundle file hash/size mismatch')
    frozen = read_json(bundle / 'inputs.json')
    gate = read_json(bundle / 'gate.json')
    need(receipt['inputAggregate'] == frozen['aggregate'] and receipt['inputCount'] == frozen['count'],
         'Receipt/frozen input mismatch')
    validate_gate(gate, frozen)
    for name in ('tools/sign-local.sh', 'tools/split_release.py'):
        need(receipt['files'][name]['sha256'] == frozen['files'][name], 'Transferred code differs from candidate')
        need(file_info(ROOT / name) == receipt['files'][name], 'Executing code differs from pinned bundle')
    need(receipt['files']['tools/apksigner.jar']['sha256'] == JAR_SHA, 'Unexpected signing JAR')
    need(receipt['package']['packageName'] == PACKAGE and receipt['package']['minSdk'] == 26
         and receipt['package']['targetSdk'] == 35, 'Wrong package identity')
    apk = bundle / 'OfferFilter-unsigned.apk'
    need(apk_entries(apk, unsigned=True) == receipt['payload'], 'Prepared payload inventory mismatch')
    return bundle, receipt


def prepare(source, tree, pending_root, notices):
    from release_bridge_gate import snapshot, gate
    identity(source, tree)
    target = check_pending(pathlib.Path(pending_root) / source)
    target.mkdir(parents=True, mode=0o700)
    frozen = snapshot(ROOT)
    write_new(target / 'inputs.json', encoded(frozen))
    # Logs remain local outside the downloadable bundle. Exit codes are retained.
    logs = target.parent / (source + '-local-receipts')
    logs.mkdir(mode=0o700)
    commands = []
    for name, argv in [('test', ['./gradlew', '-q', 'testDebugUnitTest', '-PallSdks']),
                       ('lint', ['./gradlew', '-q', 'lintDebug', '-PallSdks'])]:
        with (logs / (name + '.log')).open('xb') as output:
            result = subprocess.run(argv, cwd=ROOT, stdout=output, stderr=subprocess.STDOUT)
        commands.append(dict(argv=argv, exitCode=result.returncode, log=file_info(logs / (name + '.log'))))
        write_new(logs / (name + '-command.json'), encoded(commands[-1]))
        need(result.returncode == 0, 'Required ' + name + ' command failed')
    gate_result = gate(ROOT, frozen)
    unsigned = target / 'OfferFilter-unsigned.apk'
    run('./build-local.sh', '--unsigned-output', unsigned)
    version, code = version_info()
    package = package_info(unsigned, version, code)
    payload = apk_entries(unsigned, unsigned=True)
    need(gate(ROOT, frozen) == gate_result, 'Release evidence changed during packaging')
    identity(source, tree)
    write_new(target / 'gate.json', encoded(gate_result))
    reports = sorted((ROOT / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
    toolchain = {name: file_info(sdk() / name) for name in ('aapt2', 'd8', 'lib/d8.jar', 'zipalign', 'apksigner', 'lib/apksigner.jar')}
    toolchain['android-36/android.jar'] = file_info(pathlib.Path(os.environ['ANDROID_HOME']) / 'platforms/android-36/android.jar')
    toolchain['javac'] = file_info(pathlib.Path(os.environ['JAVA_HOME']) / 'bin/javac')
    evidence = dict(commands=commands,
                    junit={p.name: file_info(p) for p in reports},
                    lint=file_info(ROOT / 'app/build/reports/lint-results-debug.xml'), toolchain=toolchain,
                    packagingCommand=['./build-local.sh', '--unsigned-output', str(unsigned)], packagingExitCode=0,
                    environment={key: os.environ[key] for key in ('JAVA_HOME', 'ANDROID_HOME', 'GRADLE_USER_HOME',
                                 'ANDROID_USER_HOME', 'TMPDIR', 'LANG', 'LC_ALL') if key in os.environ})
    write_new(target / 'evidence.json', encoded(evidence))
    (target / 'tools').mkdir()
    for name in ('sign-local.sh', 'split_release.py'):
        shutil.copyfile(ROOT / 'tools' / name, target / 'tools' / name)
    jar = sdk() / 'lib/apksigner.jar'
    need(file_info(jar)['sha256'] == JAR_SHA, 'Unexpected signing JAR')
    shutil.copyfile(jar, target / 'tools/apksigner.jar')
    (target / 'notices').mkdir()
    for name in NOTICES:
        shutil.copyfile(regular(pathlib.Path(notices) / name), target / 'notices' / name)
    files = {p.relative_to(target).as_posix(): file_info(p) for p in sorted(target.rglob('*')) if p.is_file()}
    receipt = dict(schema=1, purpose='UNSIGNED_NONPUBLISHABLE', sourceCommit=source, sourceTree=tree,
                   inputAggregate=frozen['aggregate'], inputCount=frozen['count'], package=package,
                   payload=payload, files=files, originalSigner=SIGNER,
                   deviceInstallVerified=False, published=False)
    write_new(target / 'prepare.json', encoded(receipt))
    anchor = file_info(target / 'prepare.json')['sha256']
    bundle_check(target, anchor, source, tree)
    print(json.dumps(dict(bundle=str(target), preparationReceiptSha256=anchor, sourceCommit=source,
                          sourceTree=tree, inputAggregate=frozen['aggregate'], unsigned=file_info(unsigned))))


def check_sign(bundle, anchor, source, tree, output):
    bundle, receipt = bundle_check(bundle, anchor, source, tree)
    output = pathlib.Path(output)
    need(output.is_absolute() and output == output.resolve() and not output.exists(), 'Signing output must be a new absolute directory')
    need(not {'dist', 'release'} & set(output.parts) and not output.is_relative_to(bundle), 'Invalid signing quarantine output')
    return bundle, receipt, output


def stage_sign(bundle, anchor, source, tree, output, staging):
    bundle, receipt, _ = check_sign(bundle, anchor, source, tree, output)
    check_private(staging)
    staging = pathlib.Path(staging)
    for name, dest in [('OfferFilter-unsigned.apk', 'unsigned.apk'), ('tools/apksigner.jar', 'apksigner.jar')]:
        write_new(staging / dest, read_checked(bundle / name, MAX_APK))
        need(file_info(staging / dest) == receipt['files'][name], 'Staged signing input mismatch')


def record_sign(bundle, anchor, source, tree, output, staging):
    bundle, receipt, output = check_sign(bundle, anchor, source, tree, output)
    staging = pathlib.Path(staging)
    need(file_info(staging / 'unsigned.apk') == receipt['files']['OfferFilter-unsigned.apk'], 'Staged unsigned input changed')
    signed = staging / 'signed.apk'
    v1 = verify_signed(signed, staging / 'apksigner.jar')
    compare_payload(receipt['payload'], apk_entries(signed), v1)
    result = dict(schema=1, preparationReceiptSha256=anchor, sourceCommit=source, sourceTree=tree,
                  inputAggregate=receipt['inputAggregate'], unsigned=receipt['files']['OfferFilter-unsigned.apk'],
                  signed=file_info(signed), signer=SIGNER, package=receipt['package'],
                  payloadUnchanged=True, coordinatorAapt2Verified=False, canonicalAcceptanceRequired=True)
    output.mkdir(mode=0o700)
    write_new(output / 'OfferFilter-signed.apk', read_checked(signed, MAX_APK))
    write_new(output / 'signed-receipt.json', encoded(result))
    print(json.dumps(result, sort_keys=True))


def accept(bundle, anchor, source, tree, signed, signing_receipt):
    from release_bridge_gate import gate
    identity(source, tree)
    bundle, receipt = bundle_check(bundle, anchor, source, tree)
    frozen = read_json(bundle / 'inputs.json')
    need(gate(ROOT, frozen) == read_json(bundle / 'gate.json'), 'Current gate does not match preparation')
    result = read_json(signing_receipt)
    need(result['schema'] == 1 and result['preparationReceiptSha256'] == anchor, 'Signed receipt anchor mismatch')
    need(result['sourceCommit'] == source and result['sourceTree'] == tree
         and result['inputAggregate'] == receipt['inputAggregate'], 'Signed receipt candidate mismatch')
    need(result['unsigned'] == receipt['files']['OfferFilter-unsigned.apk'] and result['signer'] == SIGNER,
         'Signed receipt unsigned identity/signer mismatch')
    need(result['package'] == receipt['package'] and result['payloadUnchanged'] is True,
         'Signed receipt package/payload claims differ from preparation')
    need(result['canonicalAcceptanceRequired'] is True and result['coordinatorAapt2Verified'] is False,
         'Signed receipt claims unauthorized canonical/package verification')
    # Work only on our copy, preventing a transfer path change between checking and admission.
    with tempfile.TemporaryDirectory(prefix='offerfilter-accept-') as temp:
        staged = pathlib.Path(temp) / 'signed.apk'
        staged.write_bytes(read_checked(signed, MAX_APK))
        need(file_info(staged) == result['signed'], 'Signed return hash/size mismatch')
        v1 = verify_signed(staged)
        compare_payload(receipt['payload'], apk_entries(staged), v1)
        version, code = version_info()
        package = package_info(staged, version, code)
        need(package == receipt['package'], 'Returned package identity differs')
        identity(source, tree)
        need(gate(ROOT, frozen) == read_json(bundle / 'gate.json'), 'Frozen input/gate changed before admission')
        out = ROOT / 'dist'
        out.mkdir(exist_ok=True)
        target = out / ('OfferFilter-' + version + '.apk')
        need(out == out.resolve() and not target.exists() and not target.is_symlink(), 'Existing or linked dist output')
        receipt_target = out / ('OfferFilter-' + version + '-acceptance.json')
        need(not receipt_target.exists() and not receipt_target.is_symlink(), 'Acceptance receipt already exists')
        write_new(target, read_checked(staged, MAX_APK))
        # Never promote caller-supplied fields into canonical audit facts.
        verified_bytes = file_info(target, MAX_APK)
        public = dict(schema=1, preparationReceiptSha256=anchor, sourceCommit=source, sourceTree=tree,
                      inputAggregate=frozen['aggregate'], unsigned=receipt['files']['OfferFilter-unsigned.apk'],
                      signed=verified_bytes, accepted=verified_bytes, signer=SIGNER, package=package,
                      payloadUnchanged=True, coordinatorAapt2Verified=False, canonicalAapt2Verified=True,
                      canonicalAcceptanceRequired=False, published=False, deviceInstallVerified=False)
        write_new(receipt_target, encoded(public))
        print(json.dumps(public, sort_keys=True))


def unpack(packet, packet_sha, anchor, source, tree, destination):
    """Validate ALL members and byte hashes before extraction; never execute them."""
    need(SHA.fullmatch(packet_sha or ''), 'Independent packet SHA-256 required')
    packet_bytes = read_checked(packet, MAX_PACKET)
    need(digest(packet_bytes) == packet_sha, 'Independent packet SHA-256 mismatch')
    need(SHA.fullmatch(anchor or ''), 'Independent preparation receipt SHA-256 required')
    destination = pathlib.Path(destination)
    need(destination.is_absolute() and destination == destination.resolve() and not destination.exists(),
         'Intake destination must be new, absolute and unlinked')
    members = {}
    with zipfile.ZipFile(io.BytesIO(packet_bytes)) as archive:
        infos = archive.infolist()
        names = [i.filename for i in infos]
        need(len(names) == len(set(n.casefold() for n in names)), 'Duplicate/ambiguous packet entries')
        need(set(names) == BUNDLE_FILES | {'prepare.json'}, 'Missing or unexpected packet members')
        need(sum(i.file_size for i in infos) <= 40 * 1024 * 1024, 'Expanded packet too large')
        for info in infos:
            need(info.filename == info.orig_filename and not info.is_dir()
                 and not info.flag_bits & 1 and info.compress_type in (0, 8), 'Ambiguous packet entry')
            mode = info.external_attr >> 16
            need(stat.S_IFMT(mode) in (0, stat.S_IFREG), 'Nonregular packet member')
        # All metadata is now allowlisted; read/CRC/hash every byte before mkdir.
        for info in infos:
            members[info.filename] = archive.read(info)
    need(digest(members['prepare.json']) == anchor, 'Independent preparation receipt SHA-256 mismatch')
    receipt = json.loads(members['prepare.json'], object_pairs_hook=no_duplicates)
    need(receipt['schema'] == 1 and receipt['purpose'] == 'UNSIGNED_NONPUBLISHABLE', 'Wrong packet purpose')
    need(GIT_SHA.fullmatch(source or '') and receipt['sourceCommit'] == source, 'Unexpected candidate commit')
    need(GIT_SHA.fullmatch(tree or '') and receipt['sourceTree'] == tree, 'Unexpected candidate tree')
    need(set(receipt['files']) == BUNDLE_FILES, 'Packet manifest allowlist mismatch')
    for name in BUNDLE_FILES:
        need(receipt['files'][name] == dict(size=len(members[name]), sha256=digest(members[name])),
             'Packet member hash/size mismatch')
    destination.mkdir(mode=0o700)
    for name, data in members.items():
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        write_new(target, data)
    print(json.dumps(dict(intakeDirectory=str(destination), packetSha256=packet_sha,
                          preparationReceiptSha256=anchor, extractedMembers=len(members), executed=False)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='mode', required=True)
    for name in ('check-pending-path', 'check-private-dir'):
        sub.add_parser(name).add_argument('path')
    p = sub.add_parser('prepare')
    for name in ('source', 'tree', 'pending_root', 'notices'):
        p.add_argument(name)
    for name in ('check-sign', 'stage-sign', 'record-sign', 'accept'):
        p = sub.add_parser(name)
        for field in ('bundle', 'anchor', 'source', 'tree'):
            p.add_argument(field)
        if name == 'accept':
            p.add_argument('signed'); p.add_argument('signing_receipt')
        else:
            p.add_argument('output')
        if name in ('stage-sign', 'record-sign'):
            p.add_argument('staging')
    p = sub.add_parser('unpack')
    for name in ('packet', 'packet_sha', 'anchor', 'source', 'tree', 'destination'):
        p.add_argument(name)
    args = vars(parser.parse_args())
    mode = args.pop('mode')
    functions = {'check-pending-path': check_pending, 'check-private-dir': check_private,
                 'prepare': prepare, 'check-sign': check_sign, 'stage-sign': stage_sign,
                 'record-sign': record_sign, 'accept': accept, 'unpack': unpack}
    functions[mode](**args)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, TypeError, zipfile.BadZipFile) as error:
        print('SPLIT_RELEASE_REFUSED: ' + str(error), file=sys.stderr)
        sys.exit(1)
