#!/usr/bin/env python3
"""Real local recovery and hostile-input tests; no Android, signer or network."""
import hashlib
import json
import os
import pathlib
import shutil
import stat
import subprocess
import tempfile
import unittest
import warnings
import zipfile
from unittest.mock import patch
import release_capsule as capsule

Path = pathlib.Path

class Fixture(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='offerfilter-capsule-test-')
        self.root = Path(self.temp.name)
    def tearDown(self):
        self.temp.cleanup()
    def git(self, repo, *args, input=None, check=True):
        env = capsule._git_env()
        env.update(GIT_AUTHOR_NAME='Local fixture', GIT_AUTHOR_EMAIL='fixture@example.invalid',
                   GIT_COMMITTER_NAME='Local fixture', GIT_COMMITTER_EMAIL='fixture@example.invalid')
        return subprocess.run(capsule._git_args(*args), cwd=repo, env=env, input=input, capture_output=True, check=check)
    def repo(self):
        repo = self.root / 'original'; repo.mkdir()
        self.git(repo, 'init', '-q', '--template=')
        (repo / 'old.apk').write_bytes(b'historic APK excluded from recovery')
        (repo / 'nested').mkdir(); (repo / 'nested/keep.txt').write_text('retained')
        self.git(repo, 'add', '.'); self.git(repo, 'commit', '-qm', 'old')
        self.parent = self.git(repo, 'rev-parse', 'HEAD').stdout.strip()
        self.old_blob = self.git(repo, 'rev-parse', 'HEAD:old.apk').stdout.strip()
        (repo / 'old.apk').unlink()
        (repo / 'nested/space tab\tnewline\n.txt').write_text('portable contents')
        (repo / 'run.sh').write_text('#!/bin/sh\nexit 0\n'); (repo / 'run.sh').chmod(0o755)
        self.git(repo, 'add', '-A'); self.git(repo, 'commit', '-qm', 'candidate')
        return repo
class SourcePackTest(Fixture):
    def test_exact_nested_tree_restores_offline_without_history_or_original_workspace(self):
        repo = self.repo(); pack = self.root / 'source.pack'
        receipt = capsule.create_source_pack(repo, pack)
        shutil.rmtree(repo)
        restored = self.root / 'restored'
        capsule.restore_source_pack(pack, restored, receipt['sourceCommit'], receipt['sourceTree'])
        self.assertEqual(receipt['sourceCommit'], self.git(restored, 'rev-parse', 'HEAD').stdout.decode().strip())
        self.assertEqual(receipt['sourceTree'], self.git(restored, 'rev-parse', 'HEAD^{tree}').stdout.decode().strip())
        self.assertEqual(b'true', self.git(restored, 'rev-parse', '--is-shallow-repository').stdout.strip())
        self.assertEqual(b'', self.git(restored, 'status', '--porcelain', '--untracked-files=all').stdout)
        self.git(restored, 'fsck', '--full', '--strict')
        inventory = self.git(restored, 'cat-file', '--batch-all-objects', '--batch-check=%(objectname)').stdout.splitlines()
        self.assertNotIn(self.parent, inventory); self.assertNotIn(self.old_blob, inventory)
        self.assertTrue((restored / 'run.sh').stat().st_mode & stat.S_IXUSR)
        self.assertEqual('portable contents', (restored / 'nested/space tab\tnewline\n.txt').read_text())
    def test_restore_ignores_inherited_config_hooks_and_filters(self):
        repo = self.repo()
        (repo / '.gitattributes').write_text('*.txt filter=malicious\n')
        self.git(repo, 'add', '.'); self.git(repo, 'commit', '-qm', 'attributes')
        marker = self.root / 'EXECUTED'
        config = self.root / 'hostile-config'
        config.write_text('[filter "malicious"]\n\tsmudge = touch '+str(marker)+'\n[core]\n\tfsmonitor = touch '+str(marker)+'\n')
        pack = self.root / 'source.pack'; receipt = capsule.create_source_pack(repo, pack)
        with patch.dict(os.environ, {'GIT_CONFIG_GLOBAL':str(config), 'GIT_CONFIG_COUNT':'1',
                                    'GIT_CONFIG_KEY_0':'core.hooksPath', 'GIT_CONFIG_VALUE_0':str(self.root)}):
            capsule.restore_source_pack(pack, self.root/'restored', receipt['sourceCommit'], receipt['sourceTree'])
        self.assertFalse(marker.exists())
    def test_full_history_pack_is_rejected(self):
        repo = self.repo(); source = self.git(repo,'rev-parse','HEAD').stdout.decode().strip()
        tree = self.git(repo,'rev-parse','HEAD^{tree}').stdout.decode().strip()
        ids = self.git(repo,'rev-list','--objects','--no-object-names',source).stdout
        pack = self.root/'full.pack'; pack.write_bytes(self.git(repo,'pack-objects','--stdout',input=ids).stdout)
        with self.assertRaisesRegex(capsule.CapsuleError, 'outside candidate'):
            capsule.restore_source_pack(pack,self.root/'restored',source,tree)
        self.assertFalse((self.root/'restored').exists())
    def test_merge_commit_restores_without_either_parent(self):
        repo=self.repo(); first=self.git(repo,'rev-parse','HEAD').stdout.strip()
        self.git(repo,'checkout','-qb','side',self.parent.decode())
        (repo/'side.txt').write_text('side');self.git(repo,'add','.');self.git(repo,'commit','-qm','side')
        second=self.git(repo,'rev-parse','HEAD').stdout.strip()
        self.git(repo,'checkout','-qb','merge',first.decode());self.git(repo,'merge','-qm','merge','side')
        pack=self.root/'source.pack';receipt=capsule.create_source_pack(repo,pack);dest=self.root/'restored'
        capsule.restore_source_pack(pack,dest,receipt['sourceCommit'],receipt['sourceTree'])
        inventory=self.git(dest,'cat-file','--batch-all-objects','--batch-check=%(objectname)').stdout.splitlines()
        self.assertNotIn(first,inventory);self.assertNotIn(second,inventory)
        self.assertEqual('side',(dest/'side.txt').read_text())
    def test_missing_tree_blob_cannot_be_imported(self):
        repo=self.repo();source=self.git(repo,'rev-parse','HEAD').stdout.decode().strip()
        tree=self.git(repo,'rev-parse','HEAD^{tree}').stdout.decode().strip()
        ids=self.git(repo,'rev-list','--objects','--no-walk','--no-object-names',source).stdout.splitlines()
        pack=self.root/'incomplete.pack'
        pack.write_bytes(self.git(repo,'pack-objects','--stdout',input=b'\n'.join(ids[:-1])+b'\n').stdout)
        with self.assertRaises(capsule.CapsuleError):capsule.restore_source_pack(pack,self.root/'restored',source,tree)
        self.assertFalse((self.root/'restored').exists())
    def test_wrong_tree_or_corrupt_pack_never_promotes(self):
        repo = self.repo(); pack = self.root/'source.pack'; receipt = capsule.create_source_pack(repo,pack)
        with self.assertRaises(capsule.CapsuleError):
            capsule.restore_source_pack(pack,self.root/'restored',receipt['sourceCommit'],'0'*40)
        self.assertFalse((self.root/'restored').exists())
        pack.write_bytes(pack.read_bytes()[:-8])
        with self.assertRaises(capsule.CapsuleError):
            capsule.restore_source_pack(pack,self.root/'restored',receipt['sourceCommit'],receipt['sourceTree'])
        self.assertFalse((self.root/'restored').exists())
    def test_source_secret_and_symlink_are_rejected_before_pack_output(self):
        for name, symlink in [('private.p12',False),('link',True)]:
            with self.subTest(name=name):
                repo=self.repo()
                if symlink: (repo/name).symlink_to('nested/keep.txt')
                else: (repo/name).write_text('private fixture')
                self.git(repo,'add','.');self.git(repo,'commit','-qm','forbidden')
                with self.assertRaises(capsule.CapsuleError):capsule.create_source_pack(repo,self.root/'source.pack')
                self.assertFalse((self.root/'source.pack').exists());shutil.rmtree(repo)
    def test_existing_destination_and_interruption_are_not_overwritten(self):
        repo=self.repo();pack=self.root/'source.pack';receipt=capsule.create_source_pack(repo,pack)
        dest=self.root/'restored';dest.mkdir();(dest/'keep').write_text('untouched')
        with self.assertRaises(capsule.CapsuleError):capsule.restore_source_pack(pack,dest,receipt['sourceCommit'],receipt['sourceTree'])
        self.assertEqual('untouched',(dest/'keep').read_text());shutil.rmtree(dest)
        with patch.object(capsule,'_promote_dir',side_effect=OSError('interrupted')):
            with self.assertRaises(OSError):capsule.restore_source_pack(pack,dest,receipt['sourceCommit'],receipt['sourceTree'])
        self.assertFalse(dest.exists())
        capsule.restore_source_pack(pack,dest,receipt['sourceCommit'],receipt['sourceTree'])

class CapsuleTest(Fixture):
    def make_run(self):
        run=self.root/'run';run.mkdir()
        (run/'run.json').write_text('{"sourceCommit":"fixture"}')
        (run/'source.pack').write_bytes(b'transport fixture, not Git evidence')
        stage=run/'prepared';stage.mkdir();(stage/'reports').mkdir()
        (stage/'reports/TEST-original.xml').write_text('<testsuite tests="1"/>')
        (stage/'unsigned.apk').write_bytes(b'unsigned fixture')
        capsule.seal(stage,{'stage':'prepared','sourceCommit':'fixture'})
        return run
    def export(self,run):
        path=self.root/'capsule.zip';receipt=capsule.create_capsule(run,path);return path,receipt
    def rewrite(self,path,mutator):
        with zipfile.ZipFile(path) as archive:
            entries=[(info,archive.read(info)) for info in archive.infolist()]
        entries=mutator(entries)
        with warnings.catch_warnings():
            warnings.simplefilter('ignore')
            with zipfile.ZipFile(path,'w',compression=zipfile.ZIP_DEFLATED) as archive:
                for info,data in entries:archive.writestr(info,data)
        return hashlib.sha256(path.read_bytes()).hexdigest()
    def test_roundtrip_excludes_workspace_private_attempts_and_unsealed_stages(self):
        run=self.make_run()
        for name in ('source','attempts','private','cache','signed'):
            (run/name).mkdir();(run/name/'secret.p12').write_text('MUST NEVER TRANSFER')
        (run/'.lock').write_text('private lock')
        path,receipt=self.export(run)
        with zipfile.ZipFile(path) as archive:
            names=archive.namelist()
            self.assertFalse(any(name.startswith(('source/','attempts/','private/','cache/','signed/')) for name in names))
        shutil.rmtree(run)
        restored=self.root/'relocated';out=capsule.restore_capsule(path,receipt['sha256'],restored)
        self.assertEqual(['prepared'],out['stages'])
        self.assertEqual('prepared',capsule.verify(restored/'prepared','prepared')['stage'])
        self.assertEqual('<testsuite tests="1"/>',(restored/'prepared/reports/TEST-original.xml').read_text())
    def test_independent_hash_mismatch_fails_before_destination(self):
        path,receipt=self.export(self.make_run())
        with self.assertRaises(capsule.CapsuleError):capsule.restore_capsule(path,'0'*64,self.root/'dest')
        self.assertFalse((self.root/'dest').exists())
    def test_stage_tamper_addition_and_missing_file_fail(self):
        run=self.make_run();stage=run/'prepared';report=stage/'reports/TEST-original.xml'
        original=report.read_bytes();report.write_bytes(b'changed')
        with self.assertRaises(capsule.CapsuleError):capsule.verify(stage)
        report.write_bytes(original);(stage/'extra').write_text('extra')
        with self.assertRaises(capsule.CapsuleError):capsule.verify(stage)
        (stage/'extra').unlink();report.unlink()
        with self.assertRaises(capsule.CapsuleError):capsule.verify(stage)
    def test_symlink_and_secret_stage_members_fail(self):
        run=self.make_run();stage=run/'prepared'
        (stage/'leak').symlink_to(run/'run.json')
        with self.assertRaises(capsule.CapsuleError):capsule.verify(stage)
        (stage/'leak').unlink();(stage/'key.p12').write_text('private')
        with self.assertRaises(capsule.CapsuleError):capsule.verify(stage)
    def test_archive_unsafe_unknown_duplicate_casefold_and_nonregular_members(self):
        run=self.make_run()
        cases=[('../escape',stat.S_IFREG),('/absolute',stat.S_IFREG),('source/hooks/run',stat.S_IFREG),
               ('private.p12',stat.S_IFREG),('run.json',stat.S_IFREG),('RUN.JSON',stat.S_IFREG),
               ('prepared/link',stat.S_IFLNK),('prepared/fifo',stat.S_IFIFO)]
        for name,mode in cases:
            with self.subTest(name=name):
                path,receipt=self.export(run)
                info=zipfile.ZipInfo(name);info.external_attr=(mode|0o600)<<16
                sha=self.rewrite(path,lambda entries:entries+[(info,b'bad')])
                with self.assertRaises(capsule.CapsuleError):capsule.restore_capsule(path,sha,self.root/'dest')
                self.assertFalse((self.root/'dest').exists());path.unlink()
    def test_outer_manifest_rehash_cannot_hide_broken_stage_hash(self):
        path,receipt=self.export(self.make_run())
        def mutate(entries):
            values={i.filename:(i,data) for i,data in entries}
            name='prepared/reports/TEST-original.xml';values[name]=(values[name][0],b'forged')
            key='capsule-manifest.json';manifest=json.loads(values[key][1]);manifest['files'][name]=capsule._info(b'forged')
            values[key]=(values[key][0],capsule._json_bytes(manifest))
            return list(values.values())
        sha=self.rewrite(path,mutate)
        with self.assertRaises(capsule.CapsuleError):capsule.restore_capsule(path,sha,self.root/'dest')
        self.assertFalse((self.root/'dest').exists())
    def test_duplicate_json_field_refused(self):
        run=self.make_run();manifest=run/'prepared/manifest.json'
        manifest.write_bytes(manifest.read_bytes().replace(b'{',b'{"stage":"forged",',1))
        with self.assertRaises(capsule.CapsuleError):capsule.verify(run/'prepared')
    def test_archive_expansion_limit_and_file_size_preflight(self):
        path,receipt=self.export(self.make_run())
        with patch.object(capsule,'MAX_ARCHIVE',64):
            with self.assertRaises(capsule.CapsuleError):capsule.restore_capsule(path,receipt['sha256'],self.root/'dest')
        self.assertFalse((self.root/'dest').exists())
    def test_no_replace_promotion_and_retry_after_interruption(self):
        path,receipt=self.export(self.make_run());dest=self.root/'dest'
        with patch.object(capsule,'_promote_dir',side_effect=OSError('interruption')):
            with self.assertRaises(OSError):capsule.restore_capsule(path,receipt['sha256'],dest)
        self.assertFalse(dest.exists())
        capsule.restore_capsule(path,receipt['sha256'],dest)
        with self.assertRaises(capsule.CapsuleError):capsule.restore_capsule(path,receipt['sha256'],dest)
        self.assertEqual('prepared',capsule.verify(dest/'prepared')['stage'])
    def test_actual_directory_promotion_does_not_replace_existing_empty_directory(self):
        source=self.root/'stage';source.mkdir();(source/'evidence').write_text('kept')
        dest=self.root/'existing';dest.mkdir()
        with self.assertRaises(OSError):capsule._promote_dir(source,dest)
        self.assertTrue((source/'evidence').exists());self.assertEqual([],list(dest.iterdir()))
    def test_seal_cannot_replace_existing_manifest(self):
        run=self.make_run();original=(run/'prepared/manifest.json').read_bytes()
        with self.assertRaises(capsule.CapsuleError):capsule.seal(run/'prepared',{'stage':'prepared'})
        self.assertEqual(original,(run/'prepared/manifest.json').read_bytes())

if __name__=='__main__':unittest.main()
