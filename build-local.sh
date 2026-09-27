#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
sdk_root="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
tools="$sdk_root/build-tools/35.0.0"
platform="$sdk_root/platforms/android-36/android.jar"
work="app/build/local"
output="app/build/outputs/apk/debug/app-debug.apk"
key="$HOME/.android/debug.keystore"

for command in "$tools/aapt2" "$tools/d8" "$tools/zipalign" "$tools/apksigner" "$JAVA_HOME/bin/javac"; do
    if [[ ! -x "$command" ]]; then
        echo "Missing build tool: $command" >&2
        exit 1
    fi
done
if [[ ! -f "$platform" ]]; then
    echo "Missing Android SDK Platform 36: $platform" >&2
    exit 1
fi

rm -rf "$work"
mkdir -p "$work/compiled" "$work/classes" "$work/dex" "$(dirname "$output")"
"$tools/aapt2" compile --dir app/src/main/res -o "$work/compiled"

flat_files=()
while IFS= read -r -d '' file; do flat_files+=("$file"); done \
    < <(find "$work/compiled" -type f -name '*.flat' -print0)
"$tools/aapt2" link -o "$work/unsigned.apk" \
    --manifest app/src/main/AndroidManifest.xml \
    -I "$platform" --min-sdk-version 26 --target-sdk-version 35 \
    --version-code 1 --version-name 0.1.0 --auto-add-overlay \
    -R "${flat_files[@]}"

sources=()
while IFS= read -r -d '' file; do sources+=("$file"); done \
    < <(find app/src/main/java -type f -name '*.java' -print0)
"$JAVA_HOME/bin/javac" -source 17 -target 17 -classpath "$platform" \
    -d "$work/classes" "${sources[@]}"

classes=()
while IFS= read -r -d '' file; do classes+=("$file"); done \
    < <(find "$work/classes" -type f -name '*.class' -print0)
"$tools/d8" --lib "$platform" --min-api 26 --output "$work/dex" "${classes[@]}"
(cd "$work/dex" && zip -q -j "../unsigned.apk" classes.dex)
"$tools/zipalign" -f 4 "$work/unsigned.apk" "$work/aligned.apk"

if [[ ! -f "$key" ]]; then
    mkdir -p "$(dirname "$key")"
    "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$key" \
        -storepass android -keypass android -alias androiddebugkey \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname 'CN=Android Debug,O=Android,C=US' >/dev/null
fi
"$tools/apksigner" sign --ks "$key" --ks-pass pass:android \
    --key-pass pass:android --ks-key-alias androiddebugkey \
    --out "$output" "$work/aligned.apk"
"$tools/apksigner" verify --min-sdk-version 26 "$output"
echo "Built $output"
