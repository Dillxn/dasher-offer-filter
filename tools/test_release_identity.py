import unittest
from release_identity import check_channel, require_current_main


class ReleaseIdentityTest(unittest.TestCase):
    def test_retired_builder_requires_current_main_not_an_old_checkout(self):
        require_current_main('current', 'current\trefs/heads/main\n')
        for listing in ['', 'newer\trefs/heads/main\n', 'current\trefs/heads/other\n']:
            with self.subTest(listing=listing), self.assertRaises(ValueError):
                require_current_main('current', listing)

    def test_newer_code_may_advance_either_channel(self):
        check_channel(60, 'new-source', 'new-bytes',
                      {'versionCode': 59, 'sourceCommit': 'old', 'sha256': 'old'}, 'Render')

    def test_same_code_requires_identical_source_and_bytes(self):
        old = {'versionCode': 60, 'sourceCommit': 'source', 'sha256': 'bytes'}
        check_channel(60, 'source', 'bytes', old, 'GitHub')
        for source, digest in [('other', 'bytes'), ('source', 'other'), ('other', 'other')]:
            with self.subTest(source=source, digest=digest), self.assertRaises(ValueError):
                check_channel(60, source, digest, old, 'GitHub')

    def test_other_channel_newer_or_malformed_fails_closed(self):
        for feed in [{'versionCode': 61}, {}, {'versionCode': '60'}, {'versionCode': True}]:
            with self.subTest(feed=feed), self.assertRaises(ValueError):
                check_channel(60, 'source', 'bytes', feed, 'Render')


if __name__ == '__main__':
    unittest.main()
