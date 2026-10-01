#!/usr/bin/env bash
# Render's build: publish the repo feed's release (release/ on main, signed with the cloud key by
# tools/sign-local.sh and checked by tools/publish-repo-feed.py) as public/, without building or signing.
# The user's decision ("Render serves the GitHub build"): Render's build minutes ran out at 0.4.13, and
# phones that never connected GitHub read only Render. tools/render-build-full.sh is the old source build.
set -euo pipefail
cd "$(dirname "$0")"
python3 tools/mirror_repo_feed.py
