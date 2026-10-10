#!/usr/bin/env python3
"""Fail-closed protocol tests; no keys, signing, Android builds or network."""
import contextlib
import io
import json
import os
import pathlib
import stat
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile

import split_release as flow
import release_bridge_gate as gate


SOURCE = 'a' * 40
TREE = 'b' * 40


def apk(path, extra=(), replace=None):
    entries = {'AndroidManifest.xml': b'manifest fixture', 'classes.dex': b'dex fixture', 'resources.arsc': b'resources fixture'}
    entries.update(replace or {})
    with warnings.catch_warnings():
        warnings.simplefilter('ignore', UserWarning)
        with zipfile.ZipFile(path, 'w') as z:
            for name, value in entries.items():
                z.writestr(name, value)
            for name, value in extra:
                z.writestr(name, value)


class SplitFlowTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.bundle = self.root / 'bundle'
        self.bundle.mkdir()
        for name in flow.BUNDLE_FILES:
            p = self.bundle / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(b'nonsecret test fixture')
        apk(self.bundle / 'OfferFilter-unsigned.apk')
        files = {'tools/sign-local.sh': flow.file_info(self.bundle / 'tools/sign-local.sh')['sha256'],
                 'tools/split_release.py': flow.file_info(self.bundle / 'tools/split_release.py')['sha256']}
        aggregate = flow.digest(json.dumps(files, sort_keys=True, separators=(',', ':')).encode())
        frozen = dict(count=len(files), aggregate=aggregate, files=files)
        self.gate = dict(inputCount=len(files), inputSha256=aggregate,
                         junit=dict(tests=3382, errors=0, failures=0, skipped=0), lint=dict(errors=0, warnings=29),
                         adapterSdkCases={name: {'26': 1, '35': 1} for name in flow.ADAPTERS})
        (self.bundle / 'inputs.json').write_bytes(flow.encoded(frozen))
        (self.bundle / 'gate.json').write_bytes(flow.encoded(self.gate))
        self.receipt = dict(schema=1, purpose='UNSIGNED_NONPUBLISHABLE', sourceCommit=SOURCE, sourceTree=TREE,
                            inputCount=len(files), inputAggregate=aggregate, originalSigner=flow.SIGNER,
                            package=dict(packageName=flow.PACKAGE, versionName='0.5.8', versionCode=88, minSdk=26, targetSdk=35),
                            payload=flow.apk_entries(self.bundle / 'OfferFilter-unsigned.apk', unsigned=True))
        self.refresh()
        self.patch_root = patch.object(flow, 'ROOT', self.bundle)
        self.patch_root.start(); self.addCleanup(self.patch_root.stop)
        self.patch_jar = patch.object(flow, 'JAR_SHA', flow.file_info(self.bundle / 'tools/apksigner.jar')['sha256'])
        self.patch_jar.start(); self.addCleanup(self.patch_jar.stop)

    def refresh(self):
        self.receipt['files'] = {name: flow.file_info(self.bundle / name) for name in flow.BUNDLE_FILES}
        (self.bundle / 'prepare.json').write_bytes(flow.encoded(self.receipt))
        self.anchor = flow.file_info(self.bundle / 'prepare.json')['sha256']

    def check(self, anchor=None, source=SOURCE, tree=TREE):
        return flow.bundle_check(self.bundle, self.anchor if anchor is None else anchor, source, tree)

    def packet(self, extra=(), omit=None, link=None):
        p = self.root / 'packet.zip'
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            with zipfile.ZipFile(p, 'w') as z:
                for name in sorted(flow.BUNDLE_FILES | {'prepare.json'}):
                    if name == omit:
                        continue
                    if name == link:
                        info = zipfile.ZipInfo(name)
                        info.create_system = 3
                        info.external_attr = (stat.S_IFLNK | 0o777) << 16
                        z.writestr(info, b'elsewhere')
                    else:
                        z.write(self.bundle / name, name)
                for name, data in extra:
                    z.writestr(name, data)
        return p

    def intake(self, p, **kwargs):
        dest = self.root / 'received'
        with contextlib.redirect_stdout(io.StringIO()):
            flow.unpack(p, kwargs.get('packet_sha', flow.file_info(p)['sha256']), kwargs.get('anchor', self.anchor),
                        kwargs.get('source', SOURCE), kwargs.get('tree', TREE), dest)
        return dest

    def test_valid_bundle_and_intake(self):
        self.check()
        dest = self.intake(self.packet())
        self.assertEqual(flow.file_info(dest / 'OfferFilter-unsigned.apk'), flow.file_info(self.bundle / 'OfferFilter-unsigned.apk'))

    def test_independent_receipt_anchor_required(self):
        for anchor in ('', '0' * 64, 'not-a-hash'):
            with self.subTest(anchor=anchor), self.assertRaises(ValueError):
                self.check(anchor=anchor)

    def test_changed_receipt_rejected_even_with_internal_hashes(self):
        old_anchor = self.anchor
        self.receipt['sourceCommit'] = 'c' * 40
        self.refresh()
        with self.assertRaisesRegex(ValueError, 'Independent'):
            self.check(anchor=old_anchor, source='c' * 40)

    def test_candidate_commit_and_tree_are_independent(self):
        for source, tree in [('c' * 40, TREE), (SOURCE, 'c' * 40), ('', TREE)]:
            with self.subTest(source=source, tree=tree), self.assertRaises(ValueError):
                self.check(source=source, tree=tree)

    def test_missing_receipt(self):
        (self.bundle / 'prepare.json').unlink()
        with self.assertRaises(ValueError):
            self.check()

    def test_changed_unsigned_bytes(self):
        apk(self.bundle / 'OfferFilter-unsigned.apk', replace={'classes.dex': b'changed'})
        with self.assertRaisesRegex(ValueError, 'hash/size'):
            self.check()

    def test_bundle_missing_unexpected_and_symlink_members(self):
        p = self.bundle / 'extra.txt'; p.write_text('extra')
        with self.assertRaises(ValueError): self.check()
        p.unlink()
        p = self.bundle / 'evidence.json'; original = p.read_bytes(); p.unlink()
        with self.assertRaises(ValueError): self.check()
        other = self.root / 'elsewhere'; other.write_bytes(original); p.symlink_to(other)
        with self.assertRaises(ValueError): self.check()

    def test_bundle_manifest_cannot_expand_allowlist(self):
        p = self.bundle / 'unexpected.sh'; p.write_text('exit 0')
        self.receipt['files']['unexpected.sh'] = flow.file_info(p)
        (self.bundle / 'prepare.json').write_bytes(flow.encoded(self.receipt))
        self.anchor = flow.file_info(self.bundle / 'prepare.json')['sha256']
        with self.assertRaisesRegex(ValueError, 'allowlist'): self.check()

    def test_gate_failures_and_missing_pairs_rejected(self):
        for mutation in ['errors', 'failures', 'skipped', 'count', 'lint', 'adapter', 'pair', 'aggregate']:
            with self.subTest(mutation=mutation):
                g = json.loads(json.dumps(self.gate))
                if mutation in ('errors', 'failures', 'skipped'): g['junit'][mutation] = 1
                elif mutation == 'count': g['junit']['tests'] = 3271
                elif mutation == 'lint': g['lint']['errors'] = 1
                elif mutation == 'adapter': g['adapterSdkCases'].pop(flow.ADAPTERS[0])
                elif mutation == 'pair': g['adapterSdkCases'][flow.ADAPTERS[0]]['35'] = 0
                else: g['inputSha256'] = '0' * 64
                (self.bundle / 'gate.json').write_bytes(flow.encoded(g)); self.refresh()
                with self.assertRaises(ValueError): self.check()

    def test_portable_adapter_contract_matches_existing_gate(self):
        self.assertEqual(flow.ADAPTERS, gate.ADAPTERS)

    def test_aapt2_build_tools_35_badging_and_sdk_rejection(self):
        # Captured field spelling from the real 35.0.0 package integration.
        good = "package: name='com.local.dasherfilter' versionCode='88' versionName='0.5.8' platformBuildVersionName='16'\nminSdkVersion:'26'\ntargetSdkVersion:'35'\n"
        with patch.object(flow, 'sdk', return_value=pathlib.Path('/verified-sdk')), patch.object(flow, 'run', return_value=good):
            self.assertEqual(flow.package_info('unsigned.apk', '0.5.8', 88)['minSdk'], 26)
        for bad in (good.replace("minSdkVersion:'26'", "minSdkVersion:'25'"),
                    good.replace("targetSdkVersion:'35'", "targetSdkVersion:'34'"),
                    good.replace("versionCode='88'", "versionCode='89'")):
            with self.subTest(bad=bad), patch.object(flow, 'sdk', return_value=pathlib.Path('/verified-sdk')), patch.object(flow, 'run', return_value=bad):
                with self.assertRaises(ValueError): flow.package_info('unsigned.apk', '0.5.8', 88)

    def test_duplicate_json_field(self):
        p = self.bundle / 'prepare.json'
        p.write_bytes(p.read_bytes().replace(b'{', b'{"schema":1,', 1))
        self.anchor = flow.file_info(p)['sha256']
        with self.assertRaisesRegex(ValueError, 'Duplicate JSON'): self.check()

    def test_apk_duplicate_traversal_absolute_and_casefold(self):
        for name in ['classes.dex', 'CLASSES.DEX', '../outside', '/absolute', 'a/../b', 'a\\b', 'a//b']:
            with self.subTest(name=name):
                apk(self.root / 'bad.apk', extra=[(name, b'bad')])
                with self.assertRaises(ValueError): flow.apk_entries(self.root / 'bad.apk', unsigned=True)

    def test_unsigned_cannot_have_jar_signature(self):
        apk(self.root / 'bad.apk', extra=[('META-INF/CERT.RSA', b'not unsigned')])
        with self.assertRaises(ValueError): flow.apk_entries(self.root / 'bad.apk', unsigned=True)

    def test_zip_symlink(self):
        p = self.root / 'bad.apk'; apk(p)
        with zipfile.ZipFile(p, 'a') as z:
            i = zipfile.ZipInfo('linked'); i.create_system = 3; i.external_attr = (stat.S_IFLNK | 0o777) << 16
            z.writestr(i, b'classes.dex')
        with self.assertRaises(ValueError): flow.apk_entries(p)

    def test_payload_change_and_unverified_signature_extras(self):
        original = self.receipt['payload']
        for change in ['change', 'remove', 'add', 'signature']:
            with self.subTest(change=change):
                actual = json.loads(json.dumps(original))
                if change == 'change': actual['classes.dex']['sha256'] = '0' * 64
                elif change == 'remove': actual.pop('classes.dex')
                elif change == 'add': actual['new.dex'] = actual['classes.dex']
                else: actual['META-INF/CERT.RSA'] = actual['classes.dex']
                with self.assertRaises(ValueError): flow.compare_payload(original, actual)

    def test_wrong_missing_extra_signer(self):
        good = 'Signer #1 certificate SHA-256 digest: ' + flow.SIGNER + '\n'
        flow.certs_ok(good)
        for output in ['', good.replace(flow.SIGNER, '0' * 64), good + good.replace('#1', '#2')]:
            with self.subTest(output=output), self.assertRaises(ValueError): flow.certs_ok(output)

    def test_api26_skipped_v1_metadata_requires_independent_verification(self):
        signed = self.root / 'signed.apk'
        apk(signed, extra=[('META-INF/CERT.RSA', b'signature')])
        cert = 'Signer #1 certificate SHA-256 digest: ' + flow.SIGNER + '\n'
        current = cert + 'Verified using v1 scheme (JAR signing): false\n'
        legacy = cert + 'Verified using v1 scheme (JAR signing): true\n'
        with patch.object(flow, 'sdk', return_value=pathlib.Path('/verified-sdk')), \
                patch.object(flow, 'run', side_effect=[current, legacy]) as verify:
            self.assertTrue(flow.verify_signed(signed))
            self.assertIn('26', verify.call_args_list[0].args)
            self.assertEqual(verify.call_args_list[1].args[-5:],
                             ('23', '--max-sdk-version', '23', '--print-certs', signed))

    def test_v1_metadata_cannot_be_admitted_without_the_original_verified_signer(self):
        signed = self.root / 'signed.apk'
        apk(signed, extra=[('META-INF/CERT.RSA', b'signature')])
        cert = 'Signer #1 certificate SHA-256 digest: ' + flow.SIGNER + '\n'
        current = cert + 'Verified using v1 scheme (JAR signing): false\n'
        legacy = cert + 'Verified using v1 scheme (JAR signing): true\n'
        for bad in (current, legacy.replace(flow.SIGNER, '0' * 64), legacy + cert.replace('#1', '#2')):
            with self.subTest(output=bad), patch.object(flow, 'sdk', return_value=pathlib.Path('/verified-sdk')), \
                    patch.object(flow, 'run', side_effect=[current, bad]), self.assertRaises(ValueError):
                flow.verify_signed(signed)

    def test_intake_rejects_before_creating_destination(self):
        for extra, omit, link in [([('../escape', b'x')], None, None), ([('/absolute', b'x')], None, None),
                                  ([('prepare.json', b'x')], None, None), ([('PREPARE.JSON', b'x')], None, None),
                                  ([('unexpected', b'x')], None, None), ([], 'gate.json', None),
                                  ([], None, 'tools/sign-local.sh')]:
            with self.subTest(extra=extra, omit=omit, link=link):
                p = self.packet(extra=extra, omit=omit, link=link)
                with self.assertRaises(ValueError): self.intake(p)
                self.assertFalse((self.root / 'received').exists())

    def test_intake_independent_hash_and_identity_rejections(self):
        for kwargs in [dict(packet_sha='0'*64), dict(anchor='0'*64), dict(source='c'*40), dict(tree='c'*40)]:
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError): self.intake(self.packet(), **kwargs)
            self.assertFalse((self.root / 'received').exists())

    def test_intake_detects_tampered_member_before_extraction(self):
        (self.bundle / 'tools/sign-local.sh').write_bytes(b'changed')
        with self.assertRaises(ValueError): self.intake(self.packet())
        self.assertFalse((self.root / 'received').exists())

    def test_unsigned_paths_cannot_reach_source_dist_release(self):
        for p in [self.bundle / 'pending-unsigned/x', self.root / 'dist/pending-unsigned/x', self.root / 'release/pending-unsigned/x', self.root / 'ordinary/x']:
            with self.subTest(path=str(p)), self.assertRaises(ValueError): flow.check_pending(p)
        flow.check_pending(self.root / 'pending-unsigned/x')

    def test_secret_directory_permissions(self):
        p = self.root / 'private'; p.mkdir(mode=0o700); flow.check_private(p)
        p.chmod(0o755)
        with self.assertRaises(ValueError): flow.check_private(p)

    def test_accept_refuses_signed_hash_before_verifying_or_admitting(self):
        result = dict(schema=1, preparationReceiptSha256=self.anchor, sourceCommit=SOURCE, sourceTree=TREE,
                      inputAggregate=self.receipt['inputAggregate'], unsigned=self.receipt['files']['OfferFilter-unsigned.apk'],
                      signer=flow.SIGNER, signed=dict(size=1, sha256='0'*64), package=self.receipt['package'],
                      payloadUnchanged=True, canonicalAcceptanceRequired=True, coordinatorAapt2Verified=False)
        p = self.root / 'signed-receipt.json'; p.write_bytes(flow.encoded(result))
        with patch.object(flow, 'identity'), patch.object(gate, 'gate', return_value=self.gate), patch.object(flow, 'verify_signed') as verify:
            with self.assertRaisesRegex(ValueError, 'Signed return hash'):
                flow.accept(self.bundle, self.anchor, SOURCE, TREE, self.bundle / 'OfferFilter-unsigned.apk', p)
            verify.assert_not_called()
        self.assertFalse((self.bundle / 'dist').exists())

    def returned_receipt(self):
        return dict(schema=1, preparationReceiptSha256=self.anchor, sourceCommit=SOURCE, sourceTree=TREE,
                    inputAggregate=self.receipt['inputAggregate'], unsigned=self.receipt['files']['OfferFilter-unsigned.apk'],
                    signer=flow.SIGNER, signed=flow.file_info(self.bundle / 'OfferFilter-unsigned.apk'),
                    package=json.loads(json.dumps(self.receipt['package'])), payloadUnchanged=True,
                    canonicalAcceptanceRequired=True, coordinatorAapt2Verified=False)

    def test_accept_rejects_forged_package_and_payload_claims(self):
        for claim in ('package', 'payloadUnchanged', 'coordinatorAapt2Verified', 'canonicalAcceptanceRequired'):
            result = self.returned_receipt()
            if claim == 'package': result[claim]['versionCode'] = 999
            else: result[claim] = not result[claim]
            p = self.root / 'signed-receipt.json'; p.write_bytes(flow.encoded(result))
            # Signature/package verification are modeled as successful; the returned
            # payload and required identity/hash fields are otherwise the valid fixture.
            with self.subTest(claim=claim), patch.object(flow, 'identity'), patch.object(gate, 'gate', return_value=self.gate), \
                    patch.object(flow, 'verify_signed', return_value=False), patch.object(flow, 'package_info', return_value=self.receipt['package']):
                with self.assertRaisesRegex(ValueError, 'claims'):
                    flow.accept(self.bundle, self.anchor, SOURCE, TREE, self.bundle / 'OfferFilter-unsigned.apk', p)
            self.assertFalse((self.bundle / 'dist').exists())

    def test_canonical_receipt_is_reconstructed_not_copied(self):
        result = self.returned_receipt()
        result.update(published=True, deviceInstallVerified=True, attackerSuppliedFact='forged')
        p = self.root / 'signed-receipt.json'; p.write_bytes(flow.encoded(result))
        with patch.object(flow, 'identity'), patch.object(gate, 'gate', return_value=self.gate), \
                patch.object(flow, 'verify_signed', return_value=False), \
                patch.object(flow, 'version_info', return_value=('0.5.8',88)), \
                patch.object(flow, 'package_info', return_value=self.receipt['package']), contextlib.redirect_stdout(io.StringIO()):
            flow.accept(self.bundle, self.anchor, SOURCE, TREE, self.bundle / 'OfferFilter-unsigned.apk', p)
        canonical = flow.read_json(self.bundle / 'dist/OfferFilter-0.5.8-acceptance.json')
        self.assertNotIn('attackerSuppliedFact', canonical)
        self.assertFalse(canonical['published'])
        self.assertFalse(canonical['deviceInstallVerified'])
        self.assertEqual(canonical['package'], self.receipt['package'])
        self.assertTrue(canonical['payloadUnchanged'])

    def test_oversized_apk_rejected_before_any_content_read(self):
        p = self.root / 'oversized.apk'
        with p.open('wb') as f: f.truncate(flow.MAX_APK + 1)
        with patch.object(flow.os, 'read') as read:
            with self.assertRaisesRegex(ValueError, 'before reading'): flow.apk_entries(p)
            read.assert_not_called()

    def test_oversized_packet_rejected_before_any_content_read(self):
        p = self.root / 'oversized.zip'
        with p.open('wb') as f: f.truncate(flow.MAX_PACKET + 1)
        with patch.object(flow.os, 'read') as read:
            with self.assertRaisesRegex(ValueError, 'before reading'):
                flow.unpack(p, 'a'*64, self.anchor, SOURCE, TREE, self.root / 'never-created')
            read.assert_not_called()
        self.assertFalse((self.root / 'never-created').exists())

    def test_file_info_limits_before_reads_and_hashes_in_bounded_chunks(self):
        p = self.root / 'file'; p.write_bytes(b'content' * 20000)
        with patch.object(flow.os, 'read') as read:
            with self.assertRaises(ValueError): flow.file_info(p, 10)
            read.assert_not_called()
        original = os.read
        with patch.object(flow.os, 'read', wraps=original) as read, \
                patch.object(pathlib.Path, 'read_bytes', side_effect=AssertionError('unbounded read')):
            info = flow.file_info(p, 200000)
        self.assertEqual(info['size'], 140000)
        self.assertTrue(all(0 < call.args[1] <= 65536 for call in read.call_args_list))

    def test_changing_file_rejected(self):
        p = self.root / 'changing'; p.write_bytes(b'12345678')
        original = os.read
        def shrink(fd, size):
            result = original(fd, size)
            if result:
                with p.open('wb') as f: f.write(b'x')
            return result
        with patch.object(flow.os, 'read', side_effect=shrink):
            with self.assertRaisesRegex(ValueError, 'changed while reading'): flow.file_info(p, 16)

    def test_file_growth_beyond_limit_rejected_during_read(self):
        p = self.root / 'growing'; p.write_bytes(b'12345678')
        original = os.read
        first = True
        def grow(fd, size):
            nonlocal first
            result = original(fd, size)
            if first:
                first = False
                with p.open('ab') as f: f.write(b'x'*32)
            return result
        with patch.object(flow.os, 'read', side_effect=grow):
            with self.assertRaisesRegex(ValueError, 'grew beyond'): flow.read_checked(p, 16)


class EntryPointTest(unittest.TestCase):
    def test_default_signing_body_is_unchanged_from_recovery_checkpoint(self):
        original = subprocess.check_output(['git', 'show', 'f2ba405cc3a279a19e920220a86ec54b43c49954:tools/sign-local.sh'], cwd=flow.ROOT)
        current = (flow.ROOT / 'tools/sign-local.sh').read_bytes()
        marker = b'KEY_FINGERPRINT='
        self.assertEqual(original[original.index(marker):], current[current.index(marker):])

    def test_bad_anchor_fails_before_requesting_secrets(self):
        env = {k:v for k,v in os.environ.items() if k not in ('OFFER_FILTER_SIGNING_PASSWORD', 'OFFER_FILTER_KEYSTORE_B64')}
        p = subprocess.run(['bash', 'tools/sign-local.sh', 'finalize-original-signature', '--sign',
                            '/nonexistent', 'not-a-hash', SOURCE, TREE, '/nonexistent-output'], cwd=flow.ROOT,
                           env=env, capture_output=True, text=True)
        self.assertNotEqual(p.returncode, 0)
        self.assertIn('Independent preparation receipt', p.stderr)
        self.assertNotIn('OFFER_FILTER_SIGNING_PASSWORD', p.stderr)


if __name__ == '__main__':
    unittest.main()
