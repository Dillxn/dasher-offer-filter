# Local build, signing and publication

The latest user instruction requires all Android building, testing and signing in
the current local execution workspace. Do not use Render or GitHub Actions for
those operations. Render remains the distribution mirror on `main` with
`bash render-build.sh`; it copies already-signed GitHub release files only.

The existing Render Dashboard's **Export → Download .env** transfer succeeded and
the private environment file reached the local workspace. This establishes
transfer only; it is not evidence of a signed APK, a published update or a phone
installation. Load only the required original-signer values privately for
`tools/sign-local.sh`. Never print or commit the file or its values, generate a
replacement key, or weaken the original-certificate check.

1. Freeze and commit the complete candidate source; recheck current GitHub main.
2. Run the meaningful local API 26/35/36 tests and lint against immutable inputs.
   `tools/sign-local.sh` runs the API 26/35/36 gate and reuses valid unchanged Gradle
   results, then signs with the original key into `dist/`.
3. Run `tools/publish-repo-feed.py` from that exact committed checkout. Preserve its
   signer, package/version, size/hash, live-channel and production updater checks.
   Local JVM probes use the current standard HTTP(S)_PROXY and NO_PROXY values
   through `tools/runtime_http_proxy.py`, preserving TLS/trust-store settings.
   Do not hardcode old proxy ports or mistake stale Java routing for denied access.
4. Commit the generated `release/` files separately and publish the complete
   source/release pair without force, preserving concurrent main changes.
5. Let the normal Render mirror copy the signed feed and verify the exact live APK
   using `tools/verify_channel.py`. Do not claim phone installation or real Dasher
   behavior from build, simulated tests or channel verification.

## Archived remote bridge — requires a new explicit exception

The procedure below is retained as historical engineering work, not the routine
release path. It must not run unless the user explicitly changes the local-only
instruction. Its tests and the publisher/identity/signer tests remain useful and
must stay intact. A remote attempt failed in about 25 seconds before producing an
APK because Render's checkout had no `origin`; the old live feed remained intact.
Render has been restored to `main`, `bash render-build.sh`, and auto-deploy off.

### Historical original-key bridge

This producer/receiver avoids moving Render's original signing key into a transient
workspace. It uses the existing Render static-site build and its build-log API.
It does not require a GitHub write token in Render or any additional service.

### Archived review and execution

1. Commit the reviewed application, these tools and its release notes on the
   candidate branch. Freeze that exact SHA and fresh main SHA. Preserve main's
   existing `release/` files in the candidate; do not alter either feed yet.
2. In the existing Render service, record the original branch/build command and
   deploy the pinned source using `bash tools/render-sign-bridge.sh SOURCE MAIN`.
   Both arguments must be full 40-character SHAs, not moving branch names. The
   actual checkout HEAD and current remote main must match them. Set Render's
   branch to the candidate branch and pin the deployment commit; do not prepend
   `git fetch origin` because Render may remove the checkout's origin remote.

   For a confirmed originless Render checkout only, the caller must freshly read
   GitHub main before the build, after the build and immediately before the final
   non-force publication. Declare that external responsibility explicitly with:

   ```sh
   bash tools/render-sign-bridge.sh SOURCE MAIN --external-main-check
   ```

   The exception requires `RENDER=true` and `RENDER_GIT_COMMIT=SOURCE`, retains
   exact checkout pinning and every signing/test/artifact gate, and truthfully
   logs `mainObservedByBuild=false`. It only exports a signed artifact and mirrors
   the old feed; it grants no new-feed publication authority. If origin is present,
   the helper always checks it directly, even when the flag was supplied. A failed
   or moved origin check cannot fall back to the external mode. Stop publication
   on any changed main and reconcile concurrent work before proceeding.
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
