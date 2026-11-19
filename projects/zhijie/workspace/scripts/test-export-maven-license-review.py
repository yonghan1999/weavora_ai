import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

sys.dont_write_bytecode = True
SCRIPT = Path(__file__).with_name("export-maven-license-review.py")
spec = importlib.util.spec_from_file_location("license_review", SCRIPT)
review = importlib.util.module_from_spec(spec)
spec.loader.exec_module(review)


class MavenLicenseReviewTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.repository = self.root / "repository"
        self.snapshot = self.root / "dependencies.txt"
        self.evidence = self.root / "evidence.json"
        self.output = self.root / "review"
        self.original = b"license\r\noriginal\x00\xff"
        self.snapshot.write_text("org.example:first:jar:1:compile\norg.example:second:jar:1:test\norg.example:missing:jar:1:runtime\n")
        for name in ("first", "second"):
            base = self.repository / "org/example" / name / "1"
            base.mkdir(parents=True)
            (base / f"{name}-1.pom").write_text("<project><licenses><license><name>Example</name></license></licenses></project>")
            with zipfile.ZipFile(base / f"{name}-1.jar", "w") as jar:
                jar.writestr("META-INF/LICENSE", self.original)
                jar.writestr("../NOTICE.txt", b"notice")
        self.evidence.write_text(json.dumps(review.collector.collect(self.snapshot, self.repository)), encoding="utf-8")

    def export(self):
        return review.export(self.evidence, self.snapshot, self.repository, self.output)

    def manifest(self):
        return json.loads((self.output / "manifest.json").read_text(encoding="utf-8"))

    def write_manifest(self, value):
        (self.output / "manifest.json").write_text(json.dumps(value), encoding="utf-8")

    def test_export_preserves_bytes_deduplicates_and_separates_scopes(self):
        summary = self.export()
        self.assertEqual(summary, {"dependencies": 3, "withOriginals": 2, "documentReferences": 4, "uniqueOriginals": 2, "reviewed": 0})
        manifest = self.manifest()
        document = manifest["dependencies"][0]["documents"][1]
        self.assertEqual((self.output / document["file"]).read_bytes(), self.original)
        self.assertFalse((self.root / "NOTICE.txt").exists())
        index = (self.output / "README.md").read_text(encoding="utf-8")
        for title in ("编译依赖（1 项）", "运行依赖（1 项）", "测试依赖（1 项）"):
            self.assertIn(title, index)
        self.assertFalse(manifest["releaseApproved"])
        self.assertEqual(manifest["dependencies"][2]["materialStatus"], "originals_missing")
        self.assertEqual(review.verify(self.output, self.evidence), summary)

    def test_stale_cache_is_rejected_before_output_creation(self):
        (self.repository / "org/example/first/1/first-1.pom").write_text("<project/>")
        with self.assertRaisesRegex(ValueError, "EVIDENCE_STALE"):
            self.export()
        self.assertFalse(self.output.exists())

    def test_existing_output_is_never_overwritten(self):
        self.export()
        before = (self.output / "manifest.json").read_bytes()
        with self.assertRaises(FileExistsError):
            self.export()
        self.assertEqual((self.output / "manifest.json").read_bytes(), before)

    def test_verify_needs_neither_repository_nor_network(self):
        self.export()
        self.repository.rename(self.root / "unavailable")
        result = subprocess.run([sys.executable, str(SCRIPT), "--verify", "--evidence", str(self.evidence),
                                 "--output-directory", str(self.output)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_modified_original_is_rejected(self):
        self.export()
        doc = self.manifest()["dependencies"][0]["documents"][0]
        (self.output / doc["file"]).write_bytes(b"altered")
        with self.assertRaisesRegex(ValueError, "DOCUMENT_CHANGED"):
            review.verify(self.output, self.evidence)

    def test_removed_documents_and_changed_sources_are_rejected(self):
        self.export()
        original = self.manifest()
        for field, value in [("coordinate", "changed"), ("scope", "runtime"), ("pomSources", []),
                             ("declaredLicenses", []), ("jarPath", "other.jar"), ("jarSha256", "0" * 64),
                             ("documents", []), ("materialStatus", "originals_missing")]:
            with self.subTest(field=field):
                manifest = json.loads(json.dumps(original))
                manifest["dependencies"][0][field] = value
                self.write_manifest(manifest)
                with self.assertRaisesRegex(ValueError, "SOURCES_CHANGED"):
                    review.verify(self.output, self.evidence)

    def test_false_summary_and_review_approval_are_rejected(self):
        self.export()
        original = self.manifest()
        for field, value, error in [("summary", {}, "SUMMARY_CHANGED"), ("releaseApproved", True, "STATUS_INVALID"),
                                     ("reviewStatus", "approved", "STATUS_INVALID")]:
            manifest = {**original, field: value}
            self.write_manifest(manifest)
            with self.assertRaisesRegex(ValueError, error):
                review.verify(self.output, self.evidence)

    def test_paths_and_non_hex_hashes_are_rejected(self):
        self.export()
        original = self.manifest()
        for field, value in [("file", "../outside.txt"), ("sha256", "z" * 64)]:
            manifest = json.loads(json.dumps(original))
            manifest["dependencies"][0]["documents"][0][field] = value
            self.write_manifest(manifest)
            with self.assertRaisesRegex(ValueError, "PATH_INVALID"):
                review.verify(self.output, self.evidence)

    def test_changed_evidence_and_self_consistent_index_tampering_are_rejected(self):
        self.export()
        index = b"misleading index"
        (self.output / "README.md").write_bytes(index)
        manifest = self.manifest()
        manifest["indexSha256"] = review.collector.digest(index)
        self.write_manifest(manifest)
        with self.assertRaisesRegex(ValueError, "INDEX_CHANGED"):
            review.verify(self.output, self.evidence)
        self.evidence.write_bytes(self.evidence.read_bytes() + b"\n")
        with self.assertRaisesRegex(ValueError, "INPUT_CHANGED"):
            review.verify(self.output, self.evidence)

    def test_extra_unindexed_originals_are_rejected(self):
        self.export()
        (self.output / "texts/extra.txt").write_text("unindexed")
        with self.assertRaisesRegex(ValueError, "FILES_CHANGED"):
            review.verify(self.output, self.evidence)


if __name__ == "__main__":
    unittest.main()
