#!/usr/bin/env python3
"""Synthetic local toolchains: no Android build, network, or credentials."""
import json
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import release_environment as subject


class EnvironmentTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repo = self.root / "checkout"
        self.repo.mkdir()
        (self.repo / "gradle.properties").write_text("org.gradle.jvmargs=-Xmx3g\norg.gradle.workers.max=4\n")
        self.jdk, self.sdk = self.root / "jdk", self.root / "sdk"
        (self.jdk / "bin").mkdir(parents=True)
        self.bt = self.sdk / "build-tools/35.0.0"
        (self.bt / "lib").mkdir(parents=True)
        (self.sdk / "platforms/android-36").mkdir(parents=True)
        versions = {"java": 'openjdk version "21.0.4"', "javac": "javac 21.0.4", "keytool": "unused"}
        for name, version in versions.items():
            self.executable(self.jdk / "bin" / name, version)
        for name, version in {"aapt2": "Android Asset Packaging Tool (aapt) 2.19-11904183", "d8": "D8 8.6.27", "zipalign": "unused", "apksigner": "0.9"}.items():
            self.executable(self.bt / name, version)
        for name in ("apksigner.jar", "d8.jar"):
            (self.bt / "lib" / name).write_bytes(b"synthetic fixture")
        (self.sdk / "platforms/android-36/android.jar").write_bytes(b"synthetic fixture")
        self.env = {"JAVA_HOME": str(self.jdk), "ANDROID_HOME": str(self.sdk),
                    "HOME": str(self.root / "home"), "PATH": "/usr/bin:/bin"}
        self.memory = patch.object(subject, "_memory", return_value={"limitMiB": 4096, "availableMiB": 3200, "sources": ["fixture"]})
        self.memory.start()
        self.addCleanup(self.memory.stop)

    def executable(self, path, output):
        # Fixture output is a literal shell string; none comes from user input.
        path.write_text("#!/bin/sh\nprintf '%s\\n' '" + output + "'\n")
        path.chmod(0o755)

    def resolve(self, mode="build", env=None):
        return subject.resolve_environment(mode, self.env if env is None else env, self.repo)

    def test_complete_build_has_bounded_four_gib_profile(self):
        result = self.resolve()
        self.assertTrue(result["summary"]["ok"], result["summary"]["errors"])
        self.assertEqual(result["overrides"]["ORG_GRADLE_PROJECT_forks"], "1")
        self.assertEqual(result["overrides"]["ORG_GRADLE_PROJECT_testHeap"], "768m")
        self.assertEqual(result["overrides"]["GRADLE_OPTS"], "-Dorg.gradle.jvmargs=-Xmx1024m -Dorg.gradle.workers.max=2")
        self.assertEqual(result["summary"]["tools"]["javac"]["version"], "21.0.4")
        self.assertEqual(result["summary"]["tools"]["d8"]["version"], "8.6.27")
        self.assertFalse(result["summary"]["networkAccess"])

    def test_real_wrapper_passes_daemon_properties_before_java_main(self):
        result = self.resolve()
        # Exercise the repository's actual launcher, but replace Java with an
        # argv recorder. No Gradle code, dependency resolution or build runs.
        (self.jdk / "bin/java").write_text('#!/bin/sh\nprintf "%s\\n" "$@"\n')
        command = subprocess.run(["bash", str(subject.ROOT / "gradlew"), "--version"],
                                 cwd=self.repo, env=self.env | result["overrides"],
                                 capture_output=True, text=True, timeout=5, check=True)
        args = command.stdout.splitlines()
        main = args.index("org.gradle.wrapper.GradleWrapperMain")
        self.assertIn("-Dorg.gradle.jvmargs=-Xmx1024m", args[:main])
        self.assertIn("-Dorg.gradle.workers.max=2", args[:main])
        self.assertNotIn("-Dorg.gradle.jvmargs=-Xmx3g", args)

    def test_relocation_resolves_paths_without_workspace_assumptions(self):
        relocated = self.root / "relocated"
        self.sdk.rename(relocated)
        env = dict(self.env, ANDROID_HOME=str(relocated))
        result = self.resolve(env=env)
        self.assertTrue(result["summary"]["ok"], result["summary"]["errors"])
        self.assertEqual(result["overrides"]["ANDROID_HOME"], str(relocated))

    def test_path_discovery_and_sdk_root_alias(self):
        env = dict(self.env)
        del env["JAVA_HOME"]
        del env["ANDROID_HOME"]
        env["PATH"] = str(self.jdk / "bin") + os.pathsep + str(self.bt) + os.pathsep + self.env["PATH"]
        result = self.resolve(env=env)
        self.assertTrue(result["summary"]["ok"], result["summary"]["errors"])
        self.assertEqual(result["summary"]["choices"]["JAVA_HOME"]["source"], "PATH javac")
        env["ANDROID_SDK_ROOT"] = str(self.sdk)
        self.assertEqual(self.resolve(env=env)["summary"]["choices"]["ANDROID_HOME"]["source"], "ANDROID_SDK_ROOT")

    def test_explicit_bad_java_home_does_not_silently_fallback(self):
        env = dict(self.env, JAVA_HOME=str(self.root / "missing"), PATH=str(self.jdk / "bin"))
        result = self.resolve(env=env)
        self.assertFalse(result["summary"]["ok"])
        self.assertTrue(any("Missing executable javac" in x for x in result["summary"]["errors"]))

    def test_conflicting_sdk_and_local_properties_fail_before_build(self):
        result = self.resolve(env=dict(self.env, ANDROID_SDK_ROOT=str(self.root / "elsewhere")))
        self.assertFalse(result["summary"]["ok"])
        (self.repo / "local.properties").write_text("sdk.dir=" + str(self.root / "different") + "\n")
        result = self.resolve()
        self.assertTrue(any("local.properties" in x for x in result["summary"]["errors"]))

    def test_verify_needs_no_packaging_tools_or_platform(self):
        (self.bt / "d8").unlink()
        (self.bt / "lib/d8.jar").unlink()
        (self.sdk / "platforms/android-36/android.jar").unlink()
        self.assertTrue(self.resolve("verify")["summary"]["ok"])
        self.assertFalse(self.resolve("build")["summary"]["ok"])

    def test_accept_requires_only_offline_verification_tools(self):
        for path in (self.jdk / "bin/javac", self.jdk / "bin/keytool", self.bt / "d8",
                     self.bt / "lib/d8.jar", self.sdk / "platforms/android-36/android.jar"):
            path.unlink()
        env = dict(self.env)
        del env["JAVA_HOME"]
        env["PATH"] = str(self.jdk / "bin") + os.pathsep + env["PATH"]
        result = self.resolve("accept", env)
        self.assertTrue(result["summary"]["ok"], result["summary"]["errors"])
        self.assertEqual(result["summary"]["choices"]["JAVA_HOME"]["source"], "PATH java")
        self.assertEqual(set(result["summary"]["tools"]),
                         {"java", "git", "python3", "bash", "aapt2", "apksigner", "zipalign", "apksigner.jar"})
        self.assertNotIn("GRADLE_OPTS", result["overrides"])
        self.assertFalse(self.resolve("verify")["summary"]["ok"])
        (self.bt / "zipalign").unlink()
        self.assertFalse(self.resolve("accept", env)["summary"]["ok"])

    def test_sign_needs_no_sdk_or_compiler_and_reads_no_signer(self):
        (self.jdk / "bin/javac").unlink()
        env = dict(self.env)
        del env["ANDROID_HOME"]
        env["OFFER_FILTER_SIGNING_PASSWORD"] = "DO_NOT_DISCLOSE"
        env["OFFER_FILTER_KEYSTORE_B64"] = "DO_NOT_DISCLOSE"
        result = self.resolve("sign", env)
        self.assertTrue(result["summary"]["ok"])
        self.assertNotIn("DO_NOT_DISCLOSE", json.dumps(result))
        self.assertNotIn("GRADLE_OPTS", result["overrides"])
        self.assertTrue(result["summary"]["tools"]["base64"]["decodeFlagVerified"])
        self.assertTrue({"base64", "awk", "mktemp", "rm", "dirname"} <= set(result["summary"]["tools"]))

    def test_sign_refuses_unsupported_base64_decode_flag_before_secrets(self):
        commands = self.root / "commands"
        commands.mkdir()
        base64 = commands / "base64"
        base64.write_text('#!/bin/sh\nprintf "unsupported DO_NOT_DISCLOSE\\n" >&2\nexit 64\n')
        base64.chmod(0o755)
        env = dict(self.env, PATH=str(commands) + os.pathsep + self.env["PATH"],
                   OFFER_FILTER_SIGNING_PASSWORD="DO_NOT_DISCLOSE")
        result = self.resolve("sign", env)
        self.assertFalse(result["summary"]["ok"])
        self.assertFalse(result["summary"]["tools"]["base64"]["decodeFlagVerified"])
        self.assertTrue(any("base64 --decode is unsupported" in x for x in result["summary"]["errors"]))
        self.assertNotIn("DO_NOT_DISCLOSE", json.dumps(result))

    def test_decode_probe_passes_only_public_literal_and_exact_output(self):
        with patch.object(subject.subprocess, "run") as run:
            run.return_value.returncode = 0
            run.return_value.stdout = b"f"
            self.assertTrue(subject._base64_decode_probe(Path("/fixture/base64"), {}))
            self.assertEqual(run.call_args.args[0], ["/fixture/base64", "--decode"])
            self.assertEqual(run.call_args.kwargs["input"], b"Zg==")
            run.return_value.stdout = b"f\n"
            self.assertFalse(subject._base64_decode_probe(Path("/fixture/base64"), {}))

    def test_import_needs_only_java_python_and_no_signer_environment(self):
        for path in (self.jdk / "bin/javac", self.jdk / "bin/keytool"):
            path.unlink()
        env = dict(self.env)
        del env["ANDROID_HOME"]
        del env["JAVA_HOME"]
        env["PATH"] = str(self.jdk / "bin") + os.pathsep + env["PATH"]
        result = self.resolve("import", env)
        self.assertTrue(result["summary"]["ok"], result["summary"]["errors"])
        self.assertEqual(set(result["summary"]["tools"]), {"java", "python3"})
        self.assertIsNone(result["summary"]["requiredSdk"])
        self.assertNotIn("GRADLE_OPTS", result["overrides"])
        self.assertFalse(self.resolve("sign", env)["summary"]["ok"])

    def test_user_gradle_properties_and_environment_are_preserved(self):
        user = self.root / "home/.gradle"
        user.mkdir(parents=True)
        (user / "gradle.properties").write_text("forks=2\ntestHeap=512m\norg.gradle.jvmargs=-Xmx768m -Dprivate=DO_NOT_DISCLOSE\norg.gradle.workers.max=1\n")
        result = self.resolve()
        self.assertNotIn("GRADLE_OPTS", result["overrides"])
        self.assertNotIn("ORG_GRADLE_PROJECT_forks", result["overrides"])
        self.assertNotIn("ORG_GRADLE_PROJECT_testHeap", result["overrides"])
        self.assertNotIn("DO_NOT_DISCLOSE", json.dumps(result))
        result = self.resolve(env=dict(self.env, ORG_GRADLE_PROJECT_forks="2"))
        self.assertNotIn("ORG_GRADLE_PROJECT_forks", result["overrides"])

    def test_inherited_network_options_are_retained_with_resource_defaults(self):
        env = dict(self.env, GRADLE_OPTS="-Dproxy.password=DO_NOT_DISCLOSE", JAVA_TOOL_OPTIONS="-Dprivate=DO_NOT_DISCLOSE")
        result = self.resolve(env=env)
        self.assertTrue(result["summary"]["ok"])
        self.assertEqual(result["overrides"]["GRADLE_OPTS"], env["GRADLE_OPTS"]
                         + " -Dorg.gradle.jvmargs=-Xmx1024m -Dorg.gradle.workers.max=2")
        self.assertEqual(result["overrides"]["ORG_GRADLE_PROJECT_forks"], "1")
        self.assertEqual(result["overrides"]["ORG_GRADLE_PROJECT_testHeap"], "768m")
        self.assertNotIn("JAVA_TOOL_OPTIONS", result["overrides"])
        self.assertNotIn("DO_NOT_DISCLOSE", json.dumps(result["summary"]))
        self.assertTrue(any("preserved without disclosure" in x for x in result["summary"]["warnings"]))

    def test_each_option_variable_allows_defaults_for_unrelated_settings(self):
        for name in subject.OPTION_ENV:
            with self.subTest(name=name):
                original = '-Dhttps.proxyHost=proxy.invalid -Djavax.net.ssl.trustStore="/synthetic path/trust"'
                env = dict(self.env, **{name: original})
                result = self.resolve(env=env)
                effective = env | result["overrides"]
                self.assertTrue(result["summary"]["ok"])
                self.assertEqual(effective[name], original + (
                    " -Dorg.gradle.jvmargs=-Xmx1024m -Dorg.gradle.workers.max=2" if name == "GRADLE_OPTS" else ""))
                self.assertEqual(effective["ORG_GRADLE_PROJECT_forks"], "1")
                self.assertEqual(effective["ORG_GRADLE_PROJECT_testHeap"], "768m")
                for key in ("org.gradle.jvmargs", "org.gradle.workers.max", "forks", "testHeap"):
                    self.assertEqual(result["summary"]["choices"][key]["source"], "resource-aware local release default")

    def test_each_explicit_option_preserves_only_its_resource_setting(self):
        properties = {"org.gradle.jvmargs": '"-Dorg.gradle.jvmargs=-Xmx768m -Dprivate=DO_NOT_DISCLOSE"',
                      "org.gradle.workers.max": "-Dorg.gradle.workers.max=3",
                      "forks": "-Dorg.gradle.project.forks=3",
                      "testHeap": "-Dorg.gradle.project.testHeap=640m"}
        for name in subject.OPTION_ENV:
            for key, option in properties.items():
                with self.subTest(name=name, key=key):
                    env = dict(self.env, **{name: option})
                    result = self.resolve(env=env)
                    self.assertTrue(result["summary"]["ok"])
                    self.assertEqual(result["summary"]["choices"][key]["source"], name)
                    for other in properties.keys() - {key}:
                        self.assertEqual(result["summary"]["choices"][other]["source"],
                                         "resource-aware local release default")
                    if key in ("forks", "testHeap"):
                        self.assertNotIn("ORG_GRADLE_PROJECT_" + key, result["overrides"])
                    else:
                        self.assertFalse(any(x.startswith("-D" + key + "=") for x in
                                             shlex.split(result["overrides"].get("GRADLE_OPTS", ""))
                                             if x not in shlex.split(option)))
                    self.assertNotIn("DO_NOT_DISCLOSE", json.dumps(result["summary"]))

    def test_real_wrapper_retains_quoted_options_and_adds_only_missing_defaults(self):
        inherited = ' -Dhttps.proxyHost=proxy.invalid "-Dorg.gradle.jvmargs=-Xmx768m -Dprivate=DO_NOT_DISCLOSE" '
        env = dict(self.env, GRADLE_OPTS=inherited, JAVA_OPTS='-Djavax.net.ssl.trustStore="/synthetic path/trust"')
        result = self.resolve(env=env)
        self.assertEqual(result["overrides"]["GRADLE_OPTS"], inherited + " -Dorg.gradle.workers.max=2")
        (self.jdk / "bin/java").write_text('#!/bin/sh\nprintf "%s\\n" "$@"\n')
        command = subprocess.run(["bash", str(subject.ROOT / "gradlew"), "--version"],
                                 cwd=self.repo, env=env | result["overrides"],
                                 capture_output=True, text=True, timeout=5, check=True)
        args = command.stdout.splitlines()
        main = args.index("org.gradle.wrapper.GradleWrapperMain")
        self.assertIn("-Dorg.gradle.jvmargs=-Xmx768m -Dprivate=DO_NOT_DISCLOSE", args[:main])
        self.assertIn("-Djavax.net.ssl.trustStore=/synthetic path/trust", args[:main])
        self.assertIn("-Dorg.gradle.workers.max=2", args[:main])
        self.assertEqual(sum(x.startswith("-Dorg.gradle.jvmargs=") for x in args), 1)
        self.assertNotIn("DO_NOT_DISCLOSE", json.dumps(result["summary"]))

    def test_property_names_in_other_values_do_not_disable_defaults(self):
        env = dict(self.env, GRADLE_OPTS='-Ddescription="-Dorg.gradle.jvmargs=-Xmx8g -Dorg.gradle.project.forks=8"')
        result = self.resolve(env=env)
        self.assertEqual(result["overrides"]["ORG_GRADLE_PROJECT_forks"], "1")
        self.assertIn("-Dorg.gradle.jvmargs=-Xmx1024m", shlex.split(result["overrides"]["GRADLE_OPTS"]))

    def test_global_late_heap_override_preserves_heaps_but_not_parallelism(self):
        for option in ("-Xmx512m", "-XX:MaxHeapSize=536870912"):
            with self.subTest(option=option):
                env = dict(self.env, _JAVA_OPTIONS=option)
                result = self.resolve(env=env)
                self.assertTrue(result["summary"]["ok"])
                self.assertEqual(result["overrides"]["GRADLE_OPTS"], "-Dorg.gradle.workers.max=2")
                self.assertEqual(result["overrides"]["ORG_GRADLE_PROJECT_forks"], "1")
                self.assertNotIn("ORG_GRADLE_PROJECT_testHeap", result["overrides"])
                self.assertNotIn("_JAVA_OPTIONS", result["overrides"])

    def test_client_and_early_jvm_heaps_do_not_suppress_gradle_resource_settings(self):
        for name in set(subject.OPTION_ENV) - {"_JAVA_OPTIONS"}:
            with self.subTest(name=name):
                result = self.resolve(env=dict(self.env, **{name: "-Xmx512m"}))
                self.assertEqual(result["overrides"]["ORG_GRADLE_PROJECT_testHeap"], "768m")
                self.assertIn("-Dorg.gradle.jvmargs=-Xmx1024m", shlex.split(result["overrides"]["GRADLE_OPTS"]))

    def test_empty_explicit_property_is_preserved_without_hiding_other_defaults(self):
        result = self.resolve(env=dict(self.env, GRADLE_OPTS="-Dorg.gradle.jvmargs"))
        self.assertEqual(result["overrides"]["GRADLE_OPTS"], "-Dorg.gradle.jvmargs -Dorg.gradle.workers.max=2")
        self.assertEqual(result["overrides"]["ORG_GRADLE_PROJECT_testHeap"], "768m")

    def test_mixed_explicit_options_credit_the_actual_highest_priority_source(self):
        env = dict(self.env, JAVA_TOOL_OPTIONS="-Dorg.gradle.workers.max=1",
                   JDK_JAVA_OPTIONS="-Dorg.gradle.workers.max=2", JAVA_OPTS="-Dorg.gradle.workers.max=3",
                   GRADLE_OPTS="-Dorg.gradle.workers.max=4", _JAVA_OPTIONS="-Dorg.gradle.workers.max=5")
        for highest in ("_JAVA_OPTIONS", "GRADLE_OPTS", "JAVA_OPTS", "JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS"):
            with self.subTest(highest=highest):
                result = self.resolve(env=env)
                self.assertEqual(result["summary"]["choices"]["org.gradle.workers.max"]["source"], highest)
                self.assertEqual(result["overrides"]["ORG_GRADLE_PROJECT_forks"], "1")
                del env[highest]

    def test_malformed_options_fail_without_exposing_values_or_guessing_defaults(self):
        result = self.resolve(env=dict(self.env, GRADLE_OPTS='"-Dprivate=DO_NOT_DISCLOSE'))
        self.assertFalse(result["summary"]["ok"])
        self.assertTrue(any("Cannot parse quoted options in GRADLE_OPTS" in x for x in result["summary"]["errors"]))
        self.assertNotIn("GRADLE_OPTS", result["overrides"])
        self.assertNotIn("DO_NOT_DISCLOSE", json.dumps(result))

    def test_failed_and_old_tool_versions_fail_closed(self):
        self.executable(self.jdk / "bin/java", 'openjdk version "11.0.25"')
        self.assertFalse(self.resolve()["summary"]["ok"])
        self.executable(self.jdk / "bin/java", "unrecognized DO_NOT_DISCLOSE")
        result = self.resolve()
        self.assertFalse(result["summary"]["ok"])
        self.assertNotIn("DO_NOT_DISCLOSE", json.dumps(result))
        (self.bt / "aapt2").write_text("#!/bin/sh\nexit 2\n")
        self.assertTrue(any("aapt2: Version probe failed" in x for x in self.resolve()["summary"]["errors"]))

    def test_unknown_memory_uses_small_profile(self):
        with patch.object(subject, "_memory", return_value={"limitMiB": None, "availableMiB": None, "sources": []}):
            result = self.resolve()
        self.assertEqual(result["overrides"]["ORG_GRADLE_PROJECT_forks"], "1")
        self.assertTrue(any("could not be detected" in x for x in result["summary"]["warnings"]))

    def test_low_memory_and_missing_tools_are_actionable(self):
        with patch.object(subject, "_memory", return_value={"limitMiB": 2048, "availableMiB": 1024, "sources": ["fixture"]}):
            result = self.resolve()
        self.assertFalse(result["summary"]["ok"])
        self.assertTrue(any("Less than 3 GiB" in x for x in result["summary"]["errors"]))
        (self.bt / "lib/apksigner.jar").unlink()
        self.assertTrue(any("Missing existing SDK component" in x for x in self.resolve()["summary"]["errors"]))

    def test_invalid_mode_refused(self):
        with self.assertRaises(ValueError):
            self.resolve("download")

    def test_mixed_java_and_compiler_versions_fail_early(self):
        self.executable(self.jdk / "bin/javac", "javac 17.0.16")
        self.assertTrue(any("different major JDK" in x for x in self.resolve()["summary"]["errors"]))

    def test_memory_respects_container_limit_and_usage(self):
        files = {"/proc/meminfo": "MemTotal:       67108864 kB\nMemAvailable:   33554432 kB\n",
                 "/sys/fs/cgroup/memory.max": str(4 * 1024 ** 3),
                 "/sys/fs/cgroup/memory.current": str(1 * 1024 ** 3)}
        def read(path, *args, **kwargs):
            if str(path) not in files:
                raise FileNotFoundError(str(path))
            return files[str(path)]
        self.memory.stop()
        with patch.object(Path, "read_text", read):
            memory = subject._memory()
        self.memory.start()
        self.assertEqual(memory["limitMiB"], 4096)
        self.assertEqual(memory["availableMiB"], 3072)


if __name__ == "__main__":
    unittest.main()
