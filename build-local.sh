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
version="$(sed -n "s/.*versionName '\([^']*\)'.*/\1/p" app/build.gradle)"
code="$(sed -n 's/.*versionCode \([0-9][0-9]*\).*/\1/p' app/build.gradle)"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ && "$code" =~ ^[0-9]+$ ]] || { echo 'Invalid app version' >&2; exit 1; }
for command in "$tools/aapt2" "$tools/d8" "$tools/zipalign" "$tools/apksigner" "$JAVA_HOME/bin/javac"; do
    [[ -x "$command" ]] || { echo "Missing build tool: $command" >&2; exit 1; }
done
[[ -f "$platform" && -f "$key" ]] || { echo 'Missing SDK or signing key; no replacement key will be generated.' >&2; exit 1; }
rm -rf "$work"
mkdir -p "$work/compiled" "$work/generated" "$work/classes" "$work/dex" "$(dirname "$output")"
"$tools/aapt2" compile --dir app/src/main/res -o "$work/compiled"
flat_files=(); while IFS= read -r -d '' file; do flat_files+=("$file"); done < <(find "$work/compiled" -type f -name '*.flat' -print0)
"$tools/aapt2" link -o "$work/unsigned.apk" --java "$work/generated" --manifest app/src/main/AndroidManifest.xml -I "$platform" --min-sdk-version 26 --target-sdk-version 35 --version-code "$code" --version-name "$version" --auto-add-overlay -R "${flat_files[@]}"
sources=(); while IFS= read -r -d '' file; do sources+=("$file"); done < <(find app/src/main/java "$work/generated" -type f -name '*.java' -print0)
"$JAVA_HOME/bin/javac" -source 17 -target 17 -classpath "$platform" -d "$work/classes" "${sources[@]}"
classes=(); while IFS= read -r -d '' file; do classes+=("$file"); done < <(find "$work/classes" -type f -name '*.class' -print0)
"$tools/d8" --lib "$platform" --min-api 26 --output "$work/dex" "${classes[@]}"
(cd "$work/dex" && zip -q -j '../unsigned.apk' classes.dex)
"$tools/zipalign" -f 4 "$work/unsigned.apk" "$work/aligned.apk"
"$tools/apksigner" sign --ks "$key" --ks-pass "$key_store_pass" --key-pass "$key_pass" --ks-key-alias "$key_alias" --out "$output" "$work/aligned.apk"
"$tools/apksigner" verify --min-sdk-version 26 "$output"
echo "Built $output version=$version code=$code"
