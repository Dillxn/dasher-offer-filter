import pathlib
import unittest

import help_page


class HelpPageGateTest(unittest.TestCase):
    def test_the_address_is_read_from_the_apps_own_source(self):
        self.assertEqual("https://offerfilter.org/install/", help_page.help_url())

    def test_a_page_the_website_does_not_serve_refuses_the_release(self):
        asked = []

        def serves(url):
            asked.append(url)
            return False

        with self.assertRaises(ValueError) as refused:
            help_page.check_help_page(serves)
        self.assertEqual(["https://offerfilter.org/install/"], asked)
        self.assertIn("https://offerfilter.org/install/", str(refused.exception))
        self.assertIn("deploy offerfilter.org", str(refused.exception))

    def test_a_served_page_passes(self):
        self.assertEqual("https://offerfilter.org/install/", help_page.check_help_page(lambda url: True))

    def test_publishing_asks_before_it_writes_anything(self):
        script = (help_page.ROOT / "tools/publish-repo-feed.py").read_text(encoding="utf-8")
        gate = script.index("check_help_page(page_serves)")
        self.assertLess(gate, script.index("apk_path.write_bytes"), "checked before release/ is written")
        self.assertLess(gate, script.index("feed_path.write_text"))


if __name__ == "__main__":
    unittest.main()
