#!/usr/bin/env bash
# Focused API 35 checks through AGP's existing task. No signing, lint, or release-gate substitution.
set -euo pipefail
cd "$(dirname "$0")/.."

usage() {
    cat <<'EOF'
Usage: tools/test-dev.sh TestClass[.method] [TestClass[.method] ...]
       tools/test-dev.sh --all

Run selected local tests on API 35, or the complete daily suite with --all.
Selectors may include the com.local.dasherfilter. package prefix; wildcards and Gradle options are not accepted.
Examples:
  tools/test-dev.sh BundledNotesTest
  tools/test-dev.sh BundledNotesTest.theCurrentNoticeAndVersionHaveTheirEntry
  tools/test-dev.sh OfferRuleTest GrowthTest

Results: app/build/test-results/devChecks/testDebugUnitTest/
HTML:    app/build/reports/tests/devChecks/testDebugUnitTest/index.html
The full release still requires testDebugUnitTest -PallSdks, lint, and the existing frozen-input gate.
EOF
}

if [[ $# -eq 1 && "$1" == --help ]]; then
    usage
    exit 0
fi
if [[ $# -eq 0 ]]; then
    usage >&2
    exit 2
fi

selectors=()
if [[ $# -eq 1 && "$1" == --all ]]; then
    :
else
    for selector in "$@"; do
        short="${selector#com.local.dasherfilter.}"
        if [[ ! "$short" =~ ^[A-Z][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)?$ ]]; then
            printf 'Invalid test selector: %s\n' "$selector" >&2
            usage >&2
            exit 2
        fi
        class="${short%%.*}"
        if [[ ! -f "app/src/test/java/com/local/dasherfilter/$class.java" ]]; then
            printf 'Test source not found: %s\n' "$class" >&2
            exit 2
        fi
        selectors+=(--tests "com.local.dasherfilter.$short")
    done
fi

exec ./gradlew -q --project-cache-dir "$PWD/.gradle/dev-checks" :app:testDebugUnitTest -PdevChecks "${selectors[@]}"
