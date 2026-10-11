#!/usr/bin/env python3
"""Offline, deny-by-default reducer for explicitly supplied Offer Filter logs.

This is standalone analysis tooling, not an app patch or acceptance gate.
It never uploads, connects to a device, reads another file, or emits input text.
"""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import sys

REFERENCE_COMMIT = "a778c418d17e0babcf94972d0c72071e2315efe6"
MAX_BYTES = 1024 * 1024
MAX_LINE_BYTES = 8192
MAX_EVENTS = 2000
MAX_I32 = 2**31 - 1
MAX_I64 = 2**63 - 1
COUNT = r"(?:0|[1-9][0-9]{0,18})"
# ReadLoad.ms emits plain digits below 10,000; otherwise canonical US groups.
DURATION = r"(?:[0-9]|[1-9][0-9]{1,3}|[1-9][0-9]{1,2}(?:,[0-9]{3})+|[1-9](?:,[0-9]{3}){2,})"
STAMP = r"[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3} (?:Z|[+-][0-9]{2}:[0-9]{2})"
LINE = re.compile(rf"(?P<time>{STAMP}) \[(?P<source>screen|peek)\] (?P<body>[^\r\n]+)")

PATTERNS = (
    ("read_load", "screen", re.compile(
        rf"read load: (?P<reads>{COUNT}) reads/min \(fact-free (?P<fact_free>{COUNT})\), "
        rf"nodes (?P<nodes>{COUNT}), slowest (?P<slowest_ms>{DURATION}) ms, "
        rf"median fetch (?P<median_fetch_ms>{DURATION}) ms, truncated (?P<truncated>{COUNT}), "
        rf"pruned map subtrees (?P<pruned_maps>{COUNT}), yields (?P<yields>{COUNT}), "
        rf"at once (?P<at_once>{COUNT})")),
    ("yield", "screen", re.compile(rf"yielding to Dasher: median fetch (?P<median_fetch_ms>{DURATION}) ms")),
    ("map_skip", "screen", re.compile(
        rf"map subtree skipped \((?P<children>{COUNT}) children\) on Dasher's "
        r"(?P<screen_kind>offer|navigation|delivery|waiting|unrecognised) screen")),
    ("pause", "screen", re.compile(r"not reading Dasher while paused \((?P<reason>auto-decline is off|no rule is set)\)")),
    ("resume", "screen", re.compile(r"reading Dasher again")),
    ("peek_cost", "peek", re.compile(
        rf"cost: (?P<reads>{COUNT}) reads, (?P<nodes>{COUNT}) nodes, "
        rf"(?P<remote_fetches>{COUNT}) remote fetches ≥2 ms, "
        rf"slowest (?P<slowest_ms>{COUNT}) ms, (?P<dasher_events>{COUNT}) Dasher events"
        r" car=(?P<car>yes|no|\?) lock=(?P<lock>yes|no|\?)")),
)
ENUMS = {"screen_kind", "reason", "car", "lock"}
LIMITATIONS = [
    "Log syntax matches a retained historical source contract; the installed build is unverified.",
    "Only allowlisted diagnostic records are retained; unrelated and malformed input is discarded.",
    "Read-load records are source-labelled reads/min counters, not an independently measured continuous rate.",
    "Median fetch is the rolling median of at most ten slowest-fetch samples, not a per-minute average.",
    "A load record can cover time before a pause; it does not prove reading occurred while paused.",
    "Yield and map-skip log lines are throttled; line counts are not complete occurrence counts.",
    "Missing records, truncation and masking cannot establish absence of behavior or a passed phone case.",
    "Relative timestamps are wall-clock offsets, not uptime, causal order or action authority.",
    "No request, route continuity, sound source, battery result, release or phone acceptance is inferred.",
]


class InputError(ValueError):
    """Only fixed error codes, never input-derived exception text."""


class PrivateArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        raise InputError("INVALID_ARGUMENTS")


def timestamp(value):
    # strptime accepts unusual offsets, so bound the lexical offset separately.
    offset = value.rsplit(" ", 1)[1]
    if offset != "Z" and (int(offset[1:3]) > 23 or int(offset[4:]) > 59):
        raise ValueError
    return dt.datetime.strptime(value, "%Y-%m-%d %H:%M:%S.%f %z")


def parse_record(line):
    match = LINE.fullmatch(line)
    if not match:
        return None
    try:
        at = timestamp(match["time"])
    except ValueError:
        return None
    for kind, source, pattern in PATTERNS:
        if source != match["source"]:
            continue
        found = pattern.fullmatch(match["body"])
        if not found:
            continue
        values = {}
        for name, value in found.groupdict().items():
            if name in ENUMS:
                values[name] = value.replace(" ", "_")
                continue
            try:
                number = int(value.replace(",", ""))
            except ValueError:
                return None
            limit = MAX_I64 if name.endswith("_ms") or name == "dasher_events" or (kind == "read_load" and name == "nodes") else MAX_I32
            if number < 0 or number > limit:
                return None
            values[name] = number
        if kind == "read_load" and any(values[name] > values["reads"] for name in ("fact_free", "truncated", "at_once", "yields")):
            return None
        return at, {"kind": kind, **values}
    return None


def reduce_bytes(data):
    if len(data) > MAX_BYTES:
        raise InputError("INPUT_TOO_LARGE")
    try:
        decoded = data.decode("utf-8")
    except UnicodeError:
        raise InputError("INPUT_NOT_UTF8") from None
    # Only LF is a record boundary. Other Unicode line separators are untrusted text.
    lines = decoded.split("\n")
    if lines and lines[-1] == "":
        lines.pop()
    events = []
    seen = set()
    ignored = duplicates = overlong = backsteps = 0
    origin = previous = None
    for line in lines:
        if line.endswith("\r"):
            line = line[:-1]  # Accept CRLF framing, never embedded CR.
        if len(line.encode("utf-8")) > MAX_LINE_BYTES:
            ignored += 1
            overlong += 1
            continue
        parsed = parse_record(line)
        if parsed is None:
            ignored += 1
            continue
        at, event = parsed
        key = (at, json.dumps(event, sort_keys=True))
        if key in seen:
            duplicates += 1
            continue
        if len(events) >= MAX_EVENTS:
            raise InputError("TOO_MANY_MATCHED_RECORDS")
        seen.add(key)
        origin = at if origin is None else origin
        if previous is not None and at < previous:
            backsteps += 1
        previous = at
        elapsed = at - origin
        event["wall_offset_ms"] = (elapsed.days * 86400 + elapsed.seconds) * 1000 + elapsed.microseconds // 1000
        events.append(event)
    counts = {kind: sum(event["kind"] == kind for event in events) for kind, _, _ in PATTERNS}
    loads = [event for event in events if event["kind"] == "read_load"]
    metrics = ("reads", "fact_free", "nodes", "slowest_ms", "median_fetch_ms", "truncated", "pruned_maps", "yields", "at_once")
    peaks = {name: max((event[name] for event in loads if event[name] is not None), default=None) for name in metrics}
    return {
        "schema_version": 1,
        "tool": "offerfilter-offline-diagnostic-reducer",
        "format_reference_commit": REFERENCE_COMMIT,
        "runtime_build_verified": False,
        "field_acceptance": "NOT_ASSESSED",
        "input_summary": {"bytes": len(data), "physical_lines": len(lines), "ignored_lines": ignored,
                          "overlong_lines": overlong, "duplicate_records_removed": duplicates},
        "coverage": "ALLOWLIST_RECORDS_ONLY" if events else "NO_ALLOWLIST_RECORDS",
        "record_counts": counts,
        "wall_clock_backsteps_in_input_order": backsteps,
        "read_load_record_peaks": peaks,
        "events": events,
        "limitations": LIMITATIONS,
    }


def main(argv=None):
    parser = PrivateArgumentParser(prog="reduce_diagnostics.py", description=__doc__)
    parser.add_argument("input", type=Path, help="One already-consented exported text log; '-' is not stdin")
    parser.add_argument("--output", type=Path, help="Create a new JSON file; existing output is never overwritten")
    try:
        args = parser.parse_args(argv)
        with args.input.open("rb") as source:
            data = source.read(MAX_BYTES + 1)
        result = reduce_bytes(data)
        output = json.dumps(result, ensure_ascii=True, indent=2, sort_keys=True) + "\n"
        if args.output is None:
            sys.stdout.write(output)
        else:
            descriptor = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "w", encoding="utf-8") as target:
                target.write(output)
        return 0
    except InputError as error:
        print(json.dumps({"error": str(error)}), file=sys.stderr)
    except FileExistsError:
        print('{"error":"OUTPUT_EXISTS"}', file=sys.stderr)
    except OSError:
        print('{"error":"FILE_IO_ERROR"}', file=sys.stderr)
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
