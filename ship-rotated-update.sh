#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
notes="${1:-RELEASE_NOTES.md}"
config="${OFFER_FILTER_ROTATION_CONFIG:-$HOME/.android/offer-filter-rotation.env}"

if [[ "${OFFER_FILTER_BRIDGE_CONFIRMED:-0}" != "1" ]]; then
    echo "Refusing rotated publication until the old-key bridge is confirmed installed." >&2
    echo "After Offer Filter 0.4.1 is installed, run:" >&2
    echo "  OFFER_FILTER_BRIDGE_CONFIRMED=1 ./ship-rotated-update.sh" >&2
    exit 1
fi
if [[ ! -f "$config" ]]; then
    echo "Rotation config not found: $config" >&2
    echo "Run ./prepare-key-rotation.sh first." >&2
    exit 1
fi
if [[ ! -f "$notes" ]]; then
    echo "Release notes not found: $notes" >&2
    exit 1
fi

# shellcheck disable=SC1090
source "$config"

python3 - <<'PY'
import json
import urllib.request
url = "https://raw.githubusercontent.com/Dillxn/dasher-offer-filter-updates/main/latest.json"
with urllib.request.urlopen(url + "?bridge-check=1", timeout=20) as response:
    feed = json.load(response)
if int(feed.get("versionCode", 0)) < 7:
    raise SystemExit("Public bridge release (versionCode 7 / 0.4.1) is not live yet.")
PY

./gradlew testDebugUnitTest
./build-local.sh
python3 publish-update.py app/build/outputs/apk/debug/app-debug.apk     --notes-file "$notes"     --expected-signer "$OFFER_FILTER_NEW_SIGNER_FINGERPRINT"     --lineage-file "$OFFER_FILTER_LINEAGE"     --required-ancestor "$OFFER_FILTER_OLD_SIGNER_FINGERPRINT"
