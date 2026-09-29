#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

cd "$(dirname "$0")"

sdk_root="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
apksigner="$sdk_root/build-tools/35.0.0/apksigner"
keytool="$JAVA_HOME/bin/keytool"

old_key="${OFFER_FILTER_OLD_KEYSTORE:-$HOME/.android/debug.keystore}"
old_alias="${OFFER_FILTER_OLD_KEY_ALIAS:-androiddebugkey}"
old_store_pass="${OFFER_FILTER_OLD_KEYSTORE_PASSWORD:-android}"
old_key_pass="${OFFER_FILTER_OLD_KEY_PASSWORD:-android}"
expected_old="8da09c9765a17e18635d81c87bf94d9ed78f1260eec3009a89f808bb69f6a757"

new_key="${OFFER_FILTER_NEW_KEYSTORE:-$HOME/.android/offer-filter-v2.jks}"
new_alias="${OFFER_FILTER_NEW_KEY_ALIAS:-offerfilter-v2}"
new_pass_file="${OFFER_FILTER_NEW_KEY_PASSWORD_FILE:-$HOME/.android/offer-filter-v2.pass}"
lineage="${OFFER_FILTER_LINEAGE_FILE:-$HOME/.android/offer-filter-lineage.bin}"
config="${OFFER_FILTER_ROTATION_CONFIG:-$HOME/.android/offer-filter-rotation.env}"

for command in "$apksigner" "$keytool" python3; do
    if ! command -v "$command" >/dev/null 2>&1 && [[ ! -x "$command" ]]; then
        echo "Missing required tool: $command" >&2
        exit 1
    fi
done

if [[ ! -f "$old_key" ]]; then
    echo "Original signing keystore not found: $old_key" >&2
    echo "An in-place key rotation is impossible without the predecessor private key." >&2
    exit 1
fi

fingerprint() {
    "$keytool" -list -v -keystore "$1" -alias "$2" -storepass "$3" 2>/dev/null |
        awk -F'SHA256: ' '/SHA256: / { value=$2; gsub(":", "", value); print tolower(value); exit }'
}

old_fp="$(fingerprint "$old_key" "$old_alias" "$old_store_pass")"
if [[ "$old_fp" != "$expected_old" ]]; then
    echo "Original keystore fingerprint mismatch." >&2
    echo "Expected: $expected_old" >&2
    echo "Found:    ${old_fp:-unreadable}" >&2
    exit 1
fi

mkdir -p "$(dirname "$new_key")"
umask 077
if [[ ! -f "$new_key" ]]; then
    if [[ ! -f "$new_pass_file" ]]; then
        python3 - <<'PY' > "$new_pass_file"
import secrets
print(secrets.token_urlsafe(48))
PY
    fi
    new_pass="$(cat "$new_pass_file")"
    "$keytool" -genkeypair -keystore "$new_key" -storetype PKCS12         -storepass "$new_pass" -keypass "$new_pass" -alias "$new_alias"         -keyalg RSA -keysize 4096 -validity 10000         -dname 'CN=Offer Filter,O=Local,C=US'
else
    if [[ ! -f "$new_pass_file" ]]; then
        echo "New keystore exists but its password file is missing: $new_pass_file" >&2
        exit 1
    fi
    new_pass="$(cat "$new_pass_file")"
fi

new_fp="$(fingerprint "$new_key" "$new_alias" "$new_pass")"
if [[ -z "$new_fp" || "$new_fp" == "$old_fp" ]]; then
    echo "Could not establish a distinct new signing certificate." >&2
    exit 1
fi

if [[ ! -f "$lineage" ]]; then
    "$apksigner" rotate --out "$lineage"         --old-signer         --ks "$old_key" --ks-key-alias "$old_alias"         --ks-pass "pass:$old_store_pass" --key-pass "pass:$old_key_pass"         --set-installed-data true         --set-shared-uid true         --set-permission true         --set-rollback false         --set-auth true         --new-signer         --ks "$new_key" --ks-key-alias "$new_alias"         --ks-pass "file:$new_pass_file" --key-pass "file:$new_pass_file"
fi

lineage_text="$("$apksigner" lineage --in "$lineage" --print-certs -v)"
normalized_lineage="$(printf '%s' "$lineage_text" | tr '[:upper:]' '[:lower:]' | tr -d ':')"
if [[ "$normalized_lineage" != *"$old_fp"* || "$normalized_lineage" != *"$new_fp"* ]]; then
    echo "Signing lineage does not contain both predecessor and new certificates." >&2
    exit 1
fi

cat > "$config" <<EOF
export OFFER_FILTER_OLD_KEYSTORE='$old_key'
export OFFER_FILTER_OLD_KEY_ALIAS='$old_alias'
export OFFER_FILTER_OLD_KEYSTORE_PASS_SPEC='pass:$old_store_pass'
export OFFER_FILTER_OLD_KEY_PASS_SPEC='pass:$old_key_pass'
export OFFER_FILTER_KEYSTORE='$new_key'
export OFFER_FILTER_KEY_ALIAS='$new_alias'
export OFFER_FILTER_KEYSTORE_PASS_SPEC='file:$new_pass_file'
export OFFER_FILTER_KEY_PASS_SPEC='file:$new_pass_file'
export OFFER_FILTER_LINEAGE='$lineage'
export OFFER_FILTER_OLD_SIGNER_FINGERPRINT='$old_fp'
export OFFER_FILTER_NEW_SIGNER_FINGERPRINT='$new_fp'
EOF
chmod 600 "$config"

echo "Prepared Offer Filter signing-key rotation."
echo "Old signer: $old_fp"
echo "New signer: $new_fp"
echo "Lineage: $lineage"
echo "Config: $config"
echo
echo "Do not publish the rotated signer until version 0.4.1 has been installed on the phone."
