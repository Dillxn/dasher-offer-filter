#!/usr/bin/env python3
"""Resumable LOCAL OfferFilter release runner. No CI, SDK installation or key export.

Every completed stage is an immutable, hashed directory. Disposable attempts
never become success records. The existing split signer, acceptance gate,
publisher and production channel probe remain the authorities.
"""
import argparse
import contextlib
import datetime
import fcntl
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

import release_capsule as capsule
from release_environment import resolve_environment
from release_publish import publish as publish_feed

ROOT = pathlib.Path(__file__).resolve().parents[1]
STAGES = ('prepared', 'signed', 'accepted', 'staged', 'verified')
SIGNER = '553994c4d1310bf92f236525d1d293df597f37be39a7fd34f8b58e68dda0c703'
SECRET_NAMES = ('OFFER_FILTER_SIGNING_PASSWORD', 'OFFER_FILTER_KEYSTORE_B64',
                'OFFER_FILTER_KEYSTORE', 'OFFER_FILTER_KEYSTORE_PASS_SPEC',
                'OFFER_FILTER_KEY_PASS_SPEC')


def need(value, message):
    if not value:
        raise ValueError(message)


def read_json(path):
    return capsule.read_json(path)


def write_json(path, value):
    with pathlib.Path(path).open('x') as stream:
        json.dump(value, stream, sort_keys=True, indent=2)
        stream.write('\n')
        stream.flush()
        os.fsync(stream.fileno())


def timestamp():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def command(argv, cwd, env=None, log=None):
    # Never echo argv/environment or raw subprocess output into exceptions.
    # Signing helper intentionally handles its private diagnostics itself.
    if log is None:
        result = subprocess.run([str(x) for x in argv], cwd=cwd, env=env,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    else:
        with pathlib.Path(log).open('xb') as stream:
            result = subprocess.run([str(x) for x in argv], cwd=cwd, env=env,
                                    stdout=stream, stderr=subprocess.STDOUT)
    need(result.returncode == 0, f'{pathlib.Path(argv[0]).name} failed (exit {result.returncode}); stage not completed')
    return result.stdout.decode() if result.stdout is not None else ''


def clean_env(signing=False):
    env = os.environ.copy()
    if not signing:
        for name in SECRET_NAMES:
            env.pop(name, None)
    return env


def local_only():
    need(os.environ.get('GITHUB_ACTIONS') != 'true' and os.environ.get('RENDER') != 'true',
         'This runner is local-only; hosted build execution refused')


def normalized(path):
    path = pathlib.Path(path).absolute()
    need(path == path.resolve(), 'Use an absolute path without symlinks or traversal')
    return path


@contextlib.contextmanager
def locked(run):
    run = normalized(run)
    need(run.is_dir(), 'Run directory missing; use init or restore')
    fd = os.open(run / '.lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    try:
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ValueError('Another process owns this release run; no stage was changed') from None
        yield run
    finally:
        os.close(fd)


def initialize(source, run):
    source, run = normalized(source), normalized(run)
    need(not run.exists() and not run.is_relative_to(source), 'Run must be new and outside the source checkout')
    need(not command(['git', 'status', '--porcelain', '--untracked-files=all'], source).strip(),
         'Commit the complete candidate source before initializing a release')
    run.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.release-init-', dir=run.parent) as temp:
        staged = pathlib.Path(temp) / 'run'
        staged.mkdir(mode=0o700)
        identity = capsule.create_source_pack(source, staged / 'source.pack')
        write_json(staged / 'run.json', dict(schema=1, createdAt=timestamp(), **identity,
                   gitHistory='exact source commit and full tree; explicit shallow boundary',
                   signer=SIGNER, deviceInstallVerified=False))
        capsule.promote_directory(staged, run)
    return status(run)


def load_run(run):
    state = read_json(run / 'run.json')
    need(state.get('schema') == 1 and state.get('signer') == SIGNER, 'Unsupported release run')
    for field in ('sourceCommit', 'sourceTree'):
        need(re.fullmatch('[0-9a-f]{40}', state.get(field, '')), 'Invalid source identity')
    need(capsule.file_info(run / 'source.pack') == state['sourcePack'], 'Source pack changed')
    return state


def stage(run, name):
    state = load_run(run)
    manifest = capsule.verify(run / name, name)
    need(manifest['runSha256'] == capsule.file_info(run / 'run.json')['sha256'], 'Stage belongs to a different run')
    need(manifest['sourceCommit'] == state['sourceCommit'] and manifest['sourceTree'] == state['sourceTree'],
         'Stage source identity mismatch')
    index = STAGES.index(name)
    if index:
        previous = STAGES[index - 1]
        stage(run, previous)
        need(manifest['previousManifestSha256'] == capsule.file_info(run / previous / 'manifest.json')['sha256'],
             'Previous stage changed')
    return manifest


def status(run):
    state = load_run(run)
    completed = []
    gap = False
    for name in STAGES:
        if (run / name).exists():
            need(not gap, 'Release stages are out of order')
            stage(run, name)
            completed.append(name)
        else:
            gap = True
    return dict(sourceCommit=state['sourceCommit'], sourceTree=state['sourceTree'],
                completed=completed, next=STAGES[len(completed)] if len(completed) < len(STAGES) else None,
                integrity='PASS', lastLiveCheckAt=(stage(run, 'verified')['completedAt'] if 'verified' in completed else None),
                liveCheckIsCurrent=False,
                deviceInstallVerified=False)


def commit_stage(run, name, directory, **extra):
    state = load_run(run)
    metadata = dict(schema=1, stage=name, completedAt=timestamp(), sourceCommit=state['sourceCommit'],
                    sourceTree=state['sourceTree'], runSha256=capsule.file_info(run / 'run.json')['sha256'],
                    **extra)
    index = STAGES.index(name)
    if index:
        metadata['previousManifestSha256'] = capsule.file_info(run / STAGES[index - 1] / 'manifest.json')['sha256']
    capsule.seal(directory, metadata)
    need(not (run / name).exists(), 'Stage already exists; resume instead of overwriting')
    capsule.promote_directory(directory, run / name)
    stage(run, name)


@contextlib.contextmanager
def attempt(run, name):
    with tempfile.TemporaryDirectory(prefix='.' + name + '-', dir=run) as temp:
        temporary = pathlib.Path(temp)
        try:
            yield temporary
        except Exception:
            # Failed builds remain diagnosable without exporting private signer
            # files, arbitrary workspace contents or failed evidence as success.
            if name in ('prepare', 'accept', 'stage', 'verify'):
                failures = normalized(run / 'failures')
                failures.mkdir(exist_ok=True, mode=0o700)
                saved = pathlib.Path(tempfile.mkdtemp(prefix=name + '-', dir=failures))
                write_json(saved / 'failure.json', {'phase': name, 'completed': False,
                           'result': 'FAILED', 'purpose': 'LOCAL_FAILURE_DIAGNOSTICS',
                           'createdAt': timestamp(),
                           'note': 'Local diagnostics only; excluded from successful evidence capsules'})
                candidates = [(temporary / 'operation.log', pathlib.Path('operation.log'))]
                if name == 'prepare':
                    for directory in (temporary / 'pending-unsigned').glob('*-local-receipts'):
                        candidates.extend((directory / item, pathlib.Path(item)) for item in
                                          ('test.log', 'lint.log', 'test-command.json', 'lint-command.json'))
                    source = temporary / 'checkout'
                    reports = source / 'app/build/test-results/testDebugUnitTest'
                    candidates.extend((path, pathlib.Path('reports/junit') / path.name)
                                      for path in sorted(reports.glob('TEST-*.xml')))
                    candidates.append((source / 'app/build/reports/lint-results-debug.xml',
                                       pathlib.Path('reports/lint.xml')))
                for path, relative in candidates:
                    # Never follow a linked report/directory into private or
                    # unrelated files. Preserve original report bytes only.
                    if path.is_file() and path == path.resolve():
                        copy_checked(path, saved / relative)
            raise


def checkout(run, destination):
    state = load_run(run)
    capsule.restore_source_pack(run / 'source.pack', destination, state['sourceCommit'], state['sourceTree'])
    return destination


def environment(mode, source, signing=False):
    env = clean_env(signing)
    report = resolve_environment(mode=mode, env=env, repo=source)
    need(report['summary']['ok'], 'Local preflight failed: ' + '; '.join(report['summary']['errors']))
    env.update(report['overrides'])
    return env, report['summary']


def copy_checked(source, target, expected=None):
    before = capsule.file_info(source)
    if expected is not None:
        need(before == expected, 'Original evidence hash/size mismatch: ' + pathlib.Path(source).name)
    target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    with pathlib.Path(source).open('rb') as input_stream, target.open('xb') as output_stream:
        shutil.copyfileobj(input_stream, output_stream)
    need(capsule.file_info(target) == before == capsule.file_info(source), 'Evidence changed during copy')


def original_evidence(bundle, report_root, log_root, target):
    """Preserve the actual recorded bytes, not reconstructed XML or summaries."""
    evidence = read_json(bundle / 'evidence.json')
    reports = report_root / 'app/build/test-results/testDebugUnitTest'
    need({p.name for p in reports.glob('TEST-*.xml')} == set(evidence['junit']), 'Original JUnit report set differs')
    for name, info in evidence['junit'].items():
        need(pathlib.PurePosixPath(name).name == name and name.startswith('TEST-') and name.endswith('.xml'),
             'Invalid report name')
        copy_checked(reports / name, target / 'reports/junit' / name, info)
    copy_checked(report_root / 'app/build/reports/lint-results-debug.xml',
                 target / 'reports/lint.xml', evidence['lint'])
    need(len(evidence['commands']) == 2, 'Missing original command receipts')
    for label, item in zip(('test', 'lint'), evidence['commands']):
        need(item['exitCode'] == 0, 'Original release command did not pass')
        receipt = read_json(log_root / (label + '-command.json'))
        need(receipt == item, 'Original command receipt differs')
        copy_checked(log_root / (label + '.log'), target / 'logs' / (label + '.log'), item['log'])
        copy_checked(log_root / (label + '-command.json'), target / 'logs' / (label + '-command.json'))


def restore_reports(prepared, source):
    evidence = read_json(prepared / 'bundle/evidence.json')
    for name, info in evidence['junit'].items():
        copy_checked(prepared / 'reports/junit' / name,
                     source / 'app/build/test-results/testDebugUnitTest' / name, info)
    copy_checked(prepared / 'reports/lint.xml', source / 'app/build/reports/lint-results-debug.xml', evidence['lint'])


def check_prepared(run, prepared, source, anchor):
    state = load_run(run)
    bundle = prepared / 'bundle'
    need(capsule.file_info(bundle / 'prepare.json')['sha256'] == anchor, 'Preparation anchor mismatch')
    receipt = read_json(bundle / 'prepare.json')
    need(receipt['sourceCommit'] == state['sourceCommit'] and receipt['sourceTree'] == state['sourceTree'],
         'Preparation belongs to another source')
    # Use exact recorded scripts. This explicit command is distinct from passive
    # capsule restore, which never executes received code.
    command([sys.executable, source / 'tools/split_release.py', 'check-sign', bundle, anchor,
             state['sourceCommit'], state['sourceTree'], run / '.nonexistent-sign-output'], source, clean_env())
    restore_reports(prepared, source)
    output = command([sys.executable, source / 'tools/release_bridge_gate.py', 'verify',
                      '--snapshot', bundle / 'inputs.json'], source, clean_env())
    prefix = 'OFFER_FILTER_RELEASE_GATE_V1 '
    need(output.startswith(prefix) and json.loads(output[len(prefix):]) == read_json(bundle / 'gate.json'),
         'Restored original evidence does not reproduce the release gate')
    return receipt


def adopt_prepared(run, bundle, anchor, report_root, log_root):
    """Capture an existing real preparation; never create replacement evidence."""
    need(not (run / 'prepared').exists(), 'Preparation already exists')
    with attempt(run, 'adopt') as temp:
        source = checkout(run, temp / 'checkout')
        output = temp / 'output'
        output.mkdir(mode=0o700)
        receipt = read_json(bundle / 'prepare.json')
        for name, info in receipt['files'].items():
            need(name in __import__('split_release').BUNDLE_FILES, 'Unexpected preparation member')
            copy_checked(bundle / name, output / 'bundle' / name, info)
        copy_checked(bundle / 'prepare.json', output / 'bundle/prepare.json')
        original_evidence(output / 'bundle', report_root, log_root, output)
        check_prepared(run, output, source, anchor)
        commit_stage(run, 'prepared', output, preparationReceiptSha256=anchor,
                     origin='captured existing original preparation; no test execution claimed')


def prepare(run, notices):
    if (run / 'prepared').exists():
        return stage(run, 'prepared')
    state = load_run(run)
    with attempt(run, 'prepare') as temp:
        source = checkout(run, temp / 'checkout')
        env, profile = environment('build', source)
        command(['bash', source / 'tools/sign-local.sh', 'prepare-unsigned', state['sourceCommit'],
                 state['sourceTree'], temp / 'pending-unsigned', normalized(notices)], source, env,
                log=temp / 'operation.log')
        bundle = temp / 'pending-unsigned' / state['sourceCommit']
        output = temp / 'output'
        output.mkdir(mode=0o700)
        shutil.copytree(bundle, output / 'bundle')
        original_evidence(bundle, source, bundle.parent / (state['sourceCommit'] + '-local-receipts'), output)
        # Recheck original reports in an independently restored exact checkout.
        clean = checkout(run, temp / 'validation-checkout')
        anchor = capsule.file_info(bundle / 'prepare.json')['sha256']
        check_prepared(run, output, clean, anchor)
        write_json(output / 'environment.json', profile)
        commit_stage(run, 'prepared', output, preparationReceiptSha256=anchor,
                     origin='local dual-SDK tests, lint and unsigned packaging')


def signed_inputs(run, source):
    prepared = stage(run, 'prepared')
    bundle = run / 'prepared/bundle'
    state = load_run(run)
    return [bundle, prepared['preparationReceiptSha256'], state['sourceCommit'], state['sourceTree']]


def import_signed(run, directory):
    """Validate an existing signed return without touching a signing key."""
    if (run / 'signed').exists():
        saved = stage(run, 'signed')
        for name in ('OfferFilter-signed.apk', 'signed-receipt.json'):
            need(capsule.file_info(directory / name) == saved['files'][name],
                 'Different signed return already recorded')
        return saved
    with attempt(run, 'signed-import') as temp:
        source = checkout(run, temp / 'checkout')
        output = temp / 'output'
        output.mkdir(mode=0o700)
        for name in ('OfferFilter-signed.apk', 'signed-receipt.json'):
            copy_checked(directory / name, output / name)
        validate_signed(run, source, output)
        commit_stage(run, 'signed', output, origin='verified existing original-key signed return')


def validate_signed(run, source, output):
    args = signed_inputs(run, source)
    receipt = read_json(output / 'signed-receipt.json')
    preparation = read_json(args[0] / 'prepare.json')
    need(receipt['preparationReceiptSha256'] == args[1] and receipt['sourceCommit'] == args[2]
         and receipt['sourceTree'] == args[3] and receipt['signer'] == SIGNER,
         'Signed return identity differs')
    need(receipt['signed'] == capsule.file_info(output / 'OfferFilter-signed.apk'), 'Signed return bytes differ')
    need(receipt['unsigned'] == preparation['files']['OfferFilter-unsigned.apk'], 'Unsigned identity differs')
    env, _ = environment('import', source)
    # Existing verifier checks sole original certificate and all signature schemes;
    # import uses no secret and never runs the signing shell branch.
    code = ('import split_release as s; import sys; '
            'v=s.verify_signed(sys.argv[1],sys.argv[2]); '
            's.compare_payload(s.read_json(sys.argv[3])["payload"],s.apk_entries(sys.argv[1]),v)')
    command([sys.executable, '-c', code, output / 'OfferFilter-signed.apk',
             args[0] / 'tools/apksigner.jar', args[0] / 'prepare.json'], source / 'tools', env)


def sign(run):
    if (run / 'signed').exists():
        return stage(run, 'signed')
    with attempt(run, 'sign') as temp:
        source = checkout(run, temp / 'checkout')
        args = signed_inputs(run, source)
        env, _ = environment('sign', source, signing=True)
        # Dedicated 0700 temporary secret directory is outside export allowlist.
        private = temp / 'private'
        private.mkdir(mode=0o700)
        env['TMPDIR'] = str(private)
        output = temp / 'output'
        command(['bash', source / 'tools/sign-local.sh', 'finalize-original-signature', '--sign',
                 *args, output], source, env)
        validate_signed(run, source, output)
        commit_stage(run, 'signed', output, origin='local original-key signing')


def accept(run):
    if (run / 'accepted').exists():
        return stage(run, 'accepted')
    stage(run, 'signed')
    with attempt(run, 'accept') as temp:
        source = checkout(run, temp / 'checkout')
        args = signed_inputs(run, source)
        check_prepared(run, run / 'prepared', source, args[1])
        env, profile = environment('accept', source)
        command(['bash', source / 'tools/sign-local.sh', 'finalize-original-signature', '--accept-signed',
                 *args, run / 'signed/OfferFilter-signed.apk', run / 'signed/signed-receipt.json'], source, env,
                log=temp / 'operation.log')
        output = temp / 'output'
        shutil.copytree(source / 'dist', output)
        write_json(output / 'environment.json', profile)
        commit_stage(run, 'accepted', output, origin='unchanged offline canonical acceptance')


def stage_feed(run):
    if (run / 'staged').exists():
        return stage(run, 'staged')
    accepted = stage(run, 'accepted')
    with attempt(run, 'stage') as temp:
        source = checkout(run, temp / 'checkout')
        version = read_json(run / 'prepared/bundle/prepare.json')['package']['versionName']
        name = 'OfferFilter-' + version + '.apk'
        copy_checked(run / 'accepted' / name, source / 'dist' / name, accepted['files'][name])
        env, _ = environment('verify', source)
        command([sys.executable, source / 'tools/publish-repo-feed.py'], source, env, log=temp / 'operation.log')
        output = temp / 'output'
        output.mkdir(mode=0o700)
        for name in ('latest.json', 'OfferFilter.apk'):
            copy_checked(source / 'release' / name, output / 'release' / name)
        feed = read_json(output / 'release/latest.json')
        need(feed['sourceCommit'] == load_run(run)['sourceCommit'], 'Publisher source differs')
        need(feed['sha256'] == capsule.file_info(output / 'release/OfferFilter.apk')['sha256'], 'Publisher bytes differ')
        commit_stage(run, 'staged', output, origin='existing publisher; repository feed staged only',
                     published=False, mirrorRequired=True)


def verify_live(run):
    stage(run, 'staged')
    # Always perform a fresh live read. A saved success never means still live.
    with attempt(run, 'verify') as temp:
        source = checkout(run, temp / 'checkout')
        env, _ = environment('verify', source)
        command([sys.executable, source / 'tools/verify_channel.py'], source, env, log=temp / 'operation.log')
        actual = read_json(source / '.channel-check/transport-proof.json')
        expected = read_json(run / 'staged/release/latest.json')
        for field in ('sourceCommit', 'versionCode', 'versionName', 'sha256'):
            need(actual[field] == expected[field], 'Live channel is not this staged release: ' + field)
        if (run / 'verified').exists():
            stage(run, 'verified')
            return dict(freshLiveCheck='PASS', **actual)
        output = temp / 'output'
        output.mkdir(mode=0o700)
        copy_checked(source / '.channel-check/transport-proof.json', output / 'transport-proof.json')
        commit_stage(run, 'verified', output, origin='fresh production UpdateTransport probe',
                     published=True, deviceInstallVerified=False)
        return dict(freshLiveCheck='PASS', **actual)


def install_feed(run, destination):
    """Local atomic handoff: no partial pair, commit, push or remote build."""
    stage(run, 'staged')
    destination = normalized(destination)
    need(not destination.exists(), 'Feed handoff destination must be new')
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.feed-', dir=destination.parent) as temp:
        output = pathlib.Path(temp) / 'feed'
        shutil.copytree(run / 'staged/release', output)
        capsule.promote_directory(output, destination)
    return dict(feedDirectory=str(destination), sourceCommit=load_run(run)['sourceCommit'],
                published=False, next='Commit the complete source/feed pair without force, mirror finished bytes, then verify-live')


def checkpoint(run, archive_dir=None):
    """Automatic recovery export; configure an existing durable mounted store.

    Default sibling archives survive deletion of a run, but are NOT a promise
    that an ephemeral worker itself is durable. No upload/service is implicit.
    """
    state = status(run)
    config = run / 'archive.json'
    if archive_dir is None:
        archive_dir = read_json(config)['directory'] if config.exists() else run.parent / 'release-archives'
    archive_dir = normalized(archive_dir)
    need(not archive_dir.is_relative_to(run), 'Recovery archive must be outside the release run')
    # Retain the requested target even when the first archive attempt fails.
    # Otherwise an ordinary retry could silently fall back to transient storage.
    if not config.exists() or read_json(config)['directory'] != str(archive_dir):
        with tempfile.TemporaryDirectory(prefix='.archive-config-', dir=run) as temp:
            new = pathlib.Path(temp) / 'archive.json'
            write_json(new, {'directory': str(archive_dir)})
            os.replace(new, config)
    archive_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    last = state['completed'][-1] if state['completed'] else 'source'
    anchor = capsule.file_info(run / last / 'manifest.json' if last != 'source' else run / 'run.json')['sha256']
    destination = archive_dir / (state['sourceCommit'][:12] + '-' + last + '-' + anchor[:16])
    with tempfile.TemporaryDirectory(prefix='.checkpoint-', dir=archive_dir) as temp:
        output = pathlib.Path(temp) / 'checkpoint'
        output.mkdir(mode=0o700)
        receipt = capsule.create_capsule(run, output / 'recovery.zip')
        write_json(output / 'checksum.json', receipt)
        if destination.exists():
            need(read_json(destination / 'checksum.json') == receipt and
                 capsule.file_info(destination / 'recovery.zip') == {k: receipt[k] for k in ('size', 'sha256')},
                 'Existing recovery checkpoint changed; choose a new archive directory')
        else:
            capsule.promote_directory(output, destination)
    return dict(capsule=str(destination / 'recovery.zip'), sha256=receipt['sha256'],
                storage='Configured filesystem archive; keep this directory on durable storage')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='mode', required=True)
    p = sub.add_parser('doctor')
    p.add_argument('--phase', choices=('build', 'sign', 'import', 'accept', 'verify'), default='build')
    p.add_argument('--source', type=pathlib.Path, default=ROOT)
    p = sub.add_parser('init')
    p.add_argument('--source', type=pathlib.Path, default=ROOT)
    p.add_argument('--run', type=pathlib.Path, required=True)
    p.add_argument('--archive-dir', type=pathlib.Path)
    p = sub.add_parser('restore')
    p.add_argument('--capsule', type=pathlib.Path, required=True)
    p.add_argument('--sha256', required=True)
    p.add_argument('--run', type=pathlib.Path, required=True)
    for name in ('status', 'run', 'adopt-prepared', 'import-signed', 'export', 'feed', 'publish', 'verify-live'):
        p = sub.add_parser(name)
        p.add_argument('--run', type=pathlib.Path, required=True)
        if name in ('run', 'adopt-prepared', 'import-signed'):
            p.add_argument('--archive-dir', type=pathlib.Path)
        if name == 'run':
            p.add_argument('--until', choices=STAGES, default='accepted')
            p.add_argument('--notices', type=pathlib.Path)
        elif name == 'adopt-prepared':
            for flag in ('bundle', 'report-root', 'logs'):
                p.add_argument('--' + flag, type=pathlib.Path, required=True)
            p.add_argument('--anchor', required=True)
        elif name == 'import-signed':
            p.add_argument('--directory', type=pathlib.Path, required=True)
        elif name in ('export', 'feed'):
            p.add_argument('--output', type=pathlib.Path, required=True)
        elif name == 'publish':
            p.add_argument('--checkout', type=pathlib.Path, required=True)
            p.add_argument('--expected-main', required=True)
            p.add_argument('--remote', default='origin')
            p.add_argument('--branch', default='main')
            p.add_argument('--push', action='store_true', help='Publish the pair with a normal non-force Git push')
    args = parser.parse_args()
    local_only()
    if args.mode == 'doctor':
        summary = resolve_environment(mode=args.phase, repo=args.source)['summary']
        print(json.dumps(summary, indent=2, sort_keys=True))
        return 0 if summary['ok'] else 2
    if args.mode == 'init':
        result = initialize(args.source, args.run)
        result['recovery'] = checkpoint(args.run, args.archive_dir)
    elif args.mode == 'restore':
        capsule.restore_capsule(args.capsule, args.sha256, args.run)
        result = status(args.run)
    else:
        with locked(args.run) as run:
            fresh_check = None
            recovery = None
            if args.mode in ('run', 'adopt-prepared', 'import-signed'):
                recovery = checkpoint(run, args.archive_dir)
            if args.mode == 'adopt-prepared':
                adopt_prepared(run, normalized(args.bundle), args.anchor, normalized(args.report_root), normalized(args.logs))
            elif args.mode == 'import-signed':
                import_signed(run, normalized(args.directory))
            elif args.mode == 'run':
                actions = (lambda: prepare(run, args.notices), lambda: sign(run), lambda: accept(run),
                           lambda: stage_feed(run), lambda: verify_live(run))
                for index, action in enumerate(actions):
                    if index <= STAGES.index(args.until):
                        if index == 0 and not (run / 'prepared').exists():
                            need(args.notices is not None, 'First preparation requires --notices with existing SDK provenance')
                        value = action()
                        if index == 4:
                            fresh_check = value
                        recovery = checkpoint(run, args.archive_dir)
            elif args.mode == 'verify-live':
                fresh_check = verify_live(run)
                recovery = checkpoint(run)
            if args.mode == 'export':
                status(run)
                result = capsule.create_capsule(run, args.output)
            elif args.mode == 'feed':
                result = install_feed(run, args.output)
            elif args.mode == 'publish':
                stage(run, 'staged')
                result = publish_feed(run / 'staged', normalized(args.checkout), args.expected_main,
                                      args.remote, args.branch, args.push)
            else:
                if args.mode in ('adopt-prepared', 'import-signed'):
                    recovery = checkpoint(run, args.archive_dir)
                result = status(run)
                if recovery:
                    result['recovery'] = recovery
                if fresh_check:
                    result['freshLiveCheck'] = fresh_check
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (ValueError, OSError, KeyError, TypeError) as error:
        print('RELEASE_REFUSED: ' + str(error) + '; inspect RUN/failures for available local non-signing diagnostics', file=sys.stderr)
        sys.exit(1)
