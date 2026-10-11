"""Bounded mirror propagation wait; never substitutes for channel verification."""
import math
from pathlib import Path
import re
import sys
import time

import release_capsule as capsule


class PropagationPending(ValueError):
    """The expected feed still serves the exact previously published APK."""


class ReadinessTimeout(ValueError):
    pass


def same_candidate(actual, expected):
    # apkUrl is rewritten by the mirror. The canonical metadata policy checks it.
    for field in ('packageName', 'sourceCommit', 'versionCode', 'versionName',
                  'sha256', 'size'):
        if (field not in actual or type(actual[field]) is not type(expected.get(field))
                or actual[field] != expected[field]):
            return False
    return actual.get('encoding', 'raw') == expected.get('encoding', 'raw') == 'raw'


def previous_apk_diagnostic(source, expected, log):
    """Recognize only the observed feed/new + APK/known-old propagation race.

    An arbitrary hash mismatch is an integrity failure, not permission to retry.
    Read the canonical probe's own files; no alternative HTTP request, cache
    bypass, reconstructed receipt, or trust in a provider's 'live' status.
    """
    try:
        if Path(log).read_text().splitlines()[-1:] != [
                'ValueError: Downloaded APK does not match feed']:
            return None
        observed_feed = capsule.read_json(source / '.channel-check/latest.json')
        if not same_candidate(observed_feed, expected):
            return None
        previous = capsule.read_json(source / 'release/latest.json')
        if (previous.get('packageName') != expected['packageName']
                or type(previous.get('versionCode')) is not int
                or not 0 < previous['versionCode'] < expected['versionCode']
                or not re.fullmatch('[0-9a-f]{40}', previous.get('sourceCommit', ''))
                or not re.fullmatch('[0-9a-f]{64}', previous.get('sha256', ''))
                or type(previous.get('size')) is not int or previous['size'] <= 0
                or previous.get('encoding', 'raw') != 'raw'):
            return None
        previous_info = dict(size=previous['size'], sha256=previous['sha256'])
        if (capsule.file_info(source / 'release/OfferFilter.apk') != previous_info
                or capsule.file_info(source / '.channel-check/OfferFilter.apk') != previous_info):
            return None
        return dict(category='KNOWN_PREVIOUS_APK', result='FAILED',
                    expectedSourceCommit=expected['sourceCommit'],
                    expectedSha256=expected['sha256'],
                    observedSourceCommit=previous['sourceCommit'], **previous_info)
    except (OSError, ValueError, TypeError, KeyError):
        # Missing/malformed diagnostics cannot establish a transient failure.
        return None


def wait_for_live(verify, *, wait_seconds=180, interval_seconds=15,
                  clock=time.monotonic, sleep=time.sleep, progress=None):
    """Retry only PropagationPending within a monotonic wall-clock budget.

    verify receives the absolute deadline and must enforce it before recording
    success, including a subprocess timeout recalculated after local preflight.
    Zero seconds explicitly retains a single attempt without readiness waiting.
    The canonical verifier alone can produce a PASS.
    """
    if (type(wait_seconds) not in (int, float) or not math.isfinite(wait_seconds)
            or not 0 <= wait_seconds <= 600):
        raise ValueError('Readiness wait must be between 0 and 600 seconds')
    if (type(interval_seconds) not in (int, float) or not math.isfinite(interval_seconds)
            or not 1 <= interval_seconds <= 60):
        raise ValueError('Readiness retry interval must be between 1 and 60 seconds')
    if progress is None:
        progress = lambda message: print(message, file=sys.stderr, flush=True)
    start = clock()
    deadline = start + wait_seconds
    # Also bound attempts independently of clock/sleep behavior.
    max_attempts = 1 + math.ceil(wait_seconds / interval_seconds)
    for number in range(1, max_attempts + 1):
        remaining = deadline - clock()
        if wait_seconds and remaining <= 0:
            break
        try:
            result = verify(deadline if wait_seconds else None)
        except PropagationPending:
            if not wait_seconds:
                raise
            remaining = deadline - clock()
            if remaining <= 0 or number == max_attempts:
                break
            delay = min(interval_seconds, remaining)
            progress('Waiting for the staged APK to reach the update mirror '
                     f'(attempt {number}; retry in {delay:g}s; '
                     f'at most {remaining:g}s remaining).')
            sleep(delay)
        else:
            elapsed = clock() - start
            return dict(result, readinessAttempts=number, readinessElapsedSeconds=round(elapsed, 3))
    raise ReadinessTimeout('Deployment readiness deadline expired; live channel was not verified. '
                           'Failed probe diagnostics are retained; no rebuild, signing or deploy was requested.')
