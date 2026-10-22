#!/usr/bin/env python3
"""Record immutable tracked build inputs and summarize required release gates."""
import argparse
import hashlib
import json
import os
import pathlib
import re
import subprocess
import xml.etree.ElementTree as ET

# Every test of the dual-SDK suite (testDebugUnitTest -PallSdks): 320 in 13 suites on 22 October 2026, when the owner
# had the unit tests removed ("let's remove all unit tests from the codebase") and chose to keep the safety, privacy
# and consent suites, with LegalTextsTest, SameOfferAdapterTest and the helpers they use (4,209 tests in 218 files
# before, at 0.6.30; the branch claude/last-with-unit-tests keeps them). A run below it is missing a kept suite's tests.
MIN_TESTS = 320

# The kept Android suites a release must have run on both Android 15 and 16, paired case by case (Android 15 is the
# oldest supported: the owner, 11 October 2026): what the app does by itself around Dasher (taps, the user's own map
# and touch), the first-run notice's consent and its reminder, the dispatch study's consent, and masking and the
# privacy boundary.
ADAPTERS = (
    "ActionSafetyReviewTest", "ActionSafetyTest", "ConsentGateTest", "ConsentReminderTest",
    "DispatchStudyConsentTest", "MaskedCaptureAdapterTest", "PrivacyBoundaryTest",
)
ADAPTER_SOURCES = "app/src/test/java/com/local/dasherfilter/"


def adapter_sources(root):
    """Each release adapter suite is in the repository with one explicit {35,36} configuration and no SDK override."""
    for name in ADAPTERS:
        path = root / (ADAPTER_SOURCES + name + ".java")
        if not path.is_file():
            raise ValueError("Required Android adapter suite source missing")
        annotations = re.findall(r"@Config\(([^)]*)\)", path.read_text())
        if any(re.search(r"\b(?:minSdk|maxSdk)\s*=", annotation) for annotation in annotations):
            raise ValueError("Release adapter SDK configuration changed")
        # Layout qualifiers and instrumented packages do not change the SDK pair. A second sdk declaration,
        # including a method override, still fails; only one explicit {35,36} declaration is allowed.
        configs = [sdk for annotation in annotations
                   for sdk in re.findall(r"\bsdk\s*=\s*(\{[^}]*\}|[^,\s)]+)", annotation)]
        if len(configs) != 1 or re.sub(r"\s", "", configs[0]) != "{35,36}":
            raise ValueError("Release adapter SDK configuration changed")


def check_main(root, source, expected_main, external=False, environment=None):
    """An originless Render exporter can defer main reads to its external caller.

    This does not grant publication authority: the caller must read main before
    and after this build and immediately before the non-force source/feed push.
    """
    if not re.fullmatch(r"[a-f0-9]{40}", source or "") or not re.fullmatch(r"[a-f0-9]{40}", expected_main or ""):
        raise ValueError("Invalid pinned source or main")
    def git(*args):
        try:
            return subprocess.run(["git", *args], cwd=root, check=True, text=True, capture_output=True).stdout
        except subprocess.CalledProcessError:
            raise ValueError("Git authority check failed") from None
    if git("rev-parse", "HEAD").strip() != source:
        raise ValueError("Pinned source mismatch")
    if "origin" in git("remote").splitlines():
        refs = [line.split() for line in git("ls-remote", "--exit-code", "origin", "refs/heads/main").splitlines()
                if line.strip()]
        if refs != [[expected_main, "refs/heads/main"]]:
            raise ValueError("Main moved; signing bridge refused")
        return dict(mode="origin", sourceCommit=source, observedMain=expected_main)
    env = os.environ if environment is None else environment
    if not external or env.get("RENDER") != "true" or env.get("RENDER_GIT_COMMIT") != source:
        raise ValueError("No origin: require explicit external-main checking in the pinned Render checkout")
    return dict(mode="external-required", sourceCommit=source, callerExpectedMain=expected_main,
                mainObservedByBuild=False, publishesNewFeed=False,
                callerMustCheck="before build, after build, immediately before non-force publication")


def sdk_evidence(root, reports):
    adapter_sources(root)
    evidence = {}
    for name in ADAPTERS:
        report = reports.get("com.local.dasherfilter." + name)
        if report is None:
            raise ValueError("Required Android adapter suite missing")
        cases = [case.get("name", "") for case in report.findall("testcase")]
        if len(cases) != len(set(cases)):
            raise ValueError("Duplicate Android adapter result")
        names = set(cases)
        api35 = {item[:-4] for item in names if item.endswith("[35]")}
        # Robolectric's final configured variant may retain the unsuffixed method
        # name. Accept that paired variant or explicit [36], never a count alone.
        if not api35:
            raise ValueError("Android 15 variant results missing")
        for base in api35:
            if sum(candidate in names for candidate in (base, base + "[36]")) != 1:
                raise ValueError("Android 16 paired variant result missing or ambiguous")
        if len(names) != 2 * len(api35):
            raise ValueError("Unpaired Android adapter variant result")
        evidence[name] = {"35": len(api35), "36": len(api35)}
    return evidence


def snapshot(root):
    paths = subprocess.run(["git", "ls-files", "-z"], cwd=root, check=True, capture_output=True).stdout.split(b"\0")
    entries = {}
    for raw in paths:
        if not raw:
            continue
        name = raw.decode("utf-8")
        path = root / name
        if path.is_symlink() or not path.is_file():
            raise ValueError("Tracked input is not a regular file")
        entries[name] = hashlib.sha256(path.read_bytes()).hexdigest()
    if not entries:
        raise ValueError("No committed build inputs")
    # build-local.sh compiles all files under these roots, including ignored files.
    # A restored/cache-added source must not silently enter a signed APK.
    for directory in (root / "app/src", root / "gradle", root / "tools"):
        if not directory.exists():
            continue
        for path in directory.rglob("*"):
            if path.is_file() and "__pycache__" not in path.parts:
                if path.relative_to(root).as_posix() not in entries:
                    raise ValueError("Untracked build input")
    aggregate = hashlib.sha256(json.dumps(entries, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    return dict(count=len(entries), aggregate=aggregate, files=entries)


def gate(root, frozen):
    current = snapshot(root)
    if current != frozen:
        raise ValueError("Tracked source changed during validation/signing")
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    suites = list((root / "app/build/test-results/testDebugUnitTest").glob("TEST-*.xml"))
    if not suites:
        raise ValueError("JUnit reports missing")
    reports = {}
    for path in suites:
        result = ET.parse(path).getroot()
        name = result.get("name", "")
        if name in reports:
            raise ValueError("Duplicate test suite")
        reports[name] = result
        for key in totals:
            totals[key] += int(result.get(key, 0))
    if totals["tests"] < MIN_TESTS or any(totals[key] for key in ("failures", "errors", "skipped")):
        raise ValueError("Full test gate failed")
    sdks = sdk_evidence(root, reports)
    lint = ET.parse(root / "app/build/reports/lint-results-debug.xml").getroot()
    lint_counts = dict(errors=0, warnings=0)
    for issue in lint.findall("issue"):
        level = issue.get("severity", "")
        if level in ("Fatal", "Error"):
            lint_counts["errors"] += 1
        elif level == "Warning":
            lint_counts["warnings"] += 1
    if lint_counts["errors"]:
        raise ValueError("Android lint gate failed")
    return dict(inputCount=current["count"], inputSha256=current["aggregate"], junit=totals,
                suites=len(suites), lint=lint_counts, adapterSdkCases=sdks,
                androidAdapterRuntime="Robolectric API 35 and 36; simulation only",
                deviceInstallVerified=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("freeze", "verify", "check-main"))
    parser.add_argument("--snapshot", type=pathlib.Path)
    parser.add_argument("--source")
    parser.add_argument("--expected-main")
    parser.add_argument("--external-main-check", action="store_true")
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[1]
    if args.command == "check-main":
        print("OFFER_FILTER_MAIN_CHECK_V1 " + json.dumps(check_main(
            root, args.source, args.expected_main, args.external_main_check), sort_keys=True))
        return
    if args.snapshot is None:
        parser.error("--snapshot is required for freeze/verify")
    if args.command == "freeze":
        with args.snapshot.open("x", encoding="utf-8") as output:
            json.dump(snapshot(root), output, sort_keys=True)
    else:
        receipt = gate(root, json.loads(args.snapshot.read_text(encoding="utf-8")))
        print("OFFER_FILTER_RELEASE_GATE_V1 " + json.dumps(receipt, sort_keys=True))


if __name__ == "__main__":
    main()
