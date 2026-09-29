#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
sdk_root="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
tools="$sdk_root/build-tools/35.0.0"
platform="$sdk_root/platforms/android-36/android.jar"
work="app/build/local"
output="app/build/outputs/apk/debug/app-debug.apk"
key="${OFFER_FILTER_KEYSTORE:-$HOME/.android/debug.keystore}"
key_alias="${OFFER_FILTER_KEY_ALIAS:-androiddebugkey}"
key_store_pass="${OFFER_FILTER_KEYSTORE_PASS_SPEC:-pass:android}"
key_pass="${OFFER_FILTER_KEY_PASS_SPEC:-pass:android}"
lineage="${OFFER_FILTER_LINEAGE:-}"
old_key="${OFFER_FILTER_OLD_KEYSTORE:-$HOME/.android/debug.keystore}"
old_alias="${OFFER_FILTER_OLD_KEY_ALIAS:-androiddebugkey}"
old_store_pass="${OFFER_FILTER_OLD_KEYSTORE_PASS_SPEC:-pass:android}"
old_key_pass="${OFFER_FILTER_OLD_KEY_PASS_SPEC:-pass:android}"

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
    --version-code 9 --version-name 0.4.3 --auto-add-overlay \
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
    echo "Signing keystore is missing: $key" >&2
    echo "Refusing to create a replacement key because installed phones require signing continuity." >&2
    exit 1
fi
if [[ -n "$lineage" ]]; then
    if [[ ! -f "$lineage" || ! -f "$old_key" ]]; then
        echo "Rotated signing requires the lineage and predecessor keystore." >&2
        exit 1
    fi
    "$tools/apksigner" sign \
        --ks "$old_key" --ks-key-alias "$old_alias" \
        --ks-pass "$old_store_pass" --key-pass "$old_key_pass" \
        --next-signer \
        --ks "$key" --ks-key-alias "$key_alias" \
        --ks-pass "$key_store_pass" --key-pass "$key_pass" \
        --lineage "$lineage" \
        --out "$output" "$work/aligned.apk"
else
    "$tools/apksigner" sign --ks "$key" --ks-pass "$key_store_pass" \
        --key-pass "$key_pass" --ks-key-alias "$key_alias" \
        --out "$output" "$work/aligned.apk"
fi
"$tools/apksigner" verify --min-sdk-version 26 "$output"
echo "Built $output"
