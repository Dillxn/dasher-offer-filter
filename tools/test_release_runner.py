"""Orchestration/fault-injection tests, NOT Android release evidence."""
import json
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
                        versionName='fixture', sha256='a' * 64)
        self.seal('staged', {'release/latest.json': json.dumps(expected)})
        probes = []
        def verify(argv, cwd, env=None, log=None):
            actual = expected.copy()
            probes.append(cwd)
            if len(probes) == 2:
                actual['sha256'] = 'b' * 64
            target = cwd / '.channel-check/transport-proof.json'
            target.parent.mkdir()
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
