#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
notes="${1:-RELEASE_NOTES.md}"

if [[ ! -f "$notes" ]]; then
    echo "Release notes not found: $notes" >&2
    exit 1
fi

./gradlew testDebugUnitTest
./build-local.sh
python3 publish-update.py app/build/outputs/apk/debug/app-debug.apk --notes-file "$notes"
