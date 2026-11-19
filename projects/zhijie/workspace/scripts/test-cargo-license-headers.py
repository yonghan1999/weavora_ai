import importlib.util
import io
import json
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("cargo_headers", Path(__file__).with_name("collect-cargo-license-headers.py"))
headers = importlib.util.module_from_spec(spec)
spec.loader.exec_module(headers)


class CargoHeadersTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.registry = self.root / "registry"
        self.archive = self.registry / "cache/test/example-1.crate"
        self.archive.parent.mkdir(parents=True)
        self.evidence = self.root / "evidence.json"
        self.output = self.root / "output"
        self.comment = b"// Copyright example\r\n// Licensed under MIT\r\n"
        self.context = b"before\r\n## License\r\nMIT OR Apache-2.0\r\nafter\xff"
        self.build_archive()

    def build_archive(self, extra=None):
        files = {"example-1/src/lib.rs": self.comment + b"fn unrelated() {}\n",
                 "example-1/src/duplicate.rs": self.comment + b"fn other() {}\n",
                 "example-1/README.md": self.context,
                 "example-1/src/not_a_header.rs": b'fn main() { let x = "license"; }\n'}
        files.update(extra or {})
        with tarfile.open(self.archive, "w:gz") as archive:
            for name, data in files.items():
                member = tarfile.TarInfo(name)
                member.size = len(data)
                archive.addfile(member, io.BytesIO(data))
        self.evidence.write_text(json.dumps({"dependencies": [{"name": "example", "version": "1", "declaredLicense": "MIT",
            "archiveSha256": headers.base.sha(self.archive.read_bytes()), "documents": []}]}))

    def collect(self):
        return headers.collect(self.evidence, self.registry)

    def test_preserves_comment_bytes_full_context_and_member_hashes(self):
        manifest, texts = self.collect()
        self.assertEqual(manifest["summary"], {"dependencies": 1, "withDeclarations": 1, "documentReferences": 3, "uniqueOriginals": 2, "reviewed": 0})
        self.assertEqual(set(texts.values()), {self.comment, self.context})
        docs = manifest["dependencies"][0]["documents"]
        self.assertTrue(all(doc["offset"] == 0 for doc in docs))
        self.assertEqual(len([doc for doc in docs if doc["kind"] == "leading_source_comment"]), 2)
        self.assertTrue(all(doc["entryBytes"] > doc["bytes"] for doc in docs if doc["kind"] == "leading_source_comment"))
        self.assertFalse(manifest["releaseApproved"])
        self.assertNotIn(str(self.root), json.dumps(manifest))

    def test_block_comment_boundary_and_unclosed_comment(self):
        self.assertEqual(headers.leading_comment(b"/* License MIT */\ncode"), b"/* License MIT */")
        self.assertEqual(headers.leading_comment(b"/* License MIT"), b"")
        self.assertEqual(headers.leading_comment(b"code // License MIT"), b"")

    def test_changed_archive_is_rejected(self):
        self.archive.write_bytes(self.archive.read_bytes() + b"changed")
        with self.assertRaisesRegex(ValueError, "ARCHIVE_CHANGED"):
            self.collect()

    def test_output_and_manifest_cannot_self_verify_after_tampering(self):
        manifest, texts = self.collect()
        headers.write_or_check(self.output, manifest, texts)
        headers.write_or_check(self.output, *self.collect(), check=True)
        original = (self.output / "manifest.json").read_bytes()
        changed = json.loads(original)
        changed["dependencies"][0]["documents"] = []
        (self.output / "manifest.json").write_text(json.dumps(changed))
        with self.assertRaisesRegex(ValueError, "MATERIALS_CHANGED"):
            headers.write_or_check(self.output, *self.collect(), check=True)
        (self.output / "manifest.json").write_bytes(original)
        (self.output / next(iter(texts))).write_bytes(b"tamper")
        with self.assertRaisesRegex(ValueError, "MATERIALS_CHANGED"):
            headers.write_or_check(self.output, *self.collect(), check=True)

    def test_refuses_overwrite_and_extra_files(self):
        headers.write_or_check(self.output, *self.collect())
        with self.assertRaises(FileExistsError):
            headers.write_or_check(self.output, *self.collect())
        (self.output / "extra.txt").write_text("unexpected")
        with self.assertRaisesRegex(ValueError, "FILES_CHANGED"):
            headers.write_or_check(self.output, *self.collect(), check=True)

    def test_traversal_and_invalid_coordinate_rejected_without_extraction(self):
        self.build_archive({"example-1/../../outside.rs": b"// License"})
        with self.assertRaisesRegex(ValueError, "ARCHIVE_PATH_INVALID"):
            self.collect()
        self.assertFalse((self.root / "outside.rs").exists())
        manifest = json.loads(self.evidence.read_bytes())
        manifest["dependencies"][0]["name"] = "../outside"
        self.evidence.write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValueError, "COORDINATE_INVALID"):
            self.collect()

    def test_upstream_original_packages_are_excluded_and_empty_declarations_stay_open(self):
        data = json.loads(self.evidence.read_bytes())
        data["dependencies"][0]["documents"] = [{"file": "previous-original"}]
        self.evidence.write_text(json.dumps(data))
        self.assertEqual(self.collect()[0]["summary"]["dependencies"], 0)
        self.build_archive({"example-1/src/lib.rs": b"fn main() {}", "example-1/src/duplicate.rs": b"fn main() {}", "example-1/README.md": b"no relevant terms"})
        row = self.collect()[0]["dependencies"][0]
        self.assertEqual(row["status"], "no_matching_declarations")
        self.assertEqual(row["reviewStatus"], "unreviewed")


if __name__ == "__main__":
    unittest.main()
