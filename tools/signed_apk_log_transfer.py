#!/usr/bin/env python3
"""Transfer only an already signed APK through bounded build-log records.

This is an artifact transport, never a signing or release authority. The receiving
workspace must still run publish-repo-feed.py and its original-signer checks.
No key, environment value, build directory or arbitrary file is transported.
"""
import argparse
import base64
import hashlib
import json
import pathlib
import re
import subprocess
import sys

MARKER = "OFFER_FILTER_SIGNED_APK_V1"
SIGNER = "553994c4d1310bf92f236525d1d293df597f37be39a7fd34f8b58e68dda0c703"
PACKAGE = "com.local.dasherfilter"
MAX_BYTES = 20 * 1024 * 1024
CHUNK_BYTES = 1536
SHA = re.compile(r"[a-f0-9]{40}\Z")
DIGEST = re.compile(r"[a-f0-9]{64}\Z")
VERSION = re.compile(r"[0-9]+(?:\.[0-9]+){2}\Z")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def metadata(source, version, code, data):
    if not SHA.fullmatch(source) or not VERSION.fullmatch(version):
        raise ValueError("Invalid committed source or version")
    if type(code) is not int or code < 1 or not 0 < len(data) <= MAX_BYTES:
        raise ValueError("Invalid APK size or version code")
    if not data.startswith(b"PK\x03\x04"):
        raise ValueError("Not an APK ZIP container")
    return dict(sourceCommit=source, versionName=version, versionCode=code,
                packageName=PACKAGE, signer=SIGNER, sha256=digest(data),
                size=len(data), chunks=(len(data) + CHUNK_BYTES - 1) // CHUNK_BYTES)


def encode_records(meta, data):
    transfer = meta["sourceCommit"] + "-" + meta["sha256"]
    header = base64.b64encode(json.dumps(meta, sort_keys=True, separators=(",", ":")).encode()).decode()
    yield f"{MARKER} {transfer} META {header}"
    for i, offset in enumerate(range(0, len(data), CHUNK_BYTES)):
        chunk = data[offset:offset + CHUNK_BYTES]
        yield f"{MARKER} {transfer} DATA {i} {digest(chunk)} {base64.b64encode(chunk).decode()}"
    yield f"{MARKER} {transfer} END {meta['chunks']} {meta['sha256']}"


def decode_records(lines, source, version, code):
    if not SHA.fullmatch(source) or not VERSION.fullmatch(version):
        raise ValueError("Invalid expected source or version")
    meta, transfer, end, chunks = None, None, None, {}
    for raw in lines:
        if MARKER not in raw:
            continue
        if len(raw) > 8192 or raw.count(MARKER) != 1:
            raise ValueError("Oversized or ambiguous transfer record")
        fields = raw[raw.index(MARKER):].strip().split()
        if len(fields) < 4 or fields[0] != MARKER:
            raise ValueError("Malformed transfer record")
        expected_prefix = source + "-"
        if not fields[1].startswith(expected_prefix):
            continue  # Another pinned build in the requested log interval.
        if not DIGEST.fullmatch(fields[1][len(expected_prefix):]):
            raise ValueError("Malformed artifact identifier")
        if transfer is not None and fields[1] != transfer:
            raise ValueError("Conflicting artifacts for the same source")
        transfer = fields[1]
        kind = fields[2]
        if kind == "META" and len(fields) == 4:
            item = json.loads(base64.b64decode(fields[3], validate=True))
            if meta is not None:
                raise ValueError("Duplicate metadata")
            meta = item
        elif kind == "DATA" and len(fields) == 6:
            index = int(fields[3])
            if not 0 <= index < (MAX_BYTES + CHUNK_BYTES - 1) // CHUNK_BYTES:
                raise ValueError("Invalid chunk index")
            chunk = base64.b64decode(fields[5], validate=True)
            if not 0 < len(chunk) <= CHUNK_BYTES or digest(chunk) != fields[4]:
                raise ValueError("Damaged APK chunk")
            if index in chunks:
                raise ValueError("Duplicate APK chunk")
            chunks[index] = chunk
        elif kind == "END" and len(fields) == 5:
            item = (int(fields[3]), fields[4])
            if end is not None:
                raise ValueError("Duplicate completion receipt")
            end = item
        else:
            raise ValueError("Unknown or malformed transfer record")
    if not isinstance(meta, dict) or end is None:
        raise ValueError("Incomplete artifact receipt")
    for name in ("versionCode", "size", "chunks"):
        if type(meta.get(name)) is not int:
            raise ValueError("Invalid numeric metadata")
    count = meta.get("chunks")
    if type(count) is not int or not 0 < count <= (MAX_BYTES + CHUNK_BYTES - 1) // CHUNK_BYTES:
        raise ValueError("Invalid chunk count")
    if set(chunks) != set(range(count)) or end != (count, meta.get("sha256")):
        raise ValueError("Missing chunks or completion mismatch")
    data = b"".join(chunks[i] for i in range(count))
    expected = metadata(source, version, code, data)
    if meta != expected or transfer != source + "-" + expected["sha256"]:
        raise ValueError("APK does not match expected source/version/size/hash receipt")
    return data, meta


def verify_apk(apk, build_tools, version, code):
    certs = subprocess.run([str(build_tools / "apksigner"), "verify", "--min-sdk-version", "26",
                            "--print-certs", str(apk)], check=True, text=True, capture_output=True).stdout
    cert = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([a-f0-9]+)", certs)
    if cert != [SIGNER]:
        raise ValueError("APK is not solely signed by the original cloud signer")
    badging = subprocess.run([str(build_tools / "aapt2"), "dump", "badging", str(apk)],
                             check=True, text=True, capture_output=True).stdout
    expected = f"package: name='{PACKAGE}' versionCode='{code}' versionName='{version}'"
    if expected not in badging:
        raise ValueError("APK package/version does not match expected source")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("emit", "receive"):
        item = sub.add_parser(name)
        item.add_argument("--source", required=True)
        item.add_argument("--version", required=True)
        item.add_argument("--code", type=int, required=True)
    emitter = sub.choices["emit"]
    emitter.add_argument("--apk", type=pathlib.Path, required=True)
    emitter.add_argument("--build-tools", type=pathlib.Path, required=True)
    receiver = sub.choices["receive"]
    receiver.add_argument("--logs", type=pathlib.Path, required=True,
                          help="UTF-8 file containing raw log messages, one per line")
    receiver.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    if args.command == "emit":
        # Verification precedes ANY artifact output. Commands contain no secrets.
        verify_apk(args.apk, args.build_tools, args.version, args.code)
        data = args.apk.read_bytes()
        meta = metadata(args.source, args.version, args.code, data)
        for line in encode_records(meta, data):
            print(line, flush=True)
    else:
        with args.logs.open(encoding="utf-8") as stream:
            data, meta = decode_records(stream, args.source, args.version, args.code)
        # Never replace an existing artifact silently.
        with args.output.open("xb") as output:
            output.write(data)
        print("SIGNED_APK_TRANSPORT_RECEIVED " + json.dumps(meta, sort_keys=True))
        print("Run tools/publish-repo-feed.py for independent signer and release-policy verification.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        # Exception type only: subprocess diagnostics must never leak environment data.
        sys.exit("Signed APK transport refused: " + type(error).__name__)
