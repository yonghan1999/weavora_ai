import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import urllib.error
import zipfile

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("source_licenses", Path(__file__).with_name("collect-maven-source-licenses.py"))
collector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector)
check_spec = importlib.util.spec_from_file_location("source_license_check", Path(__file__).with_name("check-maven-source-licenses.py"))
checker = importlib.util.module_from_spec(check_spec)
check_spec.loader.exec_module(checker)


class SourceLicenseTest(unittest.TestCase):
    def row(self):
        return {"coordinate": "org.example:sample:jar:1.2.3:test", "scope": "test", "declaredLicenses": []}

    def archive(self, entries):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as jar:
            for name, content in entries.items():
                jar.writestr(name, content)
        return buffer.getvalue()

    def test_exact_version_checksum_and_content_addressed_text_preserve_archive_paths(self):
        data = self.archive({"../../LICENSE": b"fixture license", "META-INF/NOTICE.txt": b"fixture notice"})
        calls = []
        def fetch(url, limit=collector.LIMIT):
            calls.append(url)
            return hashlib.sha256(data).hexdigest().encode() if url.endswith(".sha256") else data
        with patch.object(collector, "fetch", fetch):
            result, archive, contents = collector.obtain(self.row())
        self.assertEqual(result["status"], "documents_collected")
        self.assertEqual(archive, data)
        self.assertEqual(result["centralChecksum"]["algorithm"], "sha256")
        self.assertEqual(calls[0], collector.BASE + "org/example/sample/1.2.3/sample-1.2.3-sources.jar")
        self.assertEqual(len(contents), 2)
        for item in result["documents"]:
            self.assertRegex(item["file"], r"^texts/[0-9a-f]{64}\.txt$")
        self.assertEqual(result["reviewStatus"], "unreviewed")

    def test_checksum_mismatch_does_not_return_archive_or_text(self):
        data = self.archive({"LICENSE": "fixture"})
        with patch.object(collector, "fetch", side_effect=[data, b"0" * 64]):
            result, archive, contents = collector.obtain(self.row())
        self.assertEqual(result["status"], "checksum_mismatch")
        self.assertIsNone(archive)
        self.assertEqual(contents, [])

    def test_sha1_fallback_is_explicit_and_not_an_approval(self):
        data = self.archive({"package/Source.java": "source without license document"})
        missing = urllib.error.HTTPError("https://repo.maven.apache.org/fixture", 404, "missing", {}, None)
        with patch.object(collector, "fetch", side_effect=[data, missing, hashlib.sha1(data).hexdigest().encode()]):
            result, archive, _ = collector.obtain(self.row())
        self.assertEqual(result["centralChecksum"]["algorithm"], "sha1")
        self.assertEqual(result["status"], "no_source_license_documents")
        self.assertIsNotNone(archive)

    def test_missing_source_and_checksum_failures_are_distinct(self):
        missing = urllib.error.HTTPError("https://repo.maven.apache.org/fixture", 404, "missing", {}, None)
        with patch.object(collector, "fetch", side_effect=missing):
            self.assertEqual(collector.obtain(self.row())[0]["status"], "source_http_404")
        with patch.object(collector, "fetch", side_effect=[b"archive", missing, missing]):
            result, archive, _ = collector.obtain(self.row())
        self.assertEqual(result["status"], "checksum_missing")
        self.assertIsNone(archive)

    def test_invalid_archive_and_oversized_member_are_unresolved(self):
        with patch.object(collector, "fetch", side_effect=[b"invalid", hashlib.sha256(b"invalid").hexdigest().encode()]):
            self.assertEqual(collector.obtain(self.row())[0]["status"], "source_archive_invalid")
        data = self.archive({"LICENSE": b"x" * (2 * 1024 * 1024 + 1)})
        with patch.object(collector, "fetch", side_effect=[data, hashlib.sha256(data).hexdigest().encode()]):
            result, _, contents = collector.obtain(self.row())
        self.assertEqual(result["documents"][0]["status"], "too_large_unread")
        self.assertEqual(contents, [])

    def test_saved_material_checker_rejects_tampering_and_outside_paths(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "texts").mkdir()
            evidence = root / "evidence.json"
            evidence.write_text(json.dumps({"dependencies": [{"coordinate": self.row()["coordinate"], "jar": {"documents": []}}]}))
            sources = {"inputSha256": checker.sha(evidence.read_bytes()), "reviewStatus": "unreviewed", "releaseApproved": False,
                       "dependencies": [{**self.row(), "sourceUrl": "https://repo.maven.apache.org/fixture", "reviewStatus": "unreviewed", "documents": []}]}
            (root / "manifest.json").write_text(json.dumps(sources))
            (root / "logback-source-headers.json").write_text(json.dumps({"releaseApproved": False, "documents": []}))
            data = b"license fixture"
            path = "texts/" + checker.sha(data) + ".txt"
            (root / path).write_bytes(data)
            text = {"file": path, "sha256": checker.sha(data), "bytes": len(data), "reviewStatus": "unreviewed"}
            def save():
                (root / "official-texts.json").write_text(json.dumps({"releaseApproved": False, "documents": [text]}))
            save()
            self.assertEqual(checker.check(root, evidence)["materialReferencesChecked"], 1)
            (root / path).write_bytes(b"changed")
            with self.assertRaisesRegex(AssertionError, "material hash mismatch"):
                checker.check(root, evidence)
            text["file"] = "../outside.txt"
            save()
            with self.assertRaises(AssertionError):
                checker.check(root, evidence)


if __name__ == "__main__":
    unittest.main()
