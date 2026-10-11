# Local release pipeline

Use `python3 tools/release.py` for future releases. It orchestrates the existing
split release, gate, signer, publisher and production updater probe. Application
behavior, signer identity, mandatory dual-SDK tests, lint and update checks are
unchanged. Run it in an existing compatible local Bash/Python 3.9+ workspace.
There is no SDK installer, new key, CI dispatch or Render build in this runner.

## Acceptance requires a published release

The pipeline improvement remains **OPEN until it delivers at least one actual
release through the existing automatic-update channel**. Tool tests, a merged
PR, a signed APK, offline acceptance, and historical recovery experiments are
intermediate evidence; none completes this acceptance criterion.

Acceptance requires a single, traceable release record containing:

1. The exact committed source, original passing dual-SDK/lint evidence, local
   build/signing results and canonical APK acceptance produced through this
   reusable workflow, with a complete recovery capsule durably preserved. Delete
   the disposable originating checkout/run, restore the capsule elsewhere and
   pass unchanged canonical acceptance before closing this criterion. A fresh
   candidate uses its own new evidence; missing reports from an older candidate
   do not block it.
2. The repository publication commit containing the exact accepted APK/feed pair,
   plus actual delivery of those finished bytes to the existing public update
   origin. Preserve the remote branch, publication commit and APK/feed hashes.
   A staged pair or prepared Git commit is insufficient.
3. A fresh successful production `UpdateTransport` download and verification
   matching that release's source, package, version, size, SHA-256 and original
   signing certificate. Save the live proof, public feed/APK URLs and verification
   timestamp with the release. The accepted, repository and public APK hashes
   must all match. Preserve the publication command's returned receipt explicitly;
   it is not currently included in the run's recovery capsule.

An existing preparation/signed return may be adopted only with its complete,
validated original source and evidence. Re-signing just to prove the workflow
is unnecessary. Adoption cannot replace missing historical reports.

The internal `accepted` phase means **artifact acceptance only**. Overall pipeline
acceptance includes publication and live verification. An old live release or the
historical 0.5.8 relocation test cannot satisfy the new delivery requirement.
If blocked, keep this criterion OPEN and record the concrete blocker and next
action. Preserve the current canonical release owner, original signer and local
execution rule; this criterion does not itself authorize hosted builds or weaken
verification. Physical-phone and pilot gates remain additional project criteria.

## First release and normal retries

Commit the entire candidate source and select the existing JDK/SDK through
`JAVA_HOME` and `ANDROID_HOME` (or PATH discovery). Start outside the checkout:

```bash
python3 tools/release.py doctor --phase build
python3 tools/release.py init --source /absolute/checkout --run /absolute/release-run \
  --archive-dir /absolute/durable-release-archive
python3 tools/release.py run --run /absolute/release-run \
  --notices /absolute/existing-sdk-notices --until accepted
```

The notices are the same SDK provenance files required by the existing split
release tool. The original signing environment must be available for the signing
phase. Do not put credentials in commands, source, the run directory or archives.
The signer checks the original certificate before signing and removes its private
temporary directory. Non-signing subprocesses receive no signing variables.

**Repeat the same `run` command after a failure.** Every already completed phase
is verified and reused. An unfinished phase uses a new disposable checkout;
partial APKs and receipts cannot masquerade as completion. Preparing a candidate
is one indivisible phase: tests, lint and unsigned packaging must all finish before
that phase can be reused. A new source commit needs a new run and fresh preparation.

| Phase | Work | What a retry reuses |
| --- | --- | --- |
| `prepared` | Freeze source; dual-SDK tests; lint; unsigned packaging | Exact source, raw JUnit/lint, command logs/receipts, bundle and provenance |
| `signed` | Original-key signing and signed-payload checks | Verified signed APK and signed receipt; no second key access |
| `accepted` | Restore original evidence; canonical package, signer, payload and alignment checks | Accepted APK and canonical acceptance receipt |
| `staged` | Existing publisher with fresh help-page/channel/policy checks | Atomic repository APK/feed pair |
| `verified` | Fresh production `UpdateTransport` probe | Historical proof only; live verification always runs again |

`status --run …` verifies the complete chain without building. It reports the
last live-check time separately from current live status. No phase claims a phone
installation or real Dasher-session verification.

## Recovery is part of every phase

After each completed phase, the runner writes `recovery.zip` and `checksum.json`
to the archive directory. It includes the full candidate source tree and exact Git
commit, actual reports/logs, frozen input hashes, preparation tools/notices and
completed release outputs. It excludes signing material, local Git configuration,
hooks, dependency caches, temporary attempts and failed-run diagnostics.

The default archive is a sibling `release-archives` directory. **On an ephemeral
worker, explicitly use an existing durable mounted store or transfer the archive
and its separately recorded SHA-256 before losing the worker.** A local path alone
does not establish durable storage; the tool does not silently upload data. Archive
failure does not invalidate a completed phase: the next invocation retries the
archive before doing further work.

```bash
python3 tools/release.py restore --capsule /received/recovery.zip \
  --sha256 INDEPENDENTLY_RECORDED_SHA256 --run /new/location/release-run
python3 tools/release.py run --run /new/location/release-run \
  --archive-dir /new/durable-release-archive --until accepted
```

Restore validates the archive without executing contained source. Explicit `run`
executes the pinned candidate tools. The restored source is deliberately shallow:
the exact candidate commit/tree is complete, while parent history and unrelated
historical APKs are omitted. It supports canonical acceptance, not arbitrary
history-dependent tests or pushing a reconstructed branch as full history.

Build, sign, signed-return import, offline acceptance and live verification have
different preflights. Import needs Java/Python only. Acceptance needs the pinned
SDK verification tools and Java, but no compiler, Gradle, platform SDK or signer.
Full preparation still needs the existing JDK, build-tools 35.0.0 and android-36
platform. No missing reports can be replaced with reconstructed test summaries.

For an older *complete* preparation, `adopt-prepared --run … --bundle … --anchor …
--report-root … --logs …` checks and preserves the original files. Then
`import-signed --run … --directory …` admits an existing signed return after its
certificate, payload and identity checks. Neither command invents evidence or
reruns signing. Missing original source/reports still require recovery by the
candidate's owner.

## Repository publication and live delivery

```bash
python3 tools/release.py run --run /absolute/release-run --until staged
python3 tools/release.py publish --run /absolute/release-run \
  --checkout /absolute/full-integration-checkout --expected-main EXACT_REMOTE_SHA
# Inspect the returned refs/offerfilter/publications/<sha> commit, then publish:
python3 tools/release.py publish --run /absolute/release-run \
  --checkout /absolute/full-integration-checkout --expected-main EXACT_REMOTE_SHA --push
```

The full integration branch must contain the frozen candidate source and descend
from expected main. New app/source changes after the candidate are refused.
Publication adds only the two staged release files, preserves dirty working trees,
disables hooks/filters, and uses a normal non-force push with `[skip ci] [skip render]`.
The expected SHA is checked against a fresh remote fetch before pushing; it is not
an atomic compare-and-swap lease. A divergent race fails without overwriting other
commits. If the exact pair is already on the remote, retry returns success without
publishing again. Existing configured Git authentication is reused privately.

This is repository publication, not delivery to phones. Render's existing static
mirror remains a separate operation; this runner does not authorize or trigger
provider builds. Do not use the archived cloud build/sign path or dispatch hosted
CI to bypass the local-execution rule. Once the authorized mirror serves the bytes:

```bash
python3 tools/release.py verify-live --run /absolute/release-run
```

Only a fresh production updater probe matching source/version/hash establishes
live-channel success. The existing website release-sync step follows that success.
`feed --run … --output /new/path` is an alternative atomic local handoff for the
two feed files; it does not claim publication.

## Resource profile and diagnosis

On a worker below 8 GiB, preparation defaults to one 768 MiB test fork, a 1 GiB
Gradle daemon and two Gradle workers. At 8 GiB or more, it uses two 1 GiB test forks
and a 1.5 GiB daemon. Explicit user Gradle/JVM settings are preserved and reported
as overrides without printing their values. Each resource setting is detected
independently: unrelated proxy or trust-store options do not suppress memory and
worker defaults. Existing Gradle user caches remain
reusable; cached output never replaces the canonical gate. These are bounded
defaults, not a measured build-time or memory guarantee.

`doctor --phase build|sign|import|accept|verify` diagnoses missing or conflicting
tools before that phase. Failed non-signing operations keep available logs under
`RUN/failures`; failed preparation also retains original JUnit and lint XML there,
explicitly labeled failed diagnostics. Retries preserve earlier failures. Failed
evidence is excluded from successful capsules. Signing
diagnostics remain private and are cleaned up. Run directories are locked against
concurrent writers. Changed evidence or an existing conflicting destination is
refused without overwrite; recover a valid archive or start a new candidate.

## Validation

Run `python3 -m unittest discover -s tools -p 'test_release_*.py'` locally.
This covers environment profiles, real Git relocation, archive integrity,
interrupted signing/acceptance, stale live proofs, publication races and retries.
Fixtures are expressly synthetic and are not Android release evidence.

The separate [recorded recovery experiment](release-pipeline-validation.json)
uses the actual 0.5.8 source, 161 original JUnit files (3,382 tests), original lint
and original signed APK. After deleting the disposable source and run, capsule
restore elsewhere passed unchanged canonical acceptance. No Android test rerun,
new APK build, key access, publication or physical-phone validation was performed.
This does not establish acceptance of the separate frozen 0.5.9 candidate.
