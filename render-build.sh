#!/usr/bin/env bash
set -euo pipefail
trap 'code=$?; echo "BUILD FAILURE line=$LINENO exit=$code command=$BASH_COMMAND" >&2' ERR

cd "$(dirname "$0")"

ANDROID_CLI="commandlinetools-linux-15859902_latest.zip"
ANDROID_CLI_SHA="4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583"
ANDROID_CLI_URL="https://dl.google.com/android/repository/$ANDROID_CLI"
KEY_COMMIT="5e8f3ae4844503e7368cebbf135a365c0aad0435"
KEY_PATH="private-signing/offer-filter-reset.p12.b64"
KEY_FINGERPRINT="4065b43922fab40d01f3246456a1899bef33ef7f81f499e62c5d5ca917a3abf3"

: "${OFFER_FILTER_SIGNING_PASSWORD:?OFFER_FILTER_SIGNING_PASSWORD is required}"
: "${UPDATE_BASE_URL:?UPDATE_BASE_URL is required}"

export ANDROID_HOME="$PWD/.android-sdk"
if ! command -v java >/dev/null 2>&1 || ! command -v javac >/dev/null 2>&1; then
    echo "Installing Eclipse Temurin 17..."
    mkdir -p "$PWD/.jdk"
    curl -fsSL \
      "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse" \
      -o /tmp/temurin17.tar.gz
    tar -xzf /tmp/temurin17.tar.gz -C "$PWD/.jdk" --strip-components=1
    export JAVA_HOME="$PWD/.jdk"
    export PATH="$JAVA_HOME/bin:$PATH"
else
    JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
    export JAVA_HOME
fi
echo "JAVA_HOME=$JAVA_HOME"
java -version
javac -version

mkdir -p "$ANDROID_HOME/cmdline-tools" .signing public
if [[ ! -x "$ANDROID_HOME/build-tools/35.0.0/apksigner" ]]; then
    curl -fsSL "$ANDROID_CLI_URL" -o "/tmp/$ANDROID_CLI"
    printf '%s  %s\n' "$ANDROID_CLI_SHA" "/tmp/$ANDROID_CLI" | sha256sum -c -
    rm -rf "$ANDROID_HOME/cmdline-tools/latest" "$ANDROID_HOME/cmdline-tools/cmdline-tools"
    python3 - "/tmp/$ANDROID_CLI" "$ANDROID_HOME/cmdline-tools" <<'PY'
import pathlib, sys, zipfile
with zipfile.ZipFile(pathlib.Path(sys.argv[1])) as z:
    z.extractall(pathlib.Path(sys.argv[2]))
PY
    mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
    chmod +x "$ANDROID_HOME/cmdline-tools/latest/bin/"*
    echo "sdkmanager=$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
    "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --version
    set +o pipefail
    yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" --licenses >/dev/null
    license_status=$?
    set -o pipefail
    if [[ $license_status -ne 0 && $license_status -ne 141 ]]; then
        exit "$license_status"
    fi
    "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" \
        "platforms;android-36" "build-tools;35.0.0"
fi

git cat-file -e "$KEY_COMMIT:$KEY_PATH" 2>/dev/null || git fetch origin "$KEY_COMMIT" --depth=1
git show "$KEY_COMMIT:$KEY_PATH" | base64 --decode > .signing/OfferFilter-signing.p12
chmod 600 .signing/OfferFilter-signing.p12

actual_fp="$(keytool -list -v \
    -keystore .signing/OfferFilter-signing.p12 \
    -storepass "$OFFER_FILTER_SIGNING_PASSWORD" \
    -alias offerfilter-reset |
    awk -F'SHA256: ' '/SHA256: / {v=$2; gsub(":", "", v); print tolower(v); exit}')"
if [[ "$actual_fp" != "$KEY_FINGERPRINT" ]]; then
    echo "Signing certificate fingerprint mismatch." >&2
    exit 1
fi

./gradlew testDebugUnitTest

export OFFER_FILTER_KEYSTORE="$PWD/.signing/OfferFilter-signing.p12"
export OFFER_FILTER_KEY_ALIAS="offerfilter-reset"
export OFFER_FILTER_KEYSTORE_PASS_SPEC="env:OFFER_FILTER_SIGNING_PASSWORD"
export OFFER_FILTER_KEY_PASS_SPEC="env:OFFER_FILTER_SIGNING_PASSWORD"
./build-local.sh

APK="app/build/outputs/apk/debug/app-debug.apk"
VERSION="$(sed -n "s/.*versionName '\([^']*\)'.*/\1/p" app/build.gradle)"
CODE="$(sed -n 's/.*versionCode \([0-9][0-9]*\).*/\1/p' app/build.gradle)"
SHA="$(sha256sum "$APK" | awk '{print $1}')"
SIZE="$(wc -c < "$APK" | tr -d ' ')"

cp "$APK" public/OfferFilter.apk
cat > public/latest.json <<EOF
{
  "packageName": "com.local.dasherfilter",
  "versionCode": $CODE,
  "versionName": "$VERSION",
  "apkUrl": "$UPDATE_BASE_URL/OfferFilter.apk",
  "sha256": "$SHA",
  "size": $SIZE,
  "encoding": "raw"
}
EOF

{
    echo "versionName=$VERSION"
    echo "versionCode=$CODE"
    echo "sha256=$SHA"
    echo "size=$SIZE"
    "$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs "$APK"
} > public/signing-receipt.txt

cat > public/index.html <<EOF
<!doctype html>
<meta charset="utf-8">
<title>Offer Filter $VERSION</title>
<h1>Offer Filter $VERSION</h1>
<p>Signed Android build, versionCode $CODE.</p>
<p><a href="/OfferFilter.apk">Download OfferFilter.apk</a></p>
<p><a href="/signing-receipt.txt">Signing receipt</a></p>
EOF
