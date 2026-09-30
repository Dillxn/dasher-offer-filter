#!/usr/bin/env bash
# Builds and signs the app in a Claude session with the same cloud key Render signs with, for the user to install
# by hand when Render cannot build. The key comes only from the environment (OFFER_FILTER_KEYSTORE_B64 and
# OFFER_FILTER_SIGNING_PASSWORD, set in the session's environment settings, never in chat or source). It is
# decoded into a private temporary directory that is removed on exit, and signing is refused unless its
# certificate is the one installed phones trust. Nothing is published: the APK lands in dist/.
set -euo pipefail
cd "$(dirname "$0")/.."
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

./gradlew --no-daemon -q testDebugUnitTest

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
