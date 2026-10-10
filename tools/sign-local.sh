#!/usr/bin/env bash
# Builds/signs with the original cloud key from this environment. The resulting dist/ APK is an input to
# tools/publish-repo-feed.py, not an alternate phone installation path. Render may run it in place through
# render-sign-bridge.sh when a transient workspace has no signer. No key is generated or exported.
# Signing material is decoded only into a private temporary directory removed on exit; its certificate must
# match the one installed phones trust. Nothing in this script publishes the result.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ "${1:-}" == 'prepare-unsigned' ]]; then
    shift
    exec python3 tools/split_release.py prepare "$@"
elif [[ "${1:-}" == 'finalize-original-signature' ]]; then
    set +x
    shift
    if [[ "${1:-}" == '--accept-signed' ]]; then
        shift
        exec python3 tools/split_release.py accept "$@"
    fi
    [[ "${1:-}" == '--sign' && "$#" == 6 ]] || {
        echo 'Usage: sign-local.sh finalize-original-signature --sign BUNDLE RECEIPT_SHA SOURCE TREE OUTPUT_DIR' >&2; exit 1;
    }
    shift
    # Preflight validates independently supplied identities before touching any secret.
    python3 tools/split_release.py check-sign "$@"
    : "${OFFER_FILTER_SIGNING_PASSWORD:?OFFER_FILTER_SIGNING_PASSWORD is not set}"
    : "${OFFER_FILTER_KEYSTORE_B64:?OFFER_FILTER_KEYSTORE_B64 is not set}"
    : "${TMPDIR:?Set TMPDIR to the private signing workspace temporary directory}"
    python3 tools/split_release.py check-private-dir "$TMPDIR"
    command -v keytool >/dev/null
    command -v java >/dev/null
    secrets="$(mktemp -d)"
    trap 'rm -rf "$secrets"' EXIT
    umask 077
    printf '%s' "$OFFER_FILTER_KEYSTORE_B64" | base64 --decode > "$secrets/signing.p12"
    keytool -J-Duser.language=en -J-Duser.country=US -list -v -keystore "$secrets/signing.p12" -storepass:env OFFER_FILTER_SIGNING_PASSWORD \
        -alias offerfilter-cloud > "$secrets/certificate.txt" 2> "$secrets/keytool-error.txt" || {
        echo 'Original signer certificate precheck failed.' >&2; exit 1;
    }
    actual_fp="$(awk -F'SHA256: ' '/SHA256: / {v=$2; gsub(":", "", v); print tolower(v); exit}' "$secrets/certificate.txt")"
    [[ "$actual_fp" == '553994c4d1310bf92f236525d1d293df597f37be39a7fd34f8b58e68dda0c703' ]] || {
        echo 'Not the original signing certificate; refusing to sign.' >&2; exit 1;
    }
    # Stage a verified immutable copy: signing never consumes a mutable transfer path.
    python3 tools/split_release.py stage-sign "$@" "$secrets"
    java -Xmx1024M -jar "$secrets/apksigner.jar" sign --ks "$secrets/signing.p12" \
        --ks-pass env:OFFER_FILTER_SIGNING_PASSWORD --key-pass env:OFFER_FILTER_SIGNING_PASSWORD \
        --ks-key-alias offerfilter-cloud --out "$secrets/signed.apk" "$secrets/unsigned.apk" \
        > "$secrets/signing-output.txt" 2> "$secrets/signing-error.txt" || {
        echo 'Original signing failed; private diagnostics retained only until cleanup.' >&2; exit 1;
    }
    python3 tools/split_release.py record-sign "$@" "$secrets"
    exit 0
elif [[ "$#" != 0 ]]; then
    echo 'Unknown signing phase.' >&2; exit 1
fi
KEY_FINGERPRINT="553994c4d1310bf92f236525d1d293df597f37be39a7fd34f8b58e68dda0c703"
: "${OFFER_FILTER_SIGNING_PASSWORD:?OFFER_FILTER_SIGNING_PASSWORD is not set in this environment}"
: "${OFFER_FILTER_KEYSTORE_B64:?OFFER_FILTER_KEYSTORE_B64 is not set in this environment}"
: "${ANDROID_HOME:?ANDROID_HOME must point at an SDK with build-tools 35.0.0 and platform android-36}"
export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
tools="$ANDROID_HOME/build-tools/35.0.0"

secrets="$(mktemp -d)"
trap 'rm -rf "$secrets"' EXIT
umask 077
printf '%s' "$OFFER_FILTER_KEYSTORE_B64" | base64 --decode > "$secrets/signing.p12"
actual_fp="$(keytool -list -v -keystore "$secrets/signing.p12" -storepass:env OFFER_FILTER_SIGNING_PASSWORD \
    -alias offerfilter-cloud | awk -F'SHA256: ' '/SHA256: / {v=$2; gsub(":", "", v); print tolower(v); exit}')"
[[ "$actual_fp" == "$KEY_FINGERPRINT" ]] || { echo 'Not the cloud signing key; refusing to sign.' >&2; exit 1; }

# The release gate (tools/release_bridge_gate.py): the tracked inputs frozen first; then both simulated Android
# versions (8 and 15; a warm daemon and the build cache make a rerun of already-tested code nearly instant) and lint;
# then nothing signs unless the inputs are unchanged, at least MIN_TESTS ran with no failure or skip, every adapter
# suite ran on both Android versions case by case, and lint found no error.
python3 tools/release_bridge_gate.py freeze --snapshot "$secrets/inputs.json"
./gradlew -q testDebugUnitTest -PallSdks
./gradlew -q lintDebug -PallSdks
python3 tools/release_bridge_gate.py verify --snapshot "$secrets/inputs.json"

export OFFER_FILTER_KEYSTORE="$secrets/signing.p12"
export OFFER_FILTER_KEY_ALIAS='offerfilter-cloud'
export OFFER_FILTER_KEYSTORE_PASS_SPEC='env:OFFER_FILTER_SIGNING_PASSWORD'
export OFFER_FILTER_KEY_PASS_SPEC='env:OFFER_FILTER_SIGNING_PASSWORD'
./build-local.sh
apk="app/build/outputs/apk/debug/app-debug.apk"
signer="$("$tools/apksigner" verify --print-certs "$apk" | awk -F': ' '/certificate SHA-256 digest/ {print $2; exit}')"
[[ "$signer" == "$KEY_FINGERPRINT" ]] || { echo "APK signer $signer is not the cloud key" >&2; exit 1; }

version="$(sed -n "s/.*versionName '\([^']*\)'.*/\1/p" app/build.gradle)"
mkdir -p dist
cp "$apk" "dist/OfferFilter-$version.apk"
echo "SIGNED dist/OfferFilter-$version.apk sha256=$(sha256sum "dist/OfferFilter-$version.apk" | cut -d' ' -f1) signer=$signer"
