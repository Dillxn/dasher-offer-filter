import pathlib
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import release_bridge_gate as gate


class ReleaseBridgeGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temp.name)
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        (self.root / "app/src").mkdir(parents=True)
        (self.root / "app/src/Source.java").write_text("class Source {}")
        test_source = self.root / "app/src/test/java/com/local/dasherfilter"
        test_source.mkdir(parents=True)
        for name in gate.ADAPTERS:
            (test_source / (name + ".java")).write_text("@Config(sdk={26,35}) class " + name + " {}")
        subprocess.run(["git", "add", "."], cwd=self.root, check=True)
        self.frozen = gate.snapshot(self.root)
        self.junit = self.root / "app/build/test-results/testDebugUnitTest/TEST-example.xml"
        self.junit.parent.mkdir(parents=True)
        self.lint = self.root / "app/build/reports/lint-results-debug.xml"
        self.lint.parent.mkdir(parents=True)
        self.junit.write_text('<testsuite name="ordinary" tests="1774" failures="0" errors="0" skipped="0"/>')
        for name in gate.ADAPTERS:
            path = self.junit.parent / ("TEST-" + name + ".xml")
            path.write_text('<testsuite name="com.local.dasherfilter.' + name + '" tests="2">'
                            '<testcase name="example[26]"/><testcase name="example"/></testsuite>')
        self.lint.write_text('<issues><issue severity="Warning"/></issues>')

    def tearDown(self):
        self.temp.cleanup()

    def test_success_reports_counts_and_exact_frozen_inputs(self):
        result = gate.gate(self.root, self.frozen)
        self.assertEqual(1774 + 2 * len(gate.ADAPTERS), result["junit"]["tests"])
        self.assertEqual(dict(errors=0, warnings=1), result["lint"])
        self.assertEqual(self.frozen["aggregate"], result["inputSha256"])

    def test_source_mutation_rejected(self):
        (self.root / "app/src/Source.java").write_text("class Changed {}")
        with self.assertRaises(ValueError):
            gate.gate(self.root, self.frozen)

    def test_untracked_or_ignored_source_rejected(self):
        (self.root / ".gitignore").write_text("Injected.java\n")
        (self.root / "app/src/Injected.java").write_text("class Injected {}")
        with self.assertRaises(ValueError):
            gate.gate(self.root, self.frozen)

    def test_missing_failed_skipped_or_insufficient_tests_rejected(self):
        for attrs in ('tests="0"', f'tests="{1773 - 2 * len(gate.ADAPTERS)}"', 'tests="1774" failures="1"',
                      'tests="1774" errors="1"', 'tests="1774" skipped="1"'):
            self.junit.write_text('<testsuite name="ordinary" ' + attrs + "/>")
            with self.subTest(attrs=attrs), self.assertRaises(ValueError):
                gate.gate(self.root, self.frozen)
        self.junit.unlink()
        with self.assertRaises(ValueError):
            gate.gate(self.root, self.frozen)

    def test_lint_error_or_fatal_rejected(self):
        for severity in ("Error", "Fatal"):
            self.lint.write_text('<issues><issue severity="' + severity + '"/></issues>')
            with self.subTest(severity=severity), self.assertRaises(ValueError):
                gate.gate(self.root, self.frozen)

    def test_missing_sdk_variant_rejected_even_with_enough_tests(self):
        path = self.junit.parent / ("TEST-" + gate.ADAPTERS[0] + ".xml")
        original = path.read_text()
        for missing in ('<testcase name="example[26]"/>', '<testcase name="example"/>'):
            path.write_text(original.replace(missing, ""))
            with self.subTest(missing=missing), self.assertRaises(ValueError):
                gate.gate(self.root, self.frozen)

    def test_explicit_api35_marker_is_supported(self):
        path = self.junit.parent / ("TEST-" + gate.ADAPTERS[0] + ".xml")
        path.write_text(path.read_text().replace('name="example"', 'name="example[35]"'))
        result = gate.gate(self.root, self.frozen)
        self.assertEqual({"26": 1, "35": 1}, result["adapterSdkCases"][gate.ADAPTERS[0]])


class MainAuthorityTest(unittest.TestCase):
    source = "a" * 40
    main = "b" * 40

    def check(self, outputs, external=False, environment=None):
        results = [subprocess.CompletedProcess([], 0, stdout=text) for text in outputs]
        with patch.object(gate.subprocess, "run", side_effect=results):
            return gate.check_main(pathlib.Path("/tmp"), self.source, self.main, external, environment or {})

    def test_origin_is_checked_even_when_external_mode_requested(self):
        result = self.check([self.source, "origin\n", self.main + "\trefs/heads/main\n"], True)
        self.assertEqual("origin", result["mode"])
        with self.assertRaises(ValueError):
            self.check([self.source, "origin\n", "c" * 40 + "\trefs/heads/main\n"], True)

    def test_originless_render_requires_explicit_flag_and_exact_platform_commit(self):
        good = {"RENDER": "true", "RENDER_GIT_COMMIT": self.source}
        result = self.check([self.source, ""], True, good)
        self.assertEqual("external-required", result["mode"])
        self.assertFalse(result["mainObservedByBuild"])
        self.assertFalse(result["publishesNewFeed"])
        for flag, env in ((False, good), (True, {}), (True, {"RENDER": "false", "RENDER_GIT_COMMIT": self.source}),
                          (True, {"RENDER": "true", "RENDER_GIT_COMMIT": self.main})):
            with self.subTest(flag=flag, env=env), self.assertRaises(ValueError):
                self.check([self.source, ""], flag, env)

    def test_wrong_checkout_never_reaches_external_fallback(self):
        with self.assertRaises(ValueError):
            self.check([self.main], True, {"RENDER": "true", "RENDER_GIT_COMMIT": self.source})

    def test_remote_network_failure_never_becomes_external_authority(self):
        results = [subprocess.CompletedProcess([], 0, stdout=self.source),
                   subprocess.CompletedProcess([], 0, stdout="origin\n"),
                   subprocess.CalledProcessError(128, ["git", "ls-remote"], stderr="private")]
        with patch.object(gate.subprocess, "run", side_effect=results), self.assertRaisesRegex(ValueError, "Git authority check failed"):
            gate.check_main(pathlib.Path("/tmp"), self.source, self.main, True,
                            {"RENDER": "true", "RENDER_GIT_COMMIT": self.source})


if __name__ == "__main__":
    unittest.main()
