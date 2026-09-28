# Offer Filter development

- Stay on main; no branches or worktrees.
- Preserve instant first decline attempts and silent behavior.
- Offer data and filter settings stay on the phone. Network access is only for updates.
- For app behavior changes, increase versionCode and versionName in both app/build.gradle and build-local.sh. Keep the existing signing key; do not replace it.
- Build with ./build-local.sh and run the relevant local JUnit tests. Document live phone checks separately from local tests.
- After pushing approved app changes on main, publish the APK update with publish-update.py. Verify Releases in both the original repository and update repository, plus the public feed and download without authentication. This is how installed phones receive new versions automatically.
- The source repo is private; only APKs and update metadata belong in the public update repo. Never embed or publish GitHub credentials or the signing key.
