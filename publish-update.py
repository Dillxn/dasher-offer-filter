#!/usr/bin/env python3
"""Publish a signed APK and then switch the permanent update feed to it."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import urllib.request

REPO = "Dillxn/dasher-offer-filter-updates"
SOURCE_REPO = "Dillxn/dasher-offer-filter"
SIGNER = "8da09c9765a17e18635d81c87bf94d9ed78f1260eec3009a89f808bb69f6a757"
ROOT = Path(__file__).resolve().parent


def run(*args, data=None, optional=False):
    result = subprocess.run(args, input=data, text=True, capture_output=True)
    if result.returncode and not optional:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip())
    return result


def get_content(name):
    result = run("gh", "api", f"repos/{REPO}/contents/{name}", optional=True)
    if result.returncode:
        if "404" in result.stderr or "404" in result.stdout:
            return None
        raise RuntimeError(result.stderr.strip())
    return json.loads(result.stdout)


def put_content(name, content, message):
    old = get_content(name)
    if old and base64.b64decode(old["content"]) == content:
        return
    payload = {"message": message, "branch": "main", "content": base64.b64encode(content).decode()}
    if old:
        payload["sha"] = old["sha"]
    run("gh", "api", "--method", "PUT", f"repos/{REPO}/contents/{name}",
        "--input", "-", data=json.dumps(payload))


def verify_source_matches(code, version):
    gradle = (ROOT / "app/build.gradle").read_text()
    code_match = re.search(r"versionCode\s+(\d+)", gradle)
    version_match = re.search(r"versionName\s+'([^']+)'", gradle)
    if not code_match or not version_match or int(code_match.group(1)) != code or version_match.group(1) != version:
        raise RuntimeError("APK version does not match app/build.gradle")
    tracked_changes = run("git", "status", "--porcelain", "--untracked-files=no").stdout.strip()
    if tracked_changes:
        raise RuntimeError("Tracked source files have uncommitted changes; commit them before publishing")
    local_head = run("git", "rev-parse", "HEAD").stdout.strip()
    remote_head = run("gh", "api", f"repos/{SOURCE_REPO}/commits/main", "--jq", ".sha").stdout.strip()
    if local_head != remote_head:
        raise RuntimeError("Local checkout is not the current source-repo main; pull before publishing")


def verify_release_asset(repository, tag, digest, size):
    release = run("gh", "release", "view", tag, "--repo", repository, "--json", "assets")
    assets = json.loads(release.stdout)["assets"]
    asset = next((item for item in assets if item["name"] == "OfferFilter.apk"), None)
    if not asset or asset.get("digest") != f"sha256:{digest}" or int(asset.get("size", -1)) != size:
        raise RuntimeError(f"Published release asset verification failed in {repository}")


def public_bytes(url, limit):
    request = urllib.request.Request(url, headers={
        "User-Agent": "OfferFilter-Publisher",
        "Cache-Control": "no-cache",
    })
    with urllib.request.urlopen(request, timeout=30) as response:
        data = response.read(limit + 1)
    if len(data) > limit:
        raise RuntimeError("Public verification download exceeded limit")
    return data


def verify_public_channel(feed, apk_bytes):
    expected_feed = json.dumps(feed, sort_keys=True)
    last_error = "public feed did not converge"
    for attempt in range(6):
        try:
            nonce = time.time_ns()
            raw = public_bytes(
                f"https://raw.githubusercontent.com/{REPO}/main/latest.json?t={nonce}",
                16 * 1024,
            )
            published = json.loads(raw)
            if json.dumps(published, sort_keys=True) != expected_feed:
                raise RuntimeError("public latest.json is still stale")
            public_apk = public_bytes(feed["apkUrl"] + f"?t={nonce}", len(apk_bytes))
            if len(public_apk) != len(apk_bytes) or hashlib.sha256(public_apk).hexdigest() != feed["sha256"]:
                raise RuntimeError("public APK does not match the update feed")
            return
        except Exception as error:
            last_error = str(error)
            if attempt < 5:
                time.sleep(2)
    raise RuntimeError(f"Public update channel verification failed: {last_error}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--notes-file", required=True, type=Path)
    parser.add_argument("--expected-signer", default=SIGNER)
    parser.add_argument("--lineage-file", type=Path)
    parser.add_argument("--required-ancestor")
    args = parser.parse_args()
    apk = args.apk.resolve()
    sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk"))
    tools = sdk / "build-tools/35.0.0"
    os.environ.setdefault("JAVA_HOME", "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home")
    signature = run(str(tools / "apksigner"), "verify", "--print-certs", str(apk)).stdout
    normalized_signature = signature.lower().replace(":", "")
    expected_signer = args.expected_signer.lower().replace(":", "")
    if expected_signer not in normalized_signature:
        raise RuntimeError("The APK does not contain the expected current signing certificate")
    if args.lineage_file:
        lineage = args.lineage_file.resolve()
        if not lineage.is_file():
            raise RuntimeError("Signing lineage file does not exist")
        lineage_text = run(str(tools / "apksigner"), "lineage", "--in", str(lineage),
                           "--print-certs", "-v").stdout.lower().replace(":", "")
        if expected_signer not in lineage_text:
            raise RuntimeError("Expected current signer is not present in the signing lineage")
        if args.required_ancestor:
            ancestor = args.required_ancestor.lower().replace(":", "")
            if ancestor not in lineage_text:
                raise RuntimeError("Required predecessor signer is not present in the signing lineage")
    badging = run(str(tools / "aapt2"), "dump", "badging", str(apk)).stdout
    match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    if not match or match[1] != "com.local.dasherfilter":
        raise RuntimeError("Unexpected APK package")
    package, code, version = match[1], int(match[2]), match[3]
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:-[a-zA-Z0-9.]+)?", version):
        raise RuntimeError("Invalid APK version")
    data = apk.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    verify_source_matches(code, version)
    old = get_content("latest.json")
    if old:
        latest = json.loads(base64.b64decode(old["content"]))
        if latest["versionCode"] > code or (latest["versionCode"] == code and latest["sha256"] != digest):
            raise RuntimeError("Increase versionCode before publishing changed or older APKs")
    if not get_content("README.md"):
        put_content("README.md", ("# Offer Filter updates\n\n"
            "Signed Android APK releases and the automatic update feed for Offer Filter.\n\n"
            "Download the latest APK from Releases. Offer Filter checks latest.json automatically.\n"
            "Enable Allow from this source inside Android settings once for self-updates.\n"
            "Android may still require installation confirmation. No GitHub sign-in is needed.\n"
            "Offer information and app settings are not uploaded here.\n").encode(), "Initialize update feed")
    tag = f"v{version}"
    destination = ROOT / "app/build/publish/OfferFilter.apk"
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(apk, destination)
    for repository in (REPO, SOURCE_REPO):
        release = run("gh", "release", "view", tag, "--repo", repository, "--json", "assets", optional=True)
        if release.returncode == 0:
            assets = json.loads(release.stdout)["assets"]
            existing = next((item for item in assets if item["name"] == "OfferFilter.apk"), None)
            if not existing or existing.get("digest") != f"sha256:{digest}":
                raise RuntimeError(f"Existing release in {repository} differs; bump the version")
        else:
            run("gh", "release", "create", tag, str(destination), "--repo", repository,
                "--target", "main", "--title", f"Offer Filter {version}",
                "--notes-file", str(args.notes_file.resolve()))
        verify_release_asset(repository, tag, digest, len(data))
    feed = {"packageName": package, "versionCode": code, "versionName": version,
            "apkUrl": f"https://github.com/{REPO}/releases/download/{tag}/OfferFilter.apk",
            "sha256": digest, "size": len(data)}
    put_content("latest.json", (json.dumps(feed, indent=2) + "\n").encode(), f"Publish {version} update feed")
    verify_public_channel(feed, data)
    print(json.dumps(feed, indent=2))
    print(f"Release: https://github.com/{SOURCE_REPO}/releases/tag/{tag}")
    print(f"Download: https://github.com/{REPO}/releases/latest/download/OfferFilter.apk")


if __name__ == "__main__":
    main()
