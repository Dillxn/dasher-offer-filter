"""Orchestration/fault-injection tests, NOT Android release evidence."""
import json
import hashlib
import sys
import contextlib
import io
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import release as r


class RunnerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name).resolve()
        self.source = self.base / 'source'
        self.source.mkdir()
        self.git('init', '-q')
        self.git('config', 'user.name', 'Fixture')
        self.git('config', 'user.email', 'fixture@example.invalid')
        (self.source / 'README').write_text('Synthetic runner fixture, not application evidence.\n')
        self.git('add', 'README')
        self.git('commit', '-qm', 'Fixture')
        self.run = self.base / 'run'
        r.initialize(self.source, self.run)

    def git(self, *args):
        return subprocess.run(['git', *args], cwd=self.source, check=True, capture_output=True).stdout

    def seal(self, name, files=None, **extra):
        with r.attempt(self.run, 'fixture') as temp:
            output = temp / 'output'
            output.mkdir()
            for path, value in (files or {'fixture.txt': 'synthetic'}).items():
                target = output / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(value)
            r.commit_stage(self.run, name, output, **extra)

    def prepared(self):
        self.seal('prepared', {'bundle/prepare.json': '{}'}, preparationReceiptSha256='0' * 64)

    def signed(self):
        self.prepared()
        self.seal('signed')

    def test_dirty_source_cannot_initialize(self):
        (self.source / 'README').write_text('uncommitted')
        with self.assertRaisesRegex(ValueError, 'Commit the complete'):
            r.initialize(self.source, self.base / 'other-run')

    def test_source_mutation_refuses_status(self):
        with (self.run / 'source.pack').open('ab') as stream:
            stream.write(b'changed')
        with self.assertRaisesRegex(ValueError, 'Source pack changed'):
            r.status(self.run)

    def test_stage_mutation_cannot_resume(self):
        self.prepared()
        (self.run / 'prepared/bundle/prepare.json').write_text('{"changed":true}')
        with self.assertRaises(ValueError):
            r.prepare(self.run, None)

    def test_stage_cannot_be_transplanted_between_runs(self):
        self.prepared()
        other = self.base / 'other'
        r.initialize(self.source, other)
        import shutil
        shutil.copytree(self.run / 'prepared', other / 'prepared')
        with self.assertRaisesRegex(ValueError, 'different run'):
            r.status(other)

    def test_failure_after_legacy_accept_writes_apk_is_retryable(self):
        self.signed()
        calls = []
        def legacy(argv, cwd, env=None, log=None):
            # Simulate the real legacy hazard: APK exists, receipt write fails.
            dist = cwd / 'dist'
            self.assertFalse(dist.exists())
            dist.mkdir()
            (dist / 'OfferFilter-fixture.apk').write_text('synthetic apk')
            calls.append(cwd)
            if len(calls) == 1:
                raise ValueError('injected interrupted acceptance')
            (dist / 'acceptance.json').write_text('{}')
        with patch.object(r, 'environment', return_value=({}, {'ok': True})), \
             patch.object(r, 'check_prepared'), patch.object(r, 'command', side_effect=legacy):
            with self.assertRaisesRegex(ValueError, 'injected interrupted'):
                r.accept(self.run)
            self.assertFalse((self.run / 'accepted').exists())
            self.assertFalse(calls[0].exists())
            r.accept(self.run)
            self.assertNotEqual(calls[0], calls[1])
            r.accept(self.run)
            self.assertEqual(len(calls), 2)
        self.assertEqual(r.status(self.run)['completed'], ['prepared', 'signed', 'accepted'])

    def test_failure_after_sign_output_preserves_prepared_and_cleans_private(self):
        self.prepared()
        anchor = r.capsule.file_info(self.run / 'prepared/manifest.json')
        calls = []
        def signer(argv, cwd, env=None, log=None):
            output = Path(argv[-1]); output.mkdir()
            (output / 'OfferFilter-signed.apk').write_text('synthetic')
            private = Path(env['TMPDIR'])
            (private / 'secret').write_text('do not export')
            calls.append(private)
            if len(calls) == 1:
                raise ValueError('injected signing failure')
        with patch.object(r, 'environment', side_effect=lambda *a, **kw: ({}, {'ok': True})), \
             patch.object(r, 'validate_signed'), patch.object(r, 'command', side_effect=signer):
            with self.assertRaisesRegex(ValueError, 'injected signing'):
                r.sign(self.run)
            self.assertFalse((self.run / 'signed').exists())
            self.assertFalse(calls[0].exists())
            r.sign(self.run)
            r.sign(self.run)
        self.assertEqual(len(calls), 2)
        self.assertFalse(calls[1].exists())
        self.assertEqual(anchor, r.capsule.file_info(self.run / 'prepared/manifest.json'))
        self.assertFalse((self.run / 'failures').exists())

    def test_failed_preparation_preserves_original_xml_without_private_files(self):
        junit = b'<testsuite name="OriginalFailure" tests="1" failures="1"><testcase name="case"><failure message="original failure">original detail</failure></testcase></testsuite>\n'
        lint = b'<issues format="6"><issue id="OriginalLint" severity="Error" message="original lint"/></issues>\n'
        checkouts = []

        def failed_prepare(argv, cwd, env=None, log=None):
            checkouts.append(cwd)
            files = {
                'app/build/test-results/testDebugUnitTest/TEST-OriginalFailure.xml': junit,
                'app/build/reports/lint-results-debug.xml': lint,
                'app/build/test-results/testDebugUnitTest/notes.txt': b'unrelated result file',
                'app/build/test-results/otherTask/TEST-Other.xml': b'unrelated task',
                'private/keystore': b'private signing material',
            }
            for relative, content in files.items():
                target = cwd / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(content)
            # Even an allowlisted name must not traverse into a secret.
            (cwd / 'app/build/test-results/testDebugUnitTest/TEST-Linked.xml').symlink_to(cwd / 'private/keystore')
            Path(log).write_bytes(b'original failing command output\n')
            raise ValueError('original preparation failed')

        with patch.object(r, 'environment', return_value=({}, {'ok': True})), \
             patch.object(r, 'command', side_effect=failed_prepare):
            with self.assertRaisesRegex(ValueError, 'original preparation failed'):
                r.prepare(self.run, self.base)
        self.assertFalse(checkouts[0].exists())
        self.assertFalse((self.run / 'prepared').exists())
        saved, = (self.run / 'failures').iterdir()
        self.assertEqual((saved / 'reports/junit/TEST-OriginalFailure.xml').read_bytes(), junit)
        self.assertEqual((saved / 'reports/lint.xml').read_bytes(), lint)
        self.assertEqual({str(path.relative_to(saved)) for path in saved.rglob('*') if path.is_file()},
                         {'failure.json', 'operation.log', 'reports/junit/TEST-OriginalFailure.xml',
                          'reports/lint.xml'})
        marker = r.read_json(saved / 'failure.json')
        self.assertEqual(marker['result'], 'FAILED')
        self.assertEqual(marker['purpose'], 'LOCAL_FAILURE_DIAGNOSTICS')
        self.assertFalse(marker['completed'])
        import zipfile
        archive = self.base / 'diagnostic-exclusion.zip'
        r.capsule.create_capsule(self.run, archive)
        with zipfile.ZipFile(archive) as capsule:
            self.assertFalse(any(name.startswith('failures/') for name in capsule.namelist()))
            self.assertNotIn(junit, [capsule.read(name) for name in capsule.namelist()])

    def test_repeated_failed_preparations_keep_each_original_report(self):
        previous = self.run / 'failures/previous'
        previous.mkdir(parents=True)
        (previous / 'preserved.xml').write_bytes(b'previous evidence')
        for content in (b'<testsuite name="first"/>', b'<testsuite name="second"/>'):
            with self.assertRaisesRegex(ValueError, 'failed again'):
                with r.attempt(self.run, 'prepare') as temp:
                    report = temp / 'checkout/app/build/test-results/testDebugUnitTest/TEST-Failed.xml'
                    report.parent.mkdir(parents=True)
                    report.write_bytes(content)
                    raise ValueError('failed again')
            self.assertFalse(temp.exists())
        self.assertEqual((previous / 'preserved.xml').read_bytes(), b'previous evidence')
        saved = sorted((self.run / 'failures').glob('prepare-*'))
        self.assertEqual(len(saved), 2)
        self.assertEqual({(path / 'reports/junit/TEST-Failed.xml').read_bytes() for path in saved},
                         {b'<testsuite name="first"/>', b'<testsuite name="second"/>'})

    def test_original_report_change_rejected_before_accept(self):
        report = self.base / 'report.xml'; report.write_text('original XML')
        info = r.capsule.file_info(report)
        report.write_text('reconstructed summary')
        with self.assertRaisesRegex(ValueError, 'Original evidence hash'):
            r.copy_checked(report, self.base / 'copy.xml', info)
        self.assertFalse((self.base / 'copy.xml').exists())

    def test_run_lock_prevents_two_signers(self):
        with r.locked(self.run):
            with self.assertRaisesRegex(ValueError, 'Another process'):
                with r.locked(self.run):
                    self.fail('double ownership')

    def test_checkpoint_survives_run_deletion_and_resumes_without_source(self):
        self.prepared()
        saved = r.checkpoint(self.run, self.base / 'archives')
        self.assertEqual(saved, r.checkpoint(self.run))
        import shutil
        shutil.rmtree(self.run); shutil.rmtree(self.source)
        recovered = self.base / 'new-machine-run'
        r.capsule.restore_capsule(Path(saved['capsule']), saved['sha256'], recovered)
        self.assertEqual(r.status(recovered)['completed'], ['prepared'])
        self.assertFalse((recovered / 'archive.json').exists())
        restored_source = r.checkout(recovered, self.base / 'restored-source')
        self.assertEqual((restored_source / 'README').read_text(), 'Synthetic runner fixture, not application evidence.\n')

    def test_archive_error_does_not_invalidate_successful_phase(self):
        self.prepared()
        archive = self.base / 'configured-store'
        with patch.object(r.capsule, 'create_capsule', side_effect=OSError('archive unavailable')):
            with self.assertRaises(OSError):
                r.checkpoint(self.run, archive)
        self.assertEqual(r.status(self.run)['completed'], ['prepared'])
        with patch.object(r, 'command', side_effect=AssertionError('completed preparation reran')):
            r.prepare(self.run, None)
            saved = Path(r.checkpoint(self.run)['capsule'])
            self.assertTrue(saved.exists())
            self.assertTrue(saved.is_relative_to(archive))

    def test_checkpoint_tamper_refused_without_overwrite(self):
        saved = r.checkpoint(self.run)
        Path(saved['capsule']).write_bytes(b'damaged')
        with self.assertRaisesRegex(ValueError, 'checkpoint changed'):
            r.checkpoint(self.run)
        self.assertEqual(Path(saved['capsule']).read_bytes(), b'damaged')

    def test_verified_status_never_means_channel_is_current(self):
        self.signed(); self.seal('accepted'); self.seal('staged'); self.seal('verified')
        value = r.status(self.run)
        self.assertIsNotNone(value['lastLiveCheckAt'])
        self.assertFalse(value['liveCheckIsCurrent'])

    def test_verified_phase_always_probes_live_again(self):
        self.signed(); self.seal('accepted')
        expected = dict(sourceCommit=r.load_run(self.run)['sourceCommit'], versionCode=100,
                        versionName='fixture', sha256='a' * 64, size=42,
                        packageName='com.local.dasherfilter')
        self.seal('staged', {'release/latest.json': json.dumps(expected)})
        probes = []
        def verify(argv, cwd, env=None, log=None):
            actual = dict(expected, result='PASS', bytes=42, signerSha256=r.SIGNER,
                          transport='production UpdateTransport.java')
            probes.append(cwd)
            if len(probes) == 2:
                actual['sha256'] = 'b' * 64
            target = cwd / '.channel-check/transport-proof.json'
            target.parent.mkdir()
            (target.parent / 'latest.json').write_text(json.dumps(expected))
            target.write_text(json.dumps(actual))
        with patch.object(r, 'environment', return_value=({}, {'ok': True})), \
             patch.object(r, 'command', side_effect=verify):
            output = io.StringIO()
            with patch('sys.argv', ['release.py', 'verify-live', '--run', str(self.run)]), \
                 contextlib.redirect_stdout(output):
                self.assertEqual(r.main(), 0)
            result = json.loads(output.getvalue())
            self.assertEqual(result['freshLiveCheck']['freshLiveCheck'], 'PASS')
            recovery = Path(result['recovery']['capsule'])
            self.assertTrue(recovery.exists())
            self.assertIn('verified', r.read_json(recovery.parent / 'checksum.json')['stages'])
            with self.assertRaisesRegex(ValueError, 'Live channel is not'):
                r.verify_live(self.run)
        self.assertEqual(len(probes), 2)

    def staged_candidate(self):
        old = b'prior synthetic APK'
        previous = dict(sourceCommit='a' * 40, versionCode=90, versionName='0.5.10',
                        packageName='com.local.dasherfilter', size=len(old),
                        sha256=hashlib.sha256(old).hexdigest())
        (self.source / 'release').mkdir()
        (self.source / 'release/latest.json').write_text(json.dumps(previous))
        (self.source / 'release/OfferFilter.apk').write_bytes(old)
        self.git('add', 'release')
        self.git('commit', '-qm', 'Prior published synthetic release fixture')
        self.run = self.base / 'candidate-run'
        r.initialize(self.source, self.run)
        self.signed(); self.seal('accepted')
        expected = dict(previous, sourceCommit=r.load_run(self.run)['sourceCommit'],
                        versionCode=91, versionName='0.5.11', size=123, sha256='b' * 64)
        self.seal('staged', {'release/latest.json': json.dumps(expected)})
        return old, expected

    def test_readiness_retry_retains_every_failed_download_and_does_not_resign(self):
        old, expected = self.staged_candidate()
        before = {name: r.capsule.file_info(self.run / name / 'manifest.json')
                  for name in ('prepared', 'signed', 'accepted', 'staged')}
        calls = []
        def canonical(argv, cwd, env=None, log=None, timeout=None):
            self.assertEqual(Path(argv[1]).name, 'verify_channel.py')
            self.assertGreater(timeout, 0)
            calls.append(cwd)
            output = cwd / '.channel-check'
            output.mkdir()
            (output / 'latest.json').write_text(json.dumps(expected))
            if len(calls) <= 2:
                (output / 'OfferFilter.apk').write_bytes(old)
                Path(log).write_text('original failure ' + str(len(calls)) +
                    '\nValueError: Downloaded APK does not match feed\n')
                raise r.CommandFailed('canonical failure')
            (output / 'transport-proof.json').write_text(json.dumps(dict(expected,
                result='PASS', transport='production UpdateTransport.java', bytes=123,
                signerSha256=r.SIGNER)))
        now = [0]
        def sleep(seconds):
            now[0] += seconds
        with patch.object(r, 'environment', return_value=({}, {'ok': True})), \
             patch.object(r, 'command', side_effect=canonical):
            result = r.wait_for_live(lambda deadline: r.verify_live(self.run, deadline=deadline, clock=lambda: now[0]),
                wait_seconds=60, interval_seconds=15, clock=lambda: now[0], sleep=sleep,
                progress=lambda message: None)
        self.assertEqual(result['readinessAttempts'], 3)
        self.assertEqual(result['freshLiveCheck'], 'PASS')
        self.assertEqual(len(set(calls)), 3)
        self.assertTrue(all(not path.exists() for path in calls))
        failures = list((self.run / 'failures').glob('verify-*'))
        self.assertEqual(len(failures), 2)
        self.assertEqual({(path / 'operation.log').read_text().splitlines()[0] for path in failures},
                         {'original failure 1', 'original failure 2'})
        for path in failures:
            self.assertEqual((path / 'channel-check/OfferFilter.apk').read_bytes(), old)
            self.assertEqual(r.read_json(path / 'channel-check/latest.json'), expected)
            self.assertEqual(r.read_json(path / 'readiness.json')['category'], 'KNOWN_PREVIOUS_APK')
            self.assertFalse(r.read_json(path / 'failure.json')['completed'])
        self.assertEqual(before, {name: r.capsule.file_info(self.run / name / 'manifest.json')
                                 for name in before})
        self.assertEqual(r.status(self.run)['completed'][-1], 'verified')

    def test_success_receipt_with_changed_signer_or_size_cannot_complete(self):
        _, expected = self.staged_candidate()
        for changed in ({'signerSha256': '0' * 64}, {'bytes': 1}):
            def canonical(argv, cwd, env=None, log=None):
                output = cwd / '.channel-check'
                output.mkdir()
                (output / 'latest.json').write_text(json.dumps(expected))
                proof = dict(expected, result='PASS', transport='production UpdateTransport.java',
                             bytes=123, signerSha256=r.SIGNER)
                proof.update(changed)
                (output / 'transport-proof.json').write_text(json.dumps(proof))
            with self.subTest(changed=changed), \
                 patch.object(r, 'environment', return_value=({}, {'ok': True})), \
                 patch.object(r, 'command', side_effect=canonical):
                with self.assertRaisesRegex(ValueError, 'expected bytes and original signer'):
                    r.verify_live(self.run)
                self.assertFalse((self.run / 'verified').exists())

    def test_readiness_deadline_includes_preflight_and_cannot_seal_expired_probe(self):
        _, expected = self.staged_candidate()
        now = [0]
        def preflight(*args):
            now[0] = 20
            return {}, {'ok': True}
        def canonical(argv, cwd, env=None, log=None, timeout=None):
            self.assertEqual(timeout, 40)  # The local preflight already used 20.
            output = cwd / '.channel-check'
            output.mkdir()
            (output / 'latest.json').write_text(json.dumps(expected))
            (output / 'transport-proof.json').write_text(json.dumps(dict(expected,
                result='PASS', transport='production UpdateTransport.java', bytes=123,
                signerSha256=r.SIGNER)))
            now[0] = 60
        with patch.object(r, 'environment', side_effect=preflight), \
             patch.object(r, 'command', side_effect=canonical):
            with self.assertRaises(r.ReadinessTimeout):
                r.verify_live(self.run, deadline=60, clock=lambda: now[0])
        self.assertFalse((self.run / 'verified').exists())
        saved, = (self.run / 'failures').glob('verify-*')
        self.assertTrue((saved / 'channel-check/transport-proof.json').exists())
        self.assertFalse(r.read_json(saved / 'failure.json')['completed'])

    def test_probe_deadline_kills_its_java_equivalent_child_and_retains_log(self):
        import time
        pid_file = self.base / 'child.pid'
        script = ("import subprocess,sys,time; "
                  "child=subprocess.Popen([sys.executable,'-c','import time; time.sleep(30)']); "
                  "open('child.pid','w').write(str(child.pid)); "
                  "print('original probe output',flush=True); time.sleep(30)")
        log = self.base / 'timeout.log'
        start = time.monotonic()
        with self.assertRaisesRegex(r.CommandFailed, 'timed out'):
            r.command([sys.executable, '-c', script], self.base, log=log, timeout=0.3)
        self.assertLess(time.monotonic() - start, 3)
        self.assertIn('original probe output', log.read_text())
        self.assertIn('readiness deadline', log.read_text())
        pid = int(pid_file.read_text())
        # A killed descendant may await init's reap, but must no longer run.
        status = Path('/proc') / str(pid) / 'status'
        if status.exists():
            self.assertIn('Z (zombie)', status.read_text())

    def test_signing_secrets_removed_from_non_signing_environment(self):
        with patch.dict(os.environ, {name: 'private sentinel' for name in r.SECRET_NAMES}):
            self.assertTrue(all(name not in r.clean_env() for name in r.SECRET_NAMES))
            self.assertTrue(all(name in r.clean_env(signing=True) for name in r.SECRET_NAMES))

    def test_hosted_build_is_refused(self):
        for name in ('GITHUB_ACTIONS', 'RENDER'):
            with self.subTest(name=name), patch.dict(os.environ, {name: 'true'}):
                with self.assertRaisesRegex(ValueError, 'local-only'):
                    r.local_only()


if __name__ == '__main__':
    unittest.main()
