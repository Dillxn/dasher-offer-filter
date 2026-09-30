#!/usr/bin/env bash
# Waits for Render to publish <commit> on the public feed, then runs tools/verify_channel.py, which downloads the
# APK with the app's own UpdateTransport and checks size, SHA-256, package, version and signer.
# Usage: await_release.sh <full-commit-sha> [timeout-seconds]
set -euo pipefail
expected="$1"
deadline=$((SECONDS + ${2:-2400}))
feed=""
while (( SECONDS < deadline )); do
    feed="$(curl -fsS "https://dash-offer-filter-build.onrender.com/latest.json?t=$(date +%s%N)" 2>/dev/null || true)"
    commit="$(python3 -c 'import json,sys; print(json.loads(sys.stdin.read() or "{}").get("sourceCommit",""))' <<<"$feed" 2>/dev/null || true)"
    if [[ "$commit" == "$expected" ]]; then
        echo "FEED_UPDATED $feed"
        rm -rf .channel-check
        python3 tools/verify_channel.py
        exit 0
    fi
    sleep 30
done
echo "RELEASE_TIMEOUT feed still at: $feed" >&2
exit 1
