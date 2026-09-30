#!/usr/bin/env bash
# The steps of build-local.sh up to, not including, signing: resources link, Java compiles for API 26, the classes
# dex, and the APK aligns. Needs ANDROID_HOME with platforms;android-36 and build-tools;35.0.0. Never creates a key.
set -euo pipefail
tools="$ANDROID_HOME/build-tools/35.0.0"
platform="$ANDROID_HOME/platforms/android-36/android.jar"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/compiled" "$work/classes" "$work/dex"
version="$(sed -n "s/.*versionName '\([^']*\)'.*/\1/p" app/build.gradle)"
code="$(sed -n 's/.*versionCode \([0-9][0-9]*\).*/\1/p' app/build.gradle)"
"$tools/aapt2" compile --dir app/src/main/res -o "$work/compiled"
flat_files=(); while IFS= read -r -d '' file; do flat_files+=("$file"); done < <(find "$work/compiled" -type f -name '*.flat' -print0)
"$tools/aapt2" link -o "$work/unsigned.apk" --manifest app/src/main/AndroidManifest.xml -I "$platform" --min-sdk-version 26 --target-sdk-version 35 --version-code "$code" --version-name "$version" --auto-add-overlay -R "${flat_files[@]}"
sources=(); while IFS= read -r -d '' file; do sources+=("$file"); done < <(find app/src/main/java -type f -name '*.java' -print0)
javac -Xlint:-options -source 17 -target 17 -classpath "$platform" -d "$work/classes" "${sources[@]}"
classes=(); while IFS= read -r -d '' file; do classes+=("$file"); done < <(find "$work/classes" -type f -name '*.class' -print0)
"$tools/d8" --lib "$platform" --min-api 26 --output "$work/dex" "${classes[@]}"
(cd "$work/dex" && zip -q -j '../unsigned.apk' classes.dex)
"$tools/zipalign" -f 4 "$work/unsigned.apk" "$work/aligned.apk"
"$tools/zipalign" -c 4 "$work/aligned.apk"
echo "PACKAGE_OK $version ($code)"
