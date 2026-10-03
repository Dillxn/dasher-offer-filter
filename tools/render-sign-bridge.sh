#!/usr/bin/env bash
# One-time, pinned build using the original key already in Render. Only the signed
# APK leaves through build-log records. public/ remains the existing GitHub mirror.
set +x
set -euo pipefail
cd "$(dirname "$0")/.."
source_commit="${1:?Pass the exact reviewed source commit}"
expected_main="${2:?Pass the expected current main commit}"
main_check_mode="${3:-}"
[[ "$#" -le 3 && ( -z "$main_check_mode" || "$main_check_mode" == '--external-main-check' ) ]] || {
    echo 'Unknown signing bridge mode' >&2; exit 1;
}
[[ "$source_commit" =~ ^[a-f0-9]{40}$ && "$expected_main" =~ ^[a-f0-9]{40}$ ]] || exit 1
[[ "$(git rev-parse HEAD)" == "$source_commit" ]] || { echo 'Pinned source mismatch' >&2; exit 1; }
git diff --quiet HEAD -- || { echo 'Tracked source is dirty' >&2; exit 1; }
bridge_tmp="$(mktemp -d)"
trap 'rm -rf "$bridge_tmp"' EXIT
check_main() {
    local main_args=()
    [[ -z "$main_check_mode" ]] || main_args+=("$main_check_mode")
    python3 tools/release_bridge_gate.py check-main --source "$source_commit" \
        --expected-main "$expected_main" "${main_args[@]}"
}
check_main
python3 tools/release_bridge_gate.py freeze --snapshot "$bridge_tmp/inputs.json"

# Same SDK/bootstrap versions as tools/render-build-full.sh; no signing material is
# handled here. tools/sign-local.sh is still the sole session signing implementation.
ANDROID_CLI="commandlinetools-linux-15859902_latest.zip"
ANDROID_CLI_SHA="4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583"
export ANDROID_HOME="$PWD/.android-sdk"
if [[ -x "$PWD/.jdk/bin/javac" ]]; then
    export JAVA_HOME="$PWD/.jdk"
elif command -v javac >/dev/null 2>&1; then
    export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
else
    mkdir -p .jdk
    curl -fsSL 'https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse' -o "$bridge_tmp/jdk.tar.gz"
    tar -xzf "$bridge_tmp/jdk.tar.gz" -C .jdk --strip-components=1
    export JAVA_HOME="$PWD/.jdk"
fi
export PATH="$JAVA_HOME/bin:$PATH"
mkdir -p "$ANDROID_HOME/cmdline-tools"
if [[ ! -x "$ANDROID_HOME/build-tools/35.0.0/apksigner" || ! -f "$ANDROID_HOME/platforms/android-36/android.jar" ]]; then
    curl -fsSL "https://dl.google.com/android/repository/$ANDROID_CLI" -o "$bridge_tmp/$ANDROID_CLI"
    printf '%s  %s\n' "$ANDROID_CLI_SHA" "$bridge_tmp/$ANDROID_CLI" | sha256sum -c -
    rm -rf "$ANDROID_HOME/cmdline-tools/latest" "$ANDROID_HOME/cmdline-tools/cmdline-tools"
    python3 - "$bridge_tmp/$ANDROID_CLI" "$ANDROID_HOME/cmdline-tools" <<'PY'
import pathlib, sys, zipfile
with zipfile.ZipFile(pathlib.Path(sys.argv[1])) as archive:
    archive.extractall(pathlib.Path(sys.argv[2]))
PY
    mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
    chmod +x "$ANDROID_HOME/cmdline-tools/latest/bin/"*
    set +o pipefail
    yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" --licenses >/dev/null
    set -o pipefail
    "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" 'platforms;android-36' 'build-tools;35.0.0' 'platform-tools' >/dev/null
fi

python3 tools/verify_channel.py
# sign-local runs the required dual-SDK test gate once; lint reuses compiled inputs.
bash tools/sign-local.sh
./gradlew -q lintDebug -PallSdks
python3 tools/release_bridge_gate.py verify --snapshot "$bridge_tmp/inputs.json"
check_main
# This copies only the previous, already-published release/ APK, never dist/.
python3 tools/mirror_repo_feed.py
python3 tools/release_bridge_gate.py verify --snapshot "$bridge_tmp/inputs.json"
version="$(sed -n "s/.*versionName '\([^']*\)'.*/\1/p" app/build.gradle)"
code="$(sed -n 's/.*versionCode \([0-9][0-9]*\).*/\1/p' app/build.gradle)"
python3 tools/signed_apk_log_transfer.py emit --source "$source_commit" --version "$version" --code "$code" \
    --apk "dist/OfferFilter-$version.apk" --build-tools "$ANDROID_HOME/build-tools/35.0.0"
