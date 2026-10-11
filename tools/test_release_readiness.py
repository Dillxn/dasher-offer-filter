"""Synthetic local readiness tests. No Android, HTTP, signer or deploy claims."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

import release_readiness as readiness


class Clock:
    def __init__(self):
        self.now = 0
        self.sleeps = []

    def read(self):
        return self.now

    def sleep(self, seconds):
        self.sleeps.append(seconds)
        self.now += seconds


class ReadinessTests(unittest.TestCase):
    def setUp(self):
        self.clock = Clock()
        self.messages = []

    def wait(self, verify, **options):
        return readiness.wait_for_live(verify, clock=self.clock.read,
            sleep=self.clock.sleep, progress=self.messages.append, **options)

    def test_known_previous_apk_then_canonical_pass(self):
        calls = []
        def verify(timeout):
            calls.append(timeout)
            self.clock.now += 2
            if len(calls) == 1:
                raise readiness.PropagationPending('known previous APK')
            return dict(freshLiveCheck='PASS', sourceCommit='a' * 40)
        result = self.wait(verify, wait_seconds=60, interval_seconds=15)
        self.assertEqual(result['freshLiveCheck'], 'PASS')
        self.assertEqual(result['readinessAttempts'], 2)
        self.assertEqual(result['readinessElapsedSeconds'], 19)
        self.assertEqual(calls, [60, 60])
        self.assertEqual(self.clock.sleeps, [15])
        self.assertIn('Waiting for the staged APK', self.messages[0])

    def test_wrong_candidate_is_terminal(self):
        calls = []
        def verify(timeout):
            calls.append(timeout)
            raise ValueError('Live channel is not this staged release')
        with self.assertRaisesRegex(ValueError, 'not this staged release'):
            self.wait(verify)
        self.assertEqual(len(calls), 1)
        self.assertEqual(self.clock.sleeps, [])
        self.assertEqual(self.messages, [])

    def test_never_ready_has_finite_budget_and_no_pass(self):
        calls = []
        def verify(timeout):
            calls.append(timeout)
            raise readiness.PropagationPending('known previous APK')
        with self.assertRaises(readiness.ReadinessTimeout):
            self.wait(verify, wait_seconds=31, interval_seconds=15)
        self.assertEqual(calls, [31, 31, 31])
        self.assertEqual(self.clock.sleeps, [15, 15, 1])
        self.assertEqual(self.clock.now, 31)

    def test_probe_deadline_failure_is_terminal(self):
        def verify(deadline):
            self.clock.now = deadline
            raise readiness.ReadinessTimeout('probe exhausted its budget')
        with self.assertRaisesRegex(readiness.ReadinessTimeout, 'probe exhausted'):
            self.wait(verify, wait_seconds=15)
        self.assertEqual(self.clock.sleeps, [])

    def test_explicit_single_attempt_does_not_wait(self):
        def verify(timeout):
            self.assertIsNone(timeout)
            raise readiness.PropagationPending('known previous APK')
        with self.assertRaises(readiness.PropagationPending):
            self.wait(verify, wait_seconds=0)
        self.assertEqual(self.clock.sleeps, [])

    def test_invalid_or_unbounded_arguments_refused_before_probe(self):
        def verify(timeout):
            self.fail('invalid configuration reached probe')
        for value in (-1, 601, float('inf'), float('nan'), True):
            with self.subTest(wait=value), self.assertRaises(ValueError):
                self.wait(verify, wait_seconds=value)
        for value in (0, 61, float('inf'), float('nan'), True):
            with self.subTest(interval=value), self.assertRaises(ValueError):
                self.wait(verify, interval_seconds=value)


class ClassificationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.source = Path(self.temp.name)
        (self.source / '.channel-check').mkdir()
        (self.source / 'release').mkdir()
        self.old = b'known prior synthetic APK'
        self.previous = dict(packageName='com.local.dasherfilter', versionCode=90,
            versionName='0.5.10', sourceCommit='a' * 40, size=len(self.old),
            sha256=hashlib.sha256(self.old).hexdigest(), encoding='raw')
        self.expected = dict(self.previous, versionCode=91, versionName='0.5.11',
            sourceCommit='b' * 40, size=123, sha256='c' * 64)
        self.write('release/latest.json', self.previous)
        self.write('.channel-check/latest.json', self.expected)
        (self.source / 'release/OfferFilter.apk').write_bytes(self.old)
        (self.source / '.channel-check/OfferFilter.apk').write_bytes(self.old)
        self.log = self.source / 'operation.log'
        self.log.write_text('Original traceback\nValueError: Downloaded APK does not match feed\n')

    def write(self, name, value):
        (self.source / name).write_text(json.dumps(value))

    def classify(self):
        return readiness.previous_apk_diagnostic(self.source, self.expected, self.log)

    def test_only_exact_known_previous_bytes_with_expected_feed_are_retryable(self):
        result = self.classify()
        self.assertEqual(result['category'], 'KNOWN_PREVIOUS_APK')
        self.assertEqual(result['sha256'], self.previous['sha256'])
        self.assertEqual(result['result'], 'FAILED')

    def test_unknown_corrupt_bytes_are_terminal(self):
        (self.source / '.channel-check/OfferFilter.apk').write_bytes(b'corrupt')
        self.assertIsNone(self.classify())

    def test_different_live_candidate_is_terminal_for_every_identity_field(self):
        for field in ('sourceCommit', 'versionCode', 'versionName', 'sha256',
                      'size', 'packageName', 'encoding'):
            with self.subTest(field=field):
                wrong = dict(self.expected)
                wrong[field] = self.previous[field] if self.previous[field] != wrong[field] else 'wrong'
                self.write('.channel-check/latest.json', wrong)
                self.assertIsNone(self.classify())

    def test_invalid_prior_evidence_and_unrelated_failures_are_terminal(self):
        for tail in ('ValueError: Signing identity changed',
                     'ValueError: APK package/embedded version disagrees with feed',
                     'java process failed', ''):
            with self.subTest(tail=tail):
                self.log.write_text(tail + '\n')
                self.assertIsNone(self.classify())
        self.log.write_text('ValueError: Downloaded APK does not match feed\n')
        (self.source / 'release/OfferFilter.apk').write_bytes(b'changed prior source bytes')
        self.assertIsNone(self.classify())

    def test_missing_or_malformed_probe_files_are_terminal(self):
        (self.source / '.channel-check/latest.json').write_text('{bad json')
        self.assertIsNone(self.classify())
        (self.source / '.channel-check/latest.json').unlink()
        self.assertIsNone(self.classify())

    def test_same_or_newer_repository_version_is_not_previous(self):
        for version in (91, 92):
            self.write('release/latest.json', dict(self.previous, versionCode=version))
            self.assertIsNone(self.classify())


if __name__ == '__main__':
    unittest.main()
