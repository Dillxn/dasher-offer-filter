# Local release flow repair and 0.4.59 publication

0.4.59/code65 was built, tested, packaged and signed in the local execution workspace with the original certificate. The complete signed release was published to private GitHub main at `18c1b4748a0b554ba5742a6c726bd7471a250880`; its immutable app/source commit is `a0a417f9fc5eff4b878b26694829cbdc29c516cb`.

## Evidence

- Local release gate: 1,833 tests / 102 suites, no failures/errors/skips, simulated API26/35; lint zero errors / 35 warnings. All 281 tracked inputs unchanged; aggregate `a72ecdd70e16a3d23a9d998a4022b61a2ff9a9f6d8819e55d61d7ccd20994ce1`. See `validation/0.4.59-local-signed-inputs.json` and `validation/0.4.59-local-signed-result.json`.
- APK: 423,069 bytes, SHA-256 `cc1000c8ad33731f6a8fc3b9b016d7dfc6e8e6c40fdb988d23709efc247a20f4`; original signer `553994c4d1310bf92f236525d1d293df597f37be39a7fd34f8b58e68dda0c703`.
- Render deployment `dep-db0m1i9srm7s7386gv9g` copied finished signed GitHub assets only and became live October 3 at 20:09:45 UTC. No Android build or signing ran on Render or GitHub Actions for this release.
- A fresh production `UpdateTransport.java` and `UpdatePolicy.java` probe downloaded the live manifest and APK and verified original signer, embedded version/package, size and hash. GitHub manifest/blob identity also matches. Structured receipt: `validation/0.4.59-live-publication.json`.
- No physical-phone install or real Dasher behavior was verified here.

## Corrected blocker diagnosis

The 19:54 checkpoint's network-permission diagnosis was incorrect. Work network access was already on. Java did not use the current standard proxy environment and an earlier launcher supplied a stale proxy address. The unchanged publisher passed after current proxy routing was supplied; no verification gate, TLS check or network permission was bypassed.

The tooling-only follow-up adds `runtime_http_proxy.py`, used by the local publisher/channel verifier. It derives routing from current HTTP(S)_PROXY/http(s)_proxy, honors NO_PROXY/no_proxy, removes stale JVM routing properties, keeps trust-store/TLS settings, and rejects unsupported/authenticated proxy URLs without echoing values. Eight focused tests and an actual JVM option smoke passed. The live-channel probe also passed using this helper with the previously stale launcher environment. Android code and signed APK bytes are unchanged by the follow-up.

## Next release

Follow `RELEASE_BRIDGE.md`: freeze source, run local gates/signing, use the publisher, push the complete source/release pair, distribute finished assets and verify the live channel. Read current runtime proxy variables; never hardcode a prior port or request a new login because of this corrected routing issue. Preserve original-signing and updater checks. Do not rebuild unchanged 0.4.59, use paid CI, generate a new signer or send a manual-install workaround.
