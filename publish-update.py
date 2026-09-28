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

REPO = "Dillxn/dasher-offer-filter-updates"
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
    payload = {"message": message, "branch": "main", "content": base64.b64encode(content).decode()}
    if old:
        payload["sha"] = old["sha"]
    run("gh", "api", "--method", "PUT", f"repos/{REPO}/contents/{name}",
        "--input", "-", data=json.dumps(payload))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--notes-file", required=True, type=Path)
    args = parser.parse_args()
    apk = args.apk.resolve()
    sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk"))
    tools = sdk / "build-tools/35.0.0"
    os.environ.setdefault("JAVA_HOME", "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home")
    signature = run(str(tools / "apksigner"), "verify", "--print-certs", str(apk)).stdout
    if f"certificate SHA-256 digest: {SIGNER}" not in signature:
        raise RuntimeError("The APK uses a different signing key; existing phones cannot update to it")
    badging = run(str(tools / "aapt2"), "dump", "badging", str(apk)).stdout
    match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    if not match or match[1] != "com.local.dasherfilter":
        raise RuntimeError("Unexpected APK package")
    package, code, version = match[1], int(match[2]), match[3]
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:-[a-zA-Z0-9.]+)?", version):
        raise RuntimeError("Invalid APK version")
    data = apk.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
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
    release = run("gh", "release", "view", tag, "--repo", REPO, "--json", "assets", optional=True)
    if release.returncode == 0:
        assets = json.loads(release.stdout)["assets"]
        existing = next((item for item in assets if item["name"] == "OfferFilter.apk"), None)
        if not existing or existing.get("digest") != f"sha256:{digest}":
            raise RuntimeError("Existing release differs; bump the version")
    else:
        destination = ROOT / "app/build/publish/OfferFilter.apk"
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(apk, destination)
        run("gh", "release", "create", tag, str(destination), "--repo", REPO,
            "--target", "main", "--title", f"Offer Filter {version}",
            "--notes-file", str(args.notes_file.resolve()))
    feed = {"packageName": package, "versionCode": code, "versionName": version,
            "apkUrl": f"https://github.com/{REPO}/releases/download/{tag}/OfferFilter.apk",
            "sha256": digest, "size": len(data)}
    put_content("latest.json", (json.dumps(feed, indent=2) + "\n").encode(), f"Publish {version} update feed")
    print(json.dumps(feed, indent=2))
    print(f"Download: https://github.com/{REPO}/releases/latest/download/OfferFilter.apk")


if __name__ == "__main__":
    main()
