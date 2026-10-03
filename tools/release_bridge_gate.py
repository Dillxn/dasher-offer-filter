#!/usr/bin/env python3
"""Record immutable tracked build inputs and summarize required release gates."""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import xml.etree.ElementTree as ET

ADAPTERS = (
    "AndroidAdapterAlertsAndSettingsTest", "AndroidAdapterChartTest",
    "AndroidAdapterHomepageTest", "AndroidAdapterReportsAndUpdatesTest", "AccessibilityAdapterTest",
    "AutoAcceptAdapterTest", "AutoAcceptSettingsTest", "AdaptiveMinimumLifecycleTest", "ConsentGateTest",
)


def sdk_evidence(root, reports):
    evidence = {}
    for name in ADAPTERS:
        source = (root / ("app/src/test/java/com/local/dasherfilter/" + name + ".java")).read_text()
        # These classes have one explicit {26,35} configuration and no SDK override.
        configs = re.findall(r"@Config\([^)]*sdk\s*=\s*([^)]*)\)", source)
        if len(configs) != 1 or re.sub(r"\s", "", configs[0]) != "{26,35}":
            raise ValueError("Release adapter SDK configuration changed")
        report = reports.get("com.local.dasherfilter." + name)
        if report is None:
            raise ValueError("Required Android adapter suite missing")
        cases = [case.get("name", "") for case in report.findall("testcase")]
        if len(cases) != len(set(cases)):
            raise ValueError("Duplicate Android adapter result")
        names = set(cases)
        api26 = {item[:-4] for item in names if item.endswith("[26]")}
        # Robolectric's final configured variant may retain the unsuffixed method
        # name. Accept that paired variant or explicit [35], never a count alone.
        if not api26:
            raise ValueError("Android 8 variant results missing")
        for base in api26:
            if sum(candidate in names for candidate in (base, base + "[35]")) != 1:
                raise ValueError("Android 15 paired variant result missing or ambiguous")
        if len(names) != 2 * len(api26):
            raise ValueError("Unpaired Android adapter variant result")
        evidence[name] = {"26": len(api26), "35": len(api26)}
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
    if totals["tests"] < 1774 or any(totals[key] for key in ("failures", "errors", "skipped")):
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
                androidAdapterRuntime="Robolectric API 26 and 35; simulation only",
                deviceInstallVerified=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("freeze", "verify"))
    parser.add_argument("--snapshot", type=pathlib.Path, required=True)
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[1]
    if args.command == "freeze":
        with args.snapshot.open("x", encoding="utf-8") as output:
            json.dump(snapshot(root), output, sort_keys=True)
    else:
        receipt = gate(root, json.loads(args.snapshot.read_text(encoding="utf-8")))
        print("OFFER_FILTER_RELEASE_GATE_V1 " + json.dumps(receipt, sort_keys=True))


if __name__ == "__main__":
    main()
