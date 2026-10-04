#!/usr/bin/env python3
"""Supplemental offline core checks; NOT the Android unit/lint/signing/release gate.

Requires Python 3 and a JDK supporting --release 17. All tested classes are the
repository's real production classes. Only unrelated DasherScene references are
satisfied by a temporary compile boundary that throws if called. No network,
Android action, installation, or signing occurs. JUnit bridges run the same
cases in the regular unit-test gate without that temporary boundary.
"""
from pathlib import Path
import argparse
import shutil
import subprocess
import tempfile

PRODUCTION = (
    "OfferSnapshot", "OfferControls", "OfferEvidence", "OfferParser", "ItemCount",
    "AddOnOffer", "DeclineConfirmation", "DeclineErrorRecovery", "UpdatePolicy", "UpdateTransport",
)
CASES = ("ParserSafetyCases", "PolicySafetyCases")
BOUNDARY = """package com.local.dasherfilter;
import java.util.List;
final class DasherScene {
    static boolean showsRoute(List<String> ignored) { throw new AssertionError("Scene classification is outside this audit"); }
    static boolean showsEndDashQuestion(List<String> ignored) { throw new AssertionError("Scene classification is outside this audit"); }
    static boolean showsNewOffer(List<String> ignored) { throw new AssertionError("Scene classification is outside this audit"); }
}
"""


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--stack-size", choices=("1m", "256k"), default="1m")
    args = parser.parse_args()
    javac, java = shutil.which("javac"), shutil.which("java")
    if not javac or not java:
        parser.error("A JDK with javac and java is required; this tool downloads nothing.")
    sources = args.root / "app/src/main/java/com/local/dasherfilter"
    tests = args.root / "app/src/test/java/com/local/dasherfilter"
    inputs = [sources / (name + ".java") for name in PRODUCTION]
    inputs += [tests / (name + ".java") for name in CASES]
    missing = [str(path) for path in inputs if not path.is_file()]
    if missing:
        parser.error("Missing source files: " + ", ".join(missing))
    try:
        with tempfile.TemporaryDirectory(prefix="offer-filter-core-") as temporary:
            work = Path(temporary)
            boundary = work / "DasherScene.java"
            boundary.write_text(BOUNDARY, encoding="utf-8")
            classes = work / "classes"
            classes.mkdir()
            subprocess.run([javac, "--release", "17", "-encoding", "UTF-8", "-implicit:none",
                            "-d", str(classes), *map(str, inputs), str(boundary)],
                           check=True, timeout=90)
            failures = 0
            for name in CASES:
                result = subprocess.run([java, "-Xss" + args.stack_size, "-cp", str(classes),
                                         "com.local.dasherfilter." + name], timeout=90)
                failures += result.returncode != 0
            return 1 if failures else 0
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        parser.exit(1, "Core safety check failed: " + str(error) + "\n")


if __name__ == "__main__":
    raise SystemExit(main())
