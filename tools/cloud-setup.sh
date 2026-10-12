#!/usr/bin/env bash
# Readies a fresh Claude cloud container to build, test and sign Offer Filter, from the steps that worked for the
# 0.6.1 to 0.6.3 releases: the Android SDK's command-line tools from dl.google.com, build-tools 35.0.0 and
# platform android-36 (licenses accepted), ANDROID_HOME, then a Gradle warm-up that fetches the dependencies and
# Robolectric's Android 15 and 16 jars one at a time (Maven Central answers parallel first fetches with 429s, and two
# JVMs fetching the same android-all jar at once race). Safe to run again: each step skips what is already there.
# Run it from the environment's setup script: bash tools/cloud-setup.sh (from a full clone, not a shallow one).
set -euo pipefail
cd "$(dirname "$0")/.."

SDK=${ANDROID_HOME:-/opt/android-sdk}
TOOLS_ZIP=commandlinetools-linux-13114758_latest.zip

if [[ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]]; then
    tmp=$(mktemp -d)
    curl -fsSL --retry 4 -o "$tmp/tools.zip" "https://dl.google.com/android/repository/$TOOLS_ZIP"
    unzip -q "$tmp/tools.zip" -d "$tmp"
    mkdir -p "$SDK/cmdline-tools"
    rm -rf "$SDK/cmdline-tools/latest"
    mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
    rm -rf "$tmp"
fi

if [[ ! -d "$SDK/build-tools/35.0.0" || ! -d "$SDK/platforms/android-36" ]]; then
    yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses > /dev/null || true
    "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" "build-tools;35.0.0" "platforms;android-36" \
        "platform-tools" > /dev/null
fi

# Every later shell (and Gradle) finds the SDK.
if ! grep -qs "ANDROID_HOME=$SDK" "$HOME/.bashrc"; then
    echo "export ANDROID_HOME=$SDK" >> "$HOME/.bashrc"
fi
echo "sdk.dir=$SDK" > local.properties
export ANDROID_HOME="$SDK"

# The warm-up: one worker, so first fetches go one at a time; lint pulls the build's dependencies, and one quick test
# class per SDK pulls Robolectric's android-all jars for Android 15 and 16. Failures here only mean a slower first gate.
./gradlew -q --max-workers=1 lintDebug -PallSdks > /dev/null 2>&1 || echo 'cloud-setup: lint warm-up incomplete'
./gradlew -q --max-workers=1 testDebugUnitTest -PallSdks -Pforks=1 \
    --tests com.local.dasherfilter.ConsentGateTest > /dev/null 2>&1 || echo 'cloud-setup: test warm-up incomplete'
echo "cloud-setup: Android SDK at $SDK (build-tools 35.0.0, android-36); Gradle warmed"
