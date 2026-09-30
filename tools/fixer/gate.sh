#!/usr/bin/env bash
# Release gate for one fixer commit, run by the workflow from a copy taken before the fixer ran, so the fixer
# cannot change the checks that judge it. Usage: gate.sh <base-commit>. Exits non-zero with "GATE FAILED: ..." on
# the first failed check.
set -euo pipefail
# Everything runs inside main, which bash reads in full before running: the fix's own tests execute during the
# gate and must not be able to change what the rest of this script does.
main() {
base="$1"
here="$(cd "$(dirname "$0")" && pwd)"
fail() { echo "GATE FAILED: $*" >&2; exit 1; }
step() { echo "== $*"; }
tests_count() { git grep -h -o '@Test\b' "$1" -- app/src/test | wc -l; }
version_code() { sed -n 's/.*versionCode \([0-9][0-9]*\).*/\1/p'; }
version_name() { sed -n "s/.*versionName '\([^']*\)'.*/\1/p"; }

step "one clean commit on top of main"
[[ "$(git rev-list --count "$base"..HEAD)" == 1 ]] || fail "expected exactly one new commit on top of $base"
[[ "$(git rev-parse HEAD~1)" == "$(git rev-parse "$base")" ]] || fail "the commit is not on top of main"
[[ -z "$(git status --porcelain)" ]] || fail "uncommitted or untracked files: $(git status --porcelain | head -5)"

step "only app code, tests, version and notes changed"
# --no-renames lists both sides of a move, so moving a protected file away counts as changing it.
changed="$(git diff --name-only --no-renames "$base" HEAD)"
while IFS= read -r path; do
    case "$path" in
        # The updater, its transport and its GitHub sign-in decide what the phone installs; the fixer never touches them.
        app/src/main/java/*/Update*.java|app/src/main/java/*/GitHubConnect.java)
            fail "not allowed to change the updater: $path" ;;
        # Where tips go is the author's alone to set.
        app/src/main/java/*/Support.java) fail "not allowed to change where tips go: $path" ;;
        # What the place lookup is asked (rounded positions only) is a privacy line, not a bug fix.
        app/src/main/java/*/Places.java) fail "not allowed to change what the place lookup is asked: $path" ;;
        app/src/main/java/*|app/src/test/java/*|app/build.gradle|README.md|RELEASE_NOTES.md|AUDIT.md) ;;
        *) fail "not allowed to change $path" ;;
    esac
done <<<"$changed"
grep -q '^app/src/main/java/' <<<"$changed" || fail "no app code changed"
grep -q '^app/src/test/java/' <<<"$changed" || fail "no test added or changed"
[[ -z "$(git diff --name-only --no-renames --diff-filter=D "$base" HEAD -- app/src/test)" ]] || fail "a test file was deleted"
gradle_lines="$(git diff -U0 --no-renames "$base" HEAD -- app/build.gradle | grep -E '^[-+]' | grep -vE '^(\+\+\+|---) ' \
    | grep -vE "^[-+][[:space:]]*(versionCode [0-9]+|versionName '[0-9]+\.[0-9]+\.[0-9]+')[[:space:]]*$" || true)"
[[ -z "$gradle_lines" ]] || fail "app/build.gradle may change only versionCode and versionName"

step "version"
old_code="$(git show "$base:app/build.gradle" | version_code)"
new_code="$(version_code < app/build.gradle)"
old_name="$(git show "$base:app/build.gradle" | version_name)"
new_name="$(version_name < app/build.gradle)"
[[ "$new_code" == "$((old_code + 1))" ]] || fail "versionCode must go from $old_code to $((old_code + 1)), not $new_code"
[[ "$new_name" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ && "$new_name" != "$old_name" ]] || fail "versionName must change"
echo "$old_name ($old_code) -> $new_name ($new_code)"

step "no skipped or removed tests"
if git diff --no-renames "$base" HEAD -- app/src/test | grep -E '^\+' | grep -qE '@Ignore|Assume\.|assume[A-Z]'; then
    fail "tests may not be skipped"
fi
[[ "$(tests_count HEAD)" -ge "$(tests_count "$base")" ]] || fail "the number of tests dropped"

step "the changed tests compile and fail against the old app code"
classes="$(git diff --name-only --no-renames --diff-filter=AM "$base" HEAD -- app/src/test/java \
    | sed -n 's|^app/src/test/java/\(.*\)\.java$|\1|p' | tr / .)"
[[ -n "$classes" ]] || fail "no test class added or changed"
results=app/build/test-results/testDebugUnitTest
restore() { rm -rf app/src/main && git checkout -q HEAD -- app/src/main; }
rm -rf app/src/main && git checkout -q "$base" -- app/src/main
trap restore EXIT
rm -rf "$results"
filters=()
for class in $classes; do filters+=(--tests "$class"); done
./gradlew --no-daemon -q testDebugUnitTest "${filters[@]}" > "${RUNNER_TEMP:-/tmp}/gate-old-code.log" 2>&1 || true
# Needs a JUnit result per changed class: a test that does not even compile on the old code proves nothing.
python3 - "$results" $classes <<'PY'
import glob, sys, xml.etree.ElementTree as ET
results, classes = sys.argv[1], sys.argv[2:]
ran = failed = 0
for name in classes:
    for path in glob.glob(f'{results}/TEST-{name}.xml'):
        suite = ET.parse(path).getroot()
        ran += int(suite.get('tests', 0))
        failed += int(suite.get('failures', 0)) + int(suite.get('errors', 0))
if ran == 0:
    sys.exit('GATE FAILED: the changed tests do not compile or run against the old app code; '
             'test the fix through code that already exists')
if failed == 0:
    sys.exit('GATE FAILED: every changed test passes without the fix, so nothing shows the fix is needed')
print(f'old code: {failed} of {ran} changed tests fail, as they should')
PY
restore
trap - EXIT
git reset -q
[[ -z "$(git status --porcelain)" ]] || fail "could not restore the fixed code"

step "full unit and adapter tests, and lint"
./gradlew --no-daemon testDebugUnitTest lintDebug
python3 - <<'PY'
import glob, sys, xml.etree.ElementTree as ET
totals = {'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0}
for path in glob.glob('app/build/test-results/testDebugUnitTest/TEST-*.xml'):
    suite = ET.parse(path).getroot()
    for key in totals:
        totals[key] += int(suite.get(key, 0))
print('junit', totals)
if totals['tests'] < 409 or totals['failures'] or totals['errors'] or totals['skipped']:
    sys.exit('GATE FAILED: test totals ' + str(totals))
PY

step "the APK builds (unsigned; Render signs it)"
bash "$here/package_check.sh"
echo "GATE PASSED $new_name ($new_code)"
}
main "$@"
