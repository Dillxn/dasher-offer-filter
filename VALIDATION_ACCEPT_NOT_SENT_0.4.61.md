# Offer Filter 0.4.61 — automatic Accept not sent

Source `8c357f11973a08e9f5b81b3d2657f050274405c9`; release commit `277a5636f35e19bf5ff1d58e6cd978274ad4b206`.

Diagnostics #42 from .59 showed a qualifying offer's automatic acceptance canceled at the final guard before any Android click. Its history remained PASSED, not ACCEPTED. The specific old refusal predicate was not recorded. This is separate from the post-request false-success cases corrected in .60.

The correction records `AUTO_ACCEPT_NOT_SENT` / left-to-user for a canceled armed candidate, with a fixed category from the existing check. Genuine later manual acceptance still wins. Candidate replacement closes the old history entry before recording the new one, including identical figures with a fresh countdown. Diagnostic failures cannot prevent cancellation cleanup. Guard order, platform-query short circuits, quiet interval, one-click budget, consent and default-off opt-in remain. No retry, Back, automatic training or new raw diagnostic collection was added.

Three tests fail on original .60 with expected YOURS but actual PASSED. All 64 focused tests pass after the correction. The complete local gate passed **1,959 tests / 107 suites**, zero failures/errors/skips, with Robolectric API 26 and 35 adapters. Lint: zero errors and 34 warnings. All 304 frozen inputs remained unchanged, aggregate `329830fd2a3952d6cb5b311235e58096a7dae194ccd270fb15e132cc1b440d9f`. The first full attempt was incomplete because its Gradle daemon disappeared; the full clean rerun with two workers and a 1536 MiB daemon passed. No failed assertion was ignored.

Built, packaged and signed locally through `tools/sign-local.sh`. APK: 435,357 bytes, SHA-256 `d536d43b5a4e9268bd2947c9097f99f862250eb93f288830b89ffeef72cd0f09`; original certificate `553994c4d1310bf92f236525d1d293df597f37be39a7fd34f8b58e68dda0c703`. `tools/publish-repo-feed.py` freshly verified the preceding live .60 with production transport, then validated this exact source/version/artifact and wrote only release files. Source and release were nonforce-published together. Temporary signing exports were removed. No Android build/sign task ran on Render or GitHub.

Latest diagnostics audit through October 3, 21:41:26 UTC found #43, .59 report `7a580bc051c02010e4bef51209a3b397`: one item was observed but pay was unavailable, so REVIEW is appropriate. The rejected unknown-screen text was not retained and provides no evidence for a speculative parser fix. Recent history repeats earlier #40/#41/#42 evidence; it is not another independent incident for each historical offer. No new Gmail copies. No raw report body was committed or report/email sent/deleted.

These tests and release checks do not establish a physical handset install, automatic acceptance on Dasher, native initial-ring suppression or resolution of the historical .56 in-app map symptom. #38 Peek and the specific #42 guard cause remain field questions. Consent 11 remains unchanged from .60; its new notice still needs acknowledgement by installs coming from .59 or earlier.

## Live publication

Both automatic-update feeds now publish .61/code67. Render mirror `dep-db0nhp6gekts73ag6h00` became live at 21:52:04 UTC. A fresh production `UpdateTransport.java` / `UpdatePolicy.java` probe downloaded the live APK and verified the exact package/version, bytes/hash and original certificate. GitHub manifest/blob match the locally signed artifact.

The first mirror attempt failed to authenticate its private-repository clone. The existing Render account still listed access; one normal retry succeeded without changing credentials, permissions or billing. Immediately after deploy, the verifier rejected a .61 manifest paired with a still-cached .60 APK. The next unchanged production check passed after propagation. Neither failure was bypassed or reported as success. See `validation/0.4.61-live-publication.json`.
