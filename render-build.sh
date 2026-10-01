#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
trap 'rm -rf .signing' EXIT
trap 'code=$?; echo "BUILD FAILURE exit=$code" >&2' ERR
ANDROID_CLI="commandlinetools-linux-15859902_latest.zip"
ANDROID_CLI_SHA="4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583"
KEY_FINGERPRINT="553994c4d1310bf92f236525d1d293df597f37be39a7fd34f8b58e68dda0c703"
: "${OFFER_FILTER_SIGNING_PASSWORD:?Signing password not configured}"
: "${OFFER_FILTER_KEYSTORE_B64:?Signing key not configured}"
export ANDROID_HOME="$PWD/.android-sdk"
if [[ -x "$PWD/.jdk/bin/javac" ]]; then
    export JAVA_HOME="$PWD/.jdk"
elif command -v javac >/dev/null 2>&1; then
    export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
else
    mkdir -p .jdk
    curl -fsSL 'https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse' -o /tmp/offer-filter-jdk.tar.gz
    tar -xzf /tmp/offer-filter-jdk.tar.gz -C .jdk --strip-components=1
    export JAVA_HOME="$PWD/.jdk"
fi
export PATH="$JAVA_HOME/bin:$PATH"
java -version
mkdir -p "$ANDROID_HOME/cmdline-tools"
if [[ ! -x "$ANDROID_HOME/build-tools/35.0.0/apksigner" || ! -f "$ANDROID_HOME/platforms/android-36/android.jar" ]]; then
    curl -fsSL "https://dl.google.com/android/repository/$ANDROID_CLI" -o "/tmp/$ANDROID_CLI"
    printf '%s  %s\n' "$ANDROID_CLI_SHA" "/tmp/$ANDROID_CLI" | sha256sum -c -
    rm -rf "$ANDROID_HOME/cmdline-tools/latest" "$ANDROID_HOME/cmdline-tools/cmdline-tools"
    python3 - "/tmp/$ANDROID_CLI" "$ANDROID_HOME/cmdline-tools" <<'PY'
import pathlib, sys, zipfile
with zipfile.ZipFile(pathlib.Path(sys.argv[1])) as z:
    z.extractall(pathlib.Path(sys.argv[2]))
PY
    mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
    chmod +x "$ANDROID_HOME/cmdline-tools/latest/bin/"*
    set +o pipefail
    yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" --licenses >/dev/null
    set -o pipefail
    "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" 'platforms;android-36' 'build-tools;35.0.0' 'platform-tools' >/dev/null
fi
# This calls the exact HTTP implementation used by the app, not a different downloader.
python3 tools/verify_channel.py
./gradlew --no-daemon testDebugUnitTest -PallSdks
umask 077
mkdir -p .signing
printf '%s' "$OFFER_FILTER_KEYSTORE_B64" | base64 --decode > .signing/OfferFilter-signing.p12
actual_fp="$(keytool -list -v -keystore .signing/OfferFilter-signing.p12 -storepass:env OFFER_FILTER_SIGNING_PASSWORD -alias offerfilter-cloud | awk -F'SHA256: ' '/SHA256: / {v=$2; gsub(":", "", v); print tolower(v); exit}')"
[[ "$actual_fp" == "$KEY_FINGERPRINT" ]] || { echo 'Signing certificate mismatch; publication refused.' >&2; exit 1; }
export OFFER_FILTER_KEYSTORE="$PWD/.signing/OfferFilter-signing.p12"
export OFFER_FILTER_KEY_ALIAS='offerfilter-cloud'
export OFFER_FILTER_KEYSTORE_PASS_SPEC='env:OFFER_FILTER_SIGNING_PASSWORD'
export OFFER_FILTER_KEY_PASS_SPEC='env:OFFER_FILTER_SIGNING_PASSWORD'
./build-local.sh
python3 tools/finalize_release.py
