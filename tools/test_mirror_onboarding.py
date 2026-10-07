import contextlib
import html.parser
import io
import json
import pathlib
import tempfile
import unittest
from unittest.mock import patch

import mirror_repo_feed as mirror


class Page(html.parser.HTMLParser):
    def __init__(self, markup):
        super().__init__()
        self.words = []
        self.links = {}
        self.feed(markup)
        self.text = " ".join(" ".join(self.words).split())

    def handle_starttag(self, tag, attrs):
        if tag == "a":
            attributes = dict(attrs)
            self.links[attributes.get("href")] = attributes

    def handle_data(self, data):
        self.words.append(data)


class MirrorOnboardingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.output = pathlib.Path(cls.temp.name) / "public"
        # Use the checked-in signed release. No Android build, signing, or network request: the website serves
        # its legal pages here.
        with patch.object(mirror, "live_feed", side_effect=AssertionError("No network in this test")), \
                patch.object(mirror, "page_serves", side_effect=AssertionError("No network in this test")), \
                contextlib.redirect_stdout(io.StringIO()):
            cls.published = mirror.main(out=cls.output, check_live=False, serves=lambda url: True)
        cls.page = Page((cls.output / "index.html").read_text(encoding="utf-8"))

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_no_account_setup_and_exact_consent_caption(self):
        self.assertIn("No account is needed", self.page.text)
        self.assertNotIn("Connecting GitHub", self.page.text)
        self.assertNotIn("GitHub", self.page.text)
        self.assertIn("I understand and accept", self.page.text)
        self.assertIn("Not now", self.page.text)
        self.assertIn("Drag a knob", self.page.text)
        self.assertIn("mascot", self.page.text)

    def test_android_steps_are_named_as_the_homepage_names_them_and_optional_by_feature(self):
        for phrase in ("Allow restricted settings", "Turn on Offer Filter in Accessibility", "on-screen filtering",
                       "Allow notification access", "background offers and Peek", "Allow alerts",
                       "notification permission", "passing/review alerts", "Allow updates", "more to set up",
                       "Optional location"):
            with self.subTest(phrase=phrase):
                self.assertIn(phrase, self.page.text)
        steps = [self.page.text.index(step) for step in ("Allow restricted settings (Android 13",
                                                          "Turn on Offer Filter in Accessibility",
                                                          "Allow notification access", "Allow alerts",
                                                          "Allow updates")]
        self.assertEqual(sorted(steps), steps, "in the homepage's order")
        for retired in ("Screen reading is off", "Background offers are off", "Alerts are blocked"):
            with self.subTest(retired=retired):
                self.assertNotIn(retired, self.page.text)

    def test_updating_is_separate_from_the_browser_first_install(self):
        for phrase in ("allow your browser", "Allow updates", "Allow from this source",
                       "separate from your browser", "Update ready · Install now", "installation confirmation",
                       "don't uninstall", "eight quiet hours"):
            with self.subTest(phrase=phrase):
                self.assertIn(phrase, self.page.text)
        self.assertNotIn("Updates can't install", self.page.text, "Settings has no such row since 0.5.0")

    def test_beta_risks_and_behavior_defaults_are_explicit(self):
        for phrase in ("Experimental beta", "real-phone", "acceptance rate", "deactivate",
                       "Don't handle your phone while driving", "Peek is on by default",
                       "Auto-accept is off by default", "dated beta terms", "privacy@offerfilter.org",
                       "Keep Play Protect enabled",
                       "not an instruction to bypass a security warning"):
            with self.subTest(phrase=phrase):
                self.assertIn(phrase, self.page.text)

    def test_public_notices_and_existing_verification_links(self):
        for destination in ("https://offerfilter.org/privacy/", "https://offerfilter.org/terms/",
                            "https://offerfilter.org/license/", "/verification.json", "/signing-receipt.txt",
                            "/OfferFilter.apk", "https://offerfilter.org/#help"):
            with self.subTest(destination=destination):
                self.assertIn(destination, self.page.links)
        stale = [link for link in self.page.links if "app-source" in (link or "") or "github.io" in (link or "")]
        self.assertEqual([], stale, "legal texts are published on offerfilter.org, not a stale source snapshot")
        self.assertEqual("OfferFilter-" + self.published["versionName"] + ".apk",
                         self.page.links["/OfferFilter.apk"]["download"])

    def test_a_legal_page_the_website_does_not_serve_yet_links_to_its_source_copy(self):
        asked = []

        def serves(url):
            asked.append(url)
            return url.endswith("/privacy/")

        with tempfile.TemporaryDirectory() as temp, \
                patch.object(mirror, "live_feed", side_effect=AssertionError("No network in this test")), \
                patch.object(mirror, "page_serves", side_effect=AssertionError("No network in this test")), \
                contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()) as said:
            output = pathlib.Path(temp) / "public"
            mirror.main(out=output, check_live=False, serves=serves)
            page = Page((output / "index.html").read_text(encoding="utf-8"))
        self.assertEqual(["https://offerfilter.org/terms/", "https://offerfilter.org/privacy/",
                          "https://offerfilter.org/license/"], asked)
        self.assertIn("https://offerfilter.org/privacy/", page.links)
        for missing, copy in (("https://offerfilter.org/terms/", mirror.SOURCE + "TERMS.md"),
                              ("https://offerfilter.org/license/", mirror.SOURCE + "LICENSE")):
            with self.subTest(missing=missing):
                self.assertNotIn(missing, page.links)
                self.assertIn(copy, page.links)
                self.assertIn("LEGAL_PAGE_NOT_SERVED " + missing, said.getvalue())
        self.assertEqual("https://github.com/Dillxn/dasher-offer-filter/blob/main/", mirror.SOURCE)
        stale = [link for link in page.links if "app-source" in (link or "") or "github.io" in (link or "")]
        self.assertEqual([], stale)
        self.assertNotIn("GitHub", page.text)
        for label in ("Terms of use", "Privacy", "MIT License"):
            self.assertIn(label, page.text)

    def test_a_page_counts_as_served_only_when_it_answers(self):
        class Answer:
            def __init__(self, status):
                self.status = status

            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

        def answering(*statuses):
            calls = iter(statuses)

            def urlopen(request, timeout):
                status = next(calls)
                if isinstance(status, Exception):
                    raise status
                if status >= 400:
                    raise mirror.urllib.error.HTTPError(request.full_url, status, "status", {}, None)
                return Answer(status)
            return urlopen

        cases = (((200,), True), ((404,), False), ((405, 200), True), ((405, 404), False),
                 ((OSError("no route"),), False))
        for statuses, expected in cases:
            with self.subTest(statuses=statuses), \
                    patch.object(mirror.urllib.request, "urlopen", side_effect=answering(*statuses)):
                self.assertIs(expected, mirror.page_serves("https://offerfilter.org/terms/"))

    def test_onboarding_does_not_change_release_bytes_or_claim_device_install(self):
        original = json.loads((mirror.ROOT / "release/latest.json").read_text())
        actual = json.loads((self.output / "latest.json").read_text())
        for key in ("versionName", "versionCode", "sourceCommit", "sha256", "size", "packageName"):
            self.assertEqual(original[key], actual[key], key)
        self.assertEqual((mirror.ROOT / "release/OfferFilter.apk").read_bytes(),
                         (self.output / "OfferFilter.apk").read_bytes())
        proof = json.loads((self.output / "verification.json").read_text())
        self.assertIs(proof["deviceInstallVerified"], False)
        self.assertEqual(mirror.SIGNER, proof["signerSha256"])


if __name__ == "__main__":
    unittest.main()
