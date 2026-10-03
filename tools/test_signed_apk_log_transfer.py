import base64
import json
import pathlib
import subprocess
import unittest
from unittest.mock import patch

import signed_apk_log_transfer as transport


class SignedArtifactTransferTest(unittest.TestCase):
    source = "a" * 40
    version = "0.4.58"
    code = 64

    def setUp(self):
        self.data = b"PK\x03\x04" + bytes(range(256)) * 24
        self.meta = transport.metadata(self.source, self.version, self.code, self.data)
        self.records = list(transport.encode_records(self.meta, self.data))

    def decode(self, records):
        return transport.decode_records(records, self.source, self.version, self.code)

    def mutate_meta(self, key, value):
        changed = dict(self.meta)
        changed[key] = value
        fields = self.records[0].split()
        fields[-1] = base64.b64encode(json.dumps(changed).encode()).decode()
        return [" ".join(fields)] + self.records[1:]

    def test_roundtrip_out_of_order_with_render_prefix_and_noise(self):
        lines = ["Normal build output"] + ["2026-10-04T00:00:00Z " + r for r in reversed(self.records)]
        data, meta = self.decode(lines)
        self.assertEqual(self.data, data)
        self.assertEqual(self.meta, meta)

    def test_each_record_fits_bounded_log_line(self):
        self.assertLess(max(map(len, self.records)), 2300)

    def test_missing_metadata_chunk_or_end_rejected(self):
        for index in (0, 1, 3, len(self.records) - 1):
            with self.subTest(index=index), self.assertRaises(ValueError):
                self.decode(self.records[:index] + self.records[index + 1:])

    def test_duplicate_metadata_chunk_or_end_rejected(self):
        for index in (0, 1, len(self.records) - 1):
            with self.subTest(index=index), self.assertRaises(ValueError):
                self.decode(self.records + [self.records[index]])

    def test_modified_chunk_rejected(self):
        fields = self.records[1].split()
        fields[-1] = base64.b64encode(b"attacker").decode()
        with self.assertRaises(ValueError):
            self.decode([self.records[0], " ".join(fields)] + self.records[2:])

    def test_source_package_version_code_signer_size_hash_mismatches_rejected(self):
        for key, value in (("sourceCommit", "b" * 40), ("packageName", "other.package"),
                           ("versionName", "0.4.59"), ("versionCode", 65), ("signer", "f" * 64),
                           ("size", len(self.data) - 1), ("sha256", "f" * 64), ("versionCode", 64.0)):
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                self.decode(self.mutate_meta(key, value))

    def test_missing_expected_source_rejected(self):
        with self.assertRaises(ValueError):
            transport.decode_records(self.records, "b" * 40, self.version, self.code)

    def test_conflicting_artifact_for_same_source_rejected(self):
        extra = self.records[-1].replace(self.meta["sha256"], "f" * 64)
        with self.assertRaises(ValueError):
            self.decode(self.records + [extra])

    def test_bad_count_and_oversize_chunk_rejected(self):
        for bad in (0, -1, 999999, True, 4.0):
            with self.subTest(count=bad), self.assertRaises(ValueError):
                self.decode(self.mutate_meta("chunks", bad))
        with self.assertRaises(ValueError):
            self.decode(self.records + [transport.MARKER + " " + "x" * 9000])

    def test_unknown_record_and_ambiguous_markers_rejected(self):
        for extra in (self.records[1].replace(" DATA ", " EXEC "),
                      self.records[1] + transport.MARKER):
            with self.subTest(extra=extra[:50]), self.assertRaises(ValueError):
                self.decode(self.records + [extra])

    def test_zip_guard_and_invalid_source_rejected(self):
        for source, data in ((self.source, b"not a zip"), ("main", self.data), (self.source, b"")):
            with self.subTest(source=source), self.assertRaises(ValueError):
                transport.metadata(source, self.version, self.code, data)

    def test_emitter_requires_exact_original_signer_and_package_version(self):
        good_cert = "Signer #1 certificate SHA-256 digest: " + transport.SIGNER
        good_package = f"package: name='{transport.PACKAGE}' versionCode='64' versionName='0.4.58'"
        for cert, package, passes in ((good_cert, good_package, True),
                                     (good_cert.replace(transport.SIGNER, "f" * 64), good_package, False),
                                     (good_cert + "\nSigner #2 certificate SHA-256 digest: " + transport.SIGNER,
                                      good_package, False),
                                     (good_cert, good_package.replace("'64'", "'65'"), False),
                                     (good_cert, good_package.replace(transport.PACKAGE, "other.app"), False)):
            result = [subprocess.CompletedProcess([], 0, stdout=value) for value in (cert, package)]
            with self.subTest(cert=cert[:30], package=package), patch.object(transport.subprocess, "run", side_effect=result):
                if passes:
                    transport.verify_apk(pathlib.Path("signed.apk"), pathlib.Path("tools"), self.version, self.code)
                else:
                    with self.assertRaises(ValueError):
                        transport.verify_apk(pathlib.Path("signed.apk"), pathlib.Path("tools"), self.version, self.code)


if __name__ == "__main__":
    unittest.main()
