# Original-key release bridge

This producer/receiver avoids moving Render's original signing key into a transient
workspace. It uses the existing Render static-site build and its build-log API.
It does not require a GitHub write token in Render or any additional service.

## Review and execution

1. Commit the reviewed application, these tools and its release notes on the
   candidate branch. Freeze that exact SHA and fresh main SHA. Preserve main's
   existing `release/` files in the candidate; do not alter either feed yet.
2. In the existing Render service, record the original branch/build command and
   deploy the pinned source using `bash tools/render-sign-bridge.sh SOURCE MAIN`.
   Both arguments must be full 40-character SHAs, not moving branch names. The
   actual checkout HEAD and current remote main must match them.
3. The build runs the existing `tools/sign-local.sh` with Render's original signer,
   including the dual-SDK tests once, then Android lint. It hashes every tracked
   file before/after. It refuses untracked source inputs. It verifies the signed
   APK's single signer, package and embedded version before emitting anything.
4. The build retains the existing GitHub `release/` APK in `public/`. Only the new
   signed APK is encoded into tagged bounded log records; keys/environment data
   are never emitted. The final public deployment still serves the prior release.
5. Query Render build logs for this service and this deployment's time window,
   filtering text `OFFER_FILTER_SIGNED_APK_V1`. Paginate to completion. Deduplicate
   tool pagination overlap by Render log ID, not by content. Save the raw `message`
   strings as a UTF-8 file, one message per line. Do not print the binary records
   in chat. A missing/truncated/damaged/duplicate record fails reconstruction.
6. In a checkout of the exact source SHA, reconstruct the signed public artifact:

   ```sh
   python3 tools/signed_apk_log_transfer.py receive --source SOURCE \
     --version VERSION --code CODE --logs /tmp/offer-filter-build-messages.txt \
     --output dist/OfferFilter-VERSION.apk
   python3 tools/publish-repo-feed.py
   ```

   `dist/` must exist and the output file must not. The publisher independently
   verifies the cryptographic signer, package/version, fresh live channel and
   production update policy. The transport receipt does not substitute for it.
7. Commit the generated `release/` files alone, after the application source commit.
   Recheck remote main; merge/push the complete source/release pair without force
   only while concurrent work remains accounted for. Restore the original Render
   mirror command (`bash render-build.sh`) and main branch, deploy the published
   release and run `tools/verify_channel.py` against the new live bytes.

## Limits and failure handling

- Do not reuse these build records across source SHAs, versions or signer changes.
- On a build/log-transfer failure, the live old feed remains intact. Restore the
  mirror command/branch; never substitute a different key or publish partial data.
- This transfers an APK that is intended for public distribution, not private
  diagnostics. It adds no payment credentials or signing material to build logs.
- Render log retention/line delivery is an external constraint. Missing records
  are a failed transport, never permission to weaken validation. The current
  roughly 0.4 MB APK needs roughly 270 records under 2.3 KB each.
- The helper gate is tested with synthetic transport bytes and synthetic reports.
  No real signing, Render build, publication or phone install has been verified by
  those tests. Existing platform/SDK bootstrap comes from the retired full builder.
