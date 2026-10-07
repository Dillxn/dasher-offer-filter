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
        # Use the checked-in signed release. No Android build, signing, or network request.
        with patch.object(mirror, "live_feed", side_effect=AssertionError("No network in this test")), \
                contextlib.redirect_stdout(io.StringIO()):
            cls.published = mirror.main(out=cls.output, check_live=False)
        cls.page = Page((cls.output / "index.html").read_text(encoding="utf-8"))

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_no_account_setup_and_exact_consent_caption(self):
        self.assertIn("No GitHub account is needed", self.page.text)
        self.assertIn("I understand and accept", self.page.text)
        self.assertIn("Not now", self.page.text)
        self.assertIn("Drag a knob", self.page.text)
        self.assertIn("mascot", self.page.text)

    def test_three_android_accesses_are_distinct_and_optional_by_feature(self):
        for phrase in ("Screen reading is off", "Accessibility", "on-screen filtering",
                       "Background offers are off", "notification access", "background offers and Peek",
                       "Alerts are blocked", "notification permission", "passing/review alerts",
                       "Allow restricted settings", "Optional location"):
            with self.subTest(phrase=phrase):
                self.assertIn(phrase, self.page.text)

    def test_updating_is_separate_from_the_browser_first_install(self):
        for phrase in ("allow your browser", "Updates can't install", "Allow from this source",
                       "separate from your browser", "installation confirmation", "don't uninstall"):
            with self.subTest(phrase=phrase):
                self.assertIn(phrase, self.page.text)

    def test_beta_risks_and_behavior_defaults_are_explicit(self):
        for phrase in ("Experimental beta", "real-phone", "acceptance rate", "deactivate",
                       "Don't handle your phone while driving", "Peek is on by default",
                       "Auto-accept is off by default", "not legal advice", "Keep Play Protect enabled",
                       "not an instruction to bypass a security warning"):
            with self.subTest(phrase=phrase):
                self.assertIn(phrase, self.page.text)

    def test_public_notices_and_existing_verification_links(self):
        base = "https://github.com/Dillxn/dasher-offer-filter/blob/main/"
        for destination in (base + "PRIVACY.md", base + "TERMS.md", base + "LICENSE",
                            "/verification.json", "/signing-receipt.txt", "/OfferFilter.apk",
                            "https://dillxn.github.io/offer-filter-site/#help"):
            with self.subTest(destination=destination):
                self.assertIn(destination, self.page.links)
        self.assertEqual("OfferFilter-" + self.published["versionName"] + ".apk",
                         self.page.links["/OfferFilter.apk"]["download"])

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
