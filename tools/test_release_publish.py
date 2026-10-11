"""Local Git integration tests; synthetic APK bytes, no Android or network work."""
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

import release_publish as publication


class PublicationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='test-release-publish-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo = self.root / 'integration'
        self.repo.mkdir()
        self.remote = self.root / 'destination.git'
        self.git(self.root, 'init', '--bare', str(self.remote))
        self.git(self.repo, 'init', '-b', 'main')
        self.git(self.repo, 'config', 'user.name', 'Fixture')
        self.git(self.repo, 'config', 'user.email', 'fixture@example.invalid')
        (self.repo / 'release').mkdir()
        (self.repo / 'release/OfferFilter.apk').write_bytes(b'old synthetic APK')
        (self.repo / 'release/latest.json').write_text(json.dumps({'versionCode': 1}))
        (self.repo / 'app.txt').write_text('original source\n')
        self.commit('Initial source')
        self.expected = self.git(self.repo, 'rev-parse', 'HEAD').strip()
        self.git(self.repo, 'remote', 'add', 'origin', str(self.remote))
        self.git(self.repo, 'push', 'origin', 'main')
        (self.repo / 'app.txt').write_text('candidate source\n')
        self.commit('Candidate source')
        self.source = self.git(self.repo, 'rev-parse', 'HEAD').strip()
        self.staged = self.root / 'staged'
        (self.staged / 'release').mkdir(parents=True)
        self.apk = b'new synthetic APK bytes, not an Android signing claim'
        self.feed = dict(sourceCommit=self.source, versionCode=2, versionName='fixture-2',
                         packageName='com.local.dasherfilter', size=len(self.apk),
                         sha256=hashlib.sha256(self.apk).hexdigest())
        self.seal()

    @staticmethod
    def git(repo, *args):
        return subprocess.check_output(['git', '-C', str(repo), *args], stderr=subprocess.DEVNULL).decode()

    def commit(self, message):
        self.git(self.repo, 'add', '.')
        self.git(self.repo, 'commit', '-m', message)

    def seal(self):
        (self.staged / 'release/OfferFilter.apk').write_bytes(self.apk)
        (self.staged / 'release/latest.json').write_text(json.dumps(self.feed))
        files = {name: publication._info((self.staged / name).read_bytes()) for name in publication.FILES}
        (self.staged / 'manifest.json').write_text(json.dumps(dict(stage='staged',
            sourceCommit=self.feed['sourceCommit'], files=files)))

    def run_publish(self, **kwargs):
        return publication.publish(self.staged, self.repo, self.expected, **kwargs)

    def test_preparation_preserves_dirty_worktree_and_remote(self):
        (self.repo / 'app.txt').write_text('uncommitted user work\n')
        (self.repo / 'notes.txt').write_text('untracked user notes\n')
        before = self.git(self.repo, 'status', '--porcelain')
        result = self.run_publish()
        self.assertFalse(result['repositoryPublished'])
        self.assertFalse(result['liveChannelVerified'])
        self.assertEqual(self.git(self.repo, 'status', '--porcelain'), before)
        self.assertEqual((self.repo / 'app.txt').read_text(), 'uncommitted user work\n')
        self.assertEqual(self.git(self.repo, 'rev-parse', 'HEAD').strip(), self.source)
        self.assertEqual(self.git(self.remote, 'rev-parse', 'main').strip(), self.expected)
        commit = result['publicationCommit']
        self.assertEqual(self.git(self.repo, 'rev-parse', result['preparedRef']).strip(), commit)
        changed = self.git(self.repo, 'diff-tree', '--no-commit-id', '--name-only', '-r', commit).splitlines()
        self.assertEqual(set(changed), set(publication.FILES))
        message = self.git(self.repo, 'show', '-s', '--format=%B', commit)
        self.assertIn('[skip ci] [skip render]', message)
        self.assertTrue(publication._same_pair(self.repo, commit, publication._feed(self.staged)[0]))

    def test_push_includes_source_and_retry_is_idempotent(self):
        first = self.run_publish(push=True)
        second = self.run_publish(push=True)
        self.assertTrue(first['repositoryPublished'])
        self.assertTrue(second['alreadyPublished'])
        self.assertEqual(first['publicationCommit'], second['publicationCommit'])
        self.assertTrue(publication._ancestor(self.remote, self.source, first['publicationCommit']))
        self.assertEqual(self.git(self.repo, 'rev-parse', 'HEAD').strip(), self.source)

    def competitor(self):
        tree = self.git(self.repo, 'rev-parse', self.expected + '^{tree}').strip()
        commit = subprocess.check_output(['git', '-C', str(self.repo), 'commit-tree', tree,
            '-p', self.expected], input=b'Concurrent unrelated change\n', stderr=subprocess.DEVNULL).decode().strip()
        self.git(self.repo, 'push', 'origin', commit + ':refs/heads/main')
        return commit

    def test_fresh_remote_competition_is_refused(self):
        competitor = self.competitor()
        with self.assertRaisesRegex(publication.PublicationError, 'Remote branch advanced'):
            self.run_publish(push=True)
        self.assertEqual(self.git(self.remote, 'rev-parse', 'main').strip(), competitor)

    def test_nonforce_push_refuses_race_after_fetch(self):
        original_git = publication._git
        competed = []

        def racing_git(repo, *args, **kwargs):
            if args[0] == 'push' and not competed:
                competed.append(self.competitor())
            return original_git(repo, *args, **kwargs)

        with mock.patch.object(publication, '_git', side_effect=racing_git):
            with self.assertRaisesRegex(publication.PublicationError, 'Git operation refused'):
                self.run_publish(push=True)
        self.assertEqual(self.git(self.remote, 'rev-parse', 'main').strip(), competed[0])

    def test_hooks_and_filters_are_not_executed(self):
        sentinel = self.root / 'EXECUTED'
        hook = self.repo / '.git/hooks/pre-push'
        hook.write_text('#!/bin/sh\ntouch "' + str(sentinel) + '"\nexit 1\n')
        hook.chmod(0o755)
        self.git(self.repo, 'config', 'core.hooksPath', str(hook.parent))
        self.git(self.repo, 'config', 'filter.poison.clean', 'touch "' + str(sentinel) + '"')
        self.git(self.repo, 'config', 'filter.poison.smudge', 'touch "' + str(sentinel) + '"')
        (self.repo / '.git/info/attributes').write_text('* filter=poison\n')
        result = self.run_publish(push=True)
        self.assertTrue(result['repositoryPublished'])
        self.assertFalse(sentinel.exists())

    def test_corrupt_stage_refused_before_git_mutation(self):
        (self.staged / 'release/OfferFilter.apk').write_bytes(b'tampered')
        with self.assertRaisesRegex(publication.PublicationError, 'bytes changed'):
            self.run_publish()
        self.assertEqual(self.git(self.remote, 'rev-parse', 'main').strip(), self.expected)

    def test_feed_apk_mismatch_refused(self):
        self.feed['sha256'] = '0' * 64
        self.seal()
        with self.assertRaisesRegex(publication.PublicationError, 'APK differs'):
            self.run_publish()

    def test_source_not_in_integration_branch_refused(self):
        self.feed['sourceCommit'] = '0' * 40
        self.seal()
        with self.assertRaisesRegex(publication.PublicationError, 'does not contain staged source'):
            self.run_publish()

    def test_version_rollback_refused(self):
        self.feed['versionCode'] = 1
        self.seal()
        with self.assertRaisesRegex(publication.PublicationError, 'version must increase'):
            self.run_publish(push=True)

    def test_untested_source_after_frozen_candidate_refused(self):
        (self.repo / 'app.txt').write_text('changed after APK preparation\n')
        self.commit('Untested later source')
        with self.assertRaisesRegex(publication.PublicationError, 'differs from the frozen APK source'):
            self.run_publish(push=True)
        self.assertEqual(self.git(self.remote, 'rev-parse', 'main').strip(), self.expected)

    def test_shallow_integration_repo_refused(self):
        shallow = self.root / 'shallow'
        self.git(self.root, 'clone', '--depth=1', '--branch=main',
                 self.repo.as_uri(), str(shallow))
        with self.assertRaisesRegex(publication.PublicationError, 'full integration repository'):
            publication.publish(self.staged, shallow, self.source)

    def test_symlink_stage_refused(self):
        alias = self.root / 'alias'
        alias.symlink_to(self.staged, target_is_directory=True)
        with self.assertRaisesRegex(publication.PublicationError, 'symlinks'):
            publication.publish(alias, self.repo, self.expected)

    def test_preparation_does_not_contact_remote(self):
        self.git(self.repo, 'remote', 'set-url', 'origin', str(self.root / 'unreachable.git'))
        self.assertFalse(self.run_publish()['repositoryPublished'])


if __name__ == '__main__':
    unittest.main()
