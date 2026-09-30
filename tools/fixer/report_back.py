#!/usr/bin/env python3
"""Posts the fixer's outcome on the report issue and labels it. An issue is closed only when nothing is left to do:
the fix is live and verified, or the report needed an explanation rather than a change. Everything else stays
open, labeled fixer:needs-human, with the reason.

Reads the step outcomes from the environment (GATE, RELEASE, VERIFY: success/failure/skipped/cancelled, or empty),
the issue from ISSUE and GITHUB_REPOSITORY, the fixer's files from FIXER_DIR (result.json, claude, gate.log,
released-sha, release.log, issue.json), and the live-channel proof from .channel-check/.
"""
import json
import os
import pathlib
import subprocess

FIXER = pathlib.Path(os.environ.get('FIXER_DIR', '.fixer'))
MAX_COMMENT = 60_000


def gh(*args):
    subprocess.run(['gh', *args], check=True)


def tail(name, lines=40):
    path = FIXER / name
    if not path.exists():
        return ''
    text = path.read_text(errors='replace').splitlines()
    return '\n'.join(text[-lines:]).replace('```', "'''")


def read_result():
    try:
        result = json.loads((FIXER / 'result.json').read_text())
        if result.get('outcome') in ('fixed', 'explained', 'needs-human'):
            return result
    except (OSError, ValueError):
        pass
    return None


def main():
    issue, repo = os.environ['ISSUE'], os.environ['GITHUB_REPOSITORY']
    run = f"{os.environ['GITHUB_SERVER_URL']}/{repo}/actions/runs/{os.environ['GITHUB_RUN_ID']}"
    step = {name: os.environ.get(name) or 'skipped' for name in ('GATE', 'RELEASE', 'VERIFY')}
    claude_file = FIXER / 'claude'
    step['CLAUDE'] = claude_file.read_text().strip() if claude_file.exists() else 'did not run'

    result = read_result()
    try:
        title = json.loads((FIXER / 'issue.json').read_text()).get('title', '')
    except (OSError, ValueError):
        title = ''

    label, close, reason = 'fixer:needs-human', False, None
    if result is not None and step['CLAUDE'] != 'success':
        text = (f"The fixer stopped before finishing (Claude step: {step['CLAUDE']}), usually by running out of "
                "turns or time, so nothing was released. Its last note:\n\n" + (result.get('comment') or ''))
    elif result is None:
        text = (f"The fixer did not finish (Claude step: {step['CLAUDE']}), so nothing changed.\n\n"
                "The usual cause is a missing or expired `CLAUDE_CODE_OAUTH_TOKEN` repository secret; the run "
                "log has the details.")
    else:
        text = result.get('comment') or result.get('summary') or ''
        if result['outcome'] == 'explained':
            label, close = 'fixer:explained', True
            reason = 'completed' if 'Test report' in title else 'not planned'
        elif result['outcome'] == 'fixed':
            if step['GATE'] != 'success':
                text += ("\n\n---\n**Not released.** The proposed fix failed the release gate:\n\n```\n"
                         + tail('gate.log') + "\n```")
            elif step['RELEASE'] != 'success':
                text += "\n\n---\n**Not released.** The fix passed the gate, but pushing it to `main` failed."
            elif step['VERIFY'] != 'success':
                sha = (FIXER / 'released-sha').read_text().strip() if (FIXER / 'released-sha').exists() else '?'
                text += (f"\n\n---\n**Pushed {sha} to `main`, but the live release was not verified.** "
                         "Check the Render deploy.\n\n```\n" + tail('release.log') + "\n```")
            else:
                proof = json.loads(pathlib.Path('.channel-check/transport-proof.json').read_text())
                text += (f"\n\n---\n**Released {proof['versionName']} (versionCode {proof['versionCode']})** from "
                         f"`{proof['sourceCommit']}`. The live APK was verified with the app's own download code "
                         f"(size, SHA-256 `{proof['sha256'][:16]}…`, package, version, signer). The phone installs "
                         "it at its next update check.\n\nChecked here: Java unit tests and simulated Android "
                         "adapter tests (Robolectric), lint, the published APK. Not checked: installing on the "
                         "phone, and DoorDash's real sounds and vibration.")
                label, close, reason = 'fixer:shipped', True, 'completed'

    body = f"{text[:MAX_COMMENT]}\n\n---\n_Offer Filter fixer (Claude Code) · [run]({run})_"
    gh('issue', 'comment', issue, '--repo', repo, '--body', body)
    gh('issue', 'edit', issue, '--repo', repo, '--add-label', label, '--remove-label', 'fixer:working')
    if close:
        gh('issue', 'close', issue, '--repo', repo, '--reason', reason)


if __name__ == '__main__':
    main()
