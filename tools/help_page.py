"""The in-app Help row's page (BetaProgram.HELP_URL in the app's source), opened at the user's tap in Settings.

The app links it with no fallback, so a release must not ship while the website does not serve it (on 6 October
2026 https://offerfilter.org/install/ answered 404: the page lived only on the site's unmerged branch).
tools/publish-repo-feed.py refuses to publish until it answers.
"""
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/java/com/local/dasherfilter/BetaProgram.java'


def help_url(source=SOURCE):
    """The address the app's Help row opens, as its source says."""
    match = re.search(r'HELP_URL = "([^"]+)"', pathlib.Path(source).read_text(encoding='utf-8'))
    if match is None:
        raise ValueError('BetaProgram.HELP_URL not found in ' + str(source))
    return match.group(1)


def check_help_page(serves, url=None):
    """The Help row's address when the website serves it now; otherwise refuses (ValueError) with what to do."""
    url = url or help_url()
    if not serves(url):
        raise ValueError(f"The app's Help row opens {url}, which the website does not serve yet: deploy "
                         'offerfilter.org (its install page) before publishing this release')
    return url
